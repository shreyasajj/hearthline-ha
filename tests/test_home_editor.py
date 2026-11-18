"""The paired tablet editor uses real HA authentication, state and config entries."""
import hashlib
from uuid import uuid4
from unittest.mock import patch
import pytest
from pytest_homeassistant_custom_component.common import MockConfigEntry
from homeassistant.setup import async_setup_component

TOKEN='editor_test_credential'

async def setup(hass,owner,definition=None):
    hass.states.async_set('light.kitchen','on',{'friendly_name':'Kitchen light','private_attribute':'never deliver'})
    hass.states.async_set('switch.desk','unavailable',{'friendly_name':'Desk switch'})
    hass.states.async_set('scene.evening','scening',{'friendly_name':'Evening'})
    hass.states.async_set('media_player.room','idle',{'friendly_name':'Room speaker','supported_features':4})
    hass.states.async_set('sensor.secret','42')
    assert await async_setup_component(hass,'pixel_home',{'pixel_home':{'llm_api':False}})
    token=TOKEN+str(uuid4())
    entry=MockConfigEntry(domain='pixel_home',unique_id=str(uuid4()),title='Kitchen',data={
        'definition':definition or {'name':'Kitchen','favorites':['light.kitchen'],'players':[],'journey_token':'keep-private'},
        'tablet_id':str(uuid4()),'owner_id':owner.id,'credential_hash':hashlib.sha256(token.encode()).hexdigest()})
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry,'/api/pixel_home/tablet/'+entry.entry_id,{'Authorization':'Bearer '+token}

async def draft(client,base,headers):
    r=await client.get(base+'/editor',headers=headers)
    assert r.status==200
    catalog=await r.json()
    return catalog,{'schema_version':1,'revision':catalog['config']['revision'],'title':'My home',
                    'favorites':['switch.desk','light.kitchen'],'scenes':['scene.evening'],'players':['media_player.room']}

async def test_catalog_is_authenticated_searchable_and_bounded(hass,hass_client_no_auth,hass_admin_user):
    entry,base,headers=await setup(hass,hass_admin_user)
    client=await hass_client_no_auth()
    assert (await client.get(base+'/editor')).status==401
    catalog,body=await draft(client,base,headers)
    assert len(catalog['choices'])==4
    assert all(set(row)=={'entity_id','name','state'} for row in catalog['choices'])
    assert catalog['truncated'] is False
    assert all('secret' not in row['entity_id'] for row in catalog['choices'])
    r=await client.get(base+'/editor?query=ROOM',headers=headers)
    assert [row['entity_id'] for row in (await r.json())['choices']]==['media_player.room']
    for i in range(220): hass.states.async_set(f'light.extra_{i}','off')
    r=await client.get(base+'/editor',headers=headers)
    many=await r.json()
    assert len(many['choices'])==200 and many['truncated']
    assert (await client.get(base+'/editor?query='+('x'*81),headers=headers)).status==400

async def test_save_updates_only_this_tablet_and_preserves_other_settings(hass,hass_client_no_auth,hass_admin_user):
    entry,base,headers=await setup(hass,hass_admin_user)
    client=await hass_client_no_auth()
    catalog,body=await draft(client,base,headers)
    original=hass.data['pixel_home_tablets'][entry.entry_id]
    r=await client.post(base+'/editor',headers=headers,json=body)
    assert r.status==200
    result=await r.json()
    assert result['dashboard']['favorites']==['switch.desk','light.kitchen']
    assert result['dashboard']['players']==['media_player.room']
    assert result['revision']!=body['revision']
    assert entry.options['journey_token']=='keep-private'
    assert entry.options['name']=='My home'
    assert 'journey_token' not in result['dashboard']
    await hass.async_block_till_done()
    assert hass.data['pixel_home_tablets'][entry.entry_id] is original
    r=await client.get(base+'/states',headers=headers)
    assert {s['entity_id'] for s in await r.json()}=={'switch.desk','light.kitchen','scene.evening','media_player.room'}
    assert (await client.post(base+'/editor',headers=headers,json=body)).status==409
    assert (await client.get('/api/pixel_home/tablet/another/editor',headers=headers)).status==401

