from datetime import datetime, timezone, timedelta
import hashlib
import json
from pathlib import Path
from uuid import uuid4
import pytest
from pytest_homeassistant_custom_component.common import MockConfigEntry
from homeassistant import config_entries
from homeassistant.setup import async_setup_component
from custom_components.pixel_home.tablet_contract import telemetry, TelemetryState, dashboard

DEFINITION={'name':'Kitchen','favorites':['light.test'],'scenes':[],'players':[]}

async def setup(hass):
    hass.states.async_set('light.test','on',{'friendly_name':'Test light','supported_color_modes':['brightness']})
    assert await async_setup_component(hass,'pixel_home',{'pixel_home':{'llm_api':False}})
    entry=MockConfigEntry(domain='pixel_home',unique_id=str(uuid4()),title='Kitchen',data={'definition':DEFINITION,'revision':1})
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry

async def test_flow_and_options(hass):
    hass.states.async_set('light.test','unavailable')
    result=await hass.config_entries.flow.async_init('pixel_home',context={'source':config_entries.SOURCE_USER})
    assert result['type']=='form'
    result=await hass.config_entries.flow.async_configure(result['flow_id'],{'name':'Kitchen','favorites':['light.missing']})
    assert result['errors']=={'base':'invalid_selection'}
    result=await hass.config_entries.flow.async_configure(result['flow_id'],DEFINITION)
    assert result['type']=='create_entry'
    await hass.async_block_till_done()
    entry=result['result']
    result=await hass.config_entries.options.async_init(entry.entry_id)
    result=await hass.config_entries.options.async_configure(result['flow_id'],dict(DEFINITION,favorites=[]))
    assert result['type']=='create_entry'
    await hass.async_block_till_done()
    assert entry.options['favorites']==[]

async def test_pair_config_state_telemetry_revoke(hass,hass_client, hass_admin_user):
    entry=await setup(hass)
    client=await hass_client()
    response=await client.get('/api/pixel_home/pair')
    assert response.status==200
    assert (await response.json())['tablets'][0]['entry_id']==entry.entry_id
    tablet_id=str(uuid4())
    response=await client.post('/api/pixel_home/pair',json={'schema_version':1,'entry_id':entry.entry_id,'tablet_id':tablet_id})
    assert response.status==200
    credential=(await response.json())['credential']
    await hass.async_block_till_done()
    assert entry.data['credential_hash']==hashlib.sha256(credential.encode()).hexdigest()
    headers={'Authorization':'Bearer '+credential}
    base='/api/pixel_home/tablet/'+entry.entry_id
    response=await client.get(base+'/config',headers=headers)
    assert response.status==200
    config=await response.json()
    assert config['dashboard']['favorites']==['light.test']
    response=await client.get(base+'/states',headers=headers)
    assert (await response.json())[0]['entity_id']=='light.test'
    response=await client.post(base+'/session',headers=headers,json={})
    session=(await response.json())['session_id']
    payload=fixture(); payload.update(tablet_id=tablet_id,session_id=session,observed_at=datetime.now(timezone.utc).isoformat())
    response=await client.post(base+'/telemetry',headers=headers,json=payload)
    assert response.status==200
    response=await client.post(base+'/telemetry',headers=headers,json=payload)
    assert response.status==400
    await hass.async_block_till_done()
    states=[s for s in hass.states.async_all() if s.entity_id.startswith('sensor.kitchen')]
    assert any(s.state=='75' for s in states)
    tablet=hass.data['pixel_home_tablets'][entry.entry_id]
    tablet.telemetry.received=datetime.now(timezone.utc)-timedelta(seconds=121); tablet.notify()
    await hass.async_block_till_done()
    assert not tablet.available
    response=await client.post(base+'/unpair',headers=headers,json={})
    assert response.status==200
    response=await client.get(base+'/config',headers=headers)
    assert response.status==401

async def test_isolation_and_command_bounds(hass,hass_client):
    first=await setup(hass)
    second=MockConfigEntry(domain='pixel_home',unique_id=str(uuid4()),title='Other',data={'definition':dict(DEFINITION,name='Other'),'revision':1})
    second.add_to_hass(hass); assert await hass.config_entries.async_setup(second.entry_id)
    client=await hass_client()
    r=await client.post('/api/pixel_home/pair',json={'schema_version':1,'entry_id':first.entry_id,'tablet_id':str(uuid4())})
    token=(await r.json())['credential']; headers={'Authorization':'Bearer '+token}
    r=await client.get(f'/api/pixel_home/tablet/{second.entry_id}/config',headers=headers); assert r.status==401
    r=await client.post(f'/api/pixel_home/tablet/{first.entry_id}/command',headers=headers,json={'entity_id':'light.secret','action':'turn_on','values':{'brightness_pct':50}}); assert r.status==403
    r=await client.post(f'/api/pixel_home/tablet/{first.entry_id}/command',headers=headers,json={'entity_id':'light.test','action':'turn_on','values':{'brightness_pct':101}}); assert r.status==400
    r=await client.post(f'/api/pixel_home/tablet/{first.entry_id}/telemetry',headers=headers,data='x'*8193); assert r.status==413


