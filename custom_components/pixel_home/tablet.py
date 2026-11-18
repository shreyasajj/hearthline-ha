"""Scoped tablet transport. Credentials never authorize arbitrary HA services."""
import asyncio
import hashlib
import logging
import re
import secrets
import time
import uuid
from datetime import datetime, timezone, timedelta
from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.auth.permissions.const import POLICY_READ, POLICY_CONTROL
from homeassistant.core import Context, callback
from homeassistant.helpers.event import async_call_later, async_track_state_change_event, async_track_time_interval
from homeassistant.helpers.json import json_dumps
from homeassistant.helpers.dispatcher import async_dispatcher_send
from .tablet_contract import dashboard, TelemetryState, UUID, FAVORITES
from .bridge import Bridge
from .providers import TemplateProviders

DOMAIN='pixel_home'
_LOGGER=logging.getLogger(__name__)
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
# Push stream: one WebSocket per foreground tablet session; extra sockets are refused.
STREAM_LIMIT=4
STREAM_PING_SECONDS=10
STREAM_QUEUE=256
# After the last stream closes, telemetry is withdrawn unless the tablet reconnects in time.
STREAM_GRACE_SECONDS=10
# Pairing codes: shown to an HA administrator in the entry options, typed on the tablet once.
CODE_DIGITS=8
CODE_TTL_SECONDS=300
# Wrong codes across all entries within one TTL before every open code is withdrawn.
CODE_FAILURE_LIMIT=10
CODE=re.compile(r'\d{%d}'%CODE_DIGITS)

class Tablet:
    def __init__(self,hass,entry):
        self.hass=hass; self.entry=entry; self.telemetry=TelemetryState(); self.loaded_options=dict(entry.options)
        self.bridge=Bridge(entry.entry_id.lower().replace("-","_"))
        self.providers=TemplateProviders(hass,self.bridge,self.definition)
        self.streams=set(); self._stream_grace=None
        self.pairing_code=None  # (code, owner_id, monotonic expiry); memory only, never persisted.
    def issue_code(self,owner_id):
        if self.entry.data.get('credential_hash'): raise ValueError('Already paired')
        code=''.join(secrets.choice('0123456789') for _ in range(CODE_DIGITS))
        self.pairing_code=(code,owner_id,time.monotonic()+CODE_TTL_SECONDS)
        return code
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
    def stream_opened(self,ws):
        self.streams.add(ws)
        if self._stream_grace: self._stream_grace(); self._stream_grace=None
    def stream_closed(self,ws):
        self.streams.discard(ws)
        if self.streams or self._stream_grace or self.telemetry.session is None: return
        @callback
        def expire(now):
            self._stream_grace=None
            # A tablet that used the push stream and left the foreground is reported unavailable promptly,
            # instead of waiting for the heartbeat timeout. Its next push must start a new session.
            if not self.streams and self.telemetry.session is not None:
                self.telemetry=TelemetryState(); self.notify()
        self._stream_grace=async_call_later(self.hass,STREAM_GRACE_SECONDS,expire)
    def close_streams(self,code=1012):
        if self._stream_grace: self._stream_grace(); self._stream_grace=None
        for ws in list(self.streams): self.hass.async_create_task(ws.close(code=code))
        self.streams.clear()
    def invalidate(self):
        self.close_streams(); self.pairing_code=None
        self.telemetry=TelemetryState(); self.providers.cancel(); self.bridge=Bridge(self.entry.entry_id.lower().replace("-","_"))
        self.providers=TemplateProviders(self.hass,self.bridge,self.definition); self.notify()

