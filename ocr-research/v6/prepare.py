"""Fetch and checksum only the pinned Apache Paddle SDK/models for optional OCR research."""
from pathlib import Path
import argparse, base64, hashlib, io, json, tarfile, urllib.request, time
from concurrent.futures import ThreadPoolExecutor

ROOT = Path(__file__).resolve().parent
lock = json.loads((ROOT / 'config/upstream-lock.json').read_text())
parser = argparse.ArgumentParser()
parser.add_argument('--offline', action='store_true')
args = parser.parse_args()

def checked(path, digest):
    return path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == digest

def download(url):
    if args.offline:
        raise RuntimeError('Missing pinned input in offline mode: ' + url)
    request = urllib.request.Request(url, headers={'User-Agent': 'MyMoney-OCR-Research'})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.read()
        except Exception:
            if attempt == 3:
                raise
            time.sleep(1 + attempt)

def store(path, data, digest):
    if hashlib.sha256(data).hexdigest() != digest:
        raise RuntimeError('Pinned checksum mismatch: ' + str(path))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)

def prepare_source(item):
    relative = item['path']
    target = ROOT / 'generated/sdk' / relative
    if checked(target, item['sha256']):
        return
    upstream = 'LICENSE' if relative == 'LICENSE' else 'deploy/ppocr-android/ppocr-sdk/' + relative
    url = 'https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/' + lock['commit'] + '/' + upstream
    data = download(url)
    store(target, data, item['sha256'])
    print('Verified source ' + relative, flush=True)

with ThreadPoolExecutor(max_workers=4) as pool:
    list(pool.map(prepare_source, lock['sources']))

for model in lock['models']:
    kind = 'det' if model['name'].endswith('_det') else 'rec'
    folder = ROOT / 'generated/assets/models/v6' / kind
    if all(checked(folder / part['path'], part['sha256']) for part in model['extracted']):
        continue
    blob = download(model['url'])
    if hashlib.sha256(blob).hexdigest() != model['sha256']:
        raise RuntimeError('Pinned model archive checksum mismatch')
    with tarfile.open(fileobj=io.BytesIO(blob)) as tar:
        for part in model['extracted']:
            matches = [m for m in tar.getmembers() if m.isfile() and Path(m.name).name == part['path']]
            if len(matches) != 1:
                raise RuntimeError('Ambiguous model archive member')
            store(folder / part['path'], tar.extractfile(matches[0]).read(), part['sha256'])

native = json.loads((ROOT / 'config/ledger-v6-runtime-lock.json').read_text())
for part in native['files']:
    target = ROOT / 'runtime' / Path(part['path']).name
    if not checked(target, part['sha256']):
        raise RuntimeError('Pinned runtime missing/mismatch: ' + str(target))
print('Pinned SDK, models and runtime inputs verified')

# Preserve byte-identical Java/resources once; union only disjoint native ABI entries.
# Deterministic ZIP metadata makes the merged local AARs independently reproducible.
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
for prefix in ('onnxruntime-reduced', 'opencv-minimal'):
    entries = {}
    for abi in ('arm64-v8a', 'x86_64'):
        with ZipFile(ROOT / 'runtime' / (prefix + '-' + abi + '.aar')) as archive:
            for name in archive.namelist():
                if name.endswith('/'):
                    continue
                blob = archive.read(name)
                if name in entries and entries[name] != blob:
                    raise RuntimeError('ABI AAR shared content differs: ' + name)
                entries[name] = blob
    target = ROOT / 'generated/runtime' / (prefix + '-dual.aar')
    target.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(target, 'w', compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for name, blob in sorted(entries.items()):
            info = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, blob, compress_type=ZIP_DEFLATED, compresslevel=9)
    print('Merged verified dual ABI runtime ' + prefix + ': ' + hashlib.sha256(target.read_bytes()).hexdigest())
# Package Paddle's original upstream license along with native notices.
notice = ROOT / 'notices/PaddleOCR-LICENSE.txt'
notice.write_bytes((ROOT / 'generated/sdk/LICENSE').read_bytes())
