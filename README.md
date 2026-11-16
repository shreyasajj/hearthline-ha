# Pixel Home — Android development build, v0.6.1

Native Kotlin + Jetpack Compose tablet app implementing the approved warm Classic dashboard and simplified idle music player. No Google Play Services, Firebase, Google Home SDK, analytics, microphone access, or cloud account dependency.

## New in v0.6.1

Assume docked now opens the main interface with a small badge. The locked dock view uses the main Home layout with read-only controls (private clock remains configurable). Lock entry immediately resets ambient dimming. The bounded local test also supports the explicitly enabled return-on-lock watcher after Android permissions, so it can be tested from the normal launcher. See STARTUP-TEST.md.

## New in v0.6.0

Configurable **ambient clock and wake**: idle to large time on black with dim backlight; first-tap wake; dark-room/black theme; real music wake; optional local face detection, offline “wake up” and a validated HA presence sensor. Camera/microphone wake are opt-in. The English wake model is bundled; HA credentials and dock authentication rules are preserved. See **AMBIENT-SETUP.md** for setup, resource costs and verified limits.

## New in v0.5.0

Built-in native **Sendspin receiver** for Music Assistant, with encrypted pairing, automatic local discovery, optional direct server URL, receiver volume/mute and truthful listener/output status. Runs only while Pixel Home is visible; disabled by default. Settings → Tablet / dock audio receiver. See **SENDSPIN-SETUP.md** for pairing, lifecycle, routing and test limits. HA credentials remain session-only; receiver pairing keys have separate Keystore-encrypted persistence.

## New in v0.4.2

Settings → Startup & lock screen now includes **Assume docked for testing**. This visibly labeled, session-only test lasts up to ten minutes and lets you preview the protected view while Pixel Home is foreground. End it with **End dock test**. It never changes physical dock readings or enables background auto-return.

## New in v0.4.1

Home Assistant long-lived-token QR import using the tablet camera or an image selected in Android Files. QR decoding stays on-device, tokens are masked and session-only, and Connect explicitly verifies against your separately entered HTTPS origin. Manual paste remains available. See **QR-SETUP.md**. This adds optional camera permission; it does not add Google Play Services, ML Kit, microphone access or a cloud scanner.

## Previously in v0.4.0

Full-screen native Journey, Weather and Home Status templates matching the supplied cream/sage references, a backend-only minimal intent tool, strict protocol 1.1 models, cancellable provider fetches and session-scoped result updates. Journey has a configurable Google-free normalization gateway and MapLibre renderer, with no hosted routing/map service enabled by default. Weather uses HA weather.get_forecasts; Home Status is a live read-only summary of configured entities. See **TEMPLATE-SETUP.md** for configuration, schemas and limitations. Fixtures are labeled demo. No voice/audio pipeline was added. The debug APK targets ARM64 devices, including Pixel Tablet and ARM64 emulators.

## Previously in v0.3.0

Native assistant answer sheet, strict semantic response validation, foreground-only HTTPS delivery, and a Home Assistant custom integration with a selectable Pixel Home display LLM API. Includes capability/context lookup, present/status/dismiss tools, delivery acknowledgements, idempotency, session isolation and lock protection. Read **DISPLAY-SETUP.md** for installation and a first response test. Wake word, microphone capture and speech playback are not included.

Optional Android Home role and protected keyguard clock/music from v0.2 remain available. Read STARTUP-TEST.md for their limits. This build uses a new debug signing key; uninstall the previous demo if required, then restore your Home/display preferences.

## Install the included debug APK

The download includes `install/PixelHome-debug.apk`. Extract the ZIP, transfer that APK to the tablet, and open it to install using Android's normal installer. It is a development build signed with a debug key, not a production release. No live device commands are issued until you connect a server in Settings. The demo does not play audio.

## Run from source

Open this directory in Android Studio. Use JDK 17 or newer, Android SDK 35 and the included Gradle wrapper. Run `./gradlew :app:assembleDebug :app:testDebugUnitTest` or select Run in Android Studio. The default installation is a clearly labeled interactive demo: it does not play audio or contact devices.

For the agreed flow, press Play in the Home music card. Music opens, then becomes the simple player after 45 seconds without touch. The idle player has album art at top left, large controls in the middle, volume below, and Home at bottom right. Tap artwork to return to Music. Tap Home to keep the dashboard visible for the current uninterrupted playback session. Track updates never steal navigation. Pausing/stopping ends the session; a later playback start can open Music again. Settings is never interrupted.

Hub/music automation remains manually enabled. Dock-aware protected presentation now has automatic public Android signals, a physical dock gate in normal mode, an explicit temporary foreground test, manual suppression and persisted configuration; see STARTUP-TEST.md. Optional return after locking from another app requires a separately enabled foreground monitor, notifications and Android overlay permission. It never interrupts unlocked use. Optional default Home selection and a protected show-over-lock-screen view are available in Settings. See STARTUP-TEST.md for setup, startup limits and how to restore your normal launcher. Home Assistant credentials remain session-only, so reconnect after a reboot. Polling stops while the activity is backgrounded.

## Connect to real Home Assistant and Music Assistant

