"""Strict, bounded provider results. Intent is separate from trusted measurements."""
import math
from datetime import datetime
from .protocol import fields, text

IDS = ('journey', 'weather', 'home_status')
STATES = ('loading', 'ready', 'partial', 'unavailable', 'error', 'clarification')

def number(v, low, high, nullable=False):
    if nullable and v is None: return
    if type(v) not in (int, float) or not math.isfinite(v) or not low <= v <= high:
        raise ValueError('Invalid measurement')

def timestamp(v):
    text(v, 40)
    if datetime.fromisoformat(v.replace('Z', '+00:00')).tzinfo is None:
        raise ValueError('Timezone required')

def array(v, maximum):
    if type(v) is not list or len(v) > maximum: raise ValueError('Array too large')

def validate_template(t, kind):
    fields(t, ['schema_version','state','source','updated_at','stale_after_seconds','data'])
    if type(t['schema_version']) is not int or t['schema_version'] != 1 or t['state'] not in STATES: raise ValueError('Unsupported template')
    text(t['source'], 120); timestamp(t['updated_at']); number(t['stale_after_seconds'], 1, 86400)
    d=t['data']
    if d is None:
        if t['state'] in ('ready','partial'): raise ValueError('Missing data')
        return
    if t['state'] not in ('ready','partial'): raise ValueError('Unexpected data')
    if kind == 'journey':
        fields(d,['destination','locality','duration_seconds','distance_meters','departure_at','timezone','traffic','directions','geometry','attribution'])
        text(d['destination'],120); text(d['locality'],240); timestamp(d['departure_at']); text(d['timezone'],80)
        from zoneinfo import ZoneInfo
        ZoneInfo(d['timezone'])
        number(d['duration_seconds'],0,604800); number(d['distance_meters'],0,20000000)
        if d['traffic'] not in ('live','unavailable'): raise ValueError('Unknown traffic')
        array(d['directions'],20)
        for step in d['directions']: text(step,300)
        array(d['geometry'],256)
        if len(d['geometry']) == 1: raise ValueError('Incomplete geometry')
        for point in d['geometry']:
            if type(point) is not list or len(point)!=2: raise ValueError('Invalid coordinate')
            number(point[0],-180,180); number(point[1],-85,85)
        text(d['attribution'],240)
    elif kind == 'weather':
        fields(d,['location','condition','temperature','unit','high','low','precipitation_probability','hours'])
        text(d['location'],120); text(d['condition'],80)
        if d['unit'] not in ('°C','°F','K'): raise ValueError('Unknown temperature unit')
        for k in ('temperature','high','low'): number(d[k],-273.15,1000,True)
        number(d['precipitation_probability'],0,100,True); array(d['hours'],6)
        for hour in d['hours']:
            fields(hour,['at','temperature','precipitation_probability','condition'])
            timestamp(hour['at']); number(hour['temperature'],-273.15,1000,True)
            number(hour['precipitation_probability'],0,100,True); text(hour['condition'],80)
    elif kind == 'home_status':
        fields(d,['scope','items']); text(d['scope'],160); array(d['items'],16)
        seen=set()
        import re
        for item in d['items']:
            fields(item,['entity_id','name','state','unit'])
            text(item['entity_id'],120)
            if not re.fullmatch(r'[a-z_]+\.[a-z0-9_]+',item['entity_id']) or item['entity_id'] in seen: raise ValueError('Invalid/duplicate entity')
            seen.add(item['entity_id']); text(item['name'],120); text(item['state'],160)
            if item['unit'] is not None: text(item['unit'],20)
    else: raise ValueError('Unknown template')
