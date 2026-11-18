"""Push stream: scoped, read-only, and tied to pairing lifetime."""
from datetime import datetime, timezone, timedelta
import asyncio
import json
from pathlib import Path
from uuid import uuid4
from aiohttp import WSMsgType
from pytest_homeassistant_custom_component.common import MockConfigEntry, async_fire_time_changed
from homeassistant.setup import async_setup_component
from custom_components.pixel_home import tablet as tablet_module

DEFINITION={'name':'Kitchen','favorites':['light.test'],'scenes':[],'players':[]}

async def paired(hass,hass_client):
    hass.states.async_set('light.test','on',{'friendly_name':'Test light'})
    hass.states.async_set('light.secret','on')
    assert await async_setup_component(hass,'pixel_home',{'pixel_home':{'llm_api':False}})
    entry=MockConfigEntry(domain='pixel_home',unique_id=str(uuid4()),title='Kitchen',data={'definition':DEFINITION,'revision':1})
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    client=await hass_client()
    tablet_id=str(uuid4())
    r=await client.post('/api/pixel_home/pair',json={'schema_version':1,'entry_id':entry.entry_id,'tablet_id':tablet_id})
    headers={'Authorization':'Bearer '+(await r.json())['credential']}
    return entry,client,headers,f'/api/pixel_home/tablet/{entry.entry_id}',tablet_id

async def test_snapshot_then_only_selected_state_changes(hass,hass_client):
    entry,client,headers,base,_=await paired(hass,hass_client)
    ws=await client.ws_connect(base+'/stream',headers=headers)
    snapshot=await ws.receive_json(timeout=2)
    assert snapshot['type']=='snapshot' and snapshot['schema_version']==1
    assert snapshot['config']==await (await client.get(base+'/config',headers=headers)).json()
    assert [s['entity_id'] for s in snapshot['states']]==['light.test']
    hass.states.async_set('light.secret','off')  # Not selected: never delivered.
    hass.states.async_set('light.test','off',{'friendly_name':'Test light'})
    message=await ws.receive_json(timeout=2)
    assert message['type']=='state' and message['state']['entity_id']=='light.test' and message['state']['state']=='off'
    hass.states.async_remove('light.test')
    assert await ws.receive_json(timeout=2)=={'type':'removed','entity_id':'light.test'}
    await ws.close()

async def test_stream_requires_scoped_credential(hass,hass_client,hass_client_no_auth):
    entry,client,headers,base,_=await paired(hass,hass_client)
    anonymous=await hass_client_no_auth()
    assert (await anonymous.get(base+'/stream')).status==401
    assert (await anonymous.get(base+'/stream',headers={'Authorization':'Bearer wrong'})).status==401

async def test_stream_is_read_only_and_bounded(hass,hass_client,monkeypatch):
    entry,client,headers,base,_=await paired(hass,hass_client)
    ws=await client.ws_connect(base+'/stream',headers=headers)
    await ws.receive_json(timeout=2)
    await ws.send_str('{"type":"command"}')
    assert (await ws.receive(timeout=2)).type in (WSMsgType.CLOSE,WSMsgType.CLOSING,WSMsgType.CLOSED)
    monkeypatch.setattr(tablet_module,'STREAM_LIMIT',1)
    first=await client.ws_connect(base+'/stream',headers=headers)
    await first.receive_json(timeout=2)
    assert (await client.get(base+'/stream',headers=headers)).status==429
    await first.close()

async def test_ping_and_revocation_close_stream(hass,hass_client,monkeypatch):
    monkeypatch.setattr(tablet_module,'STREAM_PING_SECONDS',0.05)
    entry,client,headers,base,_=await paired(hass,hass_client)
    ws=await client.ws_connect(base+'/stream',headers=headers)
    await ws.receive_json(timeout=2)
    assert await ws.receive_json(timeout=2)=={'type':'ping'}
    assert (await client.post(base+'/unpair',headers=headers,json={})).status==200
    while True:
        message=await ws.receive(timeout=2)
        if message.type!=WSMsgType.TEXT: break
    assert message.type in (WSMsgType.CLOSE,WSMsgType.CLOSING,WSMsgType.CLOSED)
    assert (await client.get(base+'/stream',headers=headers)).status==401

