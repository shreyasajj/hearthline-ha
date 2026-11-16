"""Demo-only QR import check. Requires /sdcard/Download/pixel-home-test-token.png.
Never presses Connect, opens the camera, logs token text or captures credential screens.
"""
import re
import time
import xml.etree.ElementTree as ET
from device_acceptance import adb, tree, find, click, reveal, texts

FIXTURE='pixel-home-test-token.png'
def raw_tree():
    adb('shell','uiautomator','dump','/sdcard/pixel-home-qr-window.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/pixel-home-qr-window.xml'))
def tap(node):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.5)

try:
    # ADB fixtures need media indexing; grid thumbnails may hide filenames from UIAutomator.
    adb('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d','file:///sdcard/Download/'+FIXTURE)
    click('Settings')
    click('Import token QR image',True)
    selected=False
    for attempt in range(14):
        root=raw_tree()
        nodes=list(root.iter('node'))
        if any('Token imported.' in (n.get('text') or '') and n.get('package')=='dev.pixelhome.templates' for n in nodes): break
        if selected and any(n.get('package')=='dev.pixelhome.templates' for n in nodes): break
        fixture=next((n for n in nodes if FIXTURE in (n.get('text') or '')),None)
        if fixture is not None:
            tap(fixture);selected=True;continue
        list_view=next((n for n in nodes if n.get('content-desc')=='List view'),None)
        if list_view is not None:
            tap(list_view);continue
        for label in (['Open','Select','Add'] if selected else ['Downloads','Download','Files','Files by Google']):
            choice=next((n for n in nodes if n.get('text')==label),None)
            if choice is not None:
                tap(choice);break
        else:
            navigation=next((n for n in nodes if n.get('content-desc') in ('Show roots','Show navigation drawer')),None)
            if navigation is not None: tap(navigation)
            else: time.sleep(.5)
    else: raise AssertionError('Synthetic QR fixture could not be selected from Android picker')
    reveal('Token imported.')
    root=tree()
    assert any('Token imported.' in t for t in texts(root))
    assert any(n.get('password')=='true' for n in root.iter('node')), 'Imported token field is not masked'
    print('PASS QR image selected through Android picker and imported into masked field',flush=True)
    click('Clear token',True)
    assert any('Token cleared.' in t for t in texts(tree()))
    print('PASS explicit credential clearing',flush=True)
finally:
    adb('shell','input','keyevent','KEYCODE_HOME')
    adb('shell','rm','-f','/sdcard/Download/'+FIXTURE,'/sdcard/pixel-home-qr-window.xml')
