"""Read-only tablet entities; heartbeats do not add changing attributes."""
from homeassistant.helpers.entity import DeviceInfo, EntityCategory
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from .tablet import SIGNAL

class TabletEntity:
    _attr_should_poll=False
    _attr_has_entity_name=True
    def __init__(self,tablet,key,name,diagnostic=False):
        self.tablet=tablet; self.key=key
        self._attr_unique_id=f'{tablet.entry.unique_id}_{key}'
        self._attr_name=name
        if diagnostic: self._attr_entity_category=EntityCategory.DIAGNOSTIC
        if key in ('last_seen','app_version'): self._attr_entity_registry_enabled_default=False
    @property
    def device_info(self):
        identity=self.tablet.entry.unique_id
        return DeviceInfo(identifiers={('pixel_home',identity)},name=self.tablet.definition['name'],manufacturer='Hearthline',model='Android tablet',suggested_area=self.tablet.definition.get('area_id'))
    @property
    def available(self): return self.tablet.available
    @property
    def value(self): return (self.tablet.telemetry.value or {}).get(self.key)
    async def async_added_to_hass(self):
        await super().async_added_to_hass()
        self.async_on_remove(async_dispatcher_connect(self.hass,SIGNAL+self.tablet.entry.entry_id,self.async_write_ha_state))
