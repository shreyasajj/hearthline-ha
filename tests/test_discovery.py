from ipaddress import ip_address
from unittest.mock import AsyncMock,patch
from uuid import uuid4
import json

import pytest
from pytest_homeassistant_custom_component.common import MockConfigEntry
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo
from homeassistant import config_entries
from homeassistant.setup import async_setup_component
from custom_components.pixel_home.discovery import identify_tablet,tablet_host,metadata,DiscoveryError,PATH,PORT,SERVICE_TYPE

ID='10000000-0000-4000-8000-000000000001'
HOST='192.168.20.55'
URL=f'http://{HOST}:{PORT}{PATH}'
META={'schema_version':1,'tablet_id':ID,'name':'Hearthline tablet','app_version':'0.7.3'}

def info(**changes):
    fields=dict(ip_address=ip_address(HOST),ip_addresses=[ip_address(HOST)],port=PORT,hostname='tablet.local.',type=SERVICE_TYPE,name='Hearthline._hearthline._tcp.local.',properties={'schema_version':'1','tablet_id':ID})
    fields.update(changes)
    return ZeroconfServiceInfo(**fields)

@pytest.mark.parametrize('host',['https://192.168.1.2','example.org','127.0.0.1','0.0.0.0','224.0.0.1','8.8.8.8','::1','fe80::1%eth0','fe80::1','192.168.1.2/path','169.254.169.254'])
def test_reject_non_tablet_targets(host):
    # Link-local metadata services must not be probed either.
    with pytest.raises(DiscoveryError):tablet_host(host)

def test_private_literals_and_strict_metadata():
    assert tablet_host(HOST)==HOST
    assert tablet_host('fd12:3456::20')=='fd12:3456::20'
    for change in [{'token':'secret'},{'schema_version':True},{'tablet_id':'not-a-uuid'},{'app_version':'x'*33}]:
        value=dict(META,**change)
        with pytest.raises((DiscoveryError,ValueError)):metadata(value)

async def test_manual_probe_and_no_auth(hass,aioclient_mock):
    aioclient_mock.get(URL,json=META,headers={"content-type":"application/json"})
    result=await identify_tablet(hass,HOST)
    assert result['tablet_id']==ID
    assert result['host']==HOST and result['port']==PORT
    assert aioclient_mock.call_count==1
    assert 'Authorization' not in aioclient_mock.mock_calls[0][3]

async def test_redirect_rejected_without_following(hass,aioclient_mock):
    aioclient_mock.get(URL,status=302,headers={'Location':'http://127.0.0.1/secret'})
    with pytest.raises(DiscoveryError):await identify_tablet(hass,HOST)
    assert aioclient_mock.call_count==1

async def test_oversize_response_rejected(hass,aioclient_mock):
    aioclient_mock.get(URL,content=b'x'*2049,headers={'content-type':'application/json'})
    with pytest.raises(DiscoveryError,match='too large'):await identify_tablet(hass,HOST)

async def test_changed_identity_and_wrong_port(hass,aioclient_mock):
    aioclient_mock.get(URL,json=META,headers={"content-type":"application/json"})
    with pytest.raises(DiscoveryError):await identify_tablet(hass,HOST,expected_id=str(uuid4()))
    with pytest.raises(DiscoveryError):await identify_tablet(hass,HOST,port=80)

async def test_zeroconf_confirm_creates_bound_entry(hass):
    with patch('custom_components.pixel_home.config_flow.identify_tablet',new=AsyncMock(return_value=dict(META,host=HOST,port=PORT))):
        result=await hass.config_entries.flow.async_init('pixel_home',context={'source':config_entries.SOURCE_ZEROCONF},data=info())
        assert result['step_id']=='confirm'
        result=await hass.config_entries.flow.async_configure(result['flow_id'],{})
        assert result['step_id']=='user'
        with patch('custom_components.pixel_home.async_setup_entry',return_value=True):
            result=await hass.config_entries.flow.async_configure(result['flow_id'],{'name':'Kitchen','tablet_host':HOST})
            assert result['type']=='create_entry'
            assert result['data']['discovery']['tablet_id']==ID
            assert 'tablet_host' not in result['data']['definition']
            await hass.async_block_till_done()

async def test_manual_ip_config_flow(hass):
    with patch('custom_components.pixel_home.config_flow.identify_tablet',new=AsyncMock(return_value=dict(META,host=HOST,port=PORT))):
        result=await hass.config_entries.flow.async_init('pixel_home',context={'source':config_entries.SOURCE_USER})
        with patch('custom_components.pixel_home.async_setup_entry',return_value=True):
            result=await hass.config_entries.flow.async_configure(result['flow_id'],{'name':'Kitchen','tablet_host':HOST})
            assert result['data']['discovery']['host']==HOST
            await hass.async_block_till_done()

async def test_existing_paired_tablet_is_not_duplicated(hass):
    entry=MockConfigEntry(domain='pixel_home',unique_id=str(uuid4()),data={'tablet_id':ID,'definition':{'name':'Existing'}});entry.add_to_hass(hass)
    with patch('custom_components.pixel_home.config_flow.identify_tablet',new=AsyncMock()) as probe:
        result=await hass.config_entries.flow.async_init('pixel_home',context={'source':config_entries.SOURCE_ZEROCONF},data=info())
        assert result['reason']=='already_configured'
        probe.assert_not_called()

async def test_malicious_txt_cannot_select_a_url_or_port(hass):
    with patch('custom_components.pixel_home.config_flow.identify_tablet',new=AsyncMock()) as probe:
        result=await hass.config_entries.flow.async_init('pixel_home',context={'source':config_entries.SOURCE_ZEROCONF},data=info(port=80))
        assert result['reason']=='invalid_discovery';probe.assert_not_called()

async def test_discovery_does_not_authorize_another_tablet(hass,hass_client):
    assert await async_setup_component(hass,'pixel_home',{'pixel_home':{'llm_api':False}})
    entry=MockConfigEntry(domain='pixel_home',unique_id='tablet_'+ID,title='Kitchen',data={'definition':{'name':'Kitchen'},'discovery':dict(META,host=HOST,port=PORT)})
    entry.add_to_hass(hass);assert await hass.config_entries.async_setup(entry.entry_id)
    client=await hass_client()
    response=await client.post('/api/pixel_home/pair',json={'schema_version':1,'entry_id':entry.entry_id,'tablet_id':str(uuid4())})
    assert response.status==403 and 'credential_hash' not in entry.data
    response=await client.post('/api/pixel_home/pair',json={'schema_version':1,'entry_id':entry.entry_id,'tablet_id':ID})
    assert response.status==200
