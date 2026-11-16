# Pixel Home 0.3 — assistant answers on your tablet

This update adds a native Material bottom sheet for assistant answers and a Home Assistant custom integration. It does not add a wake word, microphone capture, speech playback, a web-search provider, or a Music Assistant audio receiver. Those are separate features. Existing dashboard/music/startup behavior remains available.

## 1. Install the Android build

Install `install/PixelHome-debug.apk`. This is a development build with a new debug signing key: uninstall v0.2 first if Android reports a signature conflict. Uninstalling clears preferences and the default Home choice. Re-select Pixel Home as Home in Settings if wanted. There are no saved tokens to migrate.

Without a server, open Settings → Assistant display → Preview an assistant answer. This is explicitly labeled sample content. Try Read more, scrolling, and Done. Real responses are only accepted while Pixel Home is resumed and Android is unlocked.

## 2. Install the Home Assistant integration

Copy the entire `home-assistant/custom_components/pixel_home` directory to `/config/custom_components/pixel_home` on your own Home Assistant server. Merge this into `configuration.yaml` (do not replace your existing file):

```yaml
pixel_home:
  display_id: pixel_tablet
  area_name: Living room
  llm_api: true
```

`area_name` is an optional descriptive label you choose, not automatic HA Area Registry enrollment. This version supports one configured display per HA instance. Use the same `display_id` on the tablet. Validate your HA configuration and restart Home Assistant. Custom integrations require normal local administration; this package does not install itself remotely.

The integration registers `pixel_home.get_screen_context`, `present_response`, `get_response_status`, `dismiss_response`, and a tablet-only `exchange` action. All return response data. REST callers must append `?return_response`. Never expose `exchange` to the model; it is the authenticated tablet heartbeat and acknowledgement transport.

## 3. Connect the tablet

In Pixel Home Settings, connect to your trusted HTTPS HA origin using your long-lived access token and the existing dashboard sensor. Under Assistant display enter `pixel_tablet`, then Enable display bridge. Expect “Display bridge connected”. On session changes it can take about ten seconds before the previous heartbeat expires. Tokens, display ID and enablement are session-only; reconnect/re-enable after process restart. The tablet initiates HTTPS requests; it opens no local server port.

The bridge uses the same HA credential. It binds the display to the first authenticated HA user until HA restarts, and refuses a conflicting live tablet session. Choose a dedicated token and keep it private. Display actions follow HA service access; the integration does not create a separate per-user display ACL. Use it with trusted local HA users/agents. HA traces or service-call logging may retain request text according to your HA configuration. The bridge itself does not publish answer text to sensors or persist it to disk.

## 4. Test without an LLM

In HA Developer Tools → Actions, run:

```yaml
action: pixel_home.get_screen_context
data:
  display_id: pixel_tablet
```

Use its returned `session_id` and `capability_revision` below. Keep the tablet visible and unlocked. The return must say `remote_display_available: true`.

```yaml
action: pixel_home.present_response
data:
  display_id: pixel_tablet
  session_id: COPY_FROM_CONTEXT
  capability_revision: pixel-home-0.3
  request:
    protocol_version: '1.0'
    response_id: first_answer_1
    layout: reading
    title: Your display is connected
    summary: Home Assistant can now send structured answers to Pixel Home.
    spoken_text: Your display is connected.
    ttl_seconds: 60
    blocks:
      - type: paragraph
        heading: Ready for an assistant
        text: Longer explanations scroll in this panel. Tap Done to return to your dashboard.
      - type: bullets
        items:
          - Short answers can start collapsed.
          - Sources open only after you tap and confirm.
          - Display requests never execute device actions.
```

The action returns `accepted`. After the sheet appears and the next heartbeat arrives, run:

```yaml
action: pixel_home.get_response_status
data:
  display_id: pixel_tablet
  session_id: COPY_FROM_CONTEXT
  response_id: first_answer_1
```

