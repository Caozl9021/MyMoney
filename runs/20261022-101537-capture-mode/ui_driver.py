import subprocess,time,re,xml.etree.ElementTree as E
from pathlib import Path
ADB=['/home/caozl/Android/Sdk/platform-tools/adb','-s','emulator-5554']
OUT=Path('/home/caozl/Projects/AutoBook/runs/20261022-101537-capture-mode/outputs')
def a(*args): return subprocess.check_output(ADB+list(args))
def nodes(name='current'):
 a('shell','uiautomator','dump','/sdcard/autobook-core.xml')
 raw=a('exec-out','cat','/sdcard/autobook-core.xml');(OUT/(name+'.xml')).write_bytes(raw)
 return list(E.fromstring(raw).iter('node'))
def center(n):
 x,y,r,b=map(int,re.findall(r'\d+',n.get('bounds')));return (x+r)//2,(y+b)//2

def tap(x,y): a('shell','input','tap',str(x),str(y));time.sleep(.35)
def text(s,ns=None):
 ns=nodes() if ns is None else ns
 return next(n for n in ns if n.get('text')==s)
def taptext(s,ns=None):tap(*center(text(s,ns)))
def fill(index,value):
 ns=nodes();n=[n for n in ns if n.get('class')=='android.widget.EditText'][index];tap(*center(n))
 a('shell','input','keyevent','123',*(['67']*30));a('shell','input','text',value);a('shell','input','keyevent','4');time.sleep(.3)
def swipe():a('shell','input','swipe','540','1940','540','650','400');time.sleep(.4)
def shot(name):(OUT/(name+'.png')).write_bytes(a('exec-out','screencap','-p'))
