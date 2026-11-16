# Pixel Home remaining development work

Version 0.3 adds native semantic answer rendering and a Home Assistant display LLM API. It does not complete the original product brief.

Next: verify this build against a real HA server and GrapheneOS tablet, then add secure credential persistence, a local wake-word/microphone pipeline, transcription and speech playback with interruption/echo handling. Investigate tablet/dock audio output registration separately; display delivery does not turn the dock into a Music Assistant player.

Continue with real-device accessibility and large-font checks, room/camera screens, weather/calendar sources, actual dock detection and Music Assistant library/queue browsing. Maintain HA as the source of truth and preserve Android authentication.

For the current implemented interface, see DISPLAY-SETUP.md, ASSISTANT-PROMPT.md and display-contract/response.schema.json.


## v0.4 template milestone

The separate template brief is implemented; see TEMPLATE-SETUP.md for adapter configuration and VALIDATION.md for measured checks and remaining live-provider/device acceptance work. Journey/weather/home_status extend rather than replace the generic answer layouts. Local voice capture/transcription/playback and secure credential persistence remain separate future work.

## v0.5 / v0.6 local audio and ambient milestones

Native Sendspin receiver and encrypted pairing are implemented; see SENDSPIN-SETUP.md. Ambient clock, dimming, measured-light dark theme and optional offline “wake up”/local face wake are implemented; see AMBIENT-SETUP.md. This is a restricted display-wake phrase, not the general transcription/conversation/action voice pipeline. User Music Assistant/HA providers, actual dock audio routing, live presence/voice acceptance and telemetry push remain separate checks/milestones.
