# Hearthline tablet transport v1

The existing `pixel_home` domain hosts the new local channel. The existing generic display protocol 1.0, native template protocol 1.1 and `pixel-home-0.4` capability revision are unchanged. Each config entry owns its own Bridge and TemplateProviders instance. Legacy YAML owns a separate singleton bridge. Display services dispatch by display_id and check paired entry ownership; per-entry LLM APIs also check account ownership.

## Authentication and storage

All Android connections require an explicit HTTPS origin with platform certificate validation. No path, userinfo, query, fragment or credential-forwarding redirect is accepted. No QR-selected URL is fetched. The supported onboarding mechanism is manual server entry plus HA's existing account-token authentication; provisioning QR/discovery is not implemented. The official raw HA token QR importer and Sendspin SP:0 retain distinct behavior.

`GET /api/pixel_home/pair` requires normal HA bearer authentication and an administrator. Response:

```json
{"schema_version":1,"tablets":[{"entry_id":"HA_ENTRY_ID","name":"Kitchen","paired":false}]}
```

`POST /api/pixel_home/pair` requires the same account authorization and exactly:

```json
{"schema_version":1,"entry_id":"HA_ENTRY_ID","tablet_id":"10000000-0000-4000-8000-000000000001"}
```

Returns `{schema_version:1, entry_id, display_id, credential}`. Credential is a random 48-byte URL-safe secret, returned only once. HA stores its SHA-256 hash and authorizing user ID in the versioned config entry, alongside the installation UUID. Pairing fails with 409 if the entry or installation already has an active pairing. A revoked installation may pair again. There is no challenge replay surface because there is no provisioning envelope or unauthenticated pairing step.

Android stores `{origin, entry_id, credential, telemetry}` together encrypted using AES-GCM (random IV, fixed version/type AAD), a dedicated Android Keystore key and AtomicFile under `noBackupFilesDir`. Installation UUID is generated locally, independent of hardware identifiers. Backup/transfer is additionally disabled in the existing app manifest/extraction rules. No HA account token is persisted or sent to the tablet endpoint. No Sendspin key/PSK is reused. Key loss produces a forget/re-pair state.

Every following request supplies `Authorization: Bearer <scoped credential>` to the same origin and exact entry path. `requires_auth=False` on the custom route bypasses HA account-token requirements only: the view explicitly verifies the credential hash, entry, active owner account and operation scope. There is no anonymous webhook. Scoped credentials are invalid for ordinary `/api/states` and HA service endpoints.

`/api/pixel_home/tablet/{entry_id}/{operation}` supports:

| Method/operation | Body | Result |
|---|---|---|
| GET config | none | Versioned definition below |
| GET states | none | Standard HA state objects, restricted to selected entities and owner read permissions |
| GET artwork | `entity_id` query only | Bounded media image for a configured player with read permission; no caller-supplied URL |
| POST session | `{}` | `{schema_version:1, session_id:<server UUID>}`; invalidates previous telemetry session |
| POST telemetry | Strict telemetry object below | `{accepted:true}` |
| POST command | `{entity_id, action, values}` | `{accepted:true}` after HA service dispatch; never proof of physical completion |
| POST display | Existing Bridge.exchange body | Existing display exchange response; display_id must match this entry |
| POST unpair | `{}` | `{revoked:true}`; immediate credential invalidation and telemetry reset |

Missing/invalid/revoked credentials return 401; unauthorized entity control returns 403; unsupported operations return 404; invalid data returns 400; bodies over 8192 bytes return 413. Credentials/payloads are not logged. Diagnostics contain only pairing/availability booleans, config revision and entity counts.

Commands are a closed mapping: light on/off and optional brightness_pct 0..100 (with brightness support), switch on/off, lock/unlock, scene on, climate set_temperature within the device's supported range, and media play/pause/previous/next/volume_set 0..1/play_media. Required player capability bits are checked. Media IDs must match a configured shortcut. The owner must currently have control permission on the selected entity. No arbitrary service/domain/value forwarding or Android command exists. The app retains its existing Android unlock checks before dispatch. A scoped token is a bearer credential: possession itself is not proof of Android unlock; protect/revoke it as a delegated home-control credential.

## Config definition and revisions

`GET config` returns exactly these top-level fields (new fields require client version negotiation):

```json
{
  "schema_version":1,
  "revision":"content_hash_16",
  "entry_id":"HA_ENTRY_ID",
  "display_id":"ha_entry_id",
  "dashboard":{
    "schema_version":1,"title":"Kitchen",
    "favorites":["light.test"],"scenes":[],"players":[],
    "home_entities":[],"weather_entity":null,"music":[],"idle_seconds":45
  },
  "capabilities":{"telemetry":1,"display_protocols":["1.0","1.1"],"remote_configuration":false}
}
```

