"""Unauthenticated LAN identification only; never a credential or control channel."""
from ipaddress import ip_address
import json
from aiohttp import ClientTimeout
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from yarl import URL
from .tablet_contract import UUID, text

SERVICE_TYPE='_hearthline._tcp.local.'
PORT=8787
PATH='/hearthline/discovery/v1'
MAX_BYTES=2048

class DiscoveryError(ValueError):
    """No supported foreground tablet could be identified."""

def tablet_host(value):
    try:
        if not isinstance(value,str) or len(value)>64 or '%' in value: raise ValueError()
        address=ip_address(value.strip())
        if not address.is_private or address.is_loopback or address.is_multicast or address.is_unspecified or address.is_reserved or address.is_link_local:
            raise ValueError()
        if address.version==6 and address.is_link_local: raise ValueError()
        return str(address)
    except ValueError:
        raise DiscoveryError('Use a private unicast tablet IP (IPv4 or routed IPv6 ULA), without a URL or path') from None

def metadata(value):
    if not isinstance(value,dict) or set(value)!={'schema_version','tablet_id','app_version','name'}:
        raise DiscoveryError('Invalid identification response')
    if type(value['schema_version']) is not int or value['schema_version']!=1:
        raise DiscoveryError('Unsupported identification version')
    if not isinstance(value['tablet_id'],str) or not UUID.fullmatch(value['tablet_id']):
        raise DiscoveryError('Invalid installation ID')
    text(value['app_version'],32); text(value['name'],80)
    return dict(value)

async def identify_tablet(hass,host,port=PORT,expected_id=None):
    host=tablet_host(host)
    if type(port) is not int or port!=PORT: raise DiscoveryError('Unsupported discovery port')
    url=URL.build(scheme='http',host=host,port=PORT,path=PATH)
    try:
        # A fixed read-only path on an IP literal. No HA tokens, cookies, redirects or caller URL.
        async with async_get_clientsession(hass).get(url,allow_redirects=False,
                timeout=ClientTimeout(total=4,connect=2,sock_read=2),headers={'Accept':'application/json'}) as response:
            if response.status!=200 or response.content_type!='application/json':
                raise DiscoveryError('Tablet identification unavailable')
            raw=bytearray()
            async for chunk in response.content.iter_chunked(512):
                raw.extend(chunk)
                if len(raw)>MAX_BYTES: raise DiscoveryError('Identification response too large')
            result=metadata(json.loads(raw))
        if expected_id is not None and result['tablet_id']!=expected_id:
            raise DiscoveryError('Discovered identity changed')
        return dict(result,host=host,port=PORT)
    except DiscoveryError:
        raise
    except Exception as err:
        # Deliberately omit response bodies and network exception details from UI/logs.
        raise DiscoveryError('Cannot identify tablet; keep the updated app visible and allow TCP 8787') from err
