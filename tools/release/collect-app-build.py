#!/usr/bin/env python3
"""Collect existing APKs into a new, verified build directory; never builds or publishes."""
import argparse
from datetime import datetime
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('app_update', Path(__file__).with_name('generate-app-update.py'))
UPDATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(UPDATE)


def collect(release, debug=None, android_test=None, dist=None, notes=None):
    release = Path(release).resolve()
    package, code, version, _ = UPDATE.apk_badging(release)
    if package != UPDATE.APPLICATION_ID or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version):
        raise ValueError('Unexpected package/version in release APK')
    UPDATE.signer_digests(release)
    if debug:
        if UPDATE.apk_badging(Path(debug))[:3] != (package, code, version):
            raise ValueError('Debug and release package/version differ')
    dist = Path(dist or ROOT / 'dist').resolve()
    parent = dist / 'app' / f'{version}-{code}'
    parent.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().astimezone()
    output = Path(tempfile.mkdtemp(prefix=stamp.strftime('%Y%m%d-%H%M%S-'), dir=parent))
    artifacts = [('release', release), ('debug', debug), ('androidTest', android_test)]
    for kind, source in artifacts:
        if source:
            shutil.copy2(source, output / f'MyMoney-{version}-{code}-{kind}.apk')
    cmd = ['python3', str(Path(__file__).with_name('generate-app-update.py')),
           '--apk', str(output / f'MyMoney-{version}-{code}-release.apk'),
           '--output', str(output / 'app-update.json')]
    if notes:
        cmd += ['--notes-file', str(notes)]
        shutil.copy2(notes, output / 'RELEASE_NOTES.md')
    subprocess.run(cmd, check=True)
    revision = subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip()
    dirty = bool(subprocess.check_output(['git', '-C', str(ROOT), 'status', '--porcelain'], text=True).strip())
    manifest = {'createdAt': stamp.isoformat(), 'commit': revision, 'workingTreeDirty': dirty,
                'applicationId': package, 'versionName': version, 'versionCode': code,
                'status': 'pending_acceptance', 'provenance': 'collected existing APKs; commit describes collection workspace, not proof of APK build source',
                'inputs': {kind: str(Path(source).resolve()) for kind, source in artifacts if source}}
    (output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    hashes = ''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n'
                     for p in sorted(output.iterdir()) if p.is_file())
    (output / 'SHA256SUMS').write_text(hashes)
    latest = {'directory': output.relative_to(dist).as_posix(),
              'apk': (output / f'MyMoney-{version}-{code}-release.apk').relative_to(dist).as_posix(),
              'versionName': version, 'versionCode': code, 'status': 'pending_acceptance'}
    with tempfile.NamedTemporaryFile(mode='w', dir=dist, prefix='.latest-app-', delete=False) as file:
        json.dump(latest, file, ensure_ascii=False, indent=2)
        file.write('\n')
        pointer = Path(file.name)
    pointer.replace(dist / 'latest-app.json')
    print(output)
    return output


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', type=Path, default=ROOT/'app/build/outputs/apk/release/app-release.apk')
    parser.add_argument('--debug', type=Path)
    parser.add_argument('--android-test', type=Path)
    parser.add_argument('--dist', type=Path, default=ROOT/'dist')
    parser.add_argument('--notes', type=Path)
    parser.add_argument('--expected-version')
    parser.add_argument('--expected-code', type=int)
    args = parser.parse_args()
    actual = UPDATE.apk_badging(args.release)
    if ((args.expected_version is not None and actual[2] != args.expected_version) or
            (args.expected_code is not None and actual[1] != args.expected_code)):
        parser.error('Release APK version differs from requested build configuration')
    collect(args.release, args.debug, args.android_test, args.dist, args.notes)
