"""Run only against the separate demo package; restores any font-scale change."""
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE='dev.pixelhome.templates'
OUT=Path(__file__).resolve().parents[1]/'validation'/'reference-match'
OUT.mkdir(exist_ok=True);(OUT/'screenshots').mkdir(exist_ok=True)

def adb(*args):
    return subprocess.check_output(['adb',*args],timeout=30)
def tree():
    adb('shell','uiautomator','dump','/sdcard/pixel-home-window.xml')
    raw=adb('shell','cat','/sdcard/pixel-home-window.xml')
    root=ET.fromstring(raw)
    assert any(n.get('package')==PACKAGE for n in root.iter('node')), 'Preview not foreground; unlock tablet if needed'
    return root
def texts(root): return [n.get('text') for n in root.iter('node') if n.get('package')==PACKAGE and n.get('text')]
def find(root,label):
    return next((n for n in root.iter('node') if n.get('package')==PACKAGE and n.get('text')==label),None)
def swipe(root,forward=True):
    candidates=[n for n in root.iter('node') if n.get('package')==PACKAGE and n.get('scrollable')=='true' and n.get('class')=='android.widget.ScrollView']
    node=candidates[0] if candidates else next(root.iter('node'))
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    x=x2-40; high=int(y1+(y2-y1)*.25); low=int(y1+(y2-y1)*.8)
    adb('shell','input','swipe',str(x),str(low if forward else high),str(x),str(high if forward else low),'350');time.sleep(.2)
def click(label,scroll=False,forward=True):
    for attempt in range(24 if scroll else 1):
        root=tree();n=find(root,label)
        if n is not None:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
            adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.5);return
        swipe(root,forward if attempt<12 else not forward)
    raise AssertionError('Missing control: '+label)
def reveal(label,forward=True):
    for attempt in range(24):
        root=tree()
        if any(label in value for value in texts(root)): return
        swipe(root,forward if attempt<8 else not forward)
    raise AssertionError('Missing expanded detail: '+label)
def capture(name,expected):
    root=tree();all_text='\n'.join(texts(root))
    if find(root,'Dismiss') is not None:
        for label in ('Home','Dismiss'):
            node=find(root,label)
            if node is not None:
                parent=next(p for p in root.iter('node') if node in list(p) and p.get('clickable')=='true')
                x1,y1,x2,y2=map(int,re.findall(r'\d+',parent.get('bounds')))
                assert y2-y1>=112,(name,label,'touch target clipped or smaller than 56 dp',parent.get('bounds'))
    for value in expected: assert value in all_text,(name,value,all_text)
    (OUT/f'{name}.txt').write_text(all_text+'\n')
    (OUT/'screenshots'/f'{name}.png').write_bytes(adb('exec-out','screencap','-p'))
    print('PASS',name,flush=True)

def run():
    rotation=None
    font=adb('shell','settings','get','system','font_scale').decode().strip()
    try:
        adb('shell','am','force-stop',PACKAGE)
        adb('shell','am','start','-n',PACKAGE+'/dev.pixelhome.MainActivity');time.sleep(1)
        click('Settings')
        click('Preview journey',True)
        capture('journey-landscape',['24','min','Estimated time · traffic unavailable','Map unavailable','Home','Dismiss'])
        click('View all directions')
        capture('journey-expanded',['Hide directions'])
        click('Dismiss')
        click('Preview weather',True)
        capture('weather-landscape',['64','°F','Precipitation probability: unavailable','— = unavailable'])
        click('Dismiss')
        click('Preview home status',True)
        capture('home-landscape',['1 light on','Among 2 configured lights','unavailable','Home','Dismiss'])
        click('View all 6 configured items',True)
        # Expanded content can require scrolling, but controls remain in the same native sheet.
        reveal('Office window')
        capture('home-expanded',['Office window','unknown','Home','Dismiss'])
        click('Dismiss',True)
        adb('shell','settings','put','system','font_scale','2.0');time.sleep(1)
        click('Preview journey',True)
        capture('journey-large-font',['Airport','24','Home','Dismiss'])
        click('Dismiss',True)
        # Backgrounding ends the session and must not replay a preview on return.
        adb('shell','settings','put','system','font_scale',font);time.sleep(1)
        click('Preview weather',True)
        adb('shell','input','keyevent','KEYCODE_HOME');time.sleep(.5)
        adb('shell','am','start','-n',PACKAGE+'/dev.pixelhome.MainActivity');time.sleep(.5)
        assert not find(tree(),'Dismiss'), 'Answer replayed after background transition'
        print('PASS no-background-replay',flush=True)
        # Read-only screenshot of dark template using the app's existing appearance switch.
        click('Settings')
        for _ in range(16):
            r=tree();label=find(r,'Dark appearance')
            if label is not None: break
            swipe(r)
        assert label is not None
        parent=next(p for p in r.iter('node') if label in list(p))
        switch=next(n for n in parent.iter('node') if n.get('checkable')=='true' and n.get('clickable')=='true')
        x1,y1,x2,y2=map(int,re.findall(r'\d+',switch.get('bounds')))
        adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
        click('Preview weather',True,False)
        capture('weather-dark',['64','°F','Home','Dismiss'])
        # A fixed portrait rotation exercises the stacked layout; finally restores auto-rotation.
        rotation=adb('shell','wm','user-rotation').decode().strip()
        adb('shell','wm','user-rotation','lock','0');time.sleep(1)
        if find(tree(),'Dismiss') is None: click('Preview weather',True)
        capture('weather-portrait',['64','°F','Home','Dismiss'])
        if rotation=='free': adb('shell','wm','user-rotation','free')
        else: adb('shell','wm','user-rotation','lock',rotation.split()[-1])
        time.sleep(1)
        click('Home')
        assert find(tree(),'Good morning') is not None
        print('PASS return-home',flush=True)
    finally:
        adb('shell','settings','put','system','font_scale',font)
        if rotation is not None:
            if rotation=='free': adb('shell','wm','user-rotation','free')
            else: adb('shell','wm','user-rotation','lock',rotation.split()[-1])

if __name__=='__main__': run()
