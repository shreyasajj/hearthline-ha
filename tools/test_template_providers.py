"""Pure adapter tests with HA-shaped fakes. Does not start a real HA server."""
import asyncio
import json
import types
import unittest
from datetime import datetime,timezone,timedelta
from test_display_bridge import ROOT, Bridge, REVISION
from pixel_home.providers import TemplateProviders

class ProviderTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.entity=types.SimpleNamespace(entity_id='weather.home',state='cloudy',last_updated=datetime.now(timezone.utc),attributes=dict(friendly_name='Home',temperature=18,temperature_unit='°C',supported_features=2))
        self.values={'weather.home':self.entity}
        self.calls=[]
        async def call(domain,service,data,**kwargs):
            self.calls.append((domain,service,data,kwargs))
            return {'weather.home':{'forecast':[dict(datetime=(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat(),temperature=18,condition='cloudy')]}}
        self.hass=types.SimpleNamespace(states=types.SimpleNamespace(get=self.values.get),services=types.SimpleNamespace(async_call=call),async_create_task=asyncio.create_task,config=types.SimpleNamespace(time_zone='America/Chicago'))
        self.bridge=Bridge('pixel_tablet')
        self.client=dict(display_id='pixel_tablet',session_id='s1',capability_revision=REVISION,foreground=True,locked=False)
        self.bridge.exchange(self.client,'user')
        self.options=dict(weather_entity='weather.home',home_entities=['light.one','light.two','lock.front'])
        self.providers=TemplateProviders(self.hass,self.bridge,self.options)
    async def asyncTearDown(self):
        self.providers.cancel(); await asyncio.sleep(0)
    async def test_unknown_precipitation_is_not_zero(self):
        result=await self.providers.fetch(dict(template='weather'),'w1')
        d=result['template']['data'];self.assertIsNone(d['precipitation_probability'])
        self.assertIsNone(d['hours'][0]['precipitation_probability'])
        self.assertEqual('get_forecasts',self.calls[0][1]);self.assertTrue(self.calls[0][3]['return_response'])
    async def test_unavailable_weather_has_no_measurements(self):
        self.entity.state='unavailable'
        result=await self.providers.fetch(dict(template='weather'),'w1')
        self.assertIsNone(result['template']['data']);self.assertEqual('unavailable',result['template']['state'])
    async def test_current_weather_and_hourly_availability_are_separate(self):
        self.entity.attributes['supported_features']=0
        caps=self.providers.capabilities()
        self.assertTrue(caps['weather']['available'])
        self.assertFalse(caps['hourly_weather']['available'])
        self.entity.state='unavailable'
        self.assertFalse(self.providers.capabilities()['weather']['available'])
    async def test_missing_hourly_support(self):
        self.entity.attributes['supported_features']=0
        result=await self.providers.fetch(dict(template='weather'),'w1')
        self.assertEqual([],result['template']['data']['hours']);self.assertFalse(self.calls)
    async def test_unit_changes_are_not_cached(self):
        self.entity.attributes.update(temperature_unit='°F',temperature=70)
        result=await self.providers.fetch(dict(template='weather'),'w1')
        self.assertEqual('°F',result['template']['data']['unit']);self.assertEqual(70,result['template']['data']['temperature'])
    async def test_forecast_failure_preserves_current(self):
        async def fail(*a,**k): raise RuntimeError('offline')
        self.hass.services.async_call=fail
        result=await self.providers.fetch(dict(template='weather'),'w1')
        self.assertEqual('partial',result['template']['state']);self.assertEqual(18,result['template']['data']['temperature'])
    async def test_home_unknowns_are_not_off(self):
        self.values['light.one']=types.SimpleNamespace(state='unknown',attributes=dict(friendly_name='One'),last_updated=datetime.now(timezone.utc))
        result=await self.providers.fetch(dict(template='home_status'),'h1')
        self.assertEqual('partial',result['template']['state'])
        self.assertEqual(['unknown','unavailable','unavailable'],[i['state'] for i in result['template']['data']['items']])
        self.assertFalse(self.calls)
    async def test_unconfigured_route_never_fabricates_data(self):
        result=await self.providers.fetch(dict(template='journey',destination='Airport',mode='driving'),'j1')
        self.assertEqual('unavailable',result['template']['state']);self.assertIsNone(result['template']['data'])
        self.assertFalse(self.providers.capabilities()['routing']['configured'])
    async def test_intent_rejects_url_and_arbitrary_state(self):
        for intent in [dict(template='weather',temperature=75),dict(template='journey',destination='Airport',mode='flying'),dict(template='home_status',entity_id='lock.front')]:
            with self.assertRaises(ValueError): self.providers.present('s1',REVISION,'bad',intent)
    async def test_duplicate_intent_coalesces(self):
        self.providers.present('s1',REVISION,'w1',dict(template='weather'))
        task=self.providers.tasks['w1']
        self.providers.present('s1',REVISION,'w1',dict(template='weather'))
        self.assertIs(task,self.providers.tasks['w1'])
        with self.assertRaises(ValueError):self.providers.present('s1',REVISION,'w1',dict(template='home_status'))
    async def test_dismiss_cancels_fetch(self):
        entered=asyncio.Event(); cancelled=asyncio.Event()
        async def slow(*args):
            entered.set()
            try: await asyncio.Event().wait()
            finally: cancelled.set()
        self.providers.fetch=slow
        self.providers.present('s1',REVISION,'w1',dict(template='weather'))
        await entered.wait();self.bridge.dismiss('s1','w1')
        await asyncio.wait_for(cancelled.wait(),1)
        self.assertEqual('dismiss_requested',self.bridge.status('w1')['state'])
    def route_session(self,result):
        payload=json.dumps(result).encode(); captured={}
        class Content:
            async def iter_chunked(self,size):
                for start in range(0,len(payload),size): yield payload[start:start+size]
        class Response:
            status=200; content=Content()
            async def __aenter__(self): return self
            async def __aexit__(self,*args): pass
        class Session:
            def post(self,url,**kwargs): captured.update(url=url,**kwargs); return Response()
        import sys
        helper=types.ModuleType('homeassistant.helpers.aiohttp_client');helper.async_get_clientsession=lambda _:Session()
        sys.modules['homeassistant.helpers.aiohttp_client']=helper
        self.options.update(journey_endpoint='https://route.example/journey',home_origin='Explicit home address',journey_token='server-secret')
        return captured
    async def test_route_credentials_stay_in_server_header(self):
        req=json.loads((ROOT/'display-contract/example-journey.json').read_text())
        t=req['template'];captured=self.route_session(dict(state='ready',summary='Provider route',source=t['source'],updated_at=t['updated_at'],stale_after_seconds=60,data=t['data']))
        result=await self.providers.fetch(dict(template='journey',destination='Explicit airport',mode='driving'),'j1')
        self.assertFalse(captured['allow_redirects']);self.assertNotIn('server-secret',json.dumps(result));self.assertNotIn('server-secret',json.dumps(captured['json']))
        self.assertEqual('unavailable',result['template']['data']['traffic'])
    async def test_unconfigured_traffic_cannot_claim_live(self):
        req=json.loads((ROOT/'display-contract/example-journey.json').read_text());t=req['template'];t['data']['traffic']='live'
        self.route_session(dict(state='ready',summary='Provider route',source=t['source'],updated_at=t['updated_at'],stale_after_seconds=60,data=t['data']))
        with self.assertRaises(ValueError): await self.providers.fetch(dict(template='journey',destination='Airport',mode='driving'),'j1')
    async def test_route_byte_limit(self):
        self.route_session(dict(oversized='x'*24577))
        with self.assertRaises(ValueError): await self.providers.fetch(dict(template='journey',destination='Airport',mode='driving'),'j1')
    async def test_route_clarification_has_no_measurements(self):
        self.route_session(dict(state='clarification',summary='Which airport?',source='Test gateway',updated_at=datetime.now(timezone.utc).isoformat(),stale_after_seconds=60,data=None))
        result=await self.providers.fetch(dict(template='journey',destination='Airport',mode='driving'),'j1')
        self.assertEqual('clarification',result['template']['state']);self.assertIsNone(result['template']['data'])
    async def test_frame_is_rendered_before_data_update(self):
        self.providers.present('s1',REVISION,'w1',dict(template='weather'))
        await asyncio.sleep(.02)
        self.assertEqual('loading',self.bridge.records['w1']['request']['template']['state'])
        self.bridge.exchange(dict(self.client,ack=dict(response_id='w1',state='rendered')),'user')
        await asyncio.sleep(.15)
        command=self.bridge.exchange(self.client,'user')['command']
        self.assertEqual('update',command['kind']);self.assertEqual('weather',command['request']['layout'])

if __name__=='__main__':unittest.main()
