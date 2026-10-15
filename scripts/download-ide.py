"""Download one pinned Linux SDK for compilation or Plugin Verifier (Python 3.12+)."""
import hashlib
import json
import subprocess
import sys
import tarfile
from pathlib import Path

version, cache = sys.argv[1:]
manifest = json.loads((Path(__file__).resolve().parents[1] / 'docs/compatibility-targets.json').read_text())
target = next(item for item in manifest['targets'] if item['version'] == version)
destination = Path(cache).resolve() / version
sdk = destination / 'android-studio'
marker = destination / 'verified.sha256'
if not marker.exists() or marker.read_text() != target['linuxSha256'] or not (sdk / 'product-info.json').exists():
    destination.mkdir(parents=True, exist_ok=True)
    archive = destination / 'studio.tar.gz'
    subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error', '--retry', '3',
                    '--output', str(archive), target['linuxUrl']], check=True)
    with archive.open('rb') as source:
        assert hashlib.file_digest(source, 'sha256').hexdigest() == target['linuxSha256'], 'SDK checksum mismatch'
    with tarfile.open(archive) as source:
        source.extractall(destination, filter='data')
    marker.write_text(target['linuxSha256'])
    archive.unlink()
assert json.loads((sdk / 'product-info.json').read_text())['buildNumber'] == target['build'].removeprefix('AI-')
print(sdk)
