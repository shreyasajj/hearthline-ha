"""One config entry per tablet; options are the server-owned dashboard."""
import uuid
import voluptuous as vol
from homeassistant import config_entries
from homeassistant.core import callback
from homeassistant.helpers import selector
from .tablet_contract import dashboard, FAVORITES
from .discovery import identify_tablet, tablet_host, DiscoveryError, SERVICE_TYPE, PORT
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo

DOMAIN='pixel_home'

def form(defaults=None, options=False):
    d=defaults or {}
    fields={vol.Required('name',default=d.get('name','Hearthline')): selector.TextSelector(),
        vol.Optional('area_id', **({'default':d['area_id']} if d.get('area_id') else {})):selector.AreaSelector()}
    for key,domains in [('favorites',sorted(FAVORITES)),('scenes',['scene']),('players',['media_player']),('home_entities',['light','switch','climate','lock','binary_sensor','sensor'])]:
        fields[vol.Optional(key,default=d.get(key,[]))]=selector.EntitySelector(selector.EntitySelectorConfig(domain=domains,multiple=True))
    fields[vol.Optional('weather_entity', **({'default':d['weather_entity']} if d.get('weather_entity') else {}))]=selector.EntitySelector(selector.EntitySelectorConfig(domain='weather'))
    fields[vol.Optional('music',default=d.get('music',[]))]=selector.ObjectSelector()
    fields[vol.Optional('idle_seconds',default=d.get('idle_seconds',45))]=vol.All(vol.Coerce(int),vol.Range(min=15,max=600))
    fields[vol.Optional('heartbeat_timeout',default=d.get('heartbeat_timeout',120))]=vol.All(vol.Coerce(int),vol.Range(min=60,max=900))
    for key, kind in [('journey_endpoint',selector.TextSelectorType.URL),('home_origin',selector.TextSelectorType.TEXT),('journey_token',selector.TextSelectorType.PASSWORD)]:
        fields[vol.Optional(key,**({'default':d[key]} if d.get(key) else {}))]=selector.TextSelector(selector.TextSelectorConfig(type=kind))
    fields[vol.Optional('journey_traffic',default=d.get('journey_traffic',False))]=bool
    if not options:
        fields[vol.Optional('tablet_host', **({'default':d['tablet_host']} if d.get('tablet_host') else {}))]=selector.TextSelector()
    if options: fields[vol.Optional('revoke',default=False)]=bool
    return vol.Schema(fields)

class Flow(config_entries.ConfigFlow, domain=DOMAIN):
    VERSION=1
    def __init__(self):
        super().__init__()
        self._candidate=None

    def _known_tablet(self,tablet_id):
        return any(entry.data.get('tablet_id')==tablet_id or entry.data.get('discovery',{}).get('tablet_id')==tablet_id
            for entry in self._async_current_entries())

    async def async_step_zeroconf(self,discovery_info: ZeroconfServiceInfo):
        properties=discovery_info.properties
        identifier=properties.get('tablet_id')
        from .tablet_contract import UUID
        if discovery_info.type!=SERVICE_TYPE or discovery_info.port!=PORT or properties.get('schema_version')!='1' or not isinstance(identifier,str) or not UUID.fullmatch(identifier):
            return self.async_abort(reason='invalid_discovery')
        if self._known_tablet(identifier): return self.async_abort(reason='already_configured')
        await self.async_set_unique_id('tablet_'+identifier)
        self._abort_if_unique_id_configured()
        addresses=sorted(set([discovery_info.host]+[str(ip) for ip in discovery_info.ip_addresses]),key=lambda value: ':' in value)
        host=None
        for address in addresses:
            try: host=tablet_host(address); break
            except DiscoveryError: continue
        if host is None: return self.async_abort(reason='invalid_discovery')
        try:
            self._candidate=await identify_tablet(self.hass,host,discovery_info.port,identifier)
        except DiscoveryError:
            return self.async_abort(reason='cannot_connect')
        self.context['title_placeholders']={'name':self._candidate['name']}
        return await self.async_step_confirm()

    async def async_step_confirm(self,user_input=None):
        if self._candidate is None: return self.async_abort(reason='invalid_discovery')
        if user_input is not None: return await self.async_step_user()
        return self.async_show_form(step_id='confirm',data_schema=vol.Schema({}),
            description_placeholders={'name':self._candidate['name'],'host':self._candidate['host'],'tablet_id':self._candidate['tablet_id']})

    async def async_step_user(self,user_input=None):
        errors={}
        if user_input is not None:
            definition=dict(user_input)
            host=definition.pop('tablet_host',None)
            try:
                dashboard(definition,self.hass.states)
                if definition.get('area_id'):
                    from homeassistant.helpers import area_registry
                    if area_registry.async_get(self.hass).async_get_area(definition['area_id']) is None: raise ValueError('Unknown area')
                candidate=self._candidate
                if host is not None and not isinstance(host,str): raise DiscoveryError('Invalid IP')
                if host and host.strip():
                    candidate=await identify_tablet(self.hass,host,expected_id=candidate['tablet_id'] if candidate else None)
                if candidate:
                    if self._known_tablet(candidate['tablet_id']): return self.async_abort(reason='already_configured')
                    await self.async_set_unique_id('tablet_'+candidate['tablet_id'])
                    self._abort_if_unique_id_configured()
                else: await self.async_set_unique_id(str(uuid.uuid4()))
                data={'definition':definition,'revision':1}
                if candidate: data['discovery']=candidate
                return self.async_create_entry(title=definition['name'],data=data)
            except DiscoveryError: errors['base']='cannot_connect'
            except (ValueError,TypeError,KeyError): errors['base']='invalid_selection'
        defaults=user_input
        if defaults is None and self._candidate:
            defaults={'name':self._candidate['name'],'tablet_host':self._candidate['host']}
        return self.async_show_form(step_id='user',data_schema=form(defaults),errors=errors)

    @staticmethod
    @callback
    def async_get_options_flow(config_entry): return Options()

class Options(config_entries.OptionsFlow):
    async def async_step_init(self,user_input=None):
        errors={}
        if user_input is not None:
            try:
                dashboard(user_input,self.hass.states)
                if user_input.get('area_id'):
                    from homeassistant.helpers import area_registry
                    if area_registry.async_get(self.hass).async_get_area(user_input['area_id']) is None: raise ValueError('Unknown area')
                return self.async_create_entry(title='',data=user_input)
            except (ValueError,TypeError,KeyError): errors['base']='invalid_selection'
        return self.async_show_form(step_id='init',data_schema=form(user_input or self.config_entry.options or self.config_entry.data['definition'],True),errors=errors)
