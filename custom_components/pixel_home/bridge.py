"""One-screen mailbox with authenticated sessions, expiry and acknowledgements."""
from copy import deepcopy
import hashlib
import json
import re
import time
from .protocol import CAPABILITIES, REVISION, validate

class Bridge:
    def __init__(self, display_id, clock=time.monotonic, area_name=None):
        if not re.fullmatch(r'[a-z0-9_]{1,48}',display_id): raise ValueError('Invalid display ID')
        self.display_id=display_id; self.clock=clock
        self.owner=None; self.session=None; self.seen=-1000
        self.current_screen=None; self.area_name=area_name
        self.locked=True; self.foreground=False; self.records={}
        self.providers=None; self.map_configured=False

    def _expire(self):
        for record in self.records.values():
            if record['state'] in ('accepted','dismiss_requested') and self.clock() >= record['expires']:
                record.update(state='delivery_unknown' if record['sent'] else 'expired', request=None)

    def context(self):
        self._expire(); online=self.clock()-self.seen < 10
        return dict(display_id=self.display_id, session_id=self.session, online=online,
            foreground=self.foreground if online else None, locked=self.locked if online else None,
            remote_display_available=online and self.foreground and not self.locked,
            capability_revision=REVISION, capabilities=deepcopy(CAPABILITIES),
            providers=self.providers.capabilities() if self.providers else {},
            current_screen=self.current_screen if online else None, area_name=self.area_name,
            active_response_ids=[key for key,value in self.records.items() if value['state'] in ('accepted','rendered','dismiss_requested','delivery_unknown')])

    def exchange(self, data, owner):
        if not owner: raise ValueError('Authenticated tablet user required')
        if data.get('display_id') != self.display_id: raise ValueError('Display ID does not match configuration')
        session=data.get('session_id')
        if type(session) is not str or not re.fullmatch(r'[A-Za-z0-9-]{1,80}',session): raise ValueError('Invalid session')
        if data.get('capability_revision') != REVISION: raise ValueError('Unsupported app version')
        if type(data.get('locked')) is not bool or type(data.get('foreground')) is not bool: raise ValueError('Explicit context required')
        if self.owner and self.owner != owner: raise ValueError('Display belongs to a different HA user until restart')
        if self.session and self.session != session and self.clock()-self.seen < 10:
            raise ValueError('Previous session still live; retry in ten seconds')
        if self.session != session:
            if self.providers: self.providers.cancel()
            self.records={}; self.session=session
        self.current_screen=data.get('current_screen') if data.get('current_screen') in ('home','music','idle','settings','locked_clock_music') else None
        self.map_configured=data.get('map_configured') is True
        self.owner=owner; self.seen=self.clock(); self.locked=data['locked']; self.foreground=data['foreground']
        self._expire()
        ack=data.get('ack') or {}
        if type(ack) is not dict: raise ValueError('Invalid acknowledgement')
        record=self.records.get(ack.get('response_id'))
        if record:
            state=ack.get('state')
            # A render acknowledgement must never undo a pending dismiss.
            if state in ('dismissed','rejected') and record['state'] not in ('expired','dismissed','rejected'):
                record.update(state=state,request=None)
                if self.providers: self.providers.cancel(ack.get('response_id'))
            elif state=='rendered' and record['state']=='accepted' and ack.get('sequence',0)==record.get('sequence',0):
                elapsed=round((self.clock()-record['accepted_at'])*1000)
                if record.get('sequence',0)==0: record.setdefault('request_to_frame_ack_ms',elapsed)
                elif record['request'].get('template',{}).get('data') is not None: record.setdefault('request_to_data_ack_ms',elapsed)
                record.update(state='rendered',request=None)
        command=None
        for response_id, record in self.records.items():
            if record['state']=='accepted':
                if self.locked or not self.foreground:
                    record.update(state='rejected',request=None)
                else:
                    record['sent']=True
                    command=dict(kind='update' if record.get('sequence',0)>0 else 'present', sequence=record.get('sequence',0), response_id=response_id, session_id=session,
                        remaining_ms=max(0,int((record['expires']-self.clock())*1000)), request=record['request'])
            elif record['state']=='dismiss_requested':
                record['sent']=True
                command=dict(kind='dismiss',response_id=response_id,session_id=session)
        return dict(command=deepcopy(command))

    def present(self, session_id, capability_revision, request):
        validate(request)
        if session_id!=self.session or capability_revision!=REVISION: raise ValueError('Fetch fresh screen context')
        self._expire(); response_id=request['response_id']
        fingerprint=hashlib.sha256(json.dumps(request,sort_keys=True).encode()).hexdigest()
        old=self.records.get(response_id)
        if old:
            if old['fingerprint']!=fingerprint: raise ValueError('Response ID reused with different content')
            return self.status(response_id)
        context=self.context()
        if not context['remote_display_available']: raise ValueError('Display offline, backgrounded or locked')
        if context['active_response_ids']: raise ValueError('Dismiss current answer and wait for acknowledgement first')
        if len(self.records)>=128: raise ValueError('Reconnect tablet to begin a new session')
        self.records[response_id]=dict(state='accepted',fingerprint=fingerprint,request=deepcopy(request),
            expires=self.clock()+request['ttl_seconds'],sent=False,accepted_at=self.clock(),layout=request['layout'])
        return self.status(response_id)

    def status(self, response_id, session_id=None):
        if session_id is not None and session_id!=self.session: raise ValueError('Fetch fresh screen context')
        self._expire(); record=self.records.get(response_id)
        state=record['state'] if record else 'unknown'
        if state in ('accepted','dismiss_requested') and self.clock()-self.seen>=10: state='delivery_unknown'
        return dict(response_id=response_id,state=state,read_by_user=None, timings={key:record[key] for key in ('request_to_frame_ack_ms','request_to_data_ack_ms') if record and key in record})

    def dismiss(self, session_id, response_id):
        if session_id!=self.session: raise ValueError('Fetch fresh screen context')
        self._expire(); record=self.records.get(response_id)
        if record and record['state'] in ('accepted','rendered','delivery_unknown'):
            record.update(state='dismiss_requested',request=None,expires=self.clock()+30)
            if self.providers: self.providers.cancel(response_id)
        return self.status(response_id)


    def update(self, session_id, request):
        """Only a trusted provider may update a still-active native response."""
        validate(request); self._expire()
        record=self.records.get(request['response_id'])
        if session_id!=self.session or not record or record['state'] not in ('accepted','rendered') or not self.context()['remote_display_available']:
            return False
        if request.get('protocol_version')!='1.1' or request['layout']!=record['layout']: raise ValueError('Native updates only')
        # Never replace loading before the client has actually rendered the known frame.
        if record['state']=='accepted':
            return False
        record.update(state='accepted',request=deepcopy(request),sequence=record.get('sequence',0)+1,
            expires=self.clock()+request['ttl_seconds'],sent=False)
        return True
