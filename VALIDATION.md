# hearthline-ha validation — November 16, 2026

This repository is now scoped to the **Home Assistant integration only**. The Android source and all latest tablet layout/pairing changes were moved into the sibling `pixel-home` project. Both source trees and Git history were backed up before the split. The tablet's existing validation reports, APKs and device evidence were preserved with that project.

HA compatibility is pinned to the exercised **2026.10.4** release, tested on Python **3.14.10** with `pytest-homeassistant-custom-component==0.13.375`.

- **28 tests** use actual HA config-entry/flow/options/entity/device/HTTP fixtures. Coverage includes pairing authentication, per-tablet isolation, duplicate pairing, options/revision delivery, closed service dispatch, revocation, disabled owner, entry reload, receipt-based availability, telemetry validation, sequence/session fencing, diagnostics redaction and recorder coalescing.
- **45 dependency-free display/provider regressions** cover the existing 1.0/1.1 display contracts and semantic template providers.
- The three example automations pass HA's actual automation configuration validator. Notification services are clearly labeled placeholders; no live notifications were sent.
- JSON fixtures have been relocated to `tests/fixtures`. Tests do not import or depend on an Android source tree.
- The shared telemetry fixture is also retained in `../pixel-home/app/src/test/resources/tablet-telemetry.json` for Android tests.

`validation/ha-tests.log` and `validation/display-provider-tests.log` record the current runs. `tools/package_integration.py` packages only HA source, contracts, tests, examples and docs; it excludes Android code/APKs, Git data, caches and private keys.

A successful fixture test is not live onboarding acceptance. Still outstanding: a clean standalone HA instance with Android-trusted HTTPS, live APK pairing without a dashboard sensor, native dashboard delivery, edited player order, device restart/Keystore reconnect, real-player controls/artwork, revoke/unpair and physical dock attach/detach. These require an authorized disposable server and user-unlocked tablet. No production HA deployment or certificate installation has been performed.

HA config-entry reload is covered; a separate HA process/storage restart and Android lifecycle instrumentation are not live-acceptance tested. Provisioning QR/discovery is not implemented; explicit manual HTTPS server setup is supported. Official HA raw-token QR and Sendspin SP:0 are distinct tablet-side flows.

Availability is based on server receipt time, defaults to 120 seconds, is configurable to 60–900 seconds and is evaluated every 15 seconds. Missing telemetry becomes unavailable rather than undocked/unlocked/zero battery. Last-seen and app-version diagnostics start disabled. The Android sender's activity-only lifecycle is documented in TABLET-PROTOCOL.md and implemented in the separate tablet project.