Expect `rendered`, then `dismissed` after tapping Done (allow the next heartbeat). `rendered` means the client observed a visible sheet and subsequent UI frames, not that a human read it. To close remotely, call `pixel_home.dismiss_response` with `display_id`, current `session_id`, and `response_id`. Wait for dismissal acknowledgement before sending another answer. Use a new response ID for new content; identical retries with the same ID are idempotent.

## 5. Connect the voice agent

The integration registers an LLM API called **Pixel Home display** with four tools:

- `pixel_home_get_screen_context`
- `pixel_home_present_response`
- `pixel_home_get_response_status`
- `pixel_home_dismiss_response`

In the configuration of an agent that supports HA custom LLM APIs, select Pixel Home display. Selection UI and support depend on your conversation integration. Keep Assist/device control enabled alongside it if that agent supports multiple APIs. This API only adds display tools; it does not replace or duplicate HA device tools. If your agent allows only one API or no custom APIs, an agent-specific combined adapter is still needed. Merely pasting the prompt does not register tools or grant access.

The API supplies its own usage prompt and schemas. `ASSISTANT-PROMPT.md` provides an optional copy for your larger assistant prompt. The present tool takes `request_json` as a JSON string, plus current `session_id` and `capability_revision`. The equivalent HA action takes the parsed `request` object. This keeps the LLM tool schema compatible with agents while both ends validate the full content.

Use your existing HA voice pipeline or another microphone endpoint to trigger the agent. Pixel Home 0.3 has no wake-word engine. Web search must be provided by the agent/backend; the tablet only displays supplied source links. `spoken_text` is metadata and is not played by this app.

## Rendering contract

Layouts: `compact`, `reading`, `search`, `steps`, `comparison`. Compact starts with title/summary and Read more; other layouts show detail immediately. All share one consistent bottom-sheet surface; block type determines content presentation. Six block types: paragraph, bullets, steps, sources, table and status. See `display-contract` for schema/examples and the integration's `protocol.py` for authoritative server validation. Follow-up buttons are deliberately not advertised or accepted in this release.

Payload maximum: 32 KiB UTF-8, 8 blocks; title 80 characters, summary 240, spoken text 400; sources 5 per block; tables 4 columns and 8 rows. Text renders as plain text, never HTML/Markdown code. Sources must use HTTPS and have no embedded credentials. No remote images, video, browser embeds, arbitrary intents, JavaScript, CSS, or service calls in content.

## Delivery, lock and disconnect behavior

- Heartbeats run about every two seconds after the previous exchange completes. No push wake, background service or lock bypass.
- Context becomes stale after ten seconds. A session changes when app visibility/connection/bridge settings reset; old messages cannot replay into the new session.
- The client rechecks foreground and Android keyguard before presenting and before opening sources. Locking conceals and clears the answer. Returning to the app starts a clean session.
- One answer at a time; reading is never automatically scrolled or interrupted by another answer.
- TTL applies to delivery, not reading duration. `accepted` is not proof of display; missing acknowledgement yields `delivery_unknown` once stale/expired. Unknown delivery occupies the slot until dismissed/acknowledged or the tablet reconnects.
- After 128 distinct response IDs reconnect to start a new session. IDs and fingerprints are kept in memory for idempotency; full response text is discarded from the bridge after render/dismiss acknowledgement.
- Integration/server restart clears mailbox and ownership. Client reconnects automatically while enabled and foregrounded.

## Tablet acceptance checks

Verify compact/long/search/table layouts at landscape and increased font size; scroll manually and use TalkBack. Lock during delivery, unlock, leave/reopen the app, disconnect Wi-Fi, restart HA, resend an ID, dismiss during rendering and try an invalid payload. No private answer should appear over keyguard; commands must never be inferred from display text. Check existing music navigation and Home-role behavior. This package's automated checks do not substitute for real GrapheneOS/dock testing.

Official API references: https://developers.home-assistant.io/docs/core/llm/ and https://developers.home-assistant.io/docs/api/rest/.
