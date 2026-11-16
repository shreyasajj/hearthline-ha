"""Bounded semantic display protocol. No executable or device-action content."""
import json
import re
from urllib.parse import urlsplit

REVISION = 'pixel-home-0.4'
LAYOUTS = ['compact', 'reading', 'search', 'steps', 'comparison']
BLOCKS = ['paragraph', 'bullets', 'steps', 'sources', 'table', 'status']
CAPABILITIES = dict(protocol_version='1.0', revision=REVISION, layouts=LAYOUTS,
    blocks=BLOCKS, max_payload_bytes=32768, max_blocks=8, max_table_columns=4,
    max_table_rows=8, max_sources_per_block=5,
    unsupported=['followups','html','javascript','images','video','web_search',
                 'wake_word','tts','device_actions','background_display','locked_display'])

def fields(obj, required, optional=()):
    if type(obj) is not dict or not set(required) <= obj.keys() or obj.keys() - set(required) - set(optional):
        raise ValueError('Unknown or missing fields')

def text(value, maximum):
    if type(value) is not str or not value.strip() or len(value) > maximum:
        raise ValueError('Invalid text length or type')

def items(value, maximum, length):
    if type(value) is not list or not 1 <= len(value) <= maximum:
        raise ValueError('Invalid item count')
    for item in value:
        text(item, length)

def validate(request):
    if type(request) is not dict: raise ValueError('Response must be an object')
    if len(json.dumps(request, ensure_ascii=False, separators=(',', ':')).encode()) > 32768:
        raise ValueError('Response exceeds 32 KiB')
    if request.get('protocol_version') == '1.1':
        return validate_native(request)
    fields(request, ['protocol_version','response_id','layout','title','summary','spoken_text','blocks','ttl_seconds'])
    if request['protocol_version'] != '1.0' or request['layout'] not in LAYOUTS:
        raise ValueError('Unsupported version or layout')
    text(request['response_id'],80)
    if not re.fullmatch(r'[A-Za-z0-9_-]+', request['response_id']):
        raise ValueError('Invalid response ID')
    for name, maximum in [('title',80),('summary',240),('spoken_text',400)]:
        text(request[name],maximum)
    ttl=request['ttl_seconds']
    if type(ttl) is not int or not 15 <= ttl <= 300:
        raise ValueError('TTL must be 15..300 seconds')
    blocks=request['blocks']
    if type(blocks) is not list or not 1 <= len(blocks) <= 8:
        raise ValueError('Expected 1..8 blocks')
    for block in blocks:
        if type(block) is not dict:
            raise ValueError('Invalid block')
        kind=block.get('type')
        if kind=='paragraph':
            fields(block,['type','text'],['heading']); text(block['text'],4000)
        elif kind in ('bullets','steps'):
            fields(block,['type','items'],['heading'])
            items(block['items'],10 if kind=='steps' else 8,600 if kind=='steps' else 300)
        elif kind=='sources':
            fields(block,['type','items'])
            if type(block['items']) is not list or not 1 <= len(block['items']) <= 5:
                raise ValueError('Expected 1..5 sources')
            for source in block['items']:
                fields(source,['title','url'],['excerpt']); text(source['title'],100); text(source['url'],2048)
                url=source['url']; parsed=urlsplit(url)
                if not url.startswith('https://') or not parsed.hostname or parsed.username is not None or any(c.isspace() or ord(c)<32 for c in url):
                    raise ValueError('HTTPS sources without credentials required')
                if 'excerpt' in source: text(source['excerpt'],300)
        elif kind=='table':
            fields(block,['type','columns','rows']); items(block['columns'],4,60)
            if type(block['rows']) is not list or not 1 <= len(block['rows']) <= 8:
                raise ValueError('Expected 1..8 rows')
            for row in block['rows']:
                items(row,4,160)
                if len(row)!=len(block['columns']): raise ValueError('Table width mismatch')
        elif kind=='status':
            fields(block,['type','label','state'],['detail']); text(block['label'],120)
            if block['state'] not in ('success','partial','pending','error'): raise ValueError('Invalid status')
            if 'detail' in block: text(block['detail'],500)
        else:
            raise ValueError('Unsupported block')
        if 'heading' in block: text(block['heading'],80)
    return request


def validate_native(request):
    from .templates import IDS, validate_template
    fields(request,['protocol_version','response_id','layout','title','summary','spoken_text','ttl_seconds','template'])
    if request['layout'] not in IDS: raise ValueError('Unknown template')
    text(request['response_id'],80)
    if not re.fullmatch(r'[A-Za-z0-9_-]+',request['response_id']): raise ValueError('Invalid response ID')
    for k,n in [('title',80),('summary',240),('spoken_text',400)]: text(request[k],n)
    if type(request['ttl_seconds']) is not int or not 15 <= request['ttl_seconds'] <= 300: raise ValueError('Invalid TTL')
    validate_template(request['template'],request['layout'])
    return request

CAPABILITIES['templates'] = {key: dict(schema_version=1, installed=True,
    interactions=['expand_details','dismiss','home'], max_geometry_points=256 if key=='journey' else 0)
    for key in ('journey','weather','home_status')}
CAPABILITIES['template_protocol_version']='1.1'
CAPABILITIES['template_intents'] = dict(journey=dict(required=['destination','mode'], optional=[], modes=['driving','walking','cycling']),
    weather=dict(required=[],optional=[]),home_status=dict(required=[],optional=[]))
CAPABILITIES['units']=dict(duration='seconds',distance='meters',temperature=['°C','°F','K'],precipitation_probability='percent')
CAPABILITIES['template_states']=['loading','ready','partial','unavailable','error','clarification']
CAPABILITIES['unsupported'] += ['turn_by_turn_navigation','location_tracking','offline_maps','template_device_controls','audio_ducking']
