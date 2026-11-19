#!/usr/bin/env python3
"""Build a deterministic complete snapshot; no APK or signing key is needed."""
import argparse, hashlib, json, pathlib, subprocess, zipfile, io, tempfile
from datetime import datetime
ROOT = pathlib.Path(__file__).resolve().parent

def build(out):
    subprocess.run(['python3', str(ROOT/'generate.py'), '--check'], check=True)
    assets = ROOT.parent/'src/main/assets/spec'
    meta = json.loads((ROOT/'package.json').read_text())
    manifest = json.loads((assets/'manifest.json').read_text())
    files = {name:(assets/name).read_bytes() for name in manifest['files']}
    files['notification-templates.json'] = (assets/'notification-templates.json').read_bytes()
    files['samples.json'] = (ROOT.parent/'src/test/resources/spec/samples.json').read_bytes()
    revisions=meta.pop("ruleRevisions")
    manifest.update(meta,kind='MyMoneyRulePackage',schemaVersion=1,packageId='mymoney-official',version=meta['packageVersion'])
    manifest['files']={name:hashlib.sha256(data).hexdigest() for name,data in files.items()}
    for row in manifest['rules']:row['revision']=revisions[row['id']]
    files['manifest.json']=(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode()
    buffer=io.BytesIO()
    with zipfile.ZipFile(buffer,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for name,data in sorted(files.items()):
            info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
            z.writestr(info,data,compresslevel=9)
    out.mkdir(parents=True,exist_ok=True)
    name=f"MyMoney-rules-{meta['packageVersion']}.mymoney-rules.zip"
    data=buffer.getvalue()
    if (out/name).exists() or (out/(name+'.sha256')).exists():
        raise FileExistsError(f"Refusing to overwrite rule artifacts in {out}")
    with (out/name).open('xb') as f: f.write(data)
    with (out/(name+'.sha256')).open('x') as f: f.write(hashlib.sha256(data).hexdigest()+'  '+name+'\n')
    print(out/name)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output-dir',type=pathlib.Path)
    args=parser.parse_args()
    out=args.output_dir
    if out is None:
        version=json.loads((ROOT/'package.json').read_text())['packageVersion']
        parent=ROOT.parents[1]/'dist/rules'/version
        parent.mkdir(parents=True,exist_ok=True)
        out=pathlib.Path(tempfile.mkdtemp(prefix=datetime.now().strftime('%Y%m%d-%H%M%S-'),dir=parent))
    build(out)
