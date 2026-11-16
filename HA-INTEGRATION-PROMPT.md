# Pixel Home — Home Assistant setup, dashboard and telemetry integration prompt

Copy this prompt into a separate coding conversation with the current Pixel Home project attached. This specifies the next integration milestone. Integration-managed dashboard setup, remembered tablet pairing and telemetry push are not implemented yet.

---

Act as a senior Home Assistant integration and Android engineer. Inspect the supplied repository, AGENTS.md, build configuration, tests and current contracts, then implement a reviewable local-first Home Assistant integration for Pixel Home, covering tablet pairing, dashboard configuration/delivery, automatic reconnect and truthful telemetry. Preserve the existing native Journey / Weather / Home Status templates, generic assistant answers, music/dashboard behavior, Home role and Android authentication boundaries. Do the implementation and tests, not only a plan. Do not deploy to my HA instance or configure paid/cloud providers without my authorization.

## Existing architecture to inspect

- Native Kotlin / Jetpack Compose Android tablet app, package dev.pixelhome; separate testing package dev.pixelhome.templates.
- `HubHost.kt`: Home role, protected clock/music above keyguard, dock-aware presentation preferences and dismissal/rearm behavior. Inspect its actual implementation; do not infer automatic activity launch over other apps.
- `DockMonitor.kt` / `DockPolicy.kt`: public Android dock/audio/charging signals and pure decision policy. Inspect before adding duplicate detection.
- `DockReturnService.kt` / `DockReturnGate.kt`: optional, user-enabled foreground lock watcher, with notification and Android overlay permission. It only attempts a protected activity return while locked and dock-eligible. It is not a general background telemetry authorization or an unlock bypass. Inspect before adding another service.
- `HomeAssistantConnection.kt`, `HaTokenQr.kt`, `HaConnectionDraft.kt`: on-device import of the official raw long-lived-token QR. Keep credentials masked, process-only and absent from telemetry/diagnostics.
- `HomeAssistant.kt`, `HubViewModel.kt`, `AssistantDisplay.kt`: authenticated HTTPS, session-only HA credentials, foreground polling and display delivery.
- Existing HA custom integration: `home-assistant/custom_components/pixel_home/` with bridge.py, protocol.py, providers.py, templates.py, llm_api.py. Extend this integration rather than shipping a competing domain.
- `display-contract/`, `TEMPLATE-SETUP.md`, `DISPLAY-SETUP.md`, `STARTUP-TEST.md`, `VALIDATION.md`: native template schema, current provider setup and tested limits.
- Capability revision currently pixel-home-0.4; generic protocol 1.0 and native template protocol 1.1. Verify before changing.

The tablet queried during development exposed an Android digital dock audio device named `USB-Audio - charging speaker dock`, while battery telemetry reported AC=true, USB=false, wireless=false and dock-powered=false. Thus AC/charging alone is not evidence of the official dock. Do not assume the same signals on GrapheneOS or other Android versions. Public AudioManager/AudioDeviceInfo, ACTION_DOCK_EVENT and battery signals are acceptable; Google Play Services, vendor cloud APIs, Firebase and Google accounts are not dependencies.

## Goal

Remove the need for users to manually create `sensor.pixel_home_dashboard` or paste a dashboard sensor ID into the Android application. After installing the integration, users should add Pixel Home in HA, choose actual dashboard entities/players, and pair a tablet once. The paired tablet should fetch its native dashboard configuration and reconnect automatically on later app launches. Implement the Android side too; installing only a Python integration cannot change the existing APK's sensor-only connection code.

Also push bounded, truthful tablet telemetry to HA and make it easy to consume through ordinary HA entities, one registered device per configured tablet, a config flow, clear availability and examples. Keep tablet telemetry separate from HA-owned smart-home state, dashboard configuration and semantic answer results.

Inspect current Android code for an actual sender. If missing, implement both sender and receiver. Do not create HA entities that can never receive updates. A prompt, schema or a successful HTTP call does not establish live integration.

## Integration-managed dashboard and automatic connection

The current APK requires an HTTPS HA origin, a process-only HA long-lived token and a `sensor.*` entity ID. HubViewModel.refresh reads `/api/states` and parses configuration from that sensor's attributes. The existing YAML-only display bridge has no config flow, tablet registration or dashboard provisioning. The current telemetry prompt alone will not solve dashboard onboarding; implement all of the following.

