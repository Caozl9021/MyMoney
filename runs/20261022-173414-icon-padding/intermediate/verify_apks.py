from pathlib import Path
import subprocess,zipfile,re,json,hashlib,xml.etree.ElementTree as ET
run=Path('runs/20261022-173414-icon-padding')
buildtools=Path.home()/'Android/Sdk/build-tools/35.0.0'
def execute(*args):
 return subprocess.check_output([str(a) for a in args],text=True)
result={}
for variant in ['debug','release']:
 apk=Path(f'dist/AutoBook-0.6.5-{variant}.apk'); old=Path(f'dist/AutoBook-0.6.4-{variant}.apk')
 badging=execute(buildtools/'aapt2','dump','badging',apk)
 assert "versionCode='24' versionName='0.6.5'" in badging
 cert=execute(buildtools/'apksigner','verify','--print-certs',apk)
 oldcert=execute(buildtools/'apksigner','verify','--print-certs',old)
 fingerprint=re.search(r'Signer #1 certificate SHA-256 digest: (\w+)',cert).group(1)
 assert fingerprint==re.search(r'Signer #1 certificate SHA-256 digest: (\w+)',oldcert).group(1)
 resources=execute(buildtools/'aapt2','dump','resources',apk)
 for name,data in [('badging',badging),('signature',cert),('resources',resources)]:
  (run/f'logs/{variant}-{name}.txt').write_text(data)
 background=re.search(r'resource 0x[\da-f]+ color/ic_launcher_background\n(.*?)(?=\n\s*resource|\Z)',resources,re.S)
 assert background and '#ffffffff' in background.group(0).lower()
 # Resolve the launcher's compiled adaptive XML, its foreground id, and density PNG paths.
 icon=re.search(r"application:.*icon='([^']+)'",badging).group(1)
 tree=execute(buildtools/'aapt2','dump','xmltree',apk,'--file',icon)
 (run/f'logs/{variant}-adaptive-xmltree.txt').write_text(tree)
 foreground=re.search(r'E: foreground.*?=@(0x[\da-f]+)',tree,re.S).group(1)
 foreground_block=re.search(r'resource '+foreground+r' .*?\n(.*?)(?=\n\s*resource|\Z)',resources,re.S).group(0)
 (run/f'logs/{variant}-icon-resources.txt').write_text(background.group(0)+'\n'+foreground_block)
 pngs=re.findall(r'\(file\) (res/\S+\.png)',foreground_block)
 assert len(pngs)==5,(variant,pngs)
 package_images=[]
 with zipfile.ZipFile(apk) as z:
  for i,name in enumerate(pngs):
   data=z.read(name); target=run/f'intermediate/{variant}-foreground-{i}.png'; target.write_bytes(data)
   info=execute('magick',target,'-format','%wx%h opaque=%[opaque]','info:').strip()
   bbox=execute('magick',target,'-alpha','extract','-threshold','0','-format','%@','info:').strip()
   width=int(info.split('x')[0]); assert 'opaque=False' in info
   bw,bh,x,y=map(int,re.fullmatch(r'(\d+)x(\d+)\+(\d+)\+(\d+)',bbox).groups())
   assert max(bw,bh)<=width/2 and abs(2*x+bw-width)<=2 and abs(2*y+bh-width)<=2
   package_images.append({'path':name,'bytes':len(data),'geometry':info,'alphaBoundingBox':bbox})
  if variant=='debug':
   names=[n for n in z.namelist() if 'ic_launcher' in n]
   assert len(names)==17
   for source in Path('app/src/main/res').glob('mipmap-*/ic_launcher*.png'):
    suffix=str(source).split('/res/')[1].replace('/','-v4/',1)
    assert z.read('res/'+suffix)==source.read_bytes(),source
 result[variant]={'path':str(apk),'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'certificateSha256':fingerprint,'foregrounds':package_images,'versionName':'0.6.5','versionCode':24,'background':'#ffffffff'}
counts={key:0 for key in ['tests','failures','errors','skipped']}
files=list((run/'test-results').glob('*.xml'))
for f in files:
 root=ET.parse(f).getroot()
 for key in counts: counts[key]+=int(root.attrib.get(key,0))
assert counts=={'tests':167,'failures':0,'errors':0,'skipped':0},counts
result['tests']={**counts,'classes':len(files)}
(run/'apk-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False,indent=2))
