hearthline-ha 0.7.3 adds tablet network discovery and a private IP fallback.

With tablet APK 0.7.3 or later visible on private Wi-Fi/Ethernet, HA discovers `_hearthline._tcp.local.` and confirms the installation ID before dashboard configuration. If multicast is unavailable or blocked across VLANs, manually add hearthline-ha and enter the tablet IP. Allow HA → tablet TCP 8787; mDNS needs a reflector for automatic discovery across VLAN boundaries. Router/firewall settings are not changed automatically.

Discovery is a bounded, read-only identification channel with no HA credentials, commands, permissions or background service. Pairing still requires HTTPS HA origin/account authorization on the tablet. Existing paired/manual entries remain supported. The Android update belongs in the separate pixel-home project; older APKs cannot be discovered merely by installing this HA release.

The new discovery/config-flow, invalid-target/redirect/payload and cross-tablet pairing tests run with existing HA, HACS and display/provider gates. Full trusted-HTTPS onboarding and routed VLAN acceptance still require live network/user configuration; see VALIDATION.md.
