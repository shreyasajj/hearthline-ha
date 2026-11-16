"""Optional HA LLM API. Display-only; select alongside Assist device control."""
import json
try:
    import probatio as schema
except ImportError:
    import voluptuous as schema
from homeassistant.helpers import llm
from homeassistant.exceptions import HomeAssistantError

PROMPT='''Pixel Home is a display, not a search engine or device controller. First call
pixel_home_get_screen_context. Use only that connected display and its advertised capabilities.
Do not send private answers if remote_display_available is false. Do not replace an answer
someone is reading: ask before dismissing it. Use a short summary and scrollable blocks for detail.
Get search results from your actual search tools first; never invent citations. Present only real
outcomes returned by separate authorized device tools. No device actions are accepted in a display
request. A successful present call means accepted, not rendered. Check status before saying it
appeared; rendered does not mean read. On failure answer normally in the conversation. Do not loop
while waiting. Images, arbitrary HTML, voice activation and speech playback are unsupported.
request_json must be JSON with protocol_version "1.0", a unique response_id using letters/digits/_/-,
layout compact|reading|search|steps|comparison, title (80 chars), summary (240), spoken_text (400,
metadata only; not spoken by tablet), ttl_seconds (integer 15..300), and blocks (1..8).
Blocks: {type:paragraph,text:4000 chars,heading?:80}; {type:bullets,items:[1..8 strings <=300],heading?:80};
{type:steps,items:[1..10 strings <=600],heading?:80}; {type:sources,items:[1..5 {title:100,url:HTTPS,
excerpt?:300}]}; {type:table,columns:[1..4 strings <=60],rows:[1..8 arrays of matching width,
cells <=160]}; {type:status,label:120,state:success|partial|pending|error,detail?:500}.
No other fields. Payload maximum 32 KiB UTF-8. TTL expires delivery, not reading time.'''

PROMPT += """
For journey/weather/home_status use pixel_home_present_template with only semantic intent.
Journey requires destination and driving/walking/cycling mode. Ask when ambiguous; origin is
explicit server configuration, never assume tablet location. Weather and home_status take no
extra arguments. Fetch fresh context and inspect providers first. Installed renderer does not
mean configured/live provider. Never invent temperature, forecasts, route geometry, traffic or
entity states. Backend clarification must be answered by the user. Keep speech to one or two
useful sentences. spoken_text is text metadata; the tablet has no voice transport. Do not make
another model call just to format the known layout. If unavailable, answer normally. Native
home summaries are read-only; use authorized device tools separately for actions. Do not claim
physical completion from service acceptance."""

class DisplayTool(llm.Tool):
    integration='pixel_home'
    def __init__(self, action, description, parameters):
        self.action=action; self.name='pixel_home_'+action
        self.description=description; self.parameters=schema.Schema(parameters)
        if hasattr(llm,'ToolAnnotations'):
            self.annotations=llm.ToolAnnotations(read_only=action in ('get_screen_context','get_response_status'),
                destructive=False,idempotent=True,open_world=False)
    async def async_call(self,hass,tool_input,llm_context):
        bridge=hass.data['pixel_home']; args=tool_input.tool_args
        try:
            if self.action=='get_screen_context': data=bridge.context()
            elif self.action=='present_template':
                intent=dict(template=args['template'])
                for key in ('destination','mode'):
                    if key in args: intent[key]=args[key]
                data=bridge.providers.present(args['session_id'],args['capability_revision'],args['response_id'],intent)
            elif self.action=='present_response':
                raw=args['request_json']
                if len(raw.encode())>32768: raise ValueError('Request exceeds 32 KiB')
                request=json.loads(raw)
                if request.get('protocol_version')!='1.0': raise ValueError('Use present_template for native templates; providers own measurements')
                data=bridge.present(args['session_id'],args['capability_revision'],request)
            elif self.action=='get_response_status': data=bridge.status(args['response_id'],args['session_id'])
            else: data=bridge.dismiss(args['session_id'],args['response_id'])
        except (KeyError,TypeError,ValueError) as err:
            raise HomeAssistantError(str(err)) from err
        return llm.ToolResult(data=data) if hasattr(llm,'ToolResult') else data

class DisplayAPI(llm.API):
    async def async_get_api_instance(self,llm_context):
        required=schema.Required
        tools=[DisplayTool('get_screen_context','Read the configured Pixel Home display capabilities and current availability.',{}),
            DisplayTool('present_template','Show a native journey, weather or configured home summary. Backend fetches real data. No layout or measurements.',
                {required('session_id'):str,required('capability_revision'):str,required('response_id'):str,
                 required('template'):schema.In(['journey','weather','home_status']),schema.Optional('destination'):str,
                 schema.Optional('mode'):schema.In(['driving','walking','cycling'])}),
            DisplayTool('present_response','Display a structured answer. Returns accepted; check status for rendered.',
                {required('session_id'):str,required('capability_revision'):str,required('request_json'):str}),
            DisplayTool('get_response_status','Check whether an answer was rendered, rejected, expired or dismissed.',{required('session_id'):str,required('response_id'):str}),
            DisplayTool('dismiss_response','Dismiss a specific answer after user request.',{required('session_id'):str,required('response_id'):str})]
        return llm.APIInstance(api=self,api_prompt=PROMPT,llm_context=llm_context,tools=tools)
