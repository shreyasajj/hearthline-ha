# Validation — Pixel Home v0.6.1

Work performed 15–16 November 2026, America/Chicago. Development build; production readiness is not established.

## Local checks

| Check | Result |
| --- | --- |
| Android assembleDebug / testDebugUnitTest / lintDebug | Pass, main and separate preview package |
| Pixel Home JUnit | 77 tests, no failures/errors |
| Vendored shared/player library tests | 210 shared JVM + 56 player, no failures/errors |
| Python | 45 tests, pass |
| Shared payload corpus | 29 generic + 30 native cases validated on Kotlin and Python |
| Existing standalone Java navigation harness | 15 assertions, pass |
| Integration and tool Python syntax | Pass |
| APK signatures / checksums | Verified; see APK_SHA256.txt |
| Version / architecture | 0.6.1, versionCode 9, ARM64, minimum API 31 |

JDK 17, SDK 35, AGP 8.7.3, Kotlin 2.1.20 and Gradle 8.9 were used. MapLibre Native 11.8.0 compiles; its legacy route/marker annotation calls emit deprecation warnings. Lint has **0 errors, 11 warnings**: four pinned-dependency updates, a Compose modifier convention, Android Log instead of Timber, ARM64-only ChromeOS support, an existing obsolete resource qualifier and an original launcher icon warning.

Tests cover original navigation/access/HA transport, strict generic/native schemas, explicit nulls and unknowns, units, geometry/byte bounds, unsupported versions, session isolation, idempotency, cancellation, expiry, render/update sequences, late/dismissed results, provider failures, forecast support and traffic gating. New dock tests cover evidence versus ordinary charging, manual overrides, mandatory physical docking, suppression/rearm, persisted dismissal and the next-lock return gate. They assert that automatic return is disallowed while unlocked, without permission, while undocked/unconfirmed, or during a dismissed lock cycle.

## Actual tablet observations and captures

ADB connected to a physical Pixel Tablet in landscape 2560×1600, density 320. The device reported a standard Pixel Android environment; no GrapheneOS validation is claimed. The separate `dev.pixelhome.templates` preview was installed. The original `dev.pixelhome` app, its data and default Home selection were not replaced.

The native reference-based template pass completed these checks:

- Journey / Weather / Home Status landscape rendering with clearly labeled demo data.
- Journey directions expansion; Home Status expanded detail, including unknown state.
- Dismissal and Home/Dismiss target bounds of at least 112 physical pixels (56 dp at density 320).
- Journey at 200% font scale with the primary duration present and footer controls accessible.
- Weather dark appearance.
- Background transition cleared the answer; reopening did not replay it.

Unedited screenshots and extracted text are in `validation/reference-match/`. `validation/REFERENCE-COMPARISON.md` links the supplied reference sheets and the rendered app. Earlier `validation/screenshots/` captures document the superseded bottom-sheet iteration. No reference bitmap is embedded as an app background.

Tablet testing found and corrected a fullscreen dialog inset problem; templates now render in the native activity with fixed controls and a separately scrolling body. Spacing was tightened to keep the primary answer visible. The harness needed corrections to scroll within the body rather than starting in the pinned footer, and to reveal below-fold expanded items; the later expansion checks passed.

A portrait test triggered activity recreation and cleared the delivery session before capture. Explicit orientation/screen-size configuration handling has since been added, but portrait was not retested. Font scale and rotation were restored by the harness. The screenshots predate the final dock monitor/optional return-service addition; the template visuals are unchanged apart from a Kelvin-unit formatting correction.

Android audio diagnostics identified an active digital dock audio device named `USB-Audio - charging speaker dock`. Battery diagnostics simultaneously reported AC=true and Dock powered=false. This establishes why AC charging is not used as dock confirmation. Physical attach/detach and the new app detector's transitions have not been observed end to end.

The interactive dismiss/authenticate/relock script did not complete. The user requested normal tablet use, so no further tablet commands, installs or automatic test activity launches were performed. A local process check found no running tablet test scripts. Do not claim successful secure unlock/relock validation or background auto-return.

## Optional return-on-lock behavior

Implemented after the tablet handoff and validated **locally only**:

- A configurable foreground dock/lock watcher, disabled by default.
- Explicit Android Display over other apps permission and notification consent before enabling.
- One activity-return attempt per eligible lock cycle, no attempt while unlocked, dismissal suppression, and a late-unlock activity guard.
- A notification pause action and independent protected-view/rearm/music/keep-awake/dock-mode preferences.

This updated build was **not installed or enabled on the tablet** during the handoff. Normal protected presentation and all automatic return reject undocked/unconfirmed/manual-assumption states, regardless of legacy opt-out preferences. The later explicit foreground-only test exception is documented below. The permission flow, foreground service lifetime, OEM background activity policy, actual relock presentation and permission revocation remain device acceptance checks. Force-stop/process death stops the watcher; reopening the app is required to resume it. This is not a secure-keyguard replacement or boot auto-start. See STARTUP-TEST.md for setup and limits.

## Real data and remaining checks

No real HA endpoint, weather integration, routing/geocoding gateway, licensed tile style or traffic provider was configured for these runs. Python adapter tests use HA-shaped fakes. Actual HA API registration and delivery remain unverified. Journey requires the documented trusted normalization gateway; this repo implements the bounded adapter, not a hosted gateway service. No default public/demo tile endpoint is used.

Home Status is read-only and scoped to configured entities; it does not claim completion of actions. Live updates, actual map rendering/failure behavior, genuine forecast/traffic values and provider terms/rate limits still need configured-provider acceptance. TalkBack, long translations, portrait/multi-window, GrapheneOS keyguard races, reboot behavior and real music/dock audio playback remain untested.

There is no microphone/STT/TTS or audio ducking implementation, no HA telemetry push sender. The optional native Sendspin receiving player was added later in v0.5.0; its validation is recorded below. HA-INTEGRATION-PROMPT.md specifies that separate future integration. Credentials remain session-only.

## Timing

Backend status exposes request_to_frame_ack_ms and request_to_data_ack_ms separately. They measure backend acceptance through render acknowledgement and include polling/ACK transit; they do not measure reading or speech/model latency. No live provider request-to-frame/data measurement was possible without configuration. Earlier synthetic sheet-preview timings are not reused as measurements of the final native screens or background-return feature.

## Signing and packaging

Signer SHA-256: `c92c0189665984a0ab562b9f79435c15df8b4f1ccc0d3a105c822efbdaaf85f7`.

It differs from the supplied v0.3 APK (`5378014a…`) and the tablet's original installed app (`61a39312…`). Same-package updates across keys are rejected by Android. Uninstalling erases that package's preferences; no uninstall was performed. Use `install/PixelHome-preview.apk` for the separate package or make an explicit migration choice for the main APK. Private signing keys, local SDK/cache/config files and secrets are excluded from the ZIP. `tools/package_release.py` verifies archive CRC and embedded APK checksums.

Reproduce:

```sh
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :sendspin-shared:jvmTest :sendspin-player:testDebugUnitTest
python3 -m unittest discover -s tools -p 'test_*.py'
python3 -m py_compile home-assistant/custom_components/pixel_home/*.py tools/*.py
javac -d /tmp/pixel-home-navigation app/src/main/java/dev/pixelhome/PlaybackNavigation.java tools/NavigationChecks.java
java -cp /tmp/pixel-home-navigation NavigationChecks
```

`tools/device_acceptance.py` is opt-in and temporarily changes font/rotation with restoration in finally. `tools/device_dock_checks.py` is interactive and requires the user to authenticate only on Android. Neither should run while the user wants normal tablet use.


## Installation follow-up — 16 November 2026

At the user's request, the final strict-dock preview APK was installed successfully with `adb install -r`. No uninstall was performed. The tablet was unlocked. Pixel Home Preview was the current default Home role holder, explaining why Home returned to it after unlocking. The normal installed Pixel Launcher (`com.google.android.apps.nexuslauncher/.NexusLauncherActivity`) was restored as Home, and the role holder was verified. The tablet was left on its normal launcher.

The preview's display preferences showed Automatic dock detection, dock-only=true and show-when-locked=true. `dock_auto_return` was unset (off); overlay app-ops showed no granted operation. Automatic background return is therefore not enabled yet and still needs the user's explicit Android overlay/notification permission flow. No device lock, authentication or dock attach/detach cycle was performed in this follow-up.

## QR authentication update — v0.4.1, 16 November 2026