async def setup_tablet(hass,entry):
    tablet=Tablet(hass,entry)
    hass.data.setdefault('pixel_home_tablets',{})[entry.entry_id]=tablet
    @callback
    def heartbeat(now): tablet.notify()
    entry.async_on_unload(async_track_time_interval(hass,heartbeat,timedelta(seconds=15)))
    entry.async_on_unload(entry.add_update_listener(updated))
    @callback
    def stopping(event): tablet.close_streams(1001)  # Going away: do not hold the HTTP server open on shutdown.
    entry.async_on_unload(hass.bus.async_listen('homeassistant_stop',stopping))
    from .llm_api import DisplayAPI
    from homeassistant.helpers import llm
    entry.async_on_unload(llm.async_register_api(hass,DisplayAPI(hass=hass,id='pixel_home_'+entry.entry_id,name='hearthline-ha '+entry.title)))
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
        return self.json({'schema_version':1,'tablets':[{'entry_id':t.entry.entry_id,'name':t.definition['name'],'paired':bool(t.entry.data.get('credential_hash')),'tablet_id':t.entry.data.get('discovery',{}).get('tablet_id')} for t in request.app['hass'].data.get('pixel_home_tablets',{}).values()]})
    async def post(self,request):
        hass=request.app['hass']; user=request['hass_user']
        if not user.is_admin: raise web.HTTPForbidden()
        body=await bounded_json(request)
        if set(body)!={'entry_id','tablet_id','schema_version'} or type(body['schema_version']) is not int or body['schema_version']!=1 or not UUID.fullmatch(str(body['tablet_id'])): raise web.HTTPBadRequest()
        tablet=hass.data.get('pixel_home_tablets',{}).get(body['entry_id'])
        if tablet is None: raise web.HTTPNotFound()
        return self.json(issue_credential(hass,tablet,body['tablet_id'],user.id))

def issue_credential(hass,tablet,tablet_id,owner_id):
    """Pair one tablet to one entry, as owner_id. Shared by token and code pairing."""
    expected=tablet.entry.data.get('discovery',{}).get('tablet_id')
    if expected is not None and tablet_id!=expected: raise web.HTTPForbidden(reason='This dashboard belongs to a different tablet')
    if tablet.entry.data.get('credential_hash'): raise web.HTTPConflict(reason='Revoke existing pairing in options first')
    if any(t is not tablet and t.entry.data.get('credential_hash') and t.entry.data.get('tablet_id')==tablet_id for t in hass.data['pixel_home_tablets'].values()): raise web.HTTPConflict()
    # Permission checks are repeated at each state read and control dispatch.
    token=secrets.token_urlsafe(48)
    tablet.pairing_code=None
    hass.config_entries.async_update_entry(tablet.entry,data={**tablet.entry.data,'tablet_id':tablet_id,'owner_id':owner_id,'credential_hash':hashlib.sha256(token.encode()).hexdigest()})
    return {'schema_version':1,'entry_id':tablet.entry.entry_id,'credential':token,'display_id':tablet.bridge.display_id}

