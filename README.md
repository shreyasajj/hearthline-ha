# Hearthline 0.7.0

A local Android home dashboard with integration-managed Home Assistant setup, remembered tablet pairing, and opt-in foreground tablet telemetry. This source fork lives in `pixel-table-ha`; the original `pixel-home` source is unchanged.

The Home Assistant integration is named **hearthline-ha** in Devices & services; the Android app is **Hearthline**. The HA domain remains `pixel_home` and the Kotlin namespace remains `dev.pixelhome` to preserve the display/template APIs. No dashboard configuration sensor is required for the new pairing path. Protocols 1.0/1.1 and capability revision `pixel-home-0.4` remain unchanged.

Read [HEARTHLINE-SETUP.md](HEARTHLINE-SETUP.md) for installation, pairing, migration, permissions and first-push checks. [TABLET-PROTOCOL.md](TABLET-PROTOCOL.md) specifies authentication, schemas and lifecycle. [VALIDATION.md](VALIDATION.md) records current evidence and outstanding device acceptance. [examples/tablet.yaml](examples/tablet.yaml) contains status and automation examples.

`install/Hearthline-preview.apk` uses the existing separate preview ID `dev.pixelhome.templates`. `install/Hearthline-debug.apk` uses `dev.pixelhome` and replaces an installation with the same package/signing key. Both are development builds, signed with the local Android debug certificate, not production release artifacts. Do not clear an existing app's data to test this fork without saving any settings you want to keep.

Build with JDK 17 and Android SDK 35:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug -PpixelHomeTestId=dev.pixelhome.templates
python3 -m unittest discover -s tools -p 'test_*.py'
```

HA tests use Python 3.14.2 or later and pinned HA 2026.10.4 fixtures:

```sh
python3 -m venv .venv
.venv/bin/pip install -r tests/requirements.txt
.venv/bin/pytest -q
```

Only HA 2026.10.4 has been exercised here; no wider compatibility claim is made. HA installation requires an HTTPS origin with a certificate trusted by Android. There is no insecure TLS option, provisioning QR, cloud dependency or automatic HA deployment.

Existing native templates, Sendspin, ambient settings, Home role and protected dock behavior remain in the source. Their setup references are [DISPLAY-SETUP.md](DISPLAY-SETUP.md), [TEMPLATE-SETUP.md](TEMPLATE-SETUP.md), [SENDSPIN-SETUP.md](SENDSPIN-SETUP.md) and [AMBIENT-SETUP.md](AMBIENT-SETUP.md). Their earlier evidence is archived in [VALIDATION-0.6.1.md](VALIDATION-0.6.1.md); it is not acceptance evidence for this integration milestone.