def fixture():
    return json.loads((Path(__file__).resolve().parents[1]/'tests/fixtures/tablet-telemetry.json').read_text())

@pytest.mark.parametrize('key,value',[('sequence',True),('battery_percent',101),('locked',1),('foreground',None),('power_source','AC'),('dock_evidence','manual_override'),('observed_at','2020-01-01T00:00:00Z'),('unexpected','secret')])
def test_invalid_telemetry(key,value):
    payload=fixture(); payload[key]=value
    with pytest.raises((ValueError,TypeError)): telemetry(payload,datetime(2026,11,16,tzinfo=timezone.utc))

def test_sessions_and_unknown():
    payload=fixture(); now=datetime.fromisoformat(payload['observed_at'])
    state=TelemetryState(); state.begin(payload['session_id']); state.accept(payload,payload['tablet_id'],now)
    with pytest.raises(ValueError): state.accept(payload,payload['tablet_id'],now)
    state.begin(str(uuid4()))
    with pytest.raises(ValueError): state.accept(payload,payload['tablet_id'],now)
    payload.update(battery_percent=None,battery_status='unknown',charging=None,locked=None,dock_state='unknown')
    assert telemetry(payload,now)['battery_percent'] is None

async def test_options_revision_revoke_and_restart(hass,hass_client):
    entry=await setup(hass); client=await hass_client()
    r=await client.post('/api/pixel_home/pair',json={'schema_version':1,'entry_id':entry.entry_id,'tablet_id':str(uuid4())})
    headers={'Authorization':'Bearer '+(await r.json())['credential']}
    base=f'/api/pixel_home/tablet/{entry.entry_id}'
    before=await (await client.get(base+'/config',headers=headers)).json()
    hass.states.async_set('switch.other','unavailable')
    options=dict(DEFINITION,favorites=['switch.other','light.test'])
    hass.config_entries.async_update_entry(entry,options=options)
    await hass.async_block_till_done()
    after=await (await client.get(base+'/config',headers=headers)).json()
    assert before['revision']!=after['revision']
    assert after['dashboard']['favorites']==['switch.other','light.test']
    states=await (await client.get(base+'/states',headers=headers)).json()
    assert next(s for s in states if s['entity_id']=='switch.other')['state']=='unavailable'
    # HA reload fences prior telemetry and preserves pairing and entity identity.
    await hass.config_entries.async_reload(entry.entry_id)
    await hass.async_block_till_done()
    assert (await client.get(base+'/config',headers=headers)).status==200
    assert not hass.data['pixel_home_tablets'][entry.entry_id].available
    hass.config_entries.async_update_entry(entry,options=dict(options,revoke=True))
    await hass.async_block_till_done()
    assert (await client.get(base+'/config',headers=headers)).status==401
    assert not entry.data.get('credential_hash')

async def test_duplicate_pairing_and_owner_disabled(hass,hass_client,hass_admin_user):
    entry=await setup(hass); client=await hass_client()
    body={'schema_version':1,'entry_id':entry.entry_id,'tablet_id':str(uuid4())}
    r=await client.post('/api/pixel_home/pair',json=body)
    headers={'Authorization':'Bearer '+(await r.json())['credential']}
    assert (await client.post('/api/pixel_home/pair',json=body)).status==409
    hass_admin_user.is_active=False
    assert (await client.get(f'/api/pixel_home/tablet/{entry.entry_id}/config',headers=headers)).status==401

async def test_scoped_display_and_service_permissions(hass,hass_client):
    entry=await setup(hass)
    assert await async_setup_component(hass,'api',{})
    client=await hass_client()
    result=await client.post('/api/pixel_home/pair',json={'schema_version':1,'entry_id':entry.entry_id,'tablet_id':str(uuid4())})
    result=await result.json(); headers={'Authorization':'Bearer '+result['credential']}
    from custom_components.pixel_home.protocol import REVISION
    payload={'display_id':result['display_id'],'session_id':'session-1','capability_revision':REVISION,'foreground':True,'locked':False}
    endpoint=f'/api/pixel_home/tablet/{entry.entry_id}/display'
    assert (await client.post(endpoint,headers=headers,json=payload)).status==200
    payload['display_id']='pixel_tablet'
    assert (await client.post(endpoint,headers=headers,json=payload)).status==400
    # Legacy bridge is still present and independently addressed.
    assert hass.data['pixel_home'].display_id=='pixel_tablet'
    calls=[]
    async def light_on(call): calls.append(call)
    hass.services.async_register('light','turn_on',light_on)
    r=await client.post(f'/api/pixel_home/tablet/{entry.entry_id}/command',headers=headers,json={'entity_id':'light.test','action':'turn_on','values':{'brightness_pct':20}})
    assert r.status==200 and calls[0].context.user_id==entry.data['owner_id']
    # Scoped credentials never authorize the general HA state or service API.
    assert (await client.get('/api/states',headers=headers)).status==401

