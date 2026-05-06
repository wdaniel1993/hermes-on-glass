## Context

The reference architecture is `dweddepohl/clawsses` — an Android phone-app + Rokid glasses-app pair that bridges Rokid Glasses to OpenClaw via WebSocket. We are inspired by it (same module shape, same phone-as-bridge topology, similar HUD UX) but writing fresh code that targets Hermes Agent's REST API instead of OpenClaw's WebSocket gateway.

Hermes' API surface (verified 2026-05-06 against `https://hermes-agent.nousresearch.com/docs`):

- Gateway runs on `127.0.0.1:8642` by default; configurable bind address via `API_SERVER_KEY` env var.
- Auth is Bearer token; warning in docs: "The API server gives full access to hermes-agent's toolset, including terminal commands."
- `POST /v1/responses` with `conversation: <name>` parameter chains turns server-side, persisted in SQLite, **LRU-evicts beyond 100 stored responses**.
- `POST /v1/chat/completions` is the OpenAI-compatible alternative (no server-side state).
- SSE streaming on both endpoints. `/v1/responses` emits `response.created`, `response.output_text.delta`, `response.output_item.added`, `response.output_item.done`, `response.completed`, plus custom `hermes.tool.progress`.
- `image_url` in message content accepts `http(s)://` URLs and `data:image/...;base64,...`. Uploaded files / `file_id` / non-image data: URLs return 400.
- **No audio input**. Voice mode is bound to CLI / Telegram / Discord channels — not exposed via the API server.
- `/api/jobs` exposes scheduled work that delivers to any registered channel.
- Plugin discovery from `~/.hermes/plugins/`, `.hermes/plugins/`, or pip entry points. Channels live under `gateway/platforms/`. Plugin extension surface for *custom channels* (vs. tools) is documented thinly — needs verification before MVP+1.

Topology constraints from the user:

