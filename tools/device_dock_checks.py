"""Interactive secure dock acceptance. The user enters credentials only on Android."""
import re
import time
from device_acceptance import adb,tree,texts,find,click,reveal,capture

def toggle(label,value,forward=True):
    reveal(label,forward);r=tree();text=find(r,label)
    parent=next(p for p in r.iter('node') if text in list(p))
    switch=next(n for n in parent.iter('node') if n.get('checkable')=='true' and n.get('clickable')=='true')
    previous=switch.get('checked')=='true'
    if previous!=value:
        x1,y1,x2,y2=map(int,re.findall(r'\d+',switch.get('bounds')))
        adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.4)
    return previous

def locked(): return 'mIsShowing=true' in adb('shell','dumpsys','window','policy').decode()
def await_unlock():
    for _ in range(120):
        if not locked(): time.sleep(.5);return
        time.sleep(2)
    raise AssertionError('User authentication not completed; no bypass attempted')
def lock_and_wake():
    adb('shell','input','keyevent','KEYCODE_SLEEP');time.sleep(1)
    adb('shell','input','keyevent','KEYCODE_WAKEUP');time.sleep(1)
    for _ in range(8):
        try:
            if find(tree(),'Unlock controls') is not None:return
        except AssertionError: pass
        time.sleep(.5)
    raise AssertionError('Protected dock view did not appear automatically after waking')

previous=None
try:
    click('Settings')
    reveal('Dock connected')
    capture('dock-settings-automatic',['Dock connected','Android audio dock'])
    click('Assume undocked (manual)',True)
    capture('dock-settings-manual',['Undocked · manual override'])
    click('Automatic dock signals',True,False)
    previous=toggle('Show over lock screen',True,False)
    click('Home')
    lock_and_wake()
    capture('dock-locked',['Your tablet is locked','Dock connected','Unlock controls'])
    click('Dismiss · use Android lock screen')
    print('WAITING_FOR_USER_UNLOCK_1: Dock view dismissed; unlock using Android normally.',flush=True)
    await_unlock()
    print('PASS Android authentication after dismissal',flush=True)
    adb('shell','am','start','-n','dev.pixelhome.templates/dev.pixelhome.MainActivity');time.sleep(.5)
    assert find(tree(),'Unlock controls') is None
    print('PASS normal unlocked application',flush=True)
    lock_and_wake()
    capture('dock-relocked',['Your tablet is locked','Dock connected','Unlock controls'])
    click('Unlock controls')
    print('WAITING_FOR_USER_UNLOCK_2: Dock view reappeared; authenticate on Android to return to the app.',flush=True)
    await_unlock()
    assert find(tree(),'Unlock controls') is None
    print('PASS dock rearm and authenticated return',flush=True)
finally:
    if previous is not None and not locked():
        click('Settings');toggle('Show over lock screen',previous)
        click('Home')
