"""Scoped tablet transport. Credentials never authorize arbitrary HA services."""
import asyncio
import hashlib
import secrets
import uuid
from datetime import datetime, timezone, timedelta
from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.auth.permissions.const import POLICY_READ, POLICY_CONTROL
from homeassistant.core import Context
from homeassistant.helpers.event import async_track_time_interval
from homeassistant.helpers.dispatcher import async_dispatcher_send
from .tablet_contract import dashboard, TelemetryState, UUID
from .bridge import Bridge
from .providers import TemplateProviders

DOMAIN='pixel_home'
SIGNAL='pixel_home_tablet_'
ACTIONS={
    'light': {'turn_on': {'brightness_pct'}, 'turn_off':set()},
    'switch': {'turn_on':set(),'turn_off':set()},
    'lock': {'lock':set(),'unlock':set()},
    'scene': {'turn_on':set()},
    'climate': {'set_temperature':{'temperature'}},
    'media_player': {x:set() for x in ['media_play_pause','media_play','media_pause','media_next_track','media_previous_track']},
}
ACTIONS['media_player'].update(volume_set={'volume_level'},play_media={'media_content_id','media_content_type'})

class Tablet:
    def __init__(self,hass,entry):
        self.hass=hass; self.entry=entry; self.telemetry=TelemetryState(); self.loaded_options=dict(entry.options)
        self.bridge=Bridge(entry.entry_id.lower().replace("-","_"))
        self.providers=TemplateProviders(hass,self.bridge,self.definition)
    @property
    def definition(self): return dict(self.entry.options or self.entry.data['definition'])
    @property
    def revision(self):
        import json
        return hashlib.sha256(json.dumps(self.definition,sort_keys=True).encode()).hexdigest()[:16]
    @property
    def available(self):
        received=self.telemetry.received
        return bool(self.entry.data.get('credential_hash') and received and
            (datetime.now(timezone.utc)-received).total_seconds()<self.definition.get('heartbeat_timeout',120))
    def notify(self): async_dispatcher_send(self.hass,SIGNAL+self.entry.entry_id)
    def invalidate(self):
        self.telemetry=TelemetryState(); self.providers.cancel(); self.bridge=Bridge(self.entry.entry_id.lower().replace("-","_"))
        self.providers=TemplateProviders(self.hass,self.bridge,self.definition); self.notify()

async def setup_tablet(hass,entry):
    tablet=Tablet(hass,entry)
    hass.data.setdefault('pixel_home_tablets',{})[entry.entry_id]=tablet
    entry.async_on_unload(async_track_time_interval(hass,lambda now: tablet.notify(),timedelta(seconds=15)))
    entry.async_on_unload(entry.add_update_listener(updated))
    from .llm_api import DisplayAPI
    from homeassistant.helpers import llm
    entry.async_on_unload(llm.async_register_api(hass,DisplayAPI(hass=hass,id='pixel_home_'+entry.entry_id,name='Hearthline '+entry.title)))
    await hass.config_entries.async_forward_entry_setups(entry,['sensor','binary_sensor'])
    if entry.data.get('tablet_id'):
        from homeassistant.helpers import device_registry as dr
        device=dr.async_get(hass).async_get_device(identifiers={(DOMAIN,entry.unique_id)})
        if device and tablet.definition.get('area_id'): dr.async_get(hass).async_update_device(device.id,area_id=tablet.definition['area_id'])
    return True

async def updated(hass,entry):
    tablet=hass.data.get('pixel_home_tablets',{}).get(entry.entry_id)
    if entry.options.get('revoke'):
        if tablet: tablet.invalidate()
        data={k:v for k,v in entry.data.items() if k not in ('credential_hash','owner_id')}
        options=dict(entry.options); options.pop('revoke',None)
        hass.config_entries.async_update_entry(entry,data=data,options=options)
        return
    if tablet and tablet.loaded_options != dict(entry.options):
        await hass.config_entries.async_reload(entry.entry_id)

