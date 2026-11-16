# Pixel Home — implement fast, Google-free assistant templates

## How to use this prompt

Open the Pixel Home source project in Codex, or attach and extract PixelHome-0.3.0.zip. Then ask: “Follow PixelHome-Codex-Template-Prompt.md and implement this milestone.” This file is an implementation brief, not an updated app or an installed Home Assistant configuration.

---

Act as a senior Android engineer and Material 3 product designer. Implement the following milestone in the existing Pixel Home project. Inspect the source, AGENTS.md, build configuration, current protocol, tests and documentation first. Preserve existing behavior and unrelated user changes. If the source is missing, ask for it rather than inventing a replacement app.

Work through implementation, validation and a reviewable package. Do not stop at a plan. Make routine reversible implementation decisions independently. Do not install anything onto my actual tablet or Home Assistant server, publish releases, or configure paid providers without explicit authorization.

## Product and constraints

Pixel Home is a premium native smart-home hub for a Google Pixel Tablet and official Charging Speaker Dock, running GrapheneOS. Landscape is primary. Home Assistant is the backend and source of truth. Music Assistant provides music integration. Google-free is a firm preference: no Google Maps, Google Play Services, Firebase, Google account, Google-hosted fonts or Google routing/geocoding dependency.

Keep the warm, restrained Pixel/Material You look: generous space, soft sage/cream surfaces, clear hierarchy, large rounded controls, original icons/assets where needed, elegant dark mode. This is a practical Android tablet interface, not a web dashboard or decorative concept.

The previous v0.3 implementation was described as containing:

- Kotlin + Jetpack Compose app; Home/Music/idle/Settings screens.
- Optional default Android Home role and protected clock/music above keyguard.
- Session-only HA credentials and foreground-only display delivery.
- A semantic assistant bottom sheet supporting compact/reading/search/steps/comparison layouts and paragraph/bullets/steps/sources/table/status blocks.
- A HA custom integration with display context, present, status and dismiss tools, plus a tablet exchange endpoint.
- Payload validation, capability revision checks, session isolation, expiry, duplicate detection and client rendering acknowledgements.

Treat this as context to verify against the source, not proof of current code or successful runtime integration. Likely files include AssistantDisplay.kt, AssistantSheet.kt, DisplayResponse.kt, HubViewModel.kt, HomeAssistant.kt, and home-assistant/custom_components/pixel_home/. Preserve generic answer layouts and existing device/music/startup functionality.

## Main goal: fixed templates, small requests

Add three purpose-built, native templates: `journey`, `weather`, and `home_status`.

The LLM selects the template and supplies minimal semantic intent. It must not generate screen structure, component trees, typography, coordinates, styling, map geometry, forecasts or invented device state. Native code owns presentation. Trusted backend adapters fetch and normalize real data.

For common intents, use deterministic routing when appropriate. For example, a journey request needs destination and travel mode, not a generated UI description. Ask for clarification when a destination or origin is ambiguous. Do not quietly assume the tablet's physical location or request location permissions when a configured home origin is sufficient.

Separate three things:

1. Template intent: what the user asked for.
2. Provider result: validated real data, source and freshness.
3. Native template: stable rendering and interaction rules.

Avoid an extra LLM round trip just to populate a known layout. Limit spoken answers to one or two useful sentences. Backend fetch time remains real: do not claim templates make network requests instant.

## Template 1: Journey

Landscape proportions, adjusted responsively:

| Left: about 25% | Middle: about 25% | Right: about 50% |
| --- | --- | --- |
| Destination and route overview | Very large journey duration | Real map with route fitted to view |
| Up to three major directions | Arrival time and distance | Destination marker |
| View all directions | Traffic availability/status | Required map/provider attribution |

Example design copy only: “Airport”, “24 min”, “Arrive 3:42 PM”, “12 miles”. Never present these as real values unless returned by the configured provider.

