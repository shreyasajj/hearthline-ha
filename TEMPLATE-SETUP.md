# Pixel Home 0.4 — native assistant templates

Journey, Weather and Home Status render as native full-screen activity views with a cream/sage palette, fixed Home/Dismiss controls, source freshness and adaptive stacked layouts. The existing generic assistant sheet, music/dashboard navigation, Home role and protected lock-screen path remain in place. They use protocol 1.1, schema version 1. Generic protocol 1.0 layouts remain supported. Update both the app and custom integration: capability revision is `pixel-home-0.4`; old clients are explicitly rejected rather than receiving an incompatible payload. No microphone, wake word, STT, TTS or audio ducking is implemented. `spoken_text` remains text metadata for a separately configured voice backend.

Install `install/PixelHome-debug.apk` or build with JDK 17 and Android SDK 35:

```sh
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python3 -m unittest discover -s tools -p 'test_*.py'
```

On a device with a different signing certificate, updating the same package requires uninstalling it, which removes local preferences. Do not uninstall to run a trial: `-PpixelHomeTestId=dev.pixelhome.templates` builds a separate **Pixel Home Preview** application. It has independent data and leaves the installed `dev.pixelhome` app intact. Private signing keys are excluded from the package.

## Home Assistant setup

Copy `home-assistant/custom_components/pixel_home` to your HA configuration directory using your normal deployment process; restart HA after reviewing it. This implementation does not deploy to HA automatically. Configure the integration in configuration.yaml:

```yaml
pixel_home:
  display_id: pixel_tablet
  llm_api: true
  weather_entity: weather.home  # replace with an actual weather entity
  weather_stale_seconds: 3600  # choose for your weather integration's update policy
  home_entities:              # explicit ordered scope, at most 16 unique IDs
    - light.living_room
    - lock.front_door
  home_stale_seconds: 30      # freshness of reads from the HA state machine
  # Optional: your own Google-free normalization gateway, never a public demo service.
  # journey_endpoint: https://routes.example.net/pixel-home/journey
  # home_origin: "Your explicit address or coordinates"
  # journey_token: !secret pixel_home_journey_token
  # journey_traffic: false
```

Connect the foreground, unlocked tablet to HA using its existing HTTPS Settings. Enable its display bridge, then select the Pixel Home display LLM API in your conversation agent. Credentials remain session-only. The tablet exposes no incoming network listener. The original DISPLAY-SETUP.md describes the generic API and connection process.

`pixel_home.get_screen_context` reports installed schemas separately from configured routing, geocoding, imagery, traffic, forecast capability and display availability. `available: null` means not yet verified, not live. Forecast support is checked against the current entity's supported_features; forecasts are requested through `weather.get_forecasts` with return_response, never read from deprecated forecast attributes. Current and daily values preserve original units; missing probability remains null, distinct from precipitation quantity. The hourly strip excludes past values and is bounded to the next six hours. Missing hourly/daily support does not hide current temperature. No alerts are synthesized.

Home summaries read the configured HA state machine values every five seconds while active. They execute no actions and offer no action widgets. This keeps device controls and sensitive confirmations in the existing native dashboard. Reported `unknown`/`unavailable` values remain explicit. The counts describe only configured entities and cannot establish whole-house security. Polling reads have their own configurable freshness window; weather and journey keep their source-specific policy.

## Minimal intent call

Fetch context first. Call the HA `pixel_home.present_template` service (return_response) or LLM tool with the current session/revision and a unique response ID. The HA service accepts:

```json
{"display_id":"pixel_tablet","session_id":"FROM_CONTEXT","capability_revision":"pixel-home-0.4","response_id":"journey_1","intent":{"template":"journey","destination":"An explicit destination","mode":"driving"}}
```

Weather/home_status intents contain only `template`. The LLM tool flattens intent to template/destination/mode; it never receives provider tokens or accepts measurements, URLs, arbitrary entity selections, services or UI structure. The generic LLM present tool accepts only protocol 1.0; trusted template construction stays in the backend. A loading frame is delivered first, and the result waits for its render acknowledgement. Accepted is not rendered, and rendered is not read. No response automatically dismisses while reading.

At most one answer is active. Duplicate ID and identical intent coalesce; different intent with the same ID fails. No supersession silently interrupts reading: dismiss, wait for acknowledgement, then submit another intent. Updates carry monotonic sequence numbers and the original session/response ID. They cannot create a frame after dismissal, reconnect, expiry, lock or backgrounding. Dismissal cancels adapter work. Home item ordering stays stable. Fetches time out after 20 seconds. The foreground delivery poll remains two seconds, so data arrival and presentation are not instantaneous.

## Journey adapter contract

The optional gateway must be an operator-controlled, trusted HTTPS endpoint that implements geocoding/ambiguity handling and routing using your chosen Google-free providers. This repo supplies the bounded gateway adapter, not a hosted routing service or paid provider account. No endpoint is enabled by default. The fixed server configuration URL permits no query, credentials or fragment; redirects are rejected. Provider credentials stay in HA secrets and are never forwarded to tablet or LLM. The request is:

