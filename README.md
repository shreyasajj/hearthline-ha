# hearthline-ha

The Home Assistant custom integration for the separate Hearthline Android tablet app. This repository contains **only the HA integration, its protocol contracts, tests, examples and documentation**. Tablet app source, Gradle files, APKs, Sendspin and Android device tests belong in the sibling `pixel-home` project.

The local integration folder is `pixel-table-ha`. The GitHub repository is [shreyasajj/hearthline-ha](https://github.com/shreyasajj/hearthline-ha). The HA display name is **hearthline-ha**; its domain remains `pixel_home` for compatibility with existing services and display sessions.

Copy `custom_components/pixel_home` into your Home Assistant configuration's `custom_components` directory and restart HA. In Settings → Devices & services, add **hearthline-ha**, select real entities for each tablet, and pair from the separately built Android app. A dashboard sensor is not required. See [HEARTHLINE-SETUP.md](HEARTHLINE-SETUP.md).

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

Run `python3 tools/package_integration.py` to build an HA-only source ZIP. Android build/package commands run from `../pixel-home`, independently of this repository.

Live HTTPS tablet pairing/reconnect, physical dock attach/detach and clean-install acceptance remain outstanding. See [VALIDATION.md](VALIDATION.md) for the evidence and limits. No production HA deployment is performed by these scripts.
