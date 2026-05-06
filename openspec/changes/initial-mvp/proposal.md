## Why

Hermes Agent (Nous Research) is a self-improving, channel-rich AI agent that already runs on the user's Mac mini, but today it's only reachable from terminal/Telegram/Discord/etc. The user wants the same agent on their face — Rokid Glasses — for hands-free voice interaction, photo-grounded questions, and (post-MVP) proactive notifications driven by Hermes scheduled jobs. A reference architecture exists (`dweddepohl/clawsses` for OpenClaw on Rokid Glasses), but no equivalent exists for Hermes. We need a clean-room wearable client built around Hermes' REST API server and its `/v1/responses` server-side conversation model.

## What Changes

- Introduce a three-module Android Gradle project (`shared/`, `phone-app/`, `glasses-app/`) inspired by the clawsses topology but written from scratch (no fork, no vendoring).
- Phone-app acts as the bridge: HTTPS+SSE to Hermes (`http://mac:8642` over Tailscale, Bearer auth), Bluetooth/CXR-M to the glasses, and houses the voice stack (Android `SpeechRecognizer` for STT, ElevenLabs/Edge for TTS).
- Glasses-app is HUD-only: 480×640 monochrome green Compose UI, touchpad gestures, camera capture, long-press-to-talk.
- Use Hermes' `/v1/responses` with the named `conversation` parameter for server-side state — no manual session create/list lifecycle on the Hermes side.
- Stream Hermes deltas (`response.output_text.delta`) and `hermes.tool.progress` events through to the glasses HUD as incremental chunks and a tool-running indicator.
- Send glasses-camera photos to Hermes as `image_url` content parts using `data:` URLs.
- Maintain a client-side list of named conversations to power the glasses session-picker UX (Hermes LRU-evicts beyond 100 stored responses, so the canonical list lives in the phone-app, not on the server).
- **MVP+1**: ship a small Python Hermes channel/plugin (`glasses-channel-plugin`) that registers as a Hermes channel and forwards Hermes-originated messages (cron job output, async run completions) to the phone-app via webhook, lighting up wake-on-message on the glasses. This is the differentiator from clawsses-on-OpenClaw.

## Capabilities

### New Capabilities

- `glasses-hud`: HUD app on Rokid glasses — Compose UI on the 480×640 monochrome green display, touchpad gesture routing, camera capture, long-press-to-talk trigger, session/menu navigation, wake-on-message handling, streaming-chunk rendering, tool-progress indicator.
- `phone-bridge`: Android companion app on the user's phone — Hermes HTTP+SSE client with Tailscale + Bearer auth, named-conversation manager, Bluetooth/CXR-M bridge to glasses, voice subsystem (Android STT, ElevenLabs/Edge TTS), wake-signal coordinator, glasses APK sideloading, settings UI.
- `hermes-client-protocol`: Shared protocol module — DTOs and parsers for the Hermes wire (the subset of `/v1/responses` request/response shapes and SSE events we use) and the phone↔glasses wire (chat messages, incremental streams, wake signals, voice state, session list, slash commands, camera-attached user input).
- `glasses-channel-plugin`: Python Hermes plugin (MVP+1) — registers as a Hermes channel, accepts deliveries from Hermes (scheduled jobs, completed background runs, agent-initiated nudges) and forwards them to the phone-app via an authenticated webhook so they reach the glasses with wake-on-message.

### Modified Capabilities

None. This is a greenfield project.

## Impact

- **New code:** Three Android Gradle modules (Kotlin + Jetpack Compose), one Python plugin module. No existing code to migrate.
- **External dependencies:** Rokid CXR-M SDK (phone) and CXR-S SDK (glasses) from `https://maven.rokid.com/repository/maven-public/`; OkHttp + okhttp-sse for Hermes streaming; Gson for protocol serialization; ElevenLabs/Edge TTS clients; Android SpeechRecognizer.
- **Hermes config:** Requires `API_SERVER_ENABLED=true` and a strong `API_SERVER_KEY` in `~/.hermes/.env`; gateway must be running (`hermes gateway`); Tailscale must reach the Mac mini at `mac:8642`. For MVP+1, the plugin lives in `~/.hermes/plugins/glasses-channel/` and needs a webhook URL pointing back at the phone.
- **User credentials:** `local.properties` must hold Rokid SDK credentials (`rokid.clientId`, `rokid.clientSecret`, `rokid.accessKey`); phone-app settings UI must accept Hermes server URL, API key, and TTS provider key.
- **Out of scope:** No iOS, no other AR vendors, no public-internet exposure of Hermes (Tailscale-only), no audio piped through Hermes' REST (impossible today), no Discord/Telegram/Slack parity (those are Hermes' built-in channels and not duplicated here).