- Treat this as a pre-departure overview on a home tablet, not a vehicle navigation system.
- Show an address/locality to distinguish similar destinations.
- MapLibre Native is the preferred rendering candidate; verify current Android/Compose APIs and pinned compatible versions before integration.
- Use configurable, Google-free map tiles/styles, routing and geocoding adapters. OpenStreetMap-derived data is a candidate, not a free unlimited hosted service. Verify provider terms, attribution, caching, rate limits and offline permissions. Do not assume public demo tile or geocoding endpoints are production infrastructure.
- Keep credentials out of model context and source. Prefer server-side credentials where supported. Use a restricted public client token only when a chosen map provider requires one.
- No provider credentials are supplied in this brief. Implement configuration and clear “provider not configured” states; keep fixtures explicitly labeled demo. Never claim live integration was tested without a real configured endpoint.
- Real traffic is optional and separate from base maps. Without it, display “Estimated time · traffic unavailable.” Do not draw fake congestion or infer traffic from a slower-than-usual route.
- Store duration and distance as numeric values with explicit units; localize presentation. Compute arrival from trusted departure/duration data and timezone. Identify whether timing incorporates live traffic.
- If map tiles fail but route data is available, preserve the duration and directions and show a map-unavailable placeholder. Do not block the whole answer on map imagery.
- Keep map labels secondary to the large travel summary. Avoid decorative globe spins and perpetual camera movement. Do not show a moving position dot without a real location source.
- Full directions expand only on user action. No auto-scroll while reading. Home and Dismiss stay obvious.

## Template 2: Weather

Use a large temperature and plain-language condition, a concise useful takeaway, and an uncluttered forecast strip showing at most the next six hours. Show location, units, provider/freshness, daily high/low and precipitation probability only when actually available.

- Source data from a user-configured HA weather integration/entity and its supported forecast mechanism. Verify current HA APIs; do not assume hourly forecasts are present in entity attributes.
- Unknown precipitation probability must display as unavailable, not 0%. Distinguish probability from precipitation quantity.
- Support missing forecast, unavailable entity, stale data and unit changes.
- Use restrained original weather icons; optional subtle motion honors reduced-motion settings.
- Show material alerts only from a genuine configured source; no invented warnings.
- A short spoken answer should prioritize the user's question, such as whether rain is expected, rather than reading every hour.

## Template 3: Home Status

Use a large, readable summary with a small number of room/device groups, at most four primary items initially, and expandable detail.

- Use real HA entity states, friendly names, supported features and availability.
- Separate reported state, requested action, pending acknowledgement and confirmed outcome. A service call being accepted does not prove physical completion.
- Report partial failure explicitly. Do not treat `unknown` or `unavailable` as off/closed/locked.
- Counts must specify their scope, such as “3 configured lights on”; do not claim “Everything is secure” from a subset of sensors.
- Control widgets may be generated only by native code from validated, allowed entity references and current capabilities. No arbitrary service name or action code from display payloads.
- Preserve existing sensitive-action confirmations and Android unlock requirements. Displaying a result cannot execute a device action.
- Keep current HA values updating without rearranging items or stealing the user's reading position.

## Voice-to-screen behavior

Implement visual states honestly, independent of whether the app has microphone support:

1. A small bottom panel acknowledges a request with a truthful label such as “Checking the route…”.
2. The known template frame appears without waiting for the LLM to describe a layout.
3. Useful data fills stable slots as it arrives. Loading placeholders never look like real measurements.
4. A matching short spoken response may be emitted by an existing configured voice backend. If no voice transport exists, return/display text and document that audio is not implemented.
5. The user can expand detail, dismiss the response or return Home. Do not automatically dismiss while they are reading.

Do not display “Listening” unless real microphone capture is active. Wake word, STT and TTS are separate work unless already present. Do not add a fake microphone button. Preserve music playback; audio ducking should only be connected if there is a real audio/voice integration and restoration is tested.

Use small opacity/position transitions, roughly 180–280 ms, and restrained springs for expansion. Do not move the entire layout each time data updates. Disable decorative animation for reduced motion.

## Distance readability and accessibility

Starting targets, to verify on the actual tablet:

