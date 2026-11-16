# hearthline-ha

The Home Assistant custom integration for the separate Hearthline Android tablet app. This repository contains **only the HA integration, its protocol contracts, tests, examples and documentation**. Tablet app source, Gradle files, APKs, Sendspin and Android device tests belong in the sibling `pixel-home` project.

The local integration folder is `pixel-table-ha`. The GitHub repository is [shreyasajj/hearthline-ha](https://github.com/shreyasajj/hearthline-ha). The HA display name is **hearthline-ha**; its domain remains `pixel_home` for compatibility with existing services and display sessions.

## Installation

1. Open [Releases](https://github.com/shreyasajj/hearthline-ha/releases) while signed into the GitHub account that can access this private repository. Download **`pixel_home.zip`** and **`pixel_home.zip.sha256`** from the newest preview release. Published release files are produced only after the release CI gate passes. This is a custom integration; it is not included in HA's default integration store.
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

Dependabot checks Python test dependencies and GitHub Actions weekly. A separate privileged workflow merges **only `dependabot[bot]` PRs** after successful PR CI, with matching current head/base commits. It never checks out PR code, never merges human-authored PRs, and requires a fresh successful CI run if the branch changes. When `main` advances, it refreshes the bot branch server-side and explicitly requests new CI before merging. All dependency update levels are eligible; failed CI blocks bot merging.

**Repository limitation:** GitHub currently rejects required branch protection for this private repository on the account's plan. Normal manual merges therefore cannot be blocked by a required status check. Bot merges and releases explicitly gate their own operations on successful CI. The repository remains private; neither visibility nor billing is changed. If branch protection becomes available, require the unique status check **CI gate** on `main` and require branches to be current before merging.

To publish another preview, change `manifest.json`'s version in a PR, update `RELEASE-NOTES.md`, and merge after CI passes. Under **Actions → Release → Run workflow**, select `main`. The release workflow reruns validation and publishes the exact tested commit as `v<manifest version>`, with `pixel_home.zip`, the full HA-only source ZIP and their SHA-256 files. Existing release versions are not overwritten. Do not install an untested run's files.

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
