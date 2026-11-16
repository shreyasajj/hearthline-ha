# Tablet / dock receiver — Pixel Home 0.5.0

The app includes a native Sendspin receiving player. Music Assistant supplies music and manages the player/group. No separate Android player app, Google services or account is required. Existing HA media-player controls remain independent.

## Setup

1. Open Pixel Home while Android is unlocked. Go to Settings → Tablet / dock audio receiver.
2. Set a recognizable receiver name and tap **Apply receiver settings / reconnect**. Leave the server URL blank for automatic discovery. Default listening port is 8928; the actual bound port appears in status if Android selects another available port.
3. Enable **Sendspin receiver**. Check for **Listening** and **Advertised on local network**. Music Assistant's Sendspin provider must be enabled and allowed to discover players. Tablet and server must be able to reach one another; multicast discovery usually requires the same local network. No Internet service is used for discovery.
4. Use Music Assistant's player pairing flow. **Show receiver pairing QR** displays this receiver's private `SP:0…` pairing token as a QR and text. It is separate from the HA long-lived token QR. Unpaired playback is disabled. Close the QR when finished.
5. Select the tablet player as an output in Music Assistant and start music. If controlling it through Pixel Home's existing HA dashboard, add its actual HA media-player entity to your dashboard sensor's player list after MA/HA exposes it. Pixel Home does not invent an entity ID or silently switch your existing selected player.

A server supporting encrypted Sendspin/Noise is required; legacy plaintext servers are rejected. Protocol capability varies by Music Assistant release. This implementation pins the upstream player source rather than promising compatibility with every release.

## When discovery is unavailable

Configure Music Assistant to connect to the tablet's reachable IP and the displayed listening port at `/sendspin`, if its provider supports manual clients. Alternatively enter the Music Assistant dedicated Sendspin endpoint in Pixel Home, usually `ws://YOUR_MA_HOST:8927/sendspin`, then Apply. Explicit `wss://host/sendspin` is supported for a TLS reverse proxy, with normal certificate validation. Credentials, query parameters and alternate paths are not accepted. A blank URL returns to discovery mode. Port 8095's built-in web-player endpoint is not the general external-player endpoint; consult your installed server's documentation.

WebSocket `ws://` transport is permitted for Sendspin because the protocol encrypts its application messages with Noise. HA, assistant delivery and map-provider code still require HTTPS. There is no plaintext Sendspin fallback or insecure-TLS switch.

## Lifetime and audio behavior

- Receiver is disabled by default. Enable/name/endpoint/port/volume/mute preferences persist.
- It starts when the activity is visible and stops when Android calls onStop (Home, another full-screen app, an image picker, screen-off if the activity is no longer visible). A visible protected dock clock can host the receiver. This is not an always-on background speaker service; no media foreground service, boot receiver or wake lock is added.
- It does not enable dock return or change the default Home app. Off-dock presentation rules and the explicit dock test remain separate.
- Discovery mode has a WebSocket listener advertised through Android NSD. Manual connection mode retries with a delay capped at 30 seconds. Leaving the app mutes immediately, withdraws discovery and tears down transport/audio resources; closing a stalled socket may take several seconds.
- The native library handles encryption, pairing, clock synchronization, codec decoding, buffering and AudioTrack scheduling. PCM is preferred; only formats supported by the native player are advertised.
- Receiver volume controls software gain. Android media volume also applies. Audio focus loss silences this output and reports external-source unavailability to the server. Pixel Home does not claim audible playback from a received group state alone.
- Android chooses the media output: tablet, charging speaker dock, Bluetooth or wired audio. Confirm the route on your hardware. Dock detection alone does not establish audio routing. Set static output delay using Music Assistant's player settings if needed; room synchronization requires measurement.

## Pairing privacy and reset

A dedicated Android Keystore AES-GCM key encrypts the receiver's identity, pairing PSK and long-term records in private app preferences. No plaintext storage fallback is used. These are separate from HA credentials, which remain session-only. Pairing QR uses a secure window and disappears when the activity pauses. Tokens are not logged or automatically copied to the clipboard. App backup is disabled.

**Reset receiver identity & pairings** requires an unlocked user and confirmation. It stops/disables the receiver, waits for its connections to close, removes its encrypted identity and records, then requires fresh pairing when re-enabled. It does not change HA credentials or dashboard settings. If the Keystore is unavailable, the app reports a startup error rather than silently replacing an existing identity.

## Sources and validation

Native library: [SendspinDroid](https://github.com/chrisuthe/SendspinDroid), MIT; pinned revision and build adaptations are in third-party/sendspin/UPSTREAM.md. Full notice is shipped in APK assets/licenses/SendspinDroid.txt and the source package. [Sendspin client implementation guide](https://www.sendspin-audio.com/build/guide/client/) and [Music Assistant Sendspin provider](https://www.music-assistant.io/player-support/sendspin/) describe the connection roles.

See VALIDATION.md for the exact checks completed and limitations. Physical tablet checks passed encrypted pairing and 48 kHz stereo PCM digital-silence playback against a local reference server, repeated connection playback, visible/background listener lifetime, encrypted identity persistence across process restart and reset. These are not a live Music Assistant or audible dock/synchronization test.
