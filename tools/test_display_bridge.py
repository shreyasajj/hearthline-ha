import importlib.util
import json
import sys
import types
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
PACKAGE=ROOT/'home-assistant/custom_components/pixel_home'
# Load pure transport modules without importing HA-dependent integration setup.
module=types.ModuleType('pixel_home'); module.__path__=[str(PACKAGE)]; sys.modules['pixel_home']=module
from pixel_home.bridge import Bridge
from pixel_home.protocol import validate, REVISION

class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.now=100.0
        self.bridge=Bridge('pixel_tablet',lambda:self.now)
        self.client=dict(display_id='pixel_tablet',session_id='session-1',capability_revision=REVISION,foreground=True,locked=False)
        self.bridge.exchange(self.client,'user-1')
        self.request=json.loads((ROOT/'display-contract/example-reading.json').read_text())
    def send(self): return self.bridge.present('session-1',REVISION,self.request)
    def exchange(self, **changes): return self.bridge.exchange(dict(self.client,**changes),'user-1')
    def test_offline_is_explicit(self):
        self.now+=11
        self.assertFalse(self.bridge.context()['remote_display_available'])
        with self.assertRaises(ValueError):self.send()
    def test_locked_rejected(self):
        self.exchange(locked=True)
        with self.assertRaises(ValueError):self.send()
    def test_background_rejected(self):
        self.exchange(foreground=False)
        with self.assertRaises(ValueError):self.send()
    def test_accepted_is_not_rendered(self):
        self.assertEqual('accepted',self.send()['state'])
        self.assertEqual('present',self.exchange()['command']['kind'])
        self.assertEqual('accepted',self.bridge.status(self.request['response_id'])['state'])
    def test_render_ack(self):
        self.send();self.exchange()
        self.exchange(ack=dict(response_id=self.request['response_id'],state='rendered'))
        self.assertEqual('rendered',self.bridge.status(self.request['response_id'])['state'])
        self.assertIsNone(self.bridge.records[self.request['response_id']]['request'])
    def test_dismiss_during_delivery_race(self):
        self.send();self.exchange()
        self.bridge.dismiss('session-1',self.request['response_id'])
        command=self.exchange(ack=dict(response_id=self.request['response_id'],state='rendered'))['command']
        self.assertEqual('dismiss',command['kind'])
        self.exchange(ack=dict(response_id=self.request['response_id'],state='dismissed'))
        self.assertEqual('dismissed',self.bridge.status(self.request['response_id'])['state'])
    def test_duplicate_is_idempotent(self):
        self.send();self.send();self.assertEqual(1,len(self.bridge.records))
    def test_conflicting_id_rejected(self):
        self.send();self.request['summary']='Changed'
        with self.assertRaises(ValueError): self.send()
    def test_no_overwrite_while_reading(self):
        self.send();self.request['response_id']='second'
        with self.assertRaises(ValueError):self.send()
    def test_stale_session_rejected(self):
        with self.assertRaises(ValueError):self.bridge.present('old',REVISION,self.request)
    def test_status_is_session_scoped(self):
        self.send()
        with self.assertRaises(ValueError):self.bridge.status(self.request['response_id'],'old-session')
    def test_unknown_capability_rejected(self):
        with self.assertRaises(ValueError):self.bridge.present('session-1','future',self.request)
    def test_never_delivered_expires(self):
        self.send();self.now+=301
        self.assertEqual('expired',self.bridge.status(self.request['response_id'])['state'])
    def test_missing_ack_is_unknown(self):
        self.send();self.exchange();self.now+=301
        self.assertEqual('delivery_unknown',self.bridge.status(self.request['response_id'])['state'])
    def test_reading_not_ttl_expired(self):
        self.send();self.exchange(ack=dict(response_id=self.request['response_id'],state='rendered'));self.now+=400
        self.assertEqual('rendered',self.bridge.status(self.request['response_id'])['state'])
    def test_lock_before_delivery(self):
        self.send();self.assertIsNone(self.exchange(locked=True)['command'])
        self.assertEqual('rejected',self.bridge.status(self.request['response_id'])['state'])
    def test_wrong_owner(self):
        with self.assertRaises(ValueError):self.bridge.exchange(self.client,'another-user')
    def test_session_collision(self):
        with self.assertRaises(ValueError):self.exchange(session_id='another-tablet')
    def test_restart_discards_pending(self):
        self.send();self.now+=11;self.exchange(session_id='session-2')
        self.assertEqual('unknown',self.bridge.status(self.request['response_id'])['state'])
    def test_wrong_target(self):
        with self.assertRaises(ValueError):self.exchange(display_id='other')
    def native(self,kind='weather'):
        return json.loads((ROOT/f'display-contract/example-{kind}.json').read_text())
    def test_native_shared_corpus(self):
        for f in json.loads((ROOT/'app/src/test/resources/template-fixtures.json').read_text()):
            with self.subTest(f['name']):
                if f['valid']: validate(f['request'])
                else:
                    with self.assertRaises((ValueError,TypeError)): validate(f['request'])
    def render_native(self):
        r=self.native(); self.bridge.present('session-1',REVISION,r)
        self.exchange(ack=dict(response_id=r['response_id'],state='rendered'))
        return r
    def test_loading_cannot_be_skipped(self):
        r=self.native();self.bridge.present('session-1',REVISION,r)
        self.assertFalse(self.bridge.update('session-1',r))
    def test_update_sequence_and_ack(self):
        r=self.render_native();self.assertTrue(self.bridge.update('session-1',r))
        command=self.exchange()['command']; self.assertEqual('update',command['kind']);self.assertEqual(1,command['sequence'])
        self.exchange(ack=dict(response_id=r['response_id'],state='rendered',sequence=0))
        self.assertEqual('accepted',self.bridge.status(r['response_id'])['state'])
        self.exchange(ack=dict(response_id=r['response_id'],state='rendered',sequence=1))
        self.assertEqual('rendered',self.bridge.status(r['response_id'])['state'])
    def test_late_result_cannot_reopen_dismissed(self):
        r=self.render_native();self.bridge.dismiss('session-1',r['response_id'])
        self.assertFalse(self.bridge.update('session-1',r))
        self.exchange(ack=dict(response_id=r['response_id'],state='dismissed'))
        self.assertFalse(self.bridge.update('session-1',r))
    def test_result_cannot_cross_session(self):
        r=self.render_native();self.now+=11;self.exchange(session_id='session-2')
        self.assertFalse(self.bridge.update('session-1',r))
    def test_locked_update_rejected(self):
        r=self.render_native();self.exchange(locked=True)
        self.assertFalse(self.bridge.update('session-1',r))
    def test_background_update_rejected(self):
        r=self.render_native();self.exchange(foreground=False)
        self.assertFalse(self.bridge.update('session-1',r))
    def test_expired_update_cannot_deliver_late(self):
        r=self.render_native();self.bridge.update('session-1',r);self.now+=121
        self.assertFalse(self.bridge.update('session-1',r))
    def test_shared_payload_corpus(self):
        fixtures=json.loads((ROOT/'app/src/test/resources/display-fixtures.json').read_text())
        for fixture in fixtures:
            with self.subTest(fixture['name']):
                if fixture['valid']:validate(fixture['request'])
                else:
                    with self.assertRaises((ValueError,TypeError)):validate(fixture['request'])

if __name__=='__main__': unittest.main()
