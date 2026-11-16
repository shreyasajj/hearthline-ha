from homeassistant.components.sensor import SensorEntity, SensorDeviceClass, SensorStateClass
from .tablet_entity import TabletEntity

async def async_setup_entry(hass,entry,async_add_entities):
    t=hass.data['pixel_home_tablets'][entry.entry_id]
    async_add_entities([TabletSensor(t,key,name,diagnostic) for key,name,diagnostic in [
        ('battery_percent','Battery',False),('dock_evidence','Dock detection source',True),
        ('power_source','Power source',False),('app_version','App version',True),('last_seen','Last seen',True)]])

class TabletSensor(TabletEntity,SensorEntity):
    def __init__(self,*args):
        super().__init__(*args)
        if self.key=='battery_percent':
            self._attr_device_class=SensorDeviceClass.BATTERY
            self._attr_state_class=SensorStateClass.MEASUREMENT
            self._attr_native_unit_of_measurement='%'
        elif self.key=='last_seen': self._attr_device_class=SensorDeviceClass.TIMESTAMP
    @property
    def native_value(self):
        return self.tablet.telemetry.received if self.key=='last_seen' else self.value
