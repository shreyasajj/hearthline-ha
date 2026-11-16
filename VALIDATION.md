# Hearthline 0.7.0 validation — November 16, 2026

This is a local implementation and automated-test milestone. It is **not completed live tablet onboarding acceptance** and has not been deployed to the user's HA instance. The original Pixel Home source is unchanged. Earlier upstream validation is archived separately in VALIDATION-0.6.1.md.

## Executed successfully

- Home Assistant **2026.10.4**, Python **3.14.10**, `pytest-homeassistant-custom-component==0.13.375`: **28 tests passed**. Tests use the actual HA config-entry/flow/options/entity/device/HTTP fixtures, not substitute dependency-free mocks. They cover invalid/missing entities, empty selections, preserved unavailable state, pairing/authentication, duplicate pairing, cross-tablet isolation, closed control dispatch with account context, revocation, disabled owner, options order/revision delivery, entry reload, restarted telemetry unavailability, malformed/oversized telemetry, schema/enums/nulls, timestamp bounds, sequence/session fencing, manual overrides, diagnostic/provider-secret redaction and unchanged-state heartbeat coalescing. All three example automation schemas were validated with HA's actual automation configuration validator. Notification actions are placeholders, not live notifications.
- Android JDK **17**, Android SDK compile/target **35**: `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug` passed with preview ID `dev.pixelhome.templates`. **87 app unit tests**, including the new managed-dashboard validation/origin/fixture tests and existing navigation/access/dock/ambient/token/template/display regressions. Lint: **0 errors, 11 warnings** (retained camera/platform/icon/layout warnings; see report). Default-package debug assembly also passed.
- Sendspin regression tasks `:sendspin-player:testDebugUnitTest` and `:sendspin-shared:jvmTest`: **56 + 210 = 266 tests passed**.
- Existing dependency-free Python generic/native display/provider regression suite: **45 tests passed**.
- Python modules compile successfully. APK signatures and SHA-256 hashes verified; package validation checks ZIP CRC, allowlisted contents and embedded APK hashes. Build/pytest evidence and a signature report are included in `validation/`.

## Connected-device observations only

Read-only ADB observations on the connected Pixel Tablet: Android **16**, API **36**, build **CP1A.260505.005**, security patch **2026-05-05**. Battery service reported AC=true, USB=false, wireless=false, dock-powered=false, level=100, battery status=5 (full). This is not charging=true and AC is not physical dock confirmation. Historical audio configuration includes digital-dock routes; an active named dock route and physical attach/detach were not established in this run. GrapheneOS was not tested or inferred from these observations. No PIN or hardware identity was collected into the delivery artifacts.

## Not executed / remaining acceptance

- No clean standalone HA deployment plus APK pairing against Android-trusted HTTPS; no production HA installation or account authorization was supplied or performed.
- No live tablet demonstration of configuration delivery, edited player order, account-authorized home actions, app restart/Keystore restore, revoke/unpair, or receiver/music artwork against an actual HA player. The corresponding server paths and dashboard validation have automated coverage; compilation is not live evidence.
- No physical dock attach/detach, background/resume/lock/unlock acceptance or instrumentation exercise of Keystore backup/key-loss behavior. Existing pure policy/access regressions pass, but Android lifecycle/backoff/jitter are not device-acceptance tested.
- No provisioning QR or multicast discovery is implemented. Explicit manual HTTPS setup is the supported path. The official HA raw-token and Sendspin flows remain distinct. Malicious/expired/replayed provisioning-QR acceptance is inapplicable to this build; a future QR feature would need separate challenge/expiry/replay tests.
- HA process/storage restart across a separate running server was not tested; config-entry reload tests verify retained entry credentials and reset telemetry. Persisted Android reconnect still requires live device validation.
- Compatibility is pinned to tested HA **2026.10.4** only. No broader version/GrapheneOS claim is made.

A live acceptance session needs a disposable HA server with an Android-trusted HTTPS origin, actual test entities and explicit authorization, plus a user-unlocked tablet and physical dock interaction. Follow HEARTHLINE-SETUP.md; do not install a certificate, clear existing app data, change the Home role or bypass Android authentication to satisfy a test.

## Implementation boundaries

Telemetry is opt-in and activity-resumed only, coalesced to changed snapshots and 30-second heartbeats; an in-flight request can finish after pause within 15 seconds. No new background sender/service, wake, Android permission change, remote dock-test activation or remote configuration entity was added. Unchanged sensor states do not add sequence/timestamp attributes; last-seen/app-version diagnostics start disabled. A 120-second default receipt timeout (60–900 configurable, 15-second evaluation) marks data unavailable.

New remembered authorization is a separate scoped credential. Legacy pasted/scanned HA account tokens stay process-only. Journey credentials/options stay on HA. No private signing keys, HA credentials, receiver secrets or installed-app lists are packaged.

## Primary API references checked

- https://developers.home-assistant.io/docs/creating_integration_manifest/
- https://developers.home-assistant.io/docs/config_entries_config_flow_handler/
- https://developers.home-assistant.io/docs/core/entity/
- https://developers.home-assistant.io/docs/device_registry_index/
- https://developer.android.com/reference/android/media/AudioDeviceInfo
- https://developer.android.com/reference/android/content/Intent#ACTION_DOCK_EVENT

The BatteryManager web reference timed out; Android SDK/source and the existing implementation supplied the battery constants used here.