class PairView(HomeAssistantView):
    url='/api/pixel_home/pair'
    name='api:pixel_home:pair'
    requires_auth=True
    async def get(self,request):
        user=request['hass_user']
        if not user.is_admin: raise web.HTTPForbidden()
        return self.json({'schema_version':1,'tablets':[{'entry_id':t.entry.entry_id,'name':t.definition['name'],'paired':bool(t.entry.data.get('credential_hash'))} for t in request.app['hass'].data.get('pixel_home_tablets',{}).values()]})
    async def post(self,request):
        hass=request.app['hass']; user=request['hass_user']
        if not user.is_admin: raise web.HTTPForbidden()
        body=await bounded_json(request)
        if set(body)!={'entry_id','tablet_id','schema_version'} or type(body['schema_version']) is not int or body['schema_version']!=1 or not UUID.fullmatch(str(body['tablet_id'])): raise web.HTTPBadRequest()
        tablet=hass.data.get('pixel_home_tablets',{}).get(body['entry_id'])
        if tablet is None: raise web.HTTPNotFound()
        if tablet.entry.data.get('credential_hash'): raise web.HTTPConflict(reason='Revoke existing pairing in options first')
        if any(t is not tablet and t.entry.data.get('credential_hash') and t.entry.data.get('tablet_id')==body['tablet_id'] for t in hass.data['pixel_home_tablets'].values()): raise web.HTTPConflict()
        # Permission checks are repeated at each state read and control dispatch.
        token=secrets.token_urlsafe(48)
        hass.config_entries.async_update_entry(tablet.entry,data={**tablet.entry.data,'tablet_id':body['tablet_id'],'owner_id':user.id,'credential_hash':hashlib.sha256(token.encode()).hexdigest()})
        return self.json({'schema_version':1,'entry_id':tablet.entry.entry_id,'credential':token,'display_id':tablet.bridge.display_id})

async def bounded_json(request):
    data=bytearray()
    async for chunk in request.content.iter_chunked(1024):
        data.extend(chunk)
        if len(data)>8192: raise web.HTTPRequestEntityTooLarge(max_size=8192,actual_size=len(data))
    try:
        import json
        value=json.loads(data)
        if not isinstance(value,dict): raise ValueError()
        return value
    except (ValueError,TypeError): raise web.HTTPBadRequest() from None

