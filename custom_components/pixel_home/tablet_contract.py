"""Dependency-free, strict v1 dashboard and telemetry validation."""
from datetime import datetime, timezone
import json
import re

FAVORITES = {'light', 'switch', 'climate', 'lock'}
UUID = re.compile(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
FIELDS = {'schema_version', 'tablet_id', 'session_id', 'sequence', 'observed_at', 'app_version',
          'battery_percent', 'battery_status', 'charging', 'power_source', 'dock_state', 'dock_evidence',
          'detection_mode', 'foreground', 'interactive', 'locked', 'current_screen',
          'protected_dock_view_visible', 'dock_display_enabled', 'dock_test_active'}

def integer(value, low, high):
    if type(value) is not int or not low <= value <= high:
        raise ValueError('Invalid integer')
    return value

def text(value, maximum):
    if not isinstance(value, str) or not 1 <= len(value) <= maximum or any(ord(c) < 32 for c in value):
        raise ValueError('Invalid text')
    return value

def telemetry(value, now=None):
    if not isinstance(value, dict) or set(value) != FIELDS or len(json.dumps(value).encode()) > 8192:
        raise ValueError('Invalid telemetry fields or size')
    integer(value['schema_version'], 1, 1)
    for key in ('tablet_id', 'session_id'):
        if not isinstance(value[key], str) or not UUID.fullmatch(value[key]): raise ValueError('Invalid identity')
    integer(value['sequence'], 0, 2**53-1)
    text(value['app_version'], 32)
    stamp = datetime.fromisoformat(text(value['observed_at'], 40))
    if stamp.utcoffset() is None: raise ValueError('Timestamp requires offset')
    if abs(((now or datetime.now(timezone.utc)) - stamp).total_seconds()) > 300:
        raise ValueError('Timestamp outside five-minute window')
    if value['battery_percent'] is not None: integer(value['battery_percent'], 0, 100)
    for key in ('charging', 'locked'):
        if value[key] is not None and type(value[key]) is not bool: raise ValueError('Invalid nullable boolean')
    for key in ('foreground', 'interactive', 'protected_dock_view_visible', 'dock_display_enabled', 'dock_test_active'):
        if type(value[key]) is not bool: raise ValueError('Invalid boolean')
    enums = {
        'battery_status': {'charging','discharging','full','not_charging','unknown'},
        'power_source': {'none','ac','usb','wireless','dock','multiple','unknown'},
        'dock_state': {'docked','undocked','unknown'},
        'dock_evidence': {'system_dock_event','audio_dock','dock_power','manual_override','unconfirmed'},
        'detection_mode': {'auto','force_docked','force_undocked'},
        'current_screen': {'home','music','settings','clock','unknown'},
    }
    for key, allowed in enums.items():
        if type(value[key]) is not str or value[key] not in allowed: raise ValueError('Invalid enum')
    if (value['detection_mode'] != 'auto') != (value['dock_evidence'] == 'manual_override'):
        raise ValueError('Override must be explicit')
    if value['charging'] != {'charging': True, 'discharging': False, 'full': False, 'not_charging': False, 'unknown': None}[value['battery_status']]:
        raise ValueError('Charging contradicts battery status')
    return dict(value)

def dashboard(value, states, *, allow_missing=False):
    allowed = {'name','area_id','favorites','scenes','players','weather_entity','home_entities','music','idle_seconds','heartbeat_timeout','revoke','journey_endpoint','home_origin','journey_token','journey_traffic'}
    if not isinstance(value, dict) or set(value) - allowed: raise ValueError('Unknown dashboard field')
    result = {'schema_version': 1, 'title': text(value.get('name','Hearthline'),80)}
    for key, domains, limit in [('favorites',FAVORITES,8),('scenes',{'scene'},6),('players',{'media_player'},8),('home_entities',{'light','switch','climate','lock','binary_sensor','sensor'},16)]:
        items = value.get(key, [])
        if not isinstance(items, list) or len(items)>limit or len(set(items))!=len(items): raise ValueError('Invalid selection')
        if any(not isinstance(x,str) or x.partition('.')[0] not in domains or (not allow_missing and states.get(x) is None) for x in items):
            raise ValueError('Select existing supported entities')
        result[key] = items
    weather = value.get('weather_entity')
    if weather and (not isinstance(weather,str) or not weather.startswith('weather.') or (not allow_missing and states.get(weather) is None)):
        raise ValueError('Select existing weather entity')
    result['weather_entity'] = weather
    music=value.get('music',[])
    if not isinstance(music,list) or len(music)>8: raise ValueError('Invalid shortcuts')
    for item in music:
        if not isinstance(item,dict) or set(item)!={'title','uri','type'}: raise ValueError('Invalid shortcut')
        text(item['title'],80); text(item['uri'],512); text(item['type'],40)
    from urllib.parse import urlsplit
    endpoint=value.get('journey_endpoint')
    if endpoint:
        u=urlsplit(text(endpoint,512))
        if u.scheme!='https' or not u.hostname or u.username or u.password or u.query or u.fragment: raise ValueError('Invalid fixed Journey endpoint')
    if value.get('home_origin'): text(value['home_origin'],240)
    if value.get('journey_token'): text(value['journey_token'],4096)
    if 'journey_traffic' in value and type(value['journey_traffic']) is not bool: raise ValueError('Invalid traffic preference')
    result['music']=music
    result['idle_seconds']=integer(value.get('idle_seconds',45),15,600)
    integer(value.get('heartbeat_timeout',120),60,900)
    if len(json.dumps(result).encode())>24000: raise ValueError('Dashboard exceeds delivery budget')
    return result

class TelemetryState:
    """Server-issued session fences old sessions. No client wall-clock ordering."""
    def __init__(self):
        self.session = None
        self.sequence = -1
        self.value = None
        self.received = None

    def begin(self, session):
        self.session=session; self.sequence=-1; self.value=None; self.received=None

    def accept(self, value, tablet_id, now):
        value=telemetry(value, now)
        if value['tablet_id'] != tablet_id or value['session_id'] != self.session or value['sequence'] <= self.sequence:
            raise ValueError('Stale session, identity or sequence')
        self.sequence=value['sequence']; self.value=value; self.received=now