@pytest.mark.parametrize('change',[
    {'schema_version':True},{'favorites':['light.absent']},{'favorites':['sensor.secret']},
    {'favorites':['light.kitchen']*2},{'scenes':['light.kitchen']},{'players':['switch.desk']},
    {'title':''},{'title':'x'*81},{'extra':'field'},{'favorites':'light.kitchen'},
])
async def test_invalid_edits_are_rejected_without_mutation(hass,hass_client_no_auth,hass_admin_user,change):
    entry,base,headers=await setup(hass,hass_admin_user)
    client=await hass_client_no_auth()
    catalog,body=await draft(client,base,headers)
    body.update(change)
    assert (await client.post(base+'/editor',headers=headers,json=body)).status==400
    assert not entry.options

async def test_editor_obeys_owner_permissions_and_admin_requirement(hass,hass_client_no_auth,hass_admin_user,hass_read_only_user):
    entry,base,headers=await setup(hass,hass_admin_user)
    client=await hass_client_no_auth()
    catalog,body=await draft(client,base,headers)
    with patch.object(hass_admin_user.permissions,'check_entity',return_value=False):
        r=await client.get(base+'/editor',headers=headers)
        assert (await r.json())['choices']==[]
        assert (await client.post(base+'/editor',headers=headers,json=body)).status==403
    hass.config_entries.async_update_entry(entry,data={**entry.data,'owner_id':hass_read_only_user.id})
    assert (await client.get(base+'/editor',headers=headers)).status==403
    assert (await client.post(base+'/editor',headers=headers,json=body)).status==403

async def test_editor_can_remove_a_deleted_entity_and_save_no_players(hass,hass_client_no_auth,hass_admin_user):
    entry,base,headers=await setup(hass,hass_admin_user)
    client=await hass_client_no_auth()
    hass.states.async_remove('light.kitchen')
    catalog,body=await draft(client,base,headers)
    assert catalog['config']['dashboard']['favorites']==['light.kitchen']
    body.update(favorites=[],scenes=[],players=[])
    r=await client.post(base+'/editor',headers=headers,json=body)
    assert r.status==200 and (await r.json())['dashboard']['players']==[]


async def test_save_refreshes_stream_filter_without_losing_authentication(hass,hass_client_no_auth,hass_admin_user):
    from aiohttp import WSMsgType
    entry,base,headers=await setup(hass,hass_admin_user)
    client=await hass_client_no_auth()
    old=await client.ws_connect(base+'/stream',headers=headers)
    assert (await old.receive_json())['type']=='snapshot'
    catalog,body=await draft(client,base,headers)
    body.update(favorites=['switch.desk'],scenes=[],players=[])
    r=await client.post(base+'/editor',headers=headers,json=body)
    assert r.status==200
    revision=(await r.json())['revision']
    assert (await old.receive()).type in (WSMsgType.CLOSE,WSMsgType.CLOSED)
    await hass.async_block_till_done()
    fresh=await client.ws_connect(base+'/stream',headers=headers)
    snapshot=await fresh.receive_json()
    assert snapshot['config']['revision']==revision
    assert [s['entity_id'] for s in snapshot['states']]==['switch.desk']
    # The removed entity cannot be controlled even though its HA state still exists.
    assert (await client.post(base+'/command',headers=headers,json={'entity_id':'light.kitchen','action':'turn_on','values':{}})).status==403
    await fresh.close()

async def test_tablet_credentials_cannot_edit_another_entry(hass,hass_client_no_auth,hass_admin_user):
    first,base,headers=await setup(hass,hass_admin_user)
    second,other,other_headers=await setup(hass,hass_admin_user)
    client=await hass_client_no_auth()
    catalog,body=await draft(client,base,headers)
    assert (await client.get(other+'/editor',headers=headers)).status==401
    assert (await client.post(other+'/editor',headers=headers,json=body)).status==401
    assert not first.options and not second.options
    assert (await client.get(other+'/editor',headers=other_headers)).status==200
