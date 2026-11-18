# hearthline-ha

The Home Assistant custom integration for the separate Hearthline Android tablet app. This repository contains **only the HA integration, its protocol contracts, tests, examples and documentation**. Tablet app source, Gradle files, APKs, Sendspin and Android device tests belong in the sibling `pixel-home` project.

The local integration folder is `pixel-table-ha`. The GitHub repository is [shreyasajj/hearthline-ha](https://github.com/shreyasajj/hearthline-ha). The HA display name is **hearthline-ha**; its domain remains `pixel_home` for compatibility with existing services and display sessions.

## Installation

### HACS (recommended)

[Open in HACS](https://my.home-assistant.io/redirect/hacs_repository/?owner=shreyasajj&repository=hearthline-ha&category=integration)

1. Install and configure [HACS](https://www.hacs.xyz/docs/use/) in Home Assistant if needed.
2. Open **HACS → ⋮ → Custom repositories**. Add `https://github.com/shreyasajj/hearthline-ha` and select **Integration** as the type.
3. Find **hearthline-ha** in HACS and choose **Download**. Select version **0.7.2 or later**. Older 0.7.0/0.7.1 previews were published before HACS metadata was added.
4. Restart Home Assistant, then open **Settings → Devices & services → Add integration → hearthline-ha**. Choose the actual entities for your tablet and follow the [pairing guide](HEARTHLINE-SETUP.md).
5. For future updates, download the new version through HACS and restart HA. Keep your existing config entries and tablet pairing.

This is a HACS **custom repository**; it has not been submitted to HACS's default catalog. HACS manages only `custom_components/pixel_home` from versioned release tags. The HA domain remains `pixel_home`; no second integration or migration to a new domain is created. Default-branch downloads are hidden so users select published, CI-validated releases. The separately downloadable `pixel_home.zip` below is for manual installation, not HACS's archive layout.

If you already installed manually, back up your configuration, add the same repository in HACS, and download it over the existing `custom_components/pixel_home` folder. Restart HA. Do not remove the integration/config entries just to switch install methods. Minimum declared HA version is 2026.10.4, the version exercised by the test suite; later versions are not claimed tested.

### Find a tablet automatically or by IP

Install tablet APK **0.7.3 or later** from the separate `pixel-home` project. Keep the app visible on private Wi-Fi/Ethernet and leave **Allow HA to discover this tablet while visible** enabled in its Home Assistant settings. The tablet shows its IP and installation ID. Older APKs do not advertise the new discovery service.

HA's Zeroconf integration will show a discovered **hearthline-ha** tablet. Confirm its IP/installation ID, choose the dashboard entities, then finish the existing HTTPS/account authorization **on the tablet**. Discovery identifies a candidate; it never authorizes controls or automatically pairs it.

If nothing appears, choose **Settings → Devices & services → Add integration → hearthline-ha** and enter **Tablet IP address (optional manual fallback)**. Use an IPv4 private address such as `192.168.20.55`, or a routed IPv6 ULA; do not enter a URL. Keep the tablet app visible during this check. An empty IP retains the existing manual dashboard setup for legacy clients.

For separate VLANs:

- Allow **HA → tablet TCP 8787** for the read-only identity probe. This port never accepts HA tokens, commands or settings.
- Automatic discovery needs mDNS forwarding/reflecting for **`_hearthline._tcp.local.`** between the intended networks. Multicast does not ordinarily cross a VLAN/router boundary; HACS cannot change your router or firewall. If forwarding is unavailable, use the tablet IP field.
- Allow the existing **tablet → HTTPS HA origin** route for pairing, dashboard controls and telemetry. TLS certificate/origin validation remains required; entering a tablet IP does not weaken HA authentication.

Discovery pauses when the activity pauses; no new background service or screen wake is added. The listener binds only a private Wi-Fi/Ethernet address, and restarts after network changes while visible. If Android/GrapheneOS network controls block incoming connections, the probe reports failure. Disable the local discovery toggle to close both mDNS advertising and TCP identification. Paired tablets continue their existing outbound HA connection independently of this toggle. Discovery does not create an always-online or dock state.

With tablet APK 0.7.4 or later, discovery also stops once the tablet is paired, because HA already knows it; forgetting the server on the tablet makes it discoverable again.

### Live state updates (0.7.4)

A paired tablet running APK 0.7.4 or later keeps a read-only WebSocket open to HA while it is visible, so state changes appear on the tablet as they happen rather than on a two-second poll. It travels over the same tablet → HTTPS HA route; no new firewall rule is needed. If a reverse proxy sits in front of HA, it must allow WebSocket upgrades on `/api/pixel_home/tablet/…/stream` (HA's own frontend already needs this). Without it, the tablet falls back to polling. Details are in TABLET-PROTOCOL.md ("Push stream").


### Manual installation

1. Open [Releases](https://github.com/shreyasajj/hearthline-ha/releases). Download **`pixel_home.zip`** and **`pixel_home.zip.sha256`** from the newest release. Published release files are produced only after the release CI gate passes. This is a custom integration; it is not included in HA's default integration store.
2. Check the download before installing:

   ```sh
   shasum -a 256 -c pixel_home.zip.sha256
   # Linux alternative: sha256sum -c pixel_home.zip.sha256
   ```

3. Extract the ZIP into your Home Assistant configuration's `custom_components` directory. Create the directory if needed. The final path must be **`/config/custom_components/pixel_home/manifest.json`**, not `custom_components/pixel_home/pixel_home/manifest.json`. For HA Container/Core installations, `/config` means the actual configuration directory mounted or passed to HA.

   ```text
   config/
     custom_components/
       pixel_home/
         manifest.json
         __init__.py
         ...
   ```

4. Restart Home Assistant. Open **Settings → Devices & services → Add integration**, search **hearthline-ha**, and select actual entities for a tablet. A manually created dashboard sensor is not required. If the integration does not appear, refresh the browser and check HA logs for `custom_components.pixel_home` setup errors.
5. Build/install the Android app from the separate `pixel-home` project. Unlock Android, enter the explicit **HTTPS HA origin trusted by Android**, authorize with an HA administrator account token, choose the configured tablet, and pair once. The scoped tablet credential is remembered encrypted; the HA account token stays process-only. Optionally enable foreground telemetry. See [HEARTHLINE-SETUP.md](HEARTHLINE-SETUP.md) for the complete authorization, migration, revoke and first-push procedure.

To update, stop/restart HA around replacing only `custom_components/pixel_home` with the newly verified release. Existing config entries are stored by HA separately; do not delete them to update. Keep a copy of the previous integration folder for rollback. Installing files does not bypass the tablet's Android unlock or HA authorization.

For a reviewed source checkout, copy its `custom_components/pixel_home` folder to the same destination. Manual file copies cannot be prevented by GitHub CI; prefer the gated release downloads above.

## CI, dependency updates and releases

The **CI / CI gate** workflow runs on PRs and main updates. It installs the pinned HA test dependencies, runs real HA fixture tests, display/provider regressions, Python compilation and both archive checks. Passing compilation alone is not live tablet onboarding acceptance.

Dependabot checks Python test dependencies and GitHub Actions weekly. A separate privileged workflow approves and merges **only `dependabot[bot]` PRs** after successful PR CI, with matching current head/base commits. It never checks out PR code, never merges human-authored PRs, and requires a fresh successful CI run if the branch changes. When `main` advances, it refreshes the bot branch server-side and explicitly requests new CI before merging. Approval is tied to the exact tested commit and posted only after the entire CI gate, including HACS validation, succeeds. GitHub Actions is allowed to submit reviews; its default workflow token remains read-only outside explicitly privileged jobs. All dependency update levels are eligible; failed CI blocks bot approval and merging.

`main` is protected: PRs must pass **CI gate** and be current with the base branch before merging. This includes administrators; force pushes and branch deletion are disabled. The same CI gate now runs the official HACS repository validator without skipped checks.

To publish another version, change `manifest.json`'s version in a PR, update `RELEASE-NOTES.md`, and merge after CI passes. Under **Actions → Release → Run workflow**, select `main`. Leave **prerelease** off for the normal HACS channel; enable it only for preview builds. The release workflow reruns HA and HACS validation and publishes the exact tested commit as `v<manifest version>`, with `pixel_home.zip`, the full HA-only source ZIP and their SHA-256 files. Existing release versions are not overwritten. Do not install an untested run's files.

The integration provides per-tablet config/options flows, versioned dashboard delivery, scoped revocable pairing, a closed set of authorized dashboard controls, private display/template sessions, and read-only tablet telemetry entities. Telemetry is sent by the Android app only when opted in and foreground; registering the HA integration alone does not establish live telemetry.

Repository contents:

- `custom_components/pixel_home/`: installable HA integration.
- `tests/`: actual HA fixture tests and shared protocol JSON fixtures.
- `tools/test_*.py`: dependency-free display/provider regressions.
- `display-contract/`: existing generic/native display schemas and examples.
- `examples/`: status/automation examples and legacy YAML configuration.
- `TABLET-PROTOCOL.md`: endpoint/authentication/schema/lifecycle contract.

Tested against Home Assistant **2026.10.4**, Python **3.14.10**. Test dependency installation requires Python 3.14.2 or later. No broader HA/GrapheneOS compatibility claim is made.

```sh
python3 -m venv .venv
.venv/bin/pip install -r tests/requirements.txt
.venv/bin/pytest -q
.venv/bin/python -m unittest discover -s tools -p 'test_*.py'
```

Run `python3 tools/package_integration.py` to build an HA-only source ZIP plus `dist/pixel_home.zip` for installation. Android build/package commands run from `../pixel-home`, independently of this repository.

Live HTTPS tablet pairing/reconnect, physical dock attach/detach and clean-install acceptance remain outstanding. See [VALIDATION.md](VALIDATION.md) for the evidence and limits. No production HA deployment is performed by these scripts.

## License

[MIT](LICENSE).

## Pair a tablet with a code (0.7.5)

Open the tablet's entry → **Configure → Pair a tablet with a code**. HA shows an 8-digit code; type it into Hearthline Settings on the tablet together with your HTTPS HA address. The code is held in memory only, works once, expires after 5 minutes and pairs as the administrator who opened it. Wrong codes count as failed logins (and IP bans, if enabled); after 10 wrong codes in 5 minutes every open code is withdrawn. Requires tablet app 0.7.5. Token pairing still works for older apps.


## Edit the tablet Home page (0.7.6)

With Hearthline Android 0.7.11, tap **Edit Home** on the unlocked tablet to search real HA entities, change the title, and select/reorder favorite devices, scenes and available media players. Saves persist in this tablet's integration entry and refresh its live stream. Other dashboard/provider settings are preserved. The paired owner must remain an active HA administrator; offered entities also require read and control permissions. Concurrent edits return a reload conflict instead of overwriting one another. See [the editor protocol](TABLET-PROTOCOL.md#home-editor-integration-076--tablet-0711) for delegated credential scope and bounds. Install this integration update and restart HA before using the new tablet editor; existing paired credentials remain valid.
