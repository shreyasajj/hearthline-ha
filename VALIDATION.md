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

## Tablet discovery update

HA 0.7.3 adds Zeroconf identification, an optional private-IP flow field and installation binding. Discovery coverage includes malformed TXT/metadata, redirects, oversized payloads, public/loopback/link-local targets, duplicate existing tablets, manual IP setup, confirmation and wrong-tablet pairing rejection. HACS remains in the required CI gate. The corresponding Android responder/advertiser is implemented only in the sibling pixel-home app and requires its updated APK.

Automatic mDNS visibility across the user's VLAN boundaries and a routed HA-instance probe must be verified against the actual router/firewall configuration; no reflector/firewall rule or production HA deployment is configured by this change. Discovery never establishes live telemetry or home-control authorization.

Live preview-device check: the updated Android 0.7.3 preview was installed without clearing its data on the connected unlocked tablet. A LAN mDNS browser observed the expected service; the production identity validator accepted a real HTTP response with only the four identity fields. The probe used a real aiohttp transport with the HA session factory substituted in a standalone harness (not a deployed HA instance). Returning the production tablet app to the foreground paused the preview and closed its TCP listener. No Home-role change or home command was sent. This is same-LAN discovery/probe evidence, not routed VLAN or authenticated onboarding acceptance.

Checks at 0.7.3: 58 pytest cases and 45 display/provider regressions pass. Android unit tests, assembly and lint pass in the separate tablet project.

## Push stream update (0.7.4)

`tests/test_stream.py` adds eight cases using HA's real HTTP/WebSocket test client: snapshot equals `GET config`/`GET states`, only selected entities are delivered (an unselected state change is never sent), removal, anonymous and wrong-credential refusal (401), the read-only rule (a tablet frame closes the socket), the per-entry limit (429), idle ping, unpair closing an open stream and refusing a new one, options change closing the stream with the next snapshot carrying the new revision, the 10-second availability grace with telemetry session fencing, a removed owner closing a stream that never goes idle (1008), and HA shutdown closing open streams (1001).

Local checks on November 17, 2026: 66 pytest cases pass (Python 3.14.10, `pytest-homeassistant-custom-component` from `tests/requirements.txt`, HA 2026.10.4), three consecutive runs.

Not yet exercised: the tablet holding the stream against a live HA server over Android-trusted HTTPS, and behaviour behind a reverse proxy. The Android client side is covered by unit tests for message parsing; see the tablet project's VALIDATION.md.

## 0.7.5 — pairing with a code

`tests/test_pair_code.py` adds six cases using HA's real HTTP flow API, as the frontend drives it: the code pairs once as the administrator who opened the form; the credential works and the code cannot be reused after unpair; a discovered entry rejects another installation ID (403) and spends the code; expired and malformed codes (letters, bad UUID, missing or extra fields, boolean schema) are refused; 10 wrong guesses withdraw every open code and return 429; a flow with no administrator request issues no code; a code from a since-deactivated administrator is refused. The options test now goes through the new menu. **72 pytest cases pass**, three consecutive runs (HA 2026.10.4, Python 3.14.10). Not yet exercised against the live HA server.