@pytest.mark.parametrize('patch',[{'schema_version':2},{'sequence':-1},{'battery_percent':1.5},{'battery_status':'full','charging':True},{'dock_state':'docked','dock_evidence':'manual_override'},{'app_version':'x'*33},{'observed_at':'2026-11-16T00:00:00'},{'app_version':{'nested':'x'}}])
def test_more_invalid_contracts(patch):
    payload=fixture(); payload.update(patch)
    with pytest.raises((ValueError,TypeError)): telemetry(payload,datetime(2026,11,16,tzinfo=timezone.utc))

def test_valid_override_and_boundary_sequences():
    payload=fixture(); payload.update(detection_mode='force_docked',dock_evidence='manual_override',dock_state='docked')
    now=datetime.fromisoformat(payload['observed_at'])
    assert telemetry(payload,now)['dock_evidence']=='manual_override'
    state=TelemetryState();state.begin(payload['session_id']);state.accept(payload,payload['tablet_id'],now)
    payload.update(sequence=1);state.accept(payload,payload['tablet_id'],now)
    assert state.sequence==1
    payload.update(sequence=0)
    with pytest.raises(ValueError):state.accept(payload,payload['tablet_id'],now)

async def test_example_automation_schemas(hass):
    import yaml
    from homeassistant.components.automation.config import async_validate_config_item
    examples=yaml.safe_load((Path(__file__).resolve().parents[1]/'examples/tablet.yaml').read_text())
    for item in examples['automation']:
        validated=await async_validate_config_item(hass,"automation",item)
        assert validated['id'].startswith('hearthline_')

async def test_pair_requires_account_authentication(hass,hass_client_no_auth):
    entry=await setup(hass); client=await hass_client_no_auth()
    assert (await client.get('/api/pixel_home/pair')).status==401
    assert (await client.post('/api/pixel_home/pair',json={'schema_version':1,'entry_id':entry.entry_id,'tablet_id':str(uuid4())})).status==401
    assert (await client.get(f'/api/pixel_home/tablet/{entry.entry_id}/config')).status==401

async def test_provider_options_never_reach_tablet_or_diagnostics(hass,hass_client):
    entry=await setup(hass)
    hass.config_entries.async_update_entry(entry,options=dict(DEFINITION,journey_endpoint='https://routing.example/route',home_origin='Configured home',journey_token='fixture-only-provider-token',journey_traffic=False))
    await hass.async_block_till_done()
    client=await hass_client()
    r=await client.post('/api/pixel_home/pair',json={'schema_version':1,'entry_id':entry.entry_id,'tablet_id':str(uuid4())})
    headers={'Authorization':'Bearer '+(await r.json())['credential']}
    r=await client.get(f'/api/pixel_home/tablet/{entry.entry_id}/config',headers=headers)
    assert r.status==200
    from custom_components.pixel_home.diagnostics import async_get_config_entry_diagnostics
    values=[await r.json(),await async_get_config_entry_diagnostics(hass,entry)]
    for value in values:
        serialized=json.dumps(value)
        assert 'fixture-only-provider-token' not in serialized
        assert 'routing.example' not in serialized
        assert 'Configured home' not in serialized

async def test_unchanged_heartbeat_does_not_change_measurement_states(hass,hass_client):
    entry=await setup(hass); client=await hass_client()
    tablet_id=str(uuid4())
    r=await client.post('/api/pixel_home/pair',json={'schema_version':1,'entry_id':entry.entry_id,'tablet_id':tablet_id})
    headers={'Authorization':'Bearer '+(await r.json())['credential']}; base=f'/api/pixel_home/tablet/{entry.entry_id}'
    session=(await (await client.post(base+'/session',headers=headers,json={})).json())['session_id']
    payload=fixture(); payload.update(tablet_id=tablet_id,session_id=session,observed_at=datetime.now(timezone.utc).isoformat())
    assert (await client.post(base+'/telemetry',headers=headers,json=payload)).status==200
    await hass.async_block_till_done()
    battery=hass.states.get('sensor.kitchen_battery')
    assert battery.state=='75'
    payload['sequence']=1
    assert (await client.post(base+'/telemetry',headers=headers,json=payload)).status==200
    await hass.async_block_till_done()
    latest=hass.states.get('sensor.kitchen_battery')
    assert latest.last_updated==battery.last_updated
    assert 'sequence' not in latest.attributes and 'observed_at' not in latest.attributes