async def test_options_change_closes_stream_and_new_snapshot_has_new_revision(hass,hass_client):
    entry,client,headers,base,_=await paired(hass,hass_client)
    ws=await client.ws_connect(base+'/stream',headers=headers)
    before=(await ws.receive_json(timeout=2))['config']['revision']
    hass.states.async_set('switch.other','on')
    hass.config_entries.async_update_entry(entry,options=dict(DEFINITION,favorites=['switch.other','light.test']))
    await hass.async_block_till_done()
    assert (await ws.receive(timeout=2)).type in (WSMsgType.CLOSE,WSMsgType.CLOSING,WSMsgType.CLOSED)
    ws=await client.ws_connect(base+'/stream',headers=headers)
    snapshot=await ws.receive_json(timeout=2)
    assert snapshot['config']['revision']!=before
    assert [s['entity_id'] for s in snapshot['states']]==['light.test','switch.other']
    await ws.close()

async def test_closed_stream_withdraws_telemetry_after_grace(hass,hass_client):
    entry,client,headers,base,tablet_id=await paired(hass,hass_client)
    ws=await client.ws_connect(base+'/stream',headers=headers)
    await ws.receive_json(timeout=2)
    session=(await (await client.post(base+'/session',headers=headers,json={})).json())['session_id']
    payload=json.loads((Path(__file__).resolve().parent/'fixtures/tablet-telemetry.json').read_text())
    payload.update(tablet_id=tablet_id,session_id=session,observed_at=datetime.now(timezone.utc).isoformat())
    assert (await client.post(base+'/telemetry',headers=headers,json=payload)).status==200
    tablet=hass.data['pixel_home_tablets'][entry.entry_id]
    assert tablet.available
    await ws.close()
    for _ in range(50):
        if not tablet.streams: break
        await hass.async_block_till_done()
    assert tablet.available  # Still inside the reconnect grace period.
    async_fire_time_changed(hass,datetime.now(timezone.utc)+timedelta(seconds=tablet_module.STREAM_GRACE_SECONDS+1))
    await hass.async_block_till_done()
    assert not tablet.available
    # The old telemetry session is fenced; the tablet must begin a new one.
    payload['sequence']=1
    assert (await client.post(base+'/telemetry',headers=headers,json=payload)).status==400

async def test_busy_stream_still_closes_when_owner_is_removed(hass,hass_client,hass_admin_user,monkeypatch):
    monkeypatch.setattr(tablet_module,'STREAM_PING_SECONDS',0.05)
    entry,client,headers,base,_=await paired(hass,hass_client)
    assert entry.data['owner_id']==hass_admin_user.id
    ws=await client.ws_connect(base+'/stream',headers=headers)
    await ws.receive_json(timeout=2)
    await hass.auth.async_remove_user(hass_admin_user)
    # Keep the stream busy so it never goes idle; authorization must still be re-checked.
    for i in range(200):
        hass.states.async_set('light.test','on',{'friendly_name':'Test light','n':i})
        message=await ws.receive(timeout=2)
        if message.type!=WSMsgType.TEXT: break
        assert json.loads(message.data)['type']=='state'
        await asyncio.sleep(0.01)
    assert message.type in (WSMsgType.CLOSE,WSMsgType.CLOSING,WSMsgType.CLOSED)
    assert ws.close_code==1008

async def test_shutdown_closes_streams(hass,hass_client):
    entry,client,headers,base,_=await paired(hass,hass_client)
    ws=await client.ws_connect(base+'/stream',headers=headers)
    await ws.receive_json(timeout=2)
    hass.bus.async_fire('homeassistant_stop')
    await hass.async_block_till_done()
    assert (await ws.receive(timeout=2)).type in (WSMsgType.CLOSE,WSMsgType.CLOSING,WSMsgType.CLOSED)
    assert ws.close_code==1001
