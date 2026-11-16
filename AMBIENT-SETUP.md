# Ambient clock and wake — Pixel Home 0.6.0

On entering an eligible dock lock, the main dock presentation appears immediately and starts a fresh ambient idle interval. The black clock appears only after that interval. See STARTUP-TEST.md for dashboard/privacy and lock-return permissions.

Settings → **Ambient clock & wake** controls the visible application's idle display. This is an app presentation mode, not Android's global battery saver or a replacement for keyguard.

## Default behavior

- After 60 seconds without activity, show only large gray time on pure black. Seconds are hidden; the clock updates once per minute and moves slightly each minute.
- Idle backlight is 3%, capped at 2% when the public ambient-light sensor reports a dark room. A first tap anywhere wakes the interface and consumes that gesture, so it cannot activate a home control underneath. Back also wakes the clock.
- The full interface follows Android's existing brightness policy (adaptive brightness if already enabled in Android). Pixel Home does not change the system brightness setting. You can choose a manual full-interface brightness instead.
- Real Sendspin AudioTrack playback or fresh, connected, non-demo HA selected-player playback wakes and keeps the interface visible. Demo music never counts as actual audio. Existing music/idle/Home navigation remains independent.
- Pending actions and displayed assistant answers stay visible. Settings stays awake while editing; **Preview ambient clock** is an explicit exception for testing.
- When docked, the new **Keep ambient clock on while docked** option keeps this visible app/eligible dock clock on. It does not launch the app. Off-dock ambient mode lets Android's normal screen timeout apply. Existing active-interface keep-awake preferences still apply outside ambient mode.
- Leaving the app restores its window brightness override and system bars, and stops sensors, microphone and front camera. Normal launcher and dock auto-return behavior are unchanged.

This does not promise continuous display after Android turns the panel off. No new background launch, boot service, wake lock, device-admin permission or keyguard bypass is added. A tap, face or wake phrase only changes presentation. Locked controls remain behind Android authentication, and an undocked/ineligible locked activity remains concealed. The existing explicit dock-test exception is preserved.

## Dark room and black theme

Automatic dark appearance uses the public Android light sensor, with a 3-second dwell and separate enter/exit thresholds (8 / 20 lux) to avoid flickering from shadows. No sample means unknown, not darkness. This tablet exposes an ambient-light sensor but no public proximity sensor.

**Use a pure black background in dark theme** applies black to the full interface's dark surface; **Always use the black theme** works independently of room light. The ambient clock itself is always black. Normal manual dark-appearance settings continue to work. A black image on this LCD does not turn off its backlight; actual energy savings have not been benchmarked.

## Optional wake signals

Camera and microphone options are **off by default** and request their respective Android permission only when you enable them.

**Offline “wake up”** listens only while this visible app is showing its ambient clock. It uses the bundled English Vosk model and a restricted grammar on the tablet. Audio and recognized phrases are not saved, logged or uploaded. Speaking wakes presentation only; it is not voice authentication and does not dispatch a home command. Live room acoustics, accents, false activations and distance still need user testing. Android's microphone privacy control can prevent capture; system privacy indicators remain visible.

**Front-camera face wake** looks for a face locally at roughly one processed frame per second while the app is visible outside Settings, assistant/credential flows and actual music playback. Frames stay in memory; there is no identity recognition, storage or upload. A recent detection keeps the interface awake. It may miss faces in low light, at a distance, with an unsuitable angle or on unsupported camera implementations. Camera use adds power cost; leave it off when not needed. QR scanning owns the camera in Settings. Android's camera privacy control and indicator remain in force.

**Near proximity** is offered only if a public proximity sensor exists. It detects a nearby object, not a person's identity or room occupancy. It is unavailable on the tested Pixel Tablet.

**HA presence** optionally accepts one explicit `binary_sensor` entity covering the display. A fresh real `on` holds the interface awake; `off` releases it to the idle timer. Demo, stale (>20 seconds), failed/disconnected, unknown, unavailable and absent data never count as presence. The app polls HA while foreground; configuring a sensor does not create one or add telemetry push. Entity state does not establish that a person is specifically in front of the screen.

## Testing and limits

**Preview ambient clock** tests the layout immediately without locking the device. Select a 15-second timeout and leave Settings for an actual idle test. Tap to wake. Restore the timeout afterward if desired.

Actual tablet screenshots are in validation/ambient/. Tests passed a real idle timeout, black time-only rendering, a 2% window brightness override in the measured dark room and first-tap wake. On-device inference tests fed a synthetic “wake up” PCM fixture directly to the bundled decoder and a blank bitmap to the system face detector; neither opened microphone/camera hardware. Live camera/face wake, microphone capture/permission UX, actual HA occupancy and a secure dock relock cycle remain unverified. See VALIDATION.md for the subsequent actual music-wake check and exact results.

## Offline model and licenses

Vosk Android 0.3.75 and JNA 5.18.1 are pinned. The unmodified [vosk-model-small-en-us-0.15](https://alphacephei.com/vosk/models) is bundled (~39 MiB compressed, ~68 MiB when first extracted). Runtime speech recognition needs no download, Google service, account or Internet connection. Model extraction uses a pinned SHA-256, bounded entries/bytes and path checks; the cache is private and excluded from backup. The Apache 2.0 license is in APK assets/licenses/Vosk.txt. The decoder library is also Apache 2.0; JNA's upstream dual license notices remain in its dependency.

Model ZIP SHA-256: `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498`.

Primary API references: [Android window brightness](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness), [Android sensors](https://developer.android.com/reference/android/hardware/Sensor), [system face detector](https://developer.android.com/reference/android/media/FaceDetector), [Vosk offline Android](https://alphacephei.com/vosk/android).