Implemented the exact raw-token QR payload emitted by Home Assistant's long-lived-token dialog, verified against the upstream frontend source. The QR does not set a server URL. Added a permission-gated embedded camera scanner with camera switching, Android Files image import, masked token staging, explicit Connect and Clear token controls. The client display capability revision and backend response contracts remain unchanged.

The new 13 JUnit tests bring the total to 49. They cover the official payload shape, redacted secret values, URL/OTP/invented-envelope rejection, unsigned/malformed/oversized/deeply nested payloads, single-use/expiry, lock/background/cancellation gates, request lifecycle and credential clearing. The production decoder successfully reads a synthetic token QR with a Home Assistant-style center-logo space using Q error correction. All 45 backend tests still pass. Assembly and lint pass for main and preview APKs; lint retains 0 errors / 9 warnings. The embedded scanner also emits a legacy Android Camera API deprecation warning during compilation.

Installed `dev.pixelhome.templates` version 0.4.1 / versionCode 5 successfully. The default Home role remains `com.google.android.apps.nexuslauncher`. Camera permission was not granted or exercised; app-ops reported CAMERA ignored. No camera frames were captured and no real token or server login was requested.

An initial synthetic-image UI check routed through Android's photo picker, which was unsuitable for selecting the named downloaded fixture in this harness. Image import now uses Android's file picker via OpenDocument, without retaining URI permission or requesting broad storage/photo access. The subsequent device attempt found the tablet locked/asleep before reaching the preview. A request to unlock for the optional final check was left with the user. Full camera capture, camera permission UI, final file-picker import and real HA authentication remain device acceptance checks; no success is claimed for those paths. Test cleanup returned Home and removed the synthetic image. Tokens remain session-only, connection/scanner windows use FLAG_SECURE, and no raw token is logged, copied to clipboard or persisted.

See QR-SETUP.md for usage. `tools/GenerateTokenQrFixture.java` creates only a non-authenticating synthetic QR; `tools/device_qr_checks.py` never presses Connect or opens the camera.

## Temporary dock test — v0.4.2

Added Settings → Startup & lock screen → Assume docked for testing, a visible TEST MODE label and End dock test controls in Settings and the protected clock. The session uses monotonic elapsed time, expires after ten minutes and is never persisted. Only an actual unlocked user can start it. It temporarily enables protected foreground presentation, still respects dismissal, and leaves physical observations and the background-return service unchanged. Automatic-return intents also independently require physical dock evidence.

Six new tests cover explicit activation, expiry, cancellation, clock regression, isolated foreground eligibility and dismissal, bringing JUnit to 55 passing tests. Secure unlock/relock and physical attach/detach remain unverified on-device.

### QR device follow-up — v0.4.2

With the tablet unlocked by the user, imported the synthetic, non-authenticating QR through Android Files into the masked token field and verified explicit Clear token. No Connect action or camera capture was performed. The harness was corrected to index its ADB-created fixture and use Files list view: grid thumbnails omit filenames from UIAutomator. Cleanup removed the synthetic fixture and returned to the normal launcher. Live HA authentication and camera scanning remain untested.

Installed preview 0.4.2 / versionCode 6 with an in-place update. On-device UI checks passed: Assume docked for testing activates the TEST MODE label; End dock test removes it and restores the activation button. The test was ended and the tablet returned to its normal launcher. No lock/unlock cycle was triggered. Default Home remained Pixel Launcher.

## Native Sendspin receiver — v0.5.0

Vendored the upstream SendspinDroid shared/player libraries at the revision in third-party/sendspin/UPSTREAM.md (MIT notice shipped in source and APK). Adapted Gradle to API 35 / Java 17 and Kotlin 2.1.20; no upstream app services, UI or account settings were imported. A small transport adapter preserves explicit TLS URLs. Pixel Home owns the visible-activity lifecycle, independent configuration, Keystore AES-GCM identity/record storage, secure pairing QR, bounded operator endpoint validation, audio focus, software gain and reset flow. HA session-only credential policy and existing music/player navigation are preserved.

Local validation: 60 app tests; 210 shared JVM tests, including Noise/CPACE vectors, handshake/fragmentation/pairing, protocol and clock behavior; 56 player tests including decoder/stream-change behavior. App assembly, unit tests and lint pass for both main and preview packages. App lint has 0 errors / 9 warnings: four pinned-dependency updates and existing layout/logging/resource/icon/ARM64-only warnings. Sendspin's ws transport carries mandatory Noise encryption; HA and map transport validators still require HTTPS.

