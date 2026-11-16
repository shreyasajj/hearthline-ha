"""Backend-only adapters. Never accept URLs, state or geometry from an LLM."""
import asyncio
import json
import math
import time
from datetime import datetime, timezone
from urllib.parse import urlsplit
from .protocol import validate, fields, text


def utcnow(): return datetime.now(timezone.utc).isoformat()
def measurement(v): return v if type(v) in (float,int) and math.isfinite(v) else None

def envelope(kind, response_id, state, summary, data=None, source='Pixel Home', updated=None, stale_after=300):
    result=dict(protocol_version='1.1',response_id=response_id,layout=kind,
        title={'journey':'Journey','weather':'Weather','home_status':'Home status'}[kind],
        summary=summary,spoken_text=summary,ttl_seconds=120,
        template=dict(schema_version=1,state=state,source=source,updated_at=updated or utcnow(),stale_after_seconds=stale_after,data=data))
    return validate(result)

class TemplateProviders:
    def __init__(self,hass,bridge,options):
        self.hass=hass; self.bridge=bridge; self.options=options; self.tasks={}; self.started={}
        self.bridge.providers=self
        endpoint=options.get('journey_endpoint')
        if endpoint:
            u=urlsplit(endpoint)
            if u.scheme!='https' or not u.hostname or u.username or u.password or u.query or u.fragment: raise ValueError('Journey endpoint must be a fixed HTTPS URL without credentials')

    def capabilities(self):
        entity=self.hass.states.get(self.options.get('weather_entity',''))
        configured=bool(self.options.get('journey_endpoint') and self.options.get('home_origin'))
        return dict(weather=dict(configured=bool(self.options.get('weather_entity')),available=bool(entity and entity.state not in ('unknown','unavailable'))),
            routing=dict(configured=configured,available=None),
            geocoding=dict(configured=configured,available=None,adapter='journey endpoint resolves destination or returns clarification'),
            map_imagery=dict(configured=self.bridge.map_configured,available=None),
            hourly_weather=dict(configured=bool(self.options.get('weather_entity')),available=bool(entity and entity.state not in ('unknown','unavailable') and int(entity.attributes.get('supported_features',0)) & 2)),
            traffic=dict(configured=bool(self.options.get('journey_traffic',False)),available=None),
            home_status=dict(configured=bool(self.options.get('home_entities')),available=bool(self.options.get('home_entities'))))

    def cancel(self,response_id=None):
        tasks=list(self.tasks.values()) if response_id is None else [self.tasks.get(response_id)]
        for task in tasks:
            if task: task.cancel()

    def present(self,session,revision,response_id,intent):
        fields(intent,['template'],['destination','mode'])
        kind=intent['template']
        if kind not in ('journey','weather','home_status'): raise ValueError('Unknown template')
        if kind=='journey':
            fields(intent,['template','destination','mode']); text(intent['destination'],240)
            if intent['mode'] not in ('driving','walking','cycling'): raise ValueError('Unknown mode')
        elif len(intent)!=1: raise ValueError('Unexpected intent arguments')
        # Stable loading envelope is also the idempotency fingerprint. Same ID cannot change intent.
        import hashlib
        fingerprint=hashlib.sha256(json.dumps(intent,sort_keys=True).encode()).hexdigest()
        record=self.bridge.records.get(response_id)
        if record:
            if record.get('intent_fingerprint')!=fingerprint: raise ValueError('Response ID reused with different intent')
            if session!=self.bridge.session or revision!=self.bridge.context()['capability_revision']: raise ValueError('Fetch fresh context')
            return self.bridge.status(response_id,session)
        loading=envelope(kind,response_id,'loading',{'journey':'Checking the route…','weather':'Checking the weather…','home_status':'Checking configured devices…'}[kind])
        result=self.bridge.present(session,revision,loading)
        self.bridge.records[response_id]['intent_fingerprint']=fingerprint
        self.started[response_id]=time.monotonic()
        self.tasks[response_id]=self.hass.async_create_task(self._run(session,response_id,intent))
        return result

    async def _run(self,session,response_id,intent):
        try:
            while True:
                try:
                    result=await asyncio.wait_for(self.fetch(intent,response_id),timeout=20)
                except asyncio.CancelledError: raise
                except Exception:
                    result=envelope(intent['template'],response_id,'error','The provider could not be reached. Try again when it is available.')
                while session==self.bridge.session and self.bridge.records.get(response_id,{}).get('state')=='accepted':
                    self.bridge._expire()
                    if not self.bridge.context()['remote_display_available']: return
                    await asyncio.sleep(0.1)
                if not self.bridge.update(session,result): return
                # Live read-only home states keep their configured order; no actions are executed.
                if intent['template']!='home_status' or result['template']['data'] is None: return
                await asyncio.sleep(5)
        except asyncio.CancelledError: pass
        finally:
            if self.tasks.get(response_id) is asyncio.current_task():
                self.tasks.pop(response_id,None); self.started.pop(response_id,None)

    async def fetch(self,intent,response_id):
        kind=intent['template']; o=self.options
        if kind=='weather':
            entity=self.hass.states.get(o.get('weather_entity',''))
            if entity is None or entity.state in ('unknown','unavailable'):
                return envelope(kind,response_id,'unavailable','Configure an available Home Assistant weather entity.')
            a=entity.attributes; unit=a.get('temperature_unit')
            if unit not in ('°C','°F','K'): return envelope(kind,response_id,'unavailable','The weather entity does not provide a supported temperature unit.')
            hours=[]; daily=[]
            # Forecasts are service responses, never assumed to be entity attributes.
            async def forecast(frequency):
                data=await self.hass.services.async_call('weather','get_forecasts',dict(entity_id=entity.entity_id,type=frequency),blocking=True,return_response=True)
                return (data or {}).get(entity.entity_id,{}).get('forecast',[])
            features=int(a.get('supported_features',0))
            if features & 2:
                try:
                    forecast_hours=await forecast('hourly')
                    for h in forecast_hours:
                        at=datetime.fromisoformat(h['datetime'].replace('Z','+00:00'))
                        if at.tzinfo and datetime.now(timezone.utc)<=at<=datetime.now(timezone.utc)+__import__('datetime').timedelta(hours=6):
                            hours.append(dict(at=h['datetime'],temperature=measurement(h.get('temperature')),precipitation_probability=measurement(h.get('precipitation_probability')),condition=h.get('condition') or 'unavailable'))
                        if len(hours)==6: break
                except Exception: hours=[]
            if features & 1:
                try: daily=await forecast('daily')
                except Exception: daily=[]
            today=None
            from zoneinfo import ZoneInfo
            zone=ZoneInfo(self.hass.config.time_zone)
            for day in daily:
                if datetime.fromisoformat(day['datetime'].replace('Z','+00:00')).astimezone(zone).date()==datetime.now(zone).date(): today=day; break
            d=dict(location=a.get('friendly_name',entity.entity_id),condition=entity.state,temperature=measurement(a.get('temperature')),unit=unit,
                high=measurement(today.get('temperature')) if today else None,low=measurement(today.get('templow')) if today else None,
                precipitation_probability=measurement(today.get('precipitation_probability')) if today else None,hours=hours)
            return envelope(kind,response_id,'ready' if hours and d['temperature'] is not None else 'partial',
                'Hourly precipitation probability is unavailable.' if not any(h['precipitation_probability'] is not None for h in hours) else 'See the hourly strip for precipitation probabilities.',
                d,source=entity.entity_id,updated=entity.last_updated.isoformat(),stale_after=o.get('weather_stale_seconds',3600))
        if kind=='home_status':
            ids=o.get('home_entities',[])
            if not ids: return envelope(kind,response_id,'unavailable','Configure the Home Assistant entities to include in this summary.')
            items=[]; updated=[]
            for entity_id in ids:
                e=self.hass.states.get(entity_id)
                items.append(dict(entity_id=entity_id,name=e.attributes.get('friendly_name',entity_id) if e else entity_id,
                    state=e.state if e else 'unavailable',unit=e.attributes.get('unit_of_measurement') if e else None))
                if e: updated.append(e.last_updated)
            return envelope(kind,response_id,'partial' if any(i['state'] in ('unknown','unavailable') for i in items) else 'ready',
                'Reported states for configured entities only. No actions were requested.',dict(scope=f'{len(ids)} configured entities · read-only reported state',items=items),
                source='Home Assistant state machine',stale_after=o.get('home_stale_seconds',30))
        if not o.get('journey_endpoint') or not o.get('home_origin'):
            return envelope(kind,response_id,'unavailable','Journey provider not configured. Set a routing endpoint and explicit home origin on the server.')
        from homeassistant.helpers.aiohttp_client import async_get_clientsession
        headers={}
        if o.get('journey_token'): headers['Authorization']='Bearer '+o['journey_token']
        async with async_get_clientsession(self.hass).post(o['journey_endpoint'],json=dict(destination=intent['destination'],mode=intent['mode'],origin=o['home_origin']),headers=headers,allow_redirects=False) as response:
            if response.status!=200: raise ValueError('Route provider failed')
            # Stream to a hard cap, including chunked responses. No result references/URLs accepted.
            raw=bytearray()
            async for chunk in response.content.iter_chunked(4096):
                raw.extend(chunk)
                if len(raw)>24576: raise ValueError('Route result exceeds 24 KiB')
            result=json.loads(raw)
        fields(result,['state','summary','source','updated_at','stale_after_seconds','data'])
        if result['state'] not in ('ready','partial','unavailable','clarification'): raise ValueError('Unsupported provider state')
        if result['data'] and result['data'].get('traffic')=='live' and not o.get('journey_traffic',False): raise ValueError('Traffic not configured')
        return envelope(kind,response_id,result['state'],result['summary'],result['data'],result['source'],result['updated_at'],result['stale_after_seconds'])