class TabletView(HomeAssistantView):
    url='/api/pixel_home/tablet/{entry_id}/{operation}'
    name='api:pixel_home:tablet'
    requires_auth=False  # Scoped credential authenticated below, never an anonymous webhook.
    async def authenticate(self,request,entry_id):
        hass=request.app['hass']; tablet=hass.data.get('pixel_home_tablets',{}).get(entry_id)
        header=request.headers.get('Authorization','')
        if tablet is None or not header.startswith('Bearer ') or len(header)>256: raise web.HTTPUnauthorized()
        expected=tablet.entry.data.get('credential_hash')
        if not expected or not secrets.compare_digest(hashlib.sha256(header[7:].encode()).hexdigest(),expected): raise web.HTTPUnauthorized()
        user=await hass.auth.async_get_user(tablet.entry.data['owner_id'])
        if not user or not user.is_active: raise web.HTTPUnauthorized()
        return tablet,user
    async def get(self,request,entry_id,operation):
        tablet,user=await self.authenticate(request,entry_id)
        try:
            config=dashboard(tablet.definition,tablet.hass.states)
            if operation=='config':
                return self.json({'schema_version':1,'revision':tablet.revision,'display_id':tablet.bridge.display_id,'entry_id':entry_id,'dashboard':config,'capabilities':{'telemetry':1,'display_protocols':['1.0','1.1'],'remote_configuration':False}})
            if operation=='artwork':
                entity=request.query.get('entity_id')
                if entity not in config['players'] or not user.permissions.check_entity(entity,POLICY_READ): raise web.HTTPForbidden()
                from homeassistant.components.media_player.const import DATA_COMPONENT
                component=tablet.hass.data.get(DATA_COMPONENT)
                player=component.get_entity(entity) if component else None
                if player is None: raise web.HTTPNotFound()
                async with asyncio.timeout(10): data,content_type=await player.async_get_media_image()
                if data is None: raise web.HTTPNotFound()
                if len(data)>4*1024*1024 or content_type not in ('image/jpeg','image/png','image/webp'): raise web.HTTPBadRequest()
                return web.Response(body=data,content_type=content_type,headers={'Cache-Control':'private, max-age=30'})
            if operation=='states':
                ids=set(config['favorites']+config['scenes']+config['players']+config['home_entities'])
                if config['weather_entity']: ids.add(config['weather_entity'])
                return self.json([tablet.hass.states.get(x).as_dict() for x in sorted(ids) if user.permissions.check_entity(x,POLICY_READ) and tablet.hass.states.get(x)])
        except (ValueError,TypeError,KeyError): raise web.HTTPBadRequest(reason='Dashboard selection is no longer valid') from None
        raise web.HTTPNotFound()
    async def post(self,request,entry_id,operation):
        tablet,user=await self.authenticate(request,entry_id)
        body=await bounded_json(request)
        try:
            if operation=='session':
                if body!={}: raise ValueError()
                session=str(uuid.uuid4()); tablet.telemetry.begin(session); tablet.notify()
                return self.json({'schema_version':1,'session_id':session})
            if operation=='telemetry':
                tablet.telemetry.accept(body,tablet.entry.data['tablet_id'],datetime.now(timezone.utc)); tablet.notify()
                return self.json({'accepted':True})
            if operation=='unpair':
                if body!={}: raise ValueError()
                tablet.hass.config_entries.async_update_entry(tablet.entry,data={k:v for k,v in tablet.entry.data.items() if k not in ('credential_hash','owner_id')})
                tablet.invalidate(); return self.json({'revoked':True})
            if operation=='display':
                if body.get('display_id')!=tablet.bridge.display_id: raise ValueError('Wrong display')
                return self.json(tablet.bridge.exchange(body,user.id))
            if operation=='command':
                await command(tablet,user,body)
                return self.json({'accepted':True})
        except (ValueError,TypeError,KeyError): raise web.HTTPBadRequest(reason='Invalid tablet request') from None
        raise web.HTTPNotFound()

async def command(tablet,user,body):
    if set(body)!={'entity_id','action','values'}: raise ValueError()
    entity=body['entity_id']; action=body['action']; values=body['values']; domain=entity.split('.')[0]
    config=dashboard(tablet.definition,tablet.hass.states)
    if entity not in config['favorites']+config['scenes']+config['players'] or not user.permissions.check_entity(entity,POLICY_CONTROL): raise web.HTTPForbidden()
    allowed=ACTIONS.get(domain,{}).get(action)
    if allowed is None or not isinstance(values,dict): raise ValueError()
    if domain=='light' and action=='turn_on':
        if set(values)-allowed: raise ValueError()
    elif set(values)!=allowed: raise ValueError()
    for key,low,high in [('brightness_pct',0,100),('volume_level',0,1),('temperature',-50,100)]:
        if key in values and (type(values[key]) not in (int,float) or not low<=values[key]<=high): raise ValueError()
    if action=='play_media' and not any(values=={'media_content_id':s['uri'],'media_content_type':s['type']} for s in config['music']): raise ValueError('Unknown shortcut')
    state=tablet.hass.states.get(entity)
    if state is None or state.state=='unavailable': raise ValueError('Entity unavailable')
    if domain=='light' and 'brightness_pct' in values and not set(state.attributes.get('supported_color_modes',[]))-{'onoff','unknown'}: raise ValueError('Brightness unsupported')
    if domain=='climate':
        if not state.attributes.get('supported_features',0)&1: raise ValueError('Temperature unsupported')
        if not state.attributes.get('min_temp',-50)<=values['temperature']<=state.attributes.get('max_temp',100): raise ValueError('Outside device limits')
    if domain=='media_player':
        bits={'media_pause':1,'media_play_pause':1,'volume_set':4,'media_previous_track':16,'media_next_track':32,'play_media':512,'media_play':16384}
        if not state.attributes.get('supported_features',0)&bits[action]: raise ValueError('Action unsupported')
    await tablet.hass.services.async_call(domain,action,dict(values,entity_id=entity),blocking=True,context=Context(user_id=user.id))
