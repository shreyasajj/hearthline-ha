# Home Assistant token QR — Pixel Home 0.4.1

Home Assistant's long-lived-token dialog can render the token as a QR code. Pixel Home reads that existing format directly. The QR contains the credential only; the Home Assistant server address is entered separately.

1. On another screen, open your Home Assistant profile → Security → Long-lived access tokens. Create a token for this tablet and select Generate QR code.
2. In Pixel Home Preview, open Settings → Home Assistant connection and enter your HTTPS server origin.
3. Choose **Scan Home Assistant token QR**, then allow camera access. Point the tablet at the code; **Switch camera** selects the other tablet camera when available. Cancel closes the scanner.
4. Alternatively choose **Import token QR image** and select the downloaded/saved image using Android's Files picker. This path needs no camera permission or broad photo/storage permission.
5. The imported token appears masked. Check the server address and dashboard sensor, then press **Connect**. Importing a token is not authentication success; Home Assistant verifies it when connecting.

Manual paste and Clear token remain available. The existing trusted-HTTPS requirement is unchanged. No server URL is taken from an untrusted QR, and QR scanning never connects automatically.

The decoder is bundled open-source ZXing (JourneyApps embedded 4.3.0 / core 3.5.4). It needs no Google Play Services, ML Kit, Google account or cloud decoder. Camera frames and selected images are not uploaded or saved by Pixel Home. The camera is active only in the scanner; leaving/backgrounding/locking cancels an active scan. Connection/scanner windows are protected from screenshots. Tokens are never logged, put on the clipboard, saved to preferences or restored from saved instance state.

Credentials remain **session-only**, matching the existing app. Reconnect by scanning/importing/pasting after process restart. Retaining credentials securely across restart remains a separate milestone. Image imports are bounded to 8 MiB and sampled to at most 2048×2048 pixels; malformed, oversized, deeply nested or unrelated QR payloads are rejected. Expired/revoked credentials still require server verification.

The app update does not change your default Home app or dock/lock preferences. Background dock return remains an independent opt-in feature.

Verified source: [Home Assistant token QR generation](https://github.com/home-assistant/frontend/blob/dev/src/panels/profile/ha-long-lived-access-token-dialog.ts), [authentication API](https://developers.home-assistant.io/docs/auth_api/). The frontend passes the token itself to its QR encoder; this implementation does not invent a pairing protocol or a QR envelope.
