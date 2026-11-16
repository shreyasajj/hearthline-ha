async def async_get_config_entry_diagnostics(hass,entry):
    tablet=hass.data['pixel_home_tablets'][entry.entry_id]
    return {'schema_version':1,'paired':bool(entry.data.get('credential_hash')),
        'available':tablet.available,'revision':tablet.revision,
        'telemetry_received':tablet.telemetry.received is not None,
        'favorite_count':len(tablet.definition.get('favorites',[])),
        'player_count':len(tablet.definition.get('players',[]))}