The physical tablet successfully started the listener at port 8928 and Android NSD reported registration on the local network. No live user Music Assistant/HA server has been configured. Audible dock routing, multidevice synchronization, TLS reverse proxy, codec variants and network-switch recovery require further device/provider checks.

Device reference-server validation used `aiosendspin[server]` 10.3.0 on the development Mac, connected only through an ADB localhost forward. Browser-Origin requests were rejected (HTTP 403). Pairing completed with Noise encryption, PSK finalization/re-handshake and clock convergence. A 48 kHz, stereo, 16-bit PCM digital-silence stream reached the tablet's AudioTrack; Android reported PLAYING and delivered frames. This verifies actual protocol/audio execution, not audible dock sound or multiroom accuracy. A local discovery connection from another LAN server was observed but no user-server authentication or playback was requested.

The first UI assertion missed the output status outside the scroll viewport; native logs confirmed playback. A further reconnect check found a real host integration bug: the previous stream format had to be cleared when the connection changed, or the next stream of the same format could be ignored. The host now resets that state before admitting a new source and ignores callbacks from superseded connections. The updated preview subsequently passed the rendered-output check.

The pairing test reads only this newly created receiver's temporary test pairing token via a transient tablet UIAutomator XML file, removes that file immediately, and never prints, exports or forwards the token outside localhost. It does not touch HA access tokens. Production pairing storage is encrypted; this test-only capture is not part of app behavior.

Final preview 0.5.0 / versionCode 7 was installed in place. Two consecutive encrypted-reference PCM playback checks passed after the reconnect correction. Device lifecycle checks passed: Home closes the listener; returning to the activity restarts it with the same identity; force-stop/process restart preserves that identity; private preferences contain only the encrypted receiver envelope; confirmed Reset disables listening and removes the test identity/records. The temporary reference-server pairings were removed. Receiver is left disabled, tablet is on Pixel Launcher, and the default Home role is unchanged. No PIN entry, device lock cycle or HA-token use was involved. The 45 Python backend tests also pass.

## Ambient display — v0.6.0

Implemented a native time-only black clock after inactivity, a window-only dimming override, first-touch gesture consumption, measured-light dark/black appearance, actual Sendspin/fresh HA music wake and optional configured presence. Camera/microphone settings are off by default. Offline English Vosk wake inference is bundled and does not use Google services. Front-camera face detection uses Android's local public FaceDetector; frames stay in memory. These features do not start the app, unlock keyguard or weaken the physical dock gate. The active app restores window brightness and stops sensor/capture work on pause. No battery, luminance or multi-hour thermal measurements are claimed.

Local validation: 12 new policy/configuration/signal tests bring the app total to 72, with no failures. They cover idle/re-wake timing, actual activity holds, disabled/ineligible lock states, preview/edit/answer protections, stale/demo/unknown presence, light hysteresis/dwell, exact wake phrase and bounded settings. Assembly/lint pass. Two Android inference tests passed on the physical tablet (2.8 seconds): the bundled model decoded a synthetic “wake up” PCM fixture without AudioRecord, and the system face detector returned no faces for a blank bitmap without opening a camera. This proves model/native availability, not live microphone or person detection performance.

On-device UI checks passed: manual preview rendered exactly one time label on black; the actual window attributes reported `sbrt=0.02` in the sensor-detected dark room; the first tap returned to the existing page; a real 15-second inactivity interval entered ambient mode. The test restored the configured idle delay to 60 seconds and returned Home. Unedited screenshots: validation/ambient/clock-black.png and clock-after-idle.png. A screen capture cannot show the physical backlight level. The public light sensor was present; no public proximity sensor was exposed.

Remaining checks: live camera/face capture, microphone permission/capture and room acoustics, actual HA presence, accessibility/TalkBack, very large clock font/rotation bounds, secure dock relock, long-run power/temperature, and actual output routing. No camera/microphone permission was silently granted by ADB.