`display_id` normalizes the entry ID to lowercase and replaces hyphens with underscores for the existing bridge contract. The HTTP entry ID remains unchanged. Revision is the first 16 hex characters of a SHA-256 over canonical sorted JSON of the stored definition. HA owns this definition; Android never writes it. Options edits reload only the affected entry and change the revision. HA config entries persist definition/version and authorization, never telemetry state.

Dashboard fields are strictly checked on HA and Android. Favorites: at most 8 light/switch/climate/lock entities; scenes: 6 scene entities; players: 8 media_player entities; home_entities: 16 sensor/binary_sensor/light/switch/climate/lock entities; weather_entity: null or a weather entity. Entries must exist; unknown/unavailable state is retained. Arrays are ordered and reject duplicates. Music: at most 8 objects with exactly title (1..80), uri (1..512), type (1..40). Title is 1..80, idle_seconds is integer 15..600. Unknown dashboard fields are rejected. Source disappearance makes configuration invalid rather than inventing replacements. Area is server metadata and does not authorize actions. Optional Journey adapter endpoint/origin/token/traffic options stay on HA; dashboard responses exclude all provider credentials and endpoints. The endpoint must be fixed HTTPS with no userinfo/query/fragment. The existing semantic intent validation remains unchanged.

## Telemetry

The complete required field set is in `tablet_contract.FIELDS`; every field is required, unknown fields are rejected. The shared valid fixture is `app/src/test/resources/tablet-telemetry.json`.

| Field | Validation/meaning |
|---|---|
| schema_version | Integer 1; bool rejected |
| tablet_id, session_id | Lowercase UUID strings; must match pairing and current server-issued session |
| sequence | Integer 0..2^53-1, strictly increases within session; bool rejected |
| observed_at | ISO timestamp with offset; within ±300 seconds of server receipt |
| app_version | 1..32 characters, no control characters |
| battery_percent | null or integer 0..100 |
| battery_status | charging/discharging/full/not_charging/unknown |
| charging | true only for Android BATTERY_STATUS_CHARGING, false for full/discharging/not_charging, null for unknown |
| power_source | none/ac/usb/wireless/dock/multiple/unknown |
| dock_state | docked/undocked/unknown from existing public-signal policy |
| dock_evidence | system_dock_event/audio_dock/dock_power/manual_override/unconfirmed |
| detection_mode | auto/force_docked/force_undocked; manual modes require manual_override evidence |
| foreground, interactive | Strict booleans; resumed activity and observed PowerManager state respectively |
| locked | Strict boolean when KeyguardManager observed, otherwise null |
| current_screen | home/music/settings/clock/unknown; never assistant/query content |
| protected_dock_view_visible | Conservative observation: resumed, locked, eligible, interactive, shown activity window with focus |
| dock_display_enabled | Strict boolean, user's display preference; no claim Android presented it |
| dock_test_active | Strict boolean, explicit local test lease; never physical docking |

Maximum JSON request size 8 KiB, no arbitrary nested diagnostics. Raw audio, images, queries, URLs, serials, app lists, notifications, location and credentials are absent. Sendspin is deliberately omitted from v1 telemetry; no receiver readiness/route/playback claim is fabricated. Existing physical dock observations and local ten-minute test lease are unchanged. The Docked binary entity is unknown for manual overrides or unknown observations. `no_dock_signal` from the internal policy maps to the bounded `unconfirmed` source value; an explicit negative physical observation may still report dock_state=undocked.

Each new foreground sender session requests a server UUID; old sessions, mismatched tablet IDs, duplicate/out-of-order sequences and timestamps outside tolerance cannot replace state. No telemetry survives HA restart, reload or revocation; Android starts a fresh session after restart or failed telemetry. Receipt time alone controls availability. Sequence/observation time are not state attributes. Last seen/app version are disabled diagnostics by default; unchanged sensor states do not generate changing heartbeat attributes.

## Android scheduling and lifecycle

The existing activity resumed/paused policy controls polling and sending. Foreground config/state refresh is every two seconds. At most one latest telemetry observation is sampled per refresh; unchanged values produce a heartbeat every 30 seconds. There is no offline queue/history. Failed polling uses exponential delay 4/8/16/32/60 seconds plus 0..999ms jitter; connection/read/call timeouts are 8/10/15 seconds. Pause cancels the loop, although a blocking in-flight HTTP operation can finish before its timeout. No WorkManager job, new service, boot receiver, remote wake, permission change or automatic activity launch was added.

Availability timeout defaults to 120 seconds, configurable to 60..900 and evaluated every 15 seconds. Measurements become unavailable and connection becomes off after expiry. On 401/403 the app stops polling and private display exchange and presents reauthorization; it never silently presents demo as live. Manual reconnect retries current authorization; explicit pairing is required after revocation. Auto reconnect changes neither Android authentication nor Home role or the separate user-enabled dock-return watcher.
