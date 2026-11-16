hearthline-ha 0.7.2 adds HACS custom-repository installation.

Add https://github.com/shreyasajj/hearthline-ha in HACS → Custom repositories with type Integration, download hearthline-ha, restart Home Assistant, then add hearthline-ha under Settings → Devices & services. README.md has the complete installation and pairing guide. HACS installs only custom_components/pixel_home from the tested release tag; the separately downloadable pixel_home.zip is for manual installation.

Adds hacs.json, a local house icon, issue-tracker metadata, and official HACS validation in CI and the release gate. Public-repository branch protection now requires CI before changes reach main. Existing config entries, pixel_home service/domain identifiers and tablet pairing are preserved.

This is the normal HACS distribution channel, not a claim of completed live tablet acceptance: clean trusted-HTTPS onboarding/reconnect and physical dock interaction remain unverified. Tested compatibility is Home Assistant 2026.10.4. The Android APK belongs to the separate tablet project and is not included.
