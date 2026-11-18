hearthline-ha 0.7.6 adds the tablet Home editor for Hearthline Android 0.7.11.

On the unlocked tablet, **Edit Home** searches Home Assistant for eligible lights, switches, thermostats, locks, scenes and media players. Select and reorder items, change the Home title, and save the layout to this tablet’s integration entry. Other dashboard/provider settings are preserved. The paired owner must remain an active HA administrator; each offered/saved entity also requires read and control permissions. Stale edits return a reload conflict instead of overwriting newer changes.

Saves update the dashboard in place and refresh live-stream filters without unloading the credential endpoint. Existing pairing credentials remain valid; no re-pairing is required. Existing clients continue to work. The heartbeat callback is corrected to run on HA’s event loop.

HACS users: update hearthline-ha to 0.7.6 and restart Home Assistant, then install the Hearthline 0.7.11 tablet APK. The Android update also gives active Sendspin playback priority in the shared player and supports an explicitly empty HA fallback (Sendspin only). These Android UI changes require the new APK; updating the integration alone does not change the tablet app.

Validation: 88 real HA tests and 45 provider/display tool tests pass. Coverage includes scoped authentication, administrator/read/control permissions, bounded search, invalid/deleted selections, revision conflicts, cross-tablet isolation, private settings and stream refresh. Live-server/physical tablet acceptance remains pending.

hearthline-ha 0.7.5 adds pairing with a one-time code.

In the tablet's entry, **Configure** now opens a menu: **Edit dashboard and pairing** (the previous form) or **Pair a tablet with a code**. The code replaces the long-lived admin token on the tablet: 8 digits, single use, 5-minute expiry, memory only, bound to the discovered installation ID when there is one, and owned by the administrator who opened it. New endpoint `POST /api/pixel_home/pair/code`; wrong codes go through HA's failed-login handling, and 10 wrong codes in 5 minutes withdraw every open code. Requires tablet app 0.7.5; older apps keep token pairing. Six new tests (72 in total).

hearthline-ha 0.7.4 pushes state changes to paired tablets.

Tablet APK 0.7.4 opens a read-only WebSocket (`/api/pixel_home/tablet/{entry_id}/stream`) while it is visible. HA sends a snapshot of the dashboard configuration and selected entity states, then each state change as it happens, instead of the tablet polling every two seconds. The stream uses the existing scoped tablet credential, the same entity selection and the owner's read permission. HA closes it on revocation, unpair, options change or reload, and the tablet reconnects for a fresh snapshot.

When a streaming tablet leaves the foreground, its telemetry entities become unavailable about 10 seconds later instead of after the 120-second heartbeat timeout.

Compatibility: older tablet APKs keep polling and are unaffected. Tablet 0.7.4 with an older integration falls back to polling. See TABLET-PROTOCOL.md ("Push stream") for limits and close codes.

Validation: eight new HA tests cover snapshot and change delivery, selection filtering, credential checks, the read-only rule, the per-entry limit, ping, revocation, owner removal during a busy stream, options reload, shutdown and the availability grace period (66 tests in total). The stream has not yet been exercised against a live HA server with the tablet; see VALIDATION.md.
