"""Build an HA-only archive; never package the sibling Android app."""
from pathlib import Path
import hashlib
import json
import zipfile

ROOT = Path(__file__).resolve().parents[1]
version = json.loads((ROOT / 'custom_components/pixel_home/manifest.json').read_text())['version']
output = ROOT / 'dist' / f'hearthline-ha-{version}.zip'
output.parent.mkdir(exist_ok=True)
files = set(ROOT.glob('*.md')) | {ROOT / 'pytest.ini'}
for folder in ('custom_components', 'display-contract', 'examples', 'tests', 'tools', 'validation'):
    files.update(p for p in (ROOT / folder).rglob('*') if p.is_file()
                 and '__pycache__' not in p.parts and p.suffix != '.pyc')
for path in files:
    assert path.suffix not in ('.apk', '.jks', '.keystore'), path
    assert not any(part in ('app', 'third-party', 'build', '.git') for part in path.relative_to(ROOT).parts), path
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(files):
        archive.write(path, path.relative_to(ROOT))
with zipfile.ZipFile(output) as archive:
    assert archive.testzip() is None
    assert 'custom_components/pixel_home/manifest.json' in archive.namelist()
output.with_suffix('.zip.sha256').write_text(f'{hashlib.sha256(output.read_bytes()).hexdigest()}  {output.name}\n')
print(f'{output.name}: {len(files)} HA-only files; CRC verified')

# HA installation ZIP: only the integration folder, to extract under /config/custom_components.
install = output.parent / 'pixel_home.zip'
component = ROOT / 'custom_components' / 'pixel_home'
with zipfile.ZipFile(install, 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(component.rglob('*')):
        if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc':
            archive.write(path, path.relative_to(component.parent))
with zipfile.ZipFile(install) as archive:
    assert archive.testzip() is None
    assert 'pixel_home/manifest.json' in archive.namelist()
    assert all(name.startswith('pixel_home/') for name in archive.namelist())
install.with_suffix('.zip.sha256').write_text(f'{hashlib.sha256(install.read_bytes()).hexdigest()}  {install.name}\n')
print('pixel_home.zip: installable HA-only archive; CRC verified')