### Home Assistant setup

- Add a real config flow and options flow to the existing `pixel_home` domain. Users should choose a tablet/display name, area, ordered favorites, scenes, Music Assistant/HA player entities, optional music shortcuts and supported template data sources through appropriate HA selectors. Only offer or accept actual existing entities with supported domains/capabilities; preserve unavailable/unknown states rather than inventing devices or claiming pairing made a player.
- Persist a versioned, bounded per-tablet dashboard definition in integration/config-entry storage. HA remains the source of truth. Options edits must be delivered to the correct paired tablet without editing YAML or an artificial sensor.
- Provide an authenticated, versioned setup/config endpoint or WebSocket command that returns the paired tablet's dashboard definition and validated capabilities. Reuse the existing dashboard attribute contract where appropriate, but remove the sensor dependency in the new path. Document exact response schemas, revision handling, ownership and validation.
- Do not create a meaningless template/config sensor merely to satisfy the old APK. Retain the old sensor contract as an explicit legacy fallback with migration instructions, so existing users are not broken.
- Preserve the display bridge and native template APIs, their authenticated session ownership and expiry. Migrate YAML-only singleton users deliberately to config entries; prevent duplicate service/API registration and cross-tablet data leakage. Do not let telemetry entity creation stand in for a working dashboard config channel.

### Pair once and reconnect

- Implement tablet discovery or a QR/manual-server setup flow using actual supported HA discovery/auth APIs. Discovery identifies a candidate server; it must never silently trust it, install a certificate, or authorize tablet access. Always support an explicit HTTPS origin when multicast discovery is unavailable.
- A setup QR may carry a versioned HTTPS origin plus a short-lived, single-use pairing challenge; never embed an enduring HA token, receiver PSK, arbitrary service command or arbitrary credential-forwarding URL. Validate origin/TLS, expiry, replay protection and account/tablet ownership before consuming it. Do not fetch attacker-selected QR URLs with existing credentials.
- Keep this new pairing QR format distinct from the existing official HA raw long-lived-token QR and the Sendspin `SP:0` receiver token. Those existing flows must still work and must not be reinterpreted as server-provisioning envelopes.
- A user authorizes pairing once. Choose supported HA OAuth/refresh-token handling or a narrowly scoped revocable integration pairing credential, and document exactly which operations it permits. A device-scoped configuration/telemetry token does not automatically authorize HA entity reads or service calls; implement the authenticated server-side authorization path needed for the existing home controls, or require the appropriate HA account authorization. Do not expose an unrestricted service-call proxy, anonymous webhook or automatic admin token generation.
- The user explicitly wants automatic reconnection after pairing. Persist only the selected reconnect credentials with Android Keystore encryption, private storage, backup exclusions, revocation/unpair support, origin binding and redacted diagnostics. Show that pairing will be remembered. The existing pasted/scanned HA long-lived-token flow stays process-only unless the user explicitly opts into a documented migration; reuse the encrypted Sendspin storage pattern where suitable, but keep keys, token types and trust stores separate.
- On later launches, restore the authorized pairing, fetch current validated dashboard configuration, load real device state and reconnect with bounded backoff. On expired/revoked authorization, show a re-pair/reauthenticate state; never fall back to demo while presenting it as live data. Reconnect follows the existing foreground/lifecycle policy and must not itself launch Pixel Home, wake the screen, bypass keyguard, grant Android permissions or take over the normal Home app.
- Replace the mandatory dashboard sensor field in the new Android setup with the paired integration/configuration path. Keep sensor-based connection under a clearly identified legacy/manual option. A successful connection should lead to real dashboard content without the user knowing an internal sensor ID.
- Include a clear Unpair/Forget server action, credential invalidation on both sides, an offline/revoked state, config revision display and a manual reconnect/re-authenticate path. Preserve independent Sendspin pairing and dock/ambient settings.

### Required onboarding acceptance

