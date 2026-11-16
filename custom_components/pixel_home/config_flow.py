"""One config entry per tablet; options are the server-owned dashboard."""
import uuid
import voluptuous as vol
from homeassistant import config_entries
from homeassistant.core import callback
from homeassistant.helpers import selector
from .tablet_contract import dashboard, FAVORITES

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
    if options: fields[vol.Optional('revoke',default=False)]=bool
    return vol.Schema(fields)

class Flow(config_entries.ConfigFlow, domain=DOMAIN):
    VERSION=1
    async def async_step_user(self,user_input=None):
        errors={}
        if user_input is not None:
            try:
                dashboard(user_input,self.hass.states)
                if user_input.get('area_id'):
                    from homeassistant.helpers import area_registry
                    if area_registry.async_get(self.hass).async_get_area(user_input['area_id']) is None: raise ValueError('Unknown area')
                await self.async_set_unique_id(str(uuid.uuid4()))
                return self.async_create_entry(title=user_input['name'],data={'definition':user_input,'revision':1})
            except (ValueError,TypeError,KeyError): errors['base']='invalid_selection'
        return self.async_show_form(step_id='user',data_schema=form(user_input),errors=errors)

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
