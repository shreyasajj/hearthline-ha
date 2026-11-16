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
    options=config.get(DOMAIN) or {}
    bridge=Bridge(options.get('display_id','pixel_tablet'),area_name=options.get('area_name'))
    hass.data[DOMAIN]=bridge
    from .providers import TemplateProviders
    providers=TemplateProviders(hass,bridge,options)
    hass.bus.async_listen_once('homeassistant_stop',lambda event: providers.cancel())
    async def handle(call):
        try:
            data=dict(call.data)
            if call.service=='exchange': return bridge.exchange(data,call.context.user_id)
            if data.get('display_id')!=bridge.display_id: raise ValueError('Unknown display')
            if call.service=='get_screen_context': return bridge.context()
            if call.service=='present_template': return providers.present(data['session_id'],data['capability_revision'],data['response_id'],data['intent'])
            if call.service=='present_response': return bridge.present(data['session_id'],data['capability_revision'],data['request'])
            if call.service=='get_response_status': return bridge.status(data['response_id'], data['session_id'])
            if call.service=='dismiss_response': return bridge.dismiss(data['session_id'],data['response_id'])
        except (KeyError,ValueError,TypeError) as err:
            raise ServiceValidationError(str(err)) from err
    for action in ('exchange','get_screen_context','present_response','present_template','get_response_status','dismiss_response'):
        hass.services.async_register(DOMAIN,action,handle,supports_response=SupportsResponse.ONLY)
    if options.get('llm_api',True):
        from .llm_api import DisplayAPI
        from homeassistant.helpers import llm
        unregister=llm.async_register_api(hass,DisplayAPI(hass=hass,id='pixel_home',name='Pixel Home display'))
        hass.bus.async_listen_once('homeassistant_stop',lambda event: unregister())
    return True