On a clean test HA installation with **no manually created dashboard sensor**: add the integration; choose real test entities; pair a clean preview APK; show the configured native dashboard; edit favorites/player order in HA and observe the correct tablet update; restart the app and demonstrate authenticated reconnect; revoke/unpair and prove access stops. Also verify multi-tablet isolation, TLS/origin rejection, malicious/expired/replayed setup QR rejection, invalid config schemas, empty/unavailable entity selections and migration of an existing legacy-sensor user. Run real HA config-entry/flow tests in addition to dependency-free mocks. Report precisely which live steps were not possible; compilation and telemetry-only tests are not onboarding acceptance.

## Proposed telemetry contract — version and validate it

Use a dedicated versioned contract, not unstructured display context. Start with a small payload (maximum 8 KiB) containing:

- schema_version; installation-scoped tablet_id generated locally without serial/MAC/Android hardware identifiers; session_id; monotonic sequence; observed_at timestamp with offset; app_version.
- battery_percent nullable 0..100; battery_status enum; power_source enum (none/ac/usb/wireless/dock/multiple/unknown). `charging` must reflect Android charging status, not merely external power or battery percentage.
- dock_state enum docked/undocked/unknown; dock_evidence enum system_dock_event/audio_dock/dock_power/manual_override/unconfirmed; detection_mode auto/force_docked/force_undocked. Manual override must be explicit and never published as verified physical docking. Normal protected presentation and automatic background return require physical dock confirmation. An explicitly activated local ten-minute test lease can temporarily substitute in both the foreground and the user-enabled lock watcher; it never changes physical observations. Expose that test separately, never as verified docking, and never add remote activation. The protected dock can show the main dashboard read-only when the user enables that layout; actual device commands, settings and assistant answers still require Android unlock.
- foreground boolean; interactive boolean; locked boolean only when actually observable; current_screen from a strict allowlist; protected_dock_view_visible boolean; dock display enabled/configuration state as explicit user preferences, never proof that Android allowed it to appear.
- Optional capability flags and diagnostics under a strict bounded schema. Raw installed-app lists, notifications, microphone/audio samples, user queries, tokens, location, URLs, hardware serial numbers and arbitrary device names are excluded by default.

Unsupported/unobserved values are null or unknown. Do not infer speaker playback from audio-device connection. The app now has an opt-in native Sendspin receiver (see SENDSPIN-SETUP.md), active only while its activity is visible. Its identity/PSKs have separate Keystore-encrypted persistence; HA tokens remain session-only. Telemetry may expose real receiver/listener/connection/output observations under a bounded schema, but must distinguish listener readiness, paired/connected state, AudioTrack output and actual audio route. Never infer audible dock playback or synchronized grouping. Do not turn the tablet/dock into a Music Assistant audio output merely by registering entities, and do not enable or re-pair the receiver remotely.

Use server receipt time for availability; client observed_at is provenance, not an authentication/freshness authority. Bound future/old timestamps, strings, enums, nesting and integer ranges. Reject unknown fields and invalid booleans/numbers. Do not log tokens or payloads containing sensitive content.

## Home Assistant design

Inspect current HA developer APIs and pin a tested compatibility range. Prefer standard supported integration patterns, async I/O, config entries, options flow, entity/device registry and typed state models.

- Reuse the onboarding config/options flows above for adding a tablet and securely pairing/authorizing an installation. Use an authenticated, scoped local endpoint or existing HA service/websocket mechanism; document the decision. Avoid public unauthenticated webhooks, secrets in URL queries and arbitrary endpoint origins supplied by an LLM.
- Support multiple tablets without changing the old singleton display bridge into a data-leaking global registry. Define ownership, pairing, stable unique IDs, reauthentication, revocation, deletion and duplicate pairing handling. Existing display users must have an explicit migration path.
- Create a device with battery sensor, charging binary sensor, docked binary sensor with unknown/unavailable semantics, dock-detection source diagnostic sensor, power-source sensor, foreground/interactive/locked/available states as justified by the actual sender, last-seen timestamp and app-version diagnostics.
- Use appropriate HA device classes, state classes and units. Use normal entity unique IDs; maintain them across process restart and HA restart. Do not use friendly names as identity.
- A configuration switch/select/number must represent a genuinely implemented authenticated Android command with request ID, acknowledgement, failure and expiry. Otherwise expose read-only state and defer remote configuration. Never present accepted as physically applied.
- Device unavailable after a documented, configurable heartbeat timeout. Lost telemetry is unavailable/unknown, not undocked/unlocked/0% battery. Reset connection/session state safely on HA restart and pairing revocation.
- Provide diagnostics with strict redaction and translations. Do not create periodic recorder churn from a timestamp or sequence update on every heartbeat; coalesce unchanged sensor states and allow noisy diagnostics to be disabled.

