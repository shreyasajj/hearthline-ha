First hearthline-ha preview release for the separate Hearthline Android tablet app.

Includes HA config/options flows, entity-backed per-tablet dashboards, scoped revocable pairing and home controls, isolated display/template delivery, and opt-in Android telemetry receiver/entities. Retains the `pixel_home` domain and legacy sensor/YAML compatibility.

Install: download `pixel_home.zip`, verify its accompanying SHA-256 file, extract it into your HA configuration's `custom_components` folder, restart HA, then add **hearthline-ha** under Settings → Devices & services. Detailed setup and pairing instructions are in README.md and HEARTHLINE-SETUP.md. An Android-trusted HTTPS HA origin is required for tablet pairing. The separate tablet APK is not included in this HA release.

Release publishing requires the HA fixture suite, display/provider regressions and archive validation to pass for the exact source commit. Tested compatibility is HA 2026.10.4. This is a preview: clean trusted-HTTPS tablet onboarding/reconnect and physical dock acceptance remain unverified. No production HA deployment is performed.
