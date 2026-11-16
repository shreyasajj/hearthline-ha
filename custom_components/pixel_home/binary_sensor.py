from homeassistant.components.binary_sensor import BinarySensorEntity, BinarySensorDeviceClass
from .tablet_entity import TabletEntity

async def async_setup_entry(hass,entry,async_add_entities):
    t=hass.data['pixel_home_tablets'][entry.entry_id]
    async_add_entities([TabletBinary(t,k,n,True) for k,n in [
        ('charging','Charging'),('dock_state','Docked'),('foreground','Foreground'),
        ('interactive','Interactive'),('locked','Locked'),('connection','Telemetry connected'),
        ('dock_test_active','Local dock test active'),('protected_dock_view_visible','Protected view visible'),
        ('dock_display_enabled','Dock display preference')]])

class TabletBinary(TabletEntity,BinarySensorEntity):
    def __init__(self,*args):
        super().__init__(*args)
        if self.key=='charging': self._attr_device_class=BinarySensorDeviceClass.BATTERY_CHARGING
        if self.key=='connection': self._attr_device_class=BinarySensorDeviceClass.CONNECTIVITY
    @property
    def available(self): return True if self.key=='connection' else self.tablet.available
    @property
    def is_on(self):
        if self.key=='connection': return self.tablet.available
        if self.key=='dock_state':
            if self.value=='unknown' or (self.tablet.telemetry.value or {}).get('dock_evidence')=='manual_override': return None
            return self.value=='docked'
        return self.value