- Primary duration/temperature: 64–80 sp.
- Destination or main summary: 28–32 sp.
- Supporting information: 22–24 sp.
- Interactive targets: preferably 56 dp; never below 48 dp.
- Sufficient text contrast in both themes; no status represented by color alone.
- Use adaptive layout constraints and readable backgrounds; avoid translucent text over a busy map.
- Respect font scaling, TalkBack labels and traversal order. At narrow widths or large font scales, stack sections without losing the primary answer or hiding controls.

The above are design targets, not permission to clip content at fixed heights. Test short/long place names and translated text. At normal landscape size, prioritize the main answer without requiring a scroll.

## Extend the HA capability contract safely

Inspect the current protocol before changing it. Add versioned, discoverable template capabilities while preserving existing generic responses or explicitly negotiating compatibility.

Expose:

- Supported template IDs and schema versions.
- Required inputs, optional fields, units and payload limits.
- Runtime availability: online/foreground/locked state and configured providers.
- Whether live routing, geocoding, map imagery, hourly weather and traffic are available independently.
- Source/freshness metadata and explicit missing/unknown fields.
- Supported user interactions and unsupported features.

Distinguish “renderer installed” from “provider configured” and “currently available.” Do not advertise live maps, live traffic or forecasts just because the template exists.

Keep schema validation at both backend and client boundaries. Bound nested data and geometry. Prefer authenticated, scoped result references when map geometry would exceed the existing text payload budget; do not pass unrestricted URLs or credentials through the LLM. Define expiry, byte limits and allowed endpoint origins for any referenced data. No arbitrary HTML, JavaScript, remote layouts or executable instructions.

Retain foreground/lock checks immediately before rendering and opening external links, session-scoped request IDs, idempotency, expiry and explicit accepted/rendered/rejected/dismissed/unknown outcomes. A render acknowledgement is not a read receipt. Never replay an expired or previous-session response after unlocking/reconnecting.

Use provider freshness policies rather than inventing one universal cache TTL. Mark stale values clearly. Cancel work when dismissed or superseded; a late backend result must not reopen a closed template. Coalesce duplicate requests without interrupting an active answer.

The runtime assistant prompt should describe the available templates briefly and instruct the agent to obtain real backend data, use minimal arguments, keep speech short, and fall back to normal conversation when the screen/provider is unavailable. This Codex implementation prompt is separate from that runtime prompt.

## Implementation sequence and validation

1. Inspect the repository and verify the existing architecture, tests, signing and versioning.
2. Define typed template models, schemas, provider interfaces and capability discovery. Include clearly labeled development fixtures.
3. Implement native Journey, Weather and Home Status layouts, including loading, partial, error, stale and unavailable states.
4. Implement the HA/backend adapters and minimal tool calls. Keep provider selection configurable and document required user configuration. Preserve existing device permission boundaries.
5. Extend tests around validation, real-state mapping, no fake traffic, null/unknown values, freshness, cancellation, late responses, idempotency, incompatible versions, lock/background transitions and provider failures. Reuse a shared payload fixture corpus across Android and Python where feasible.
6. Run the existing navigation/access-control regressions, Android assembly/unit tests/lint and backend tests. Inspect rendered screens on an emulator/device when available; include a manual acceptance checklist for anything untested.
7. Measure request-to-template-frame and request-to-useful-data latency separately. Report measured results only, including provider conditions; do not fabricate timing targets as achieved measurements.
8. Package a versioned ZIP with source, built debug APK if compilation succeeds, integration files, schemas, optional runtime assistant prompt, setup instructions, fixtures and validation notes. Do not ship an old APK labeled as the new build. Verify signing and checksum. If the signing key changed, explain the uninstall/update consequence accurately; exclude private keys and secrets from the ZIP.

Do not claim production readiness, live-provider support or successful tablet testing based only on compilation. Finish with a concise account of what works, checks performed, setup required and remaining limitations. If a required external configuration is unavailable, complete the independent implementation and clearly identify the precise remaining blocker.