class PairCodeView(HomeAssistantView):
    """Pairing without an account token: the tablet presents a short-lived code an administrator created."""
    url='/api/pixel_home/pair/code'
    name='api:pixel_home:pair:code'
    requires_auth=False  # The single-use code is the authorization; see issue_code.
    async def post(self,request):
        hass=request.app['hass']
        body=await bounded_json(request)
        if set(body)!={'schema_version','tablet_id','code'} or type(body['schema_version']) is not int or body['schema_version']!=1 \
            or not isinstance(body['code'],str) or not UUID.fullmatch(str(body['tablet_id'])): raise web.HTTPBadRequest()
        code=body['code'].replace('-','').replace(' ','')
        if not CODE.fullmatch(code): raise web.HTTPBadRequest()
        now=time.monotonic(); tablets=hass.data.get('pixel_home_tablets',{}).values()
        failures=hass.data.setdefault('pixel_home_code_failures',[])
        failures[:]=[t for t in failures if now-t<CODE_TTL_SECONDS]
        if len(failures)>=CODE_FAILURE_LIMIT: raise web.HTTPTooManyRequests()
        match=None
        for tablet in tablets:  # Compare against every open code; no early exit.
            open_code=tablet.pairing_code
            if open_code and open_code[2]>now and secrets.compare_digest(open_code[0],code): match=tablet
        if match is None:
            failures.append(now)
            if len(failures)>=CODE_FAILURE_LIMIT:
                for tablet in tablets: tablet.pairing_code=None
            from homeassistant.components.http.ban import process_wrong_login
            await process_wrong_login(request)
            raise web.HTTPUnauthorized()
        owner_id=match.pairing_code[1]
        match.pairing_code=None  # Single use, whatever the outcome below.
        owner=await hass.auth.async_get_user(owner_id)
        if not owner or not owner.is_active or not owner.is_admin: raise web.HTTPUnauthorized()
        return self.json(issue_credential(hass,match,body['tablet_id'],owner.id))

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
            if operation=='editor': return self.json(editor_catalog(tablet,user,entry_id,request.query.get('query','')))
            config=dashboard(tablet.definition,tablet.hass.states)
            if operation=='config': return self.json(config_payload(tablet,entry_id,config))
            if operation=='stream': return await stream(request,tablet,user,entry_id,config)
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
            if operation=='states': return self.json(selected_states(tablet,user,config))
        except (ValueError,TypeError,KeyError): raise web.HTTPBadRequest(reason='Dashboard selection is no longer valid') from None
        raise web.HTTPNotFound()
    async def post(self,request,entry_id,operation):
        tablet,user=await self.authenticate(request,entry_id)
        body=await bounded_json(request)
        try:
            if operation=='editor':
                if not user.is_admin: raise web.HTTPForbidden()
                if set(body)!={'schema_version','revision','title','favorites','scenes','players'} or type(body['schema_version']) is not int or body['schema_version']!=1: raise ValueError()
                if body['revision']!=tablet.revision: raise web.HTTPConflict(reason='Dashboard changed; reload before saving')
                definition={**tablet.definition,'name':body['title'],**{key:body[key] for key in ('favorites','scenes','players')}}
                config=dashboard(definition,tablet.hass.states)
                for entity in config['favorites']+config['scenes']+config['players']:
                    if not user.permissions.check_entity(entity,POLICY_READ) or not user.permissions.check_entity(entity,POLICY_CONTROL): raise web.HTTPForbidden()
                # Apply this narrow edit in place: a full entry reload briefly removes the
                # credential endpoint and can incorrectly look like revocation to the tablet.
                tablet.loaded_options=dict(definition)
                tablet.hass.config_entries.async_update_entry(tablet.entry,options=definition)
                tablet.providers.options=definition
                tablet.close_streams(); tablet.notify()
                return self.json(config_payload(tablet,entry_id,config))
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

def editor_catalog(tablet,user,entry_id,query):
    """Bounded entity discovery for the paired administrator's own tablet dashboard."""
    if not user.is_admin: raise web.HTTPForbidden()
    if not isinstance(query,str) or len(query)>80 or any(ord(c)<32 for c in query): raise ValueError()
    query=query.casefold().strip()
    choices=[]
    for state in tablet.hass.states.async_all():
        if state.domain not in FAVORITES|{'scene','media_player'}: continue
        if not user.permissions.check_entity(state.entity_id,POLICY_READ) or not user.permissions.check_entity(state.entity_id,POLICY_CONTROL): continue
        name=str(state.attributes.get('friendly_name',state.entity_id))[:160]
        if query and query not in (state.entity_id+' '+name).casefold(): continue
        choices.append({'entity_id':state.entity_id,'name':name,'state':state.state[:80]})
    choices.sort(key=lambda item:(item['name'].casefold(),item['entity_id']))
    return {'schema_version':1,'config':config_payload(tablet,entry_id,dashboard(tablet.definition,tablet.hass.states,allow_missing=True)),
            'choices':choices[:200],'truncated':len(choices)>200}

