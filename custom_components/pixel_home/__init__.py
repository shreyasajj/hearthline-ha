"""Pixel Home display transport and discoverable LLM API."""
from homeassistant.core import SupportsResponse
from homeassistant.exceptions import ServiceValidationError
from .bridge import Bridge

DOMAIN='pixel_home'
try:
    import probatio as schema
except ImportError:
    import voluptuous as schema

CONFIG_SCHEMA = schema.Schema({schema.Optional(DOMAIN): schema.Any(None, schema.Schema({
    schema.Optional('display_id', default='pixel_tablet'): schema.Match(r'^[a-z0-9_]{1,48}$'),
    schema.Optional('area_name'): schema.All(str, schema.Length(min=1, max=80)),
    schema.Optional('llm_api', default=True): bool,
    schema.Optional('weather_entity'): schema.Match(r'^weather\.[a-z0-9_]+$'),
    schema.Optional('weather_stale_seconds',default=3600): schema.All(int,schema.Range(min=1,max=86400)),
    schema.Optional('home_entities',default=[]): schema.All([schema.Match(r'^[a-z_]+\.[a-z0-9_]+$')],schema.Length(max=16)),
    schema.Optional('home_stale_seconds',default=30): schema.All(int,schema.Range(min=1,max=86400)),
    schema.Optional('journey_endpoint'): str,
    schema.Optional('home_origin'): schema.All(str,schema.Length(min=1,max=240)),
    schema.Optional('journey_token'): str,
    schema.Optional('journey_traffic',default=False): bool,
}))}, extra=schema.ALLOW_EXTRA)

async def async_setup(hass, config):
    from .tablet import PairView, PairCodeView, TabletView
    hass.http.register_view(PairView())
    hass.http.register_view(PairCodeView())
    hass.http.register_view(TabletView())
    options=config.get(DOMAIN) or {}
    bridge=Bridge(options.get('display_id','pixel_tablet'),area_name=options.get('area_name'))
    hass.data[DOMAIN]=bridge
    from .providers import TemplateProviders
    providers=TemplateProviders(hass,bridge,options)
    hass.bus.async_listen_once('homeassistant_stop',lambda event: providers.cancel())
    async def handle(call):
        try:
            data=dict(call.data)
            target=data.get('display_id')
            selected=next((t for t in hass.data.get('pixel_home_tablets',{}).values() if t.bridge.display_id==target),None)
            active=selected.bridge if selected else bridge
            active_providers=selected.providers if selected else providers
            if selected and call.context.user_id != selected.entry.data.get('owner_id'): raise ValueError('Display owner authorization required')
            if call.service=='exchange': return active.exchange(data,call.context.user_id)
            if data.get('display_id')!=active.display_id: raise ValueError('Unknown display')
            if call.service=='get_screen_context': return active.context()
            if call.service=='present_template': return active_providers.present(data['session_id'],data['capability_revision'],data['response_id'],data['intent'])
            if call.service=='present_response': return active.present(data['session_id'],data['capability_revision'],data['request'])
            if call.service=='get_response_status': return active.status(data['response_id'], data['session_id'])
            if call.service=='dismiss_response': return active.dismiss(data['session_id'],data['response_id'])
        except (KeyError,ValueError,TypeError) as err:
            raise ServiceValidationError(str(err)) from err
    for action in ('exchange','get_screen_context','present_response','present_template','get_response_status','dismiss_response'):
        hass.services.async_register(DOMAIN,action,handle,supports_response=SupportsResponse.ONLY)
    if options.get('llm_api',True):
        from .llm_api import DisplayAPI
        from homeassistant.helpers import llm
        unregister=llm.async_register_api(hass,DisplayAPI(hass=hass,id='pixel_home',name='hearthline-ha legacy display'))
        hass.bus.async_listen_once('homeassistant_stop',lambda event: unregister())
    return True

async def async_setup_entry(hass,entry):
    from .tablet import setup_tablet
    return await setup_tablet(hass,entry)

async def async_unload_entry(hass,entry):
    unloaded=await hass.config_entries.async_unload_platforms(entry,['sensor','binary_sensor'])
    if unloaded:
        tablet=hass.data['pixel_home_tablets'].pop(entry.entry_id)
        tablet.invalidate(); tablet.providers.cancel()
    return unloaded
