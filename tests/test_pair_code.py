"""Pairing with a short-lived code instead of an administrator token."""
from uuid import uuid4
from pytest_homeassistant_custom_component.common import MockConfigEntry
from homeassistant.setup import async_setup_component
from custom_components.pixel_home import tablet as tablet_module

DEFINITION={'name':'Kitchen','favorites':['light.test'],'scenes':[],'players':[]}

async def setup(hass,discovered=None):
    hass.states.async_set('light.test','on')
    assert await async_setup_component(hass,'config',{})
    assert await async_setup_component(hass,'pixel_home',{'pixel_home':{'llm_api':False}})
    data={'definition':DEFINITION,'revision':1}
    if discovered: data['discovery']={'tablet_id':discovered,'host':'192.168.30.159','port':8787,'name':'Hearthline tablet','app_version':'0.7.5'}
    entry=MockConfigEntry(domain='pixel_home',unique_id=str(uuid4()),title='Kitchen',data=data)
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry

async def open_code(client,entry):
    """Drive the options flow through HA's HTTP API, as the frontend does."""
    r=await client.post('/api/config/config_entries/options/flow',json={'handler':entry.entry_id})
    flow=await r.json()
    assert flow['type']=='menu'
    r=await client.post(f"/api/config/config_entries/options/flow/{flow['flow_id']}",json={'next_step_id':'pair_code'})
    step=await r.json()
    assert step['type']=='form' and step['step_id']=='pair_code', step
    return step['description_placeholders']['code'],step['flow_id']

async def test_code_pairs_once_as_the_administrator_who_opened_it(hass,hass_client,hass_client_no_auth,hass_admin_user):
    entry=await setup(hass)
    client=await hass_client(); anonymous=await hass_client_no_auth()
    code,flow_id=await open_code(client,entry)
    assert len(code)==9 and code[4]=='-'
    tablet_id=str(uuid4())
    r=await anonymous.post('/api/pixel_home/pair/code',json={'schema_version':1,'tablet_id':tablet_id,'code':code})
    assert r.status==200
    body=await r.json()
    assert body['entry_id']==entry.entry_id and body['credential'] and body['display_id']
    assert entry.data['owner_id']==hass_admin_user.id and entry.data['tablet_id']==tablet_id
    headers={'Authorization':'Bearer '+body['credential']}
    assert (await anonymous.get(f'/api/pixel_home/tablet/{entry.entry_id}/config',headers=headers)).status==200
    # Single use: the same code cannot pair again, even after the pairing is revoked.
    assert (await anonymous.post(f'/api/pixel_home/tablet/{entry.entry_id}/unpair',headers=headers,json={})).status==200
    r=await anonymous.post('/api/pixel_home/pair/code',json={'schema_version':1,'tablet_id':tablet_id,'code':code})
    assert r.status==401
    # Closing the form after pairing reports it; a paired entry refuses a new code.
    r=await client.post(f'/api/config/config_entries/options/flow/{flow_id}',json={})
    assert (await r.json())['type']=='abort'

async def test_code_is_bound_to_the_discovered_tablet(hass,hass_client,hass_client_no_auth):
    expected=str(uuid4())
    entry=await setup(hass,expected)
    client=await hass_client(); anonymous=await hass_client_no_auth()
    code,_=await open_code(client,entry)
    r=await anonymous.post('/api/pixel_home/pair/code',json={'schema_version':1,'tablet_id':str(uuid4()),'code':code})
    assert r.status==403 and 'credential_hash' not in entry.data
    # The code was spent by the wrong tablet; the right one needs a fresh code.
    r=await anonymous.post('/api/pixel_home/pair/code',json={'schema_version':1,'tablet_id':expected,'code':code})
    assert r.status==401
    code,_=await open_code(client,entry)
    r=await anonymous.post('/api/pixel_home/pair/code',json={'schema_version':1,'tablet_id':expected,'code':code.replace('-','')})
    assert r.status==200 and entry.data['tablet_id']==expected

async def test_expired_and_malformed_codes(hass,hass_client,hass_client_no_auth):
    entry=await setup(hass)
    client=await hass_client(); anonymous=await hass_client_no_auth()
    code,_=await open_code(client,entry)
    for body in [{'schema_version':1,'tablet_id':str(uuid4()),'code':'12ab5678'},{'schema_version':1,'tablet_id':'x','code':code},
                 {'schema_version':1,'tablet_id':str(uuid4())},{'schema_version':True,'tablet_id':str(uuid4()),'code':code},
                 {'schema_version':1,'tablet_id':str(uuid4()),'code':code,'entry_id':entry.entry_id}]:
        assert (await anonymous.post('/api/pixel_home/pair/code',json=body)).status==400
    tablet=hass.data['pixel_home_tablets'][entry.entry_id]
    value,owner,_=tablet.pairing_code
    tablet.pairing_code=(value,owner,0.0)  # Expired.
    r=await anonymous.post('/api/pixel_home/pair/code',json={'schema_version':1,'tablet_id':str(uuid4()),'code':code})
    assert r.status==401 and 'credential_hash' not in entry.data

async def test_guessing_withdraws_every_open_code(hass,hass_client,hass_client_no_auth):
    entry=await setup(hass)
    client=await hass_client(); anonymous=await hass_client_no_auth()
    code,_=await open_code(client,entry)
    wrong='00000000' if code.replace('-','')!='00000000' else '11111111'
    for _ in range(tablet_module.CODE_FAILURE_LIMIT):
        assert (await anonymous.post('/api/pixel_home/pair/code',json={'schema_version':1,'tablet_id':str(uuid4()),'code':wrong})).status==401
    # Limit reached: even the right code is refused, and the open code was withdrawn.
    assert (await anonymous.post('/api/pixel_home/pair/code',json={'schema_version':1,'tablet_id':str(uuid4()),'code':code})).status==429
    hass.data['pixel_home_code_failures'].clear()
    assert (await anonymous.post('/api/pixel_home/pair/code',json={'schema_version':1,'tablet_id':str(uuid4()),'code':code})).status==401

async def test_code_requires_an_administrator_request(hass):
    entry=await setup(hass)
    flow=await hass.config_entries.options.async_init(entry.entry_id)
    result=await hass.config_entries.options.async_configure(flow['flow_id'],{'next_step_id':'pair_code'})
    assert result['type']=='abort' and result['reason']=='admin_required'
    assert hass.data['pixel_home_tablets'][entry.entry_id].pairing_code is None

async def test_code_from_deactivated_administrator_is_refused(hass,hass_client,hass_client_no_auth,hass_admin_user):
    entry=await setup(hass)
    client=await hass_client(); anonymous=await hass_client_no_auth()
    code,_=await open_code(client,entry)
    await hass.auth.async_deactivate_user(hass_admin_user)
    r=await anonymous.post('/api/pixel_home/pair/code',json={'schema_version':1,'tablet_id':str(uuid4()),'code':code})
    assert r.status==401 and 'credential_hash' not in entry.data