- Hermes runs on a Mac mini at home. Reachable from the phone over Tailscale via MagicDNS hostname `mac` (so the phone-app's base URL is `http://mac:8642`).
- The glasses are Rokid Glasses (480×640 monochrome green micro-LED, JBD 0.13" panel, dual-eye, on-board AOSP). They reach the phone over Bluetooth via Rokid's CXR-M (phone) / CXR-S (glasses) SDKs.
- The phone runs Termux only as a connectivity convenience for the user's own SSH/shell access — the Android *app* itself does not depend on Termux. Bearer token auth is what protects Hermes; Tailscale provides the network boundary.

## Goals / Non-Goals

**Goals:**

- Build a wearable Hermes client that mirrors the clawsses UX (long-press-to-talk, streaming HUD, session picker, photo-attached questions, TTS playback) without copying clawsses code.
- Use Hermes' `/v1/responses` server-side conversation state to drastically simplify session management vs. clawsses' OpenClaw lifecycle.
- Render incremental Hermes deltas natively as glasses chat-stream chunks (no client-side diffing).
- Surface Hermes tool-progress events in the HUD so the user knows when the agent is mid-tool-call.
- Land MVP+1 (`glasses-channel-plugin`) so Hermes can push to the glasses without the user prompting first — the moment that distinguishes this from "wearable LLM client."

**Non-Goals:**

- No audio I/O via Hermes' REST. Voice stays phone-local (Path A from /opsx:explore). Revisit when Hermes ships a first-class audio API or once our channel plugin can route audio.
- No Discord/Telegram/Slack mirroring on the glasses. Hermes already has those; we are *another* channel, not a replacement.
- No fork or vendoring of clawsses code. Architectural inspiration only.
- No public-internet exposure of Hermes. Tailscale or LAN only.
- No iOS, Vision Pro, Meta Quest, XReal, or other AR vendors.
- No on-device LLM. Glasses thermal/battery budget makes this a bad MVP choice; the agent runs on the Mac mini.
- No replacement for Hermes' memory/skills/jobs systems. We use them; we do not duplicate them.

## Decisions

### D1: Use `/v1/responses` (Responses API) over `/v1/chat/completions`

**Choice:** Phone-app's HermesClient sends `POST /v1/responses` with `model: "hermes-agent"`, `input`, `conversation: <session-name>`, `stream: true`.

**Rationale:** The `conversation` parameter gives us server-side multi-turn state without us tracking response IDs or replaying history. The Responses API SSE event taxonomy (`response.output_text.delta`, `response.output_item.added`, etc.) is also more expressive than chat completions chunks and includes the lifecycle hooks (`response.created`, `response.completed`) we want for HUD state transitions.

**Alternatives considered:**

- *Chat Completions API*: OpenAI-compatible, simpler, more stable. Rejected because we'd have to re-send full message history every turn, which fights the spirit of using Hermes' built-in memory and ages poorly as conversations grow.
- *`/v1/runs` API*: Designed for long-running background work — overkill for interactive chat. We may use it later for fire-and-forget commands triggered from the glasses (e.g., "summarize my inbox"), but not for the MVP chat loop.

### D2: Named conversations are the unit of session

**Choice:** A "session" on the glasses maps 1:1 to a named Hermes conversation passed via the `conversation` parameter. The phone-app maintains the canonical list of conversation names in local SQLite/SharedPreferences. Default conversation name on first run: `glasses-default`.

**Rationale:** Hermes LRU-evicts beyond 100 stored responses; we cannot trust the server as the source of truth for "list of sessions ever created." Phone-side authority also matches clawsses' UX where the session list is curated client-side.

**Alternatives considered:**

- *Single global conversation*: Simpler, but the user explicitly wants a session picker.
- *Server-side list via `/v1/runs` history*: Possible but undocumented behavior under LRU eviction; client-side list is unambiguous.

### D3: Voice stays on the phone (Path A)

**Choice:** Android `SpeechRecognizer` for STT (with optional Google Cloud Speech fallback), ElevenLabs SDK or Edge TTS for output. The text round-trip with Hermes is the only thing that crosses the network.

**Rationale:** Hermes' REST does not accept audio. The two alternatives — sidecar Whisper service on Mac mini, or a Telegram-bridge hack — add latency, deployment surface, or fragility. Phone-local STT/TTS ships now and gives sub-second latency on-device.

**Alternatives considered:**

- *Sidecar STT/TTS on Mac mini*: Better voice consistency across Hermes channels. Deferred — the network module is designed so audio-leg can move to Mac later without touching glasses or Hermes integration.
- *Custom Hermes channel-plugin that accepts audio*: Real Python work and the plugin-extension surface for channels is sparsely documented. Deferred to a later proposal.

### D4: Tailscale + Bearer, no public TLS

**Choice:** Phone-app's HermesClient connects to `http://mac:8642` (MagicDNS over Tailscale) with the Bearer header `Authorization: Bearer <API_SERVER_KEY>`. No HTTPS/TLS terminator on the Hermes side.

**Rationale:** Tailscale's WireGuard tunnel is end-to-end encrypted; layering TLS on top is redundant and adds cert-rotation pain. Bearer auth alone is acceptable *only because* the network path is restricted to the tailnet. The API key still functions as a defense-in-depth secret if a tailnet device is compromised.

**Risks:** If the user later wants to reach Hermes from outside the tailnet, this decision must be revisited (add Caddy/Tailscale Funnel + TLS).

### D5: Stream Hermes deltas through unchanged; no client-side accumulation

**Choice:** Phone-app forwards each `response.output_text.delta` event directly into a phone→glasses `chat_stream { id, chunk }` envelope. Glasses appends. On `response.completed`, phone sends `chat_stream_end { id }`.

**Rationale:** Hermes deltas are already incremental, so we skip the OpenClaw-style "full accumulated text + diff" logic. This is strictly simpler than clawsses' code path.

### D6: Tool-progress as a HUD subline

**Choice:** Phone forwards `hermes.tool.progress` events as a new envelope type `tool_progress { messageId, toolName, phase }` (phase ∈ `started`/`finished`). Glasses renders a single subline under the streaming message ("⚙ web_search…") that disappears on `finished` or when stream ends.

**Rationale:** Hermes' tool calls can take seconds; without a visual signal the HUD just sits silent. This is a small, Hermes-specific differentiator.

### D7: Glasses session picker shows phone-managed conversation list

**Choice:** Phone sends `session_list { sessions: [...], currentSessionKey }` whenever the user opens the picker or after creating a new conversation. Selection sends `switch_session { sessionKey }` from glasses → phone. Phone updates `conversation` parameter on subsequent `/v1/responses` calls. New-session creation is glasses-initiated via a menu entry (`+ New session`) that auto-names (`glasses-<timestamp>`) and the user can rename later from the phone settings.

**Rationale:** Matches clawsses' UX, fits the user's stated need for a picker, keeps the glasses code dumb.

### D8: Camera capture as data: URL `image_url` content part

**Choice:** Glasses captures JPEG, sends `user_input { text, imageBase64 }` to phone. Phone constructs:

```json
{ "role": "user", "content": [
    { "type": "input_text", "text": "<text>" },
    { "type": "input_image", "image_url": "data:image/jpeg;base64,<b64>" }
] }
```

(Exact field names match Responses API content-parts schema; verify on first integration.) Image is downscaled on the glasses before base64 to keep wire size sane on Bluetooth (target: ≤256 KB after JPEG, ≤350 KB after base64).

**Rationale:** Hermes accepts data: URLs; uploading files is rejected. Downscaling on the glasses (vs. on the phone) saves Bluetooth bandwidth.

### D9: Wake-on-message preserved with new `cron_message` reason

**Choice:** Phone-app subscribes to incoming pushes from `glasses-channel-plugin` (MVP+1) over an authenticated webhook (POST to a phone-local HTTP server, or — more realistically — via long-poll/SSE from the plugin). When a push arrives and the glasses display is asleep, phone sends `wake_signal { reason: "cron_message" }` first, waits for `wake_ack`, then delivers the buffered message.

**Rationale:** Reuses the wake-on-message pattern verbatim from clawsses' design. The plugin-driven push path is what makes scheduled-job glasses notifications work.

### D10: Three Gradle modules + one Python module

**Choice:**

```
hermes-on-glass/
├── shared/                    # Kotlin: protocol DTOs (both wires)
├── phone-app/                 # Android Kotlin: bridge + voice + Hermes client
├── glasses-app/               # Android Kotlin: HUD on Rokid
└── glasses-channel-plugin/    # Python (MVP+1): Hermes channel plugin
```

The phone-app's `preBuild` task bundles the glasses APK into `phone-app/src/main/assets/glasses-app-release.apk` for sideloading via Rokid's WiFi P2P. The Python plugin is a separate folder, not a Gradle module, with its own `pyproject.toml` and install instructions targeting `~/.hermes/plugins/`.

**Rationale:** Mirrors clawsses' shape, which the user has already validated as a sensible decomposition for this hardware.

## Risks / Trade-offs

- **Hermes plugin/channel extension surface is thin in docs** → MVP+1 (`glasses-channel-plugin`) may need source-diving in `gateway/platforms/` to find the right hook. Mitigation: scope the plugin task as a spike first; fall back to a webhook-only delivery target (Hermes already supports `webhook` as a platform) if a full channel adapter is more than a week's work.
- **Hermes' 100-response LRU on Responses API** → Long-running named conversations may lose history if more than 100 responses are stored across all conversations system-wide. Mitigation: phone-app re-sends summary context on conversation resume if the server returns a 404 on the stored response chain. (Verify whether 100 is per-conversation or global.)
- **`API_SERVER_KEY` exposes terminal access** → A leaked key is a full-system compromise on the Mac mini. Mitigation: store only in Android EncryptedSharedPreferences; document this clearly in the settings UI; recommend `chmod 600 ~/.hermes/.env`.
- **Tailscale dependency** → If Tailscale is down or the phone is off-tailnet, the glasses do nothing. Mitigation: explicit "offline" state in HUD; document LAN-fallback config (`http://<lan-ip>:8642`) for users who want home-only operation without Tailscale.
- **No Bluetooth backpressure for image bytes** → Sending a large base64 image over CXR-M can stall the chat stream. Mitigation: glasses-side hard cap at 256 KB JPEG before base64; if user takes a high-res photo, downscale aggressively (target 1024×768 or smaller).
- **Android background-task hostile environment** → Foreground services and wake locks needed for Bluetooth + voice. Mitigation: structure phone-app exactly like clawsses (`GlassesConnectionService` foreground service); request battery-optimization exemption on first run.
- **Rokid SDK is a closed binary** → Behavioral changes in CXR SDK versions can break us. Mitigation: pin SDK version in `build.gradle.kts`; document upgrade procedure.
- **Drift between OpenAI Responses API spec and Hermes' implementation** → Hermes documents spec-native event names but may diverge. Mitigation: write the parser defensively (unknown event types → log + ignore), include a "raw events" debug log in the phone-app dev settings.

## Migration Plan

This is a greenfield project; no existing system to migrate. Build order matches `tasks.md`:

1. **Hardware bring-up**: Rokid glasses pairing, SDK credentials in `local.properties`, Tailscale up, Hermes gateway running with a non-default `API_SERVER_KEY`.
2. **Protocol module**: `shared/` DTOs first — the contract both apps depend on.
3. **Phone-app skeleton**: HermesClient (HTTPS + SSE + Bearer + `/v1/responses`), settings screen, no glasses integration yet. Verifiable from a single phone screen — type a message, see streaming response.
4. **Glasses-app skeleton**: HUD with streaming-text rendering, gesture routing, no camera, no voice. Sideload to glasses, verify display + gestures.
5. **Phone↔Glasses bridge**: Bluetooth/CXR-M wired up, debug-mode WebSocket fallback for emulator. End-to-end text loop works.
6. **Voice loop**: Android STT on long-press, ElevenLabs/Edge TTS playback. Verify hands-free chat.
7. **Camera path**: Glasses capture, downscale, base64, image_url. Verify "what am I looking at?" works.
8. **Session picker**: Conversation manager in phone-app, glasses menu entry, switch + create flows.
9. **Tool-progress indicator**: HUD subline.
10. **MVP+1 — Channel plugin**: Python module, registration, webhook to phone-app, wake-on-message integration. Verify cron job pushes a message that wakes the glasses.

There is no rollback strategy — the user can simply not install the APKs.

## Open Questions

- **OQ1: Clawsses license.** Confirm clawsses' actual license (the README hints at MIT-style but we should verify before publishing or any code resemblance). We are writing fresh, but knowing the license tells us how careful we need to be about visual diff.
- **OQ2: Hermes Responses API content-part field names.** Verify the exact JSON schema for `image_url` content parts on `/v1/responses` (the docs show `{"type": "image_url", "image_url": {...}}` for chat completions, but Responses API may use `input_image`). First integration spike will confirm.
- **OQ3: Is the 100-response LRU per-conversation or global?** Affects how we handle history loss. Worth a quick experiment against a running gateway.
- **OQ4: Channel-plugin extension API.** Is there a documented Python entry point for "register a custom channel," or do we need to subclass an adapter base class in `gateway/platforms/`? Spike before committing to MVP+1 scope.
- **OQ5: TTS provider default.** ElevenLabs (premium voice, requires key) vs. Edge TTS (free, no key). Default to Edge for first-run smoothness, surface ElevenLabs in settings?
- **OQ6: Foreground-service notification copy.** Android requires a persistent notification for foreground services — what does it say? "Hermes glasses connected" probably, but UX should review.