## Ambient display context (v0.6.0)

Pixel Home now has a visible-app-only ambient clock, window brightness settings, measured light-sensor dark appearance, real music wake and optional local offline wake-phrase/front-camera face wake. See AMBIENT-SETUP.md. These do not change Android authentication or authorize any home actions. Camera/microphone options are off by default. A configured HA presence binary_sensor is consumed only from fresh real backend states; it is not fabricated locally. If adding telemetry, expose only bounded, explicitly justified presentation/configuration/actual sensor availability observations. Distinguish configured brightness from measured physical luminance, and an awake interface from an unlocked device. Never transmit audio, transcripts, face images, face identities, pairing tokens or biometric-like history. Do not remotely enable microphone/camera permission, wake listening or keyguard bypass.

## Android sender

- Consume the real DockMonitor / HubHost data. Handle dock/undock callbacks, battery changes, screen on/off, lock/unlock, foreground transitions, activity recreation and process restart.
- Decide and document whether delivery is foreground-only, WorkManager-delayed or a user-opted-in foreground service with a visible notification. Do not promise instant/background telemetry if Android can suspend the sender. Respect GrapheneOS restrictions. Add no fake always-online status.
- Coalesce event bursts and use a documented bounded heartbeat, exponential backoff with jitter, finite request timeouts, cancellation, bounded latest-state buffering and no unbounded offline history. Preserve sequence ordering, session isolation and idempotency; stale/replayed/out-of-order updates must not overwrite newer state.
- Use HTTPS with normal certificate validation and explicit origin checks. No insecure TLS switch or redirects forwarding credentials. Reuse current transport where appropriate. Add secure credential persistence only after inspecting existing session-only behavior, migration, Android Keystore and backup exclusions; do not silently change credential policy.
- Include an opt-in telemetry toggle, paired device identity, connection status, last successful push and disable/unpair controls. Keep device/dock presentation preferences independently configurable. A remote configuration request must never unlock Android, bypass keyguard, enable arbitrary background launches or install an accessibility/device-admin service.

## Make it easy to consume

Ship a short setup guide and actual tested YAML examples for:

1. A tablet status dashboard with battery, truthful dock state and last seen.
2. A dock/undock automation with availability/unknown guards and debounce.
3. Battery threshold notifications using the user's chosen notification target.
4. Detecting stale/offline telemetry without treating it as an undock event.

Use real HA entity IDs generated during a test or clearly labeled placeholders. Provide a simple first-push diagnostic procedure and explain which data is unavailable when the app is backgrounded. Avoid template sensor workarounds when native integration entities can expose the data directly.

## Validation and delivery

- Share valid/invalid telemetry fixtures between Kotlin and Python where feasible. Test pairing/authentication, revocation, cross-tablet isolation, replay/order/session handling, payload limits, null/unknown state, manual overrides, retry/backoff, time skew, device availability, recorder coalescing, HA restart and Android lifecycle transitions.
- Add config-flow/entity tests using HA's current recommended test fixtures. Run the existing 1.0/1.1 display, provider, access-control and navigation regressions; Android assembly/unit tests/lint; Python integration tests. Mock tests are not a live HA test.
- Verify dock attach/detach on the actual tablet where available. Record exact Android/GrapheneOS version and public signals. Require user participation for secure unlock; never request or record their PIN.
- Package source, schemas, examples, current APK and HA integration files. Verify checksums/signing; exclude secrets and private keys. Report precisely what was tested and what configuration is still required. No production-readiness or live-provider claims based only on compilation.

Primary references to verify in that implementation session:
https://developers.home-assistant.io/docs/creating_integration_manifest/
https://developers.home-assistant.io/docs/config_entries_config_flow_handler/
https://developers.home-assistant.io/docs/core/entity/
https://developers.home-assistant.io/docs/device_registry_index/
https://developer.android.com/reference/android/media/AudioDeviceInfo
https://developer.android.com/reference/android/content/Intent#ACTION_DOCK_EVENT
https://developer.android.com/reference/android/os/BatteryManager