def config_payload(tablet,entry_id,config):
    return {'schema_version':1,'revision':tablet.revision,'display_id':tablet.bridge.display_id,'entry_id':entry_id,'dashboard':config,'capabilities':{'telemetry':1,'display_protocols':['1.0','1.1'],'remote_configuration':False}}

def selected_entities(config):
    ids=set(config['favorites']+config['scenes']+config['players']+config['home_entities'])
    if config['weather_entity']: ids.add(config['weather_entity'])
    return sorted(ids)

def selected_states(tablet,user,config):
    return [tablet.hass.states.get(x).as_dict() for x in selected_entities(config) if user.permissions.check_entity(x,POLICY_READ) and tablet.hass.states.get(x)]

async def stream(request,tablet,user,entry_id,config):
    """Server-to-tablet push of the selected entities. The tablet sends nothing on this socket.

    Same scoped credential, entity selection and owner read permission as GET states. The socket is
    closed on revocation, entry reload, owner deactivation or removal, a slow reader or HA shutdown,
    and the tablet reconnects.
    """
    if len(tablet.streams)>=STREAM_LIMIT: raise web.HTTPTooManyRequests()
    hass=tablet.hass; credential=tablet.entry.data.get('credential_hash')
    ws=web.WebSocketResponse(max_msg_size=1024)  # Liveness comes from the application ping below.
    tablet.stream_opened(ws)  # Counted before the handshake yields, so concurrent opens cannot pass the limit.
    try:
        await ws.prepare(request)
    except BaseException:
        tablet.stream_closed(ws); raise
    queue=asyncio.Queue(maxsize=STREAM_QUEUE); overflowed=False
    @callback
    def changed(event):
        nonlocal overflowed
        entity=event.data['entity_id']; new=event.data['new_state']
        if overflowed or not user.permissions.check_entity(entity,POLICY_READ): return
        try: queue.put_nowait({'type':'state','state':new.as_dict()} if new else {'type':'removed','entity_id':entity})
        except asyncio.QueueFull:
            overflowed=True; hass.async_create_task(ws.close(code=1013))  # Reader too slow: resynchronize.
    async def authorized():
        # Re-fetch the owner: a removed user keeps is_active on any object already held.
        current=await hass.auth.async_get_user(user.id)
        return bool(current and current.is_active and tablet.entry.data.get('credential_hash')==credential)
    async def write():
        loop=asyncio.get_running_loop()
        try:
            await ws.send_str(json_dumps({'type':'snapshot','schema_version':1,'config':config_payload(tablet,entry_id,config),'states':selected_states(tablet,user,config)}))
            checked=loop.time()
            while True:
                try: message=await asyncio.wait_for(queue.get(),STREAM_PING_SECONDS)
                except TimeoutError: message={'type':'ping'}  # Liveness for the tablet and any proxy.
                # Authorization is re-checked on a timer, not only when idle, so a busy stream cannot outlive it.
                if loop.time()-checked>=STREAM_PING_SECONDS:
                    if not await authorized(): await ws.close(code=1008); return
                    checked=loop.time()
                await ws.send_str(json_dumps(message))
        except (ConnectionError,RuntimeError): pass  # The tablet went away; the reader loop ends the stream.
        except Exception:  # pylint: disable=broad-except
            _LOGGER.exception('Tablet stream writer failed'); await ws.close(code=1011)
    ids=selected_entities(config)
    unsubscribe=async_track_state_change_event(hass,ids,changed) if ids else None
    writer=hass.async_create_background_task(write(),'pixel_home tablet stream')
    try:
        async for message in ws:
            # Read-only channel: any tablet data frame is a protocol violation.
            if message.type in (web.WSMsgType.TEXT,web.WSMsgType.BINARY): await ws.close(code=1003); break
    finally:
        if unsubscribe: unsubscribe()
        writer.cancel()
        tablet.stream_closed(ws)
    return ws

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
