"""Package an explicit allowlist; excludes caches, local config and signing keys."""
from pathlib import Path
import hashlib
import zipfile

ROOT=Path(__file__).resolve().parents[1]
VERSION='0.7.0'
output=ROOT/f'Hearthline-{VERSION}.zip'
files=set()
for pattern in ('*.md','*.kts','gradle.properties','gradlew','gradlew.bat','APK_SHA256.txt','SIGNING.txt','pytest.ini','.gitignore'):
    files.update(ROOT.glob(pattern))
for directory in ('app/src','gradle','home-assistant','display-contract','tools','install','validation','tests','examples'):
    files.update(p for p in (ROOT/directory).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
files.add(ROOT/'app/build.gradle.kts')
for directory in ('third-party/sendspin/shared', 'third-party/sendspin/player'):
    files.add(ROOT/directory/'build.gradle.kts')
    files.update(p for p in (ROOT/directory/'src').rglob('*') if p.is_file())
files.update((ROOT/'third-party/sendspin').glob('*.md'))
files.add(ROOT/'third-party/sendspin/LICENSE')
files.update(ROOT.glob('Pixel Home*.png'))
for entry in (ROOT/'APK_SHA256.txt').read_text().splitlines():
    digest,name=entry.split(maxsplit=1)
    assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==digest, name
for path in files:
    assert path.suffix not in ('.keystore','.jks','.pyc'),path
    assert not any(part.startswith('.') and part != '.gitignore' for part in path.relative_to(ROOT).parts),path
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
    for path in sorted(files): archive.write(path,Path(f'Hearthline-{VERSION}')/path.relative_to(ROOT))
with zipfile.ZipFile(output) as archive:
    assert archive.testzip() is None
    for name in ('Hearthline-debug.apk','Hearthline-preview.apk'):
        path=f'Hearthline-{VERSION}/install/{name}'
        assert hashlib.sha256(archive.read(path)).digest()==hashlib.sha256((ROOT/'install'/name).read_bytes()).digest()
digest=hashlib.sha256(output.read_bytes()).hexdigest()
(ROOT/'PACKAGE_SHA256.txt').write_text(f'{digest}  {output.name}\n')
print(f'{output.name}: {len(files)} files, {output.stat().st_size:,} bytes; CRC and APK checksums verified')