```json
{"origin":"explicit configured home origin","destination":"user destination","mode":"driving"}
```

The response must be JSON, at most 24 KiB, with exactly these fields:

```json
{"state":"clarification","summary":"Which airport and locality do you mean?","source":"Your provider name","updated_at":"2026-11-15T14:00:00-06:00","stale_after_seconds":300,"data":null}
```

Allowed states: ready, partial, unavailable, clarification. For ready/partial use `data` from `display-contract/example-journey.json`, replacing **every** fixture value with real provider values. `templates.schema.json` documents it. Durations are numeric seconds, distances numeric meters; departure includes an offset and timezone is an IANA ID used for arrival display. Geometry is longitude/latitude pairs (0 or 2–256 points); simplify on the gateway if needed, and respect provider geometry/attribution terms. Include address/locality, at most 20 major instructions and route attribution. No URLs or result references are accepted, so geometry cannot trigger arbitrary authenticated downloads. Traffic is independently configured; a live traffic result is rejected unless journey_traffic is enabled. Otherwise return `traffic: unavailable`; never infer congestion. The adapter must return clarification when either endpoint is ambiguous. Do not substitute tablet GPS; this app requests no location permission.

Freshness comes from the provider timestamp and stale_after_seconds (1–86400), rather than a universal cache TTL. This milestone implements no route cache or offline downloads. Attribution, caching, rate limits, usage rights and any billing must be reviewed with your actual chosen providers before configuring them. No provider-specific terms can be certified without a provider selection.

## Map imagery

MapLibre Native Android **11.8.0** is pinned and compiled through Compose AndroidView. Settings accepts a session-only HTTPS style URL, a comma-separated allowlist of all HTTPS style/tile/glyph/sprite origins, and required map/data attribution. Supply an operator-controlled Google-free style whose licensing allows your intended use. Map requests carry no HA authorization, reject redirects, and enforce the allowlist for every request. No model payload can configure these origins. No public demo tile/geocoder endpoint is installed. Prefer tokenless styles or a provider-approved restricted public token; private tokens must remain on your own tile proxy.

Both route and configured map attribution are visible as plain text; style requests cannot launch external links. The map fits real geometry once, adds a destination marker and no location dot. Route summaries survive tile/style failures. Empty geometry and unconfigured imagery show a map-unavailable card. Imagery and traffic are independent of routing. Offline map support and navigation are unsupported.

## References checked during implementation

- MapLibre Quickstart / MapView lifecycle: https://maplibre.org/maplibre-native/android/examples/getting-started/
- Pinned native release: https://github.com/maplibre/maplibre-native/releases/tag/android-v11.8.0
- HA weather state/units and get_forecasts: https://www.home-assistant.io/integrations/weather/

## Manual acceptance

Use Settings → Preview journey/weather/home status only in demo mode. Fixtures say DEMO / synthetic and may show stale times intentionally. They do not fetch map tiles, routes, device states or audio. Validate live server delivery separately after configuration.

Check landscape, portrait/narrow window, large font, long names, dark theme, TalkBack traversal and 56 dp controls. Expand details and confirm polling does not scroll/reorder; use Home and Dismiss; lock and background during loading; reconnect after expiry and verify no replay. Simulate provider timeout, unavailable weather, changed units, missing hourly forecasts and failed map tiles. Test existing music/navigation and Android sensitive-action confirmations. Do not infer dock playback or real provider success from demo previews.

Timing: response status exposes `request_to_frame_ack_ms` and `request_to_data_ack_ms` separately, measured from backend acceptance to client render acknowledgements. These include polling/ACK transit and exclude speech recognition, model selection and user perception. Android logs `PixelHomeTiming` receive-to-first-frame elapsed milliseconds, with no query or credentials. No live-provider latency is claimed without a configured endpoint and real delivery run.


## Dock presentation and separate telemetry milestone

STARTUP-TEST.md documents dock-only protected presentation, dismissal/unlock/rearm preferences and the optional permission-backed background lock watcher. It is separate from assistant template delivery and from music audio output. Automatic return never opens Pixel Home while Android is unlocked. The service/permission path remains untested on the physical tablet; it is off until explicitly enabled. HA-INTEGRATION-PROMPT.md is ready to use in another coding conversation for authenticated telemetry push and native HA entities. That sender/receiver milestone is not implemented by this template build.


## Token QR setup (0.4.1)

QR-SETUP.md explains scanning Home Assistant's existing long-lived-token QR with the camera or importing its image through Android Files. The server origin is still entered separately and Connect remains explicit. Tokens remain masked and process-only; no Google scanner service is used. Client authentication UI changed without changing the pixel-home-0.4 display revision or the backend's 1.0/1.1 response contracts.
