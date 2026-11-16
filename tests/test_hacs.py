"""Distribution invariants used by HACS's standard integration layout."""
import json
from pathlib import Path
import struct

ROOT=Path(__file__).resolve().parents[1]

def test_hacs_repository_layout():
    metadata=json.loads((ROOT/'hacs.json').read_text())
    manifest=json.loads((ROOT/'custom_components/pixel_home/manifest.json').read_text())
    assert metadata['name']==manifest['name']=='hearthline-ha'
    assert metadata['hide_default_branch'] is True
    assert metadata['zip_release'] is False  # HACS uses the tag's integration subtree.
    assert metadata['homeassistant']=='2026.10.4'
    assert manifest['issue_tracker']=='https://github.com/shreyasajj/hearthline-ha/issues'
    components=[p.name for p in (ROOT/'custom_components').iterdir() if p.is_dir() and not p.name.startswith('__')]
    assert components==['pixel_home']
    assert (ROOT/'LICENSE').read_text().startswith('MIT License')

def test_local_brand_assets():
    for suffix,size in [('',256),('@2x',512)]:
        content=(ROOT/f'custom_components/pixel_home/brand/icon{suffix}.png').read_bytes()
        assert content[:8]==b'\x89PNG\r\n\x1a\n'
        assert struct.unpack('>II',content[16:24])==(size,size)