The v0.6.0 music-wake check passed with the actual installed receiver and local encrypted aiosendspin reference server: it stayed connected while the ambient clock was visible; a 48 kHz stereo PCM digital-silence stream woke the full interface without any touch input; AudioTrack playback completed. The harness restored the 60-second delay, disabled/reset only its temporary receiver pairing, removed its test XML/ADB forward and returned to Pixel Launcher. This is not a user Music Assistant or audible dock test. Camera and microphone permissions remain ungranted; live capture was not exercised.

Current app lint: 0 errors / 11 warnings (six pinned-dependency updates, the existing Modifier convention, Log/Timber convention, ARM64 ChromeOS support, obsolete resource qualifier and monochrome icon). The two additional dependency warnings are the instrumentation runner/JUnit pins. Existing vendored shared/player regression results remain 210 + 56 passing tests; no player library source changed for this milestone.

An additional actual Home screenshot, validation/ambient/home-dark-room.png, records the full interface after the measured-light dark appearance settled. Demo dashboard data remains visibly labeled. The camera/microphone were not opened for any screenshot.

Final v0.6.0 preview was installed with an in-place update. The two inference-only Android tests passed again on the final target (0.74 seconds); the temporary instrumentation APK was then removed. CAMERA and RECORD_AUDIO permissions were both verified ungranted. Main and preview assembly/unit/lint pass; 72 app + 210 shared + 56 player tests and 45 backend tests pass. Normal Pixel Launcher remains default and foreground. Final source/APK package checksums and signatures were verified.

## Immediate dock / dashboard correction — v0.6.1

The previous protected view was a minimal clock containing a prominent test message. Assume docked left the app in Settings and could not exercise background return. The user requested the main dock interface and immediate lock presentation. The corrected button opens the actual Home interface with a small test badge, and starts the Android-consented lock-watcher setup. Locked dock defaults to the same main Home layout with an independent read-only composition gate on device switches, sliders, scenes, transport, navigation links and speaker selection, plus the existing live-keyguard action guard. A private clock-only preference is available. Settings/assistant answers remain concealed.

Actual lock entry resets the ambient timer rather than waiting for a UI poll. The watcher uses public broadcasts plus short settling checks and a 250 ms loop; it requests the configured Android turn-screen-on behavior on an eligible return. A process-only ten-minute test lease is shared by activity and watcher, expires normally, and never changes physical dock evidence. Normal unlocked and undocked states still prevent return. Suppression is not cleared by a second screen-off event in an already-locked cycle.

Five new return/lease/evidence regressions bring app JUnit to 77 passing tests. The tablet initially showed normal launcher with keyguard locked; overlay permission was allowed, but notification permission was denied and automatic monitoring was off. No claim of background return is made until the Android setup is completed and physically verified.

### Actual tablet follow-up — v0.6.1

Installed the final preview with an in-place update. Completed the normal Android notification permission dialog for the requested visible watcher; POST_NOTIFICATIONS is granted, SYSTEM_ALERT_WINDOW was already allowed, and dock_auto_return=true. The foreground monitoring notification/service was verified active. No ADB permission grant, PIN entry, owner unlock bypass or default-Home change was used.

UI checks passed: locked-dashboard preview uses the main Home layout with checkable controls and sliders disabled; Assume docked immediately opens Home with a small test badge; Home returns to the normal launcher while Android is unlocked. A real KEYCODE_SLEEP lock from that launcher returned the app to top-resumed in **1.03 seconds including ADB/inspection overhead**. Subsequent UI and window-policy checks confirmed the main Dock dashboard, Favorites, disabled switches/sliders, screen ON and Android keyguard still locked. This is activity-return timing, not a calibrated frame-photon measurement. Actual screenshots are validation/dock-dashboard/preview.png and locked.png; sample data is visibly labeled.

Dismiss stayed suppressed during that lock cycle. A subsequent screen off/on while still locked did not reopen the app. The first harness assertion incorrectly assumed a launcher activity is always top-resumed over keyguard; some keyguard states have none. The corrected assertion checks that Pixel Home does not resume, and it passed. The tablet is left at Android keyguard after dismissal with its normal Pixel Launcher role unchanged. The temporary local dock lease expires after ten minutes; automatic return is configured for real docking beyond that. Owner-authenticated unlock then relock and actual physical attach/detach were not exercised.

App unit tests: 77 passing; main/preview assembly and lint pass. Existing camera/microphone wake acceptance limits remain unchanged.