1. Set up Music Assistant and its Home Assistant integration on your own server. Identify its real `media_player` entity. The app controls that entity. The optional built-in Sendspin receiver can independently make the tablet an MA audio output after enabling and pairing; see SENDSPIN-SETUP.md.
2. Merge `home-assistant/pixel_home.yaml` into your Home Assistant template configuration, replacing the example device, scene and player IDs and playlist URI. Reload templates using your normal Home Assistant workflow. The sensor's expected entity ID is `sensor.pixel_home_dashboard`; verify it in HA because existing names may cause a different entity ID.
3. In Pixel Home Settings, enter your HTTPS Home Assistant origin, a long-lived access token and the dashboard sensor ID. Credentials remain in process memory; they are deliberately not stored in preferences, backups, logs or source. Re-enter them after process restart. Use a trusted HTTPS certificate; HTTP and insecure certificate bypass are not supported.
4. Press Connect. Real device controls stay disabled until a successful state/config refresh. Edit the sensor attributes in Home Assistant to change dashboard contents; the app reloads them while visible.

The configuration sensor is a custom contract introduced by this project, **not an existing Home Assistant dashboard feature**. It is read-only from the tablet. A visual HA configuration editor has not been implemented. It supports a welcome title, up to eight favorites, six scenes, ordered player choices, music shortcuts and an idle timeout (15–600 seconds). All settings apply on subsequent successful polling cycles.

Real mode polls `/api/states` every two seconds after the preceding request finishes. It issues `/api/services/<domain>/<service>` commands and waits for subsequent state reads before showing the result. No optimistic fake device state. Service acceptance is not proof a device completed the request. A failed connection retains last-known data with an explicit stale-state banner and disables commands. Music polling follows only the selected player. Changing the selected speaker changes the player being controlled; it does not transfer or group audio.

## Implemented

- Adaptive landscape dashboard with device favorites, scenes and now-playing card; stacked layout on narrower windows.
- Dedicated Music screen with server-configured album/playlist/track shortcuts.
- Simple idle music screen; pause/play, previous/next and volume; playback start detection.
- Capability-gated music controls using HA `supported_features`.
- Light power and brightness; switch/fan power; climate single target temperature; lock/unlock confirmation; scene activation. Other entity domains display read-only state.
- Real HA music title, artist, playback state, volume and safe same-origin artwork. Missing art uses an original code-drawn landscape. External artwork URLs are not authenticated or downloaded.
- Light/dark palettes, Material components and scrollable large-font layouts.
- Labeled demo mode, connection errors, pending command indicator, no token persistence.
- Pure JVM playback-navigation tests and Android Gradle unit tests.

## Deliberate limits / next milestone

This is a working starter implementation, not the complete initial product brief. Full Music Assistant library search, recommendation/history feeds, queue editing, synchronized speaker grouping, camera screens, detailed room pages, weather/calendar providers, photo ambient mode and a general voice assistant are not implemented. The optional offline wake phrase only wakes the display. No fake search/queue controls are presented. Music shortcuts use MA's documented HA `media_player.play_media` route; supported provider content and capabilities depend on your server and player.

Public dock/audio event detection is implemented, but physical attach/detach and GrapheneOS behavior still need device validation; dock audio output routing remains a separate investigation. Do not infer dock support from the design mockups. Playback on a selected external MA speaker can drive this interface, but playback through the tablet/dock itself uses the separately enabled Sendspin receiver; physical routing still needs hardware validation. UI/network integration still requires tablet and live-server testing.

Further production work should add secure credential persistence/OAuth, WebSocket device-state events with reconnect, a HA configuration editor, per-device permission policy, localization, screen-reader/device QA, screenshot tests and release signing. No release signing key is included.

## Source map

- `MainActivity.kt`: Compose theme, responsive screens, accessible Material controls and lifecycle hooks.
- `TemplateComponents.kt` / `TemplateSheet.kt`: reusable cream/sage cards, hero measurements, weather icons, fixed native template screens and configurable MapLibre imagery.
- `NativeTemplate.kt`: strictly validated typed template results, units and source freshness.
- `DockMonitor.kt` / `DockPolicy.kt`: actual dock evidence and configurable protected display lifecycle.
- `DockReturnService.kt` / `DockReturnGate.kt`: optional next-lock return with a visible foreground monitor; never opens while unlocked.
- `HA-INTEGRATION-PROMPT.md`: implementation brief for a separate, future HA telemetry integration.
- `HubViewModel.kt`: server/demo separation, state polling, command serialization and selection.
- `HomeAssistant.kt`: authenticated HTTPS transport, config parsing, bounded artwork loading.
- `PlaybackNavigation.java`: small Android-independent navigation policy, called directly from Kotlin.
- `home-assistant/pixel_home.yaml`: server-owned dashboard example.
- `tools/NavigationChecks.java`: standalone navigation regression harness.

Dependency versions are deliberately pinned (AGP 8.7.3, Gradle 8.9, Kotlin/Compose compiler 2.1.20, SDK 35, Compose BOM 2024.12.01), not floating “latest”.

## Reference contracts

- https://developers.home-assistant.io/docs/api/rest/
- https://www.home-assistant.io/integrations/music_assistant/
- https://www.home-assistant.io/integrations/media_player/
- https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler

See `VALIDATION.md` for the actual checks performed in this environment.
