## Context

The reference architecture is `dweddepohl/clawsses` — an Android phone-app + Rokid glasses-app pair that bridges Rokid Glasses to OpenClaw via WebSocket. We are inspired by the module shape and HUD UX but writing fresh code that targets Hermes Agent's channel-adapter surface (`gateway/platforms/`), not OpenClaw.

### Hermes Agent surface (verified 2026-05-06 against repo + voice-mode docs)

- **Channel adapters** live in `gateway/platforms/`. Base class: `BasePlatformAdapter` (`gateway/platforms/base.py:1206`). Required overrides: `connect()`, `disconnect()`, `send(chat_id, content, reply_to, metadata)`, `get_chat_info(chat_id)`. Helpers: `edit_message`, `delete_message`, `send_typing`, `send_image`, `send_voice`, `play_tts`, `send_animation`, `send_document`, `send_video` (all `base.py:1453-2140`). Lifecycle hooks: `on_processing_start` / `on_processing_complete`.
- **Inbound dispatch**: adapters call `await self.handle_message(event)` (`base.py:2543`). The framework spawns one async task per session_key; bypass commands (`/stop`, `/new`, `/reset`, `/approve`, `/deny`) are routed inline.
- **Voice is shared, not per-channel.** STT lives in `tools/transcription_tools.py` (default provider `local` = faster-whisper, no API key required; alternates: `groq`, `openai`). TTS lives in `tools/tts_tool.py` (default provider `edge` = Microsoft Edge neural TTS, free; alternates: `elevenlabs`, `openai`, `mistral`, `xai`, etc.). Both invoked from the shared response pipeline at `base.py:2792-2823`.
- **Telegram pattern (the model for our adapter):**
  1. On inbound voice memo, the adapter downloads bytes, calls `cache_audio_from_bytes(bytes(audio_bytes), ext=".ogg")` (`telegram.py:3192-3202`), sets `event.message_type = MessageType.VOICE` and `event.media_urls = [cached_path]`, and calls `handle_message(event)`.
  2. Adapter does *not* transcribe inline — the agent (model loop) calls the STT tool when it sees an audio media URL.
  3. On reply, `base.py:2792-2823` checks `_should_auto_tts_for_chat(chat_id)` and `event.message_type == VOICE`; if both true, calls `text_to_speech_tool` and then `play_tts`. The adapter overrides `send_voice` / `play_tts` to upload the rendered file to its native channel.
  4. `/voice on` / `/voice off` / `voice_only` / `all` user commands implemented in `base.py:1284-1297`. We get them for free.
- **No streaming TTS to channels** today. Each TTS provider in `tts_tool.py` writes a complete file before returning. We accept this as MVP cost; protocol leaves room for future streaming.
- **Webhook adapter is ingress-only** (`gateway/platforms/webhook.py`). Outbound delivery there re-routes through *another* adapter — webhooks are not a clean template for a bidirectional phone bridge. Our model is `discord.py` / `matrix.py` (persistent socket).
- **WS server hosting**: `webhook.py` binds routes via `aiohttp` `app.router.add_post(...)` on `0.0.0.0:8644`, demonstrating that adapters can bind on the gateway's HTTP app. We expect the same plumbing to be available for `app.router.add_get(...)` with WebSocket upgrade — confirmed in spike OQ4.

### Rokid SDK surface (verified against `buildwithfenna/rokid-docs`)

- **Three SDK trees:**
  - `cxr-m/` — phone-side (used by `phone-app`).
  - `cxr-s/` — on-glasses bridge (used by `glasses-app` for the wire to the phone). Exposes `CXRServiceBridge.subscribe(name, MsgCallback | MsgReplyCallback)` with optional request/reply via `Reply.end(Caps)` (`cxr-s/message-subscription.md`).
  - `cxr-l/` — glasses-native AIDL bridge to `com.rokid.sprite.aiapp`'s `IMediaStreamService`. APIs: `takePhoto(width, height, quality)` with `IImageStreamCbk.onImageReceived(byte[])`, `startAudioStream(codecType)` with `IAudioStreamCbk.onAudioReceived(byte[], sampleRate, channels)`, `openCustomView(json)`, `updateCustomView(...)`, `setIcons(json)`, `closeCustomView`. `AuthorizationHelper` requires the Rokid AI app installed (versionCode ≥ 100000) (`cxr-l/api-reference.md:247-252`).
- **Caps is binary**, supports nested `byte[]` and structured payloads (`cxr-s/data-structure.md`). MTU is configured per client type in `cxr-service.json` (Android-RFCOMM / iOS-GATT / iOS-MFI). The 500-char truncation in clawsses' `RokidSdkManager.kt:600` is a clawsses convention, not a Rokid SDK limit.
- **Bulk transport**: `sendStream(CxrStreamType, byte[], fileName, cb)` for streaming bytes (`cxr-m/sdk-decompiled-reference.md`); `setAudioStreamListener(...)` delivers `onAudioStream(byte[], offset, length)` chunks; ARTC has a dedicated `onARTCFrame(byte[], long ts)` channel.
- **APK push**: `CxrApi.startUploadApk(wifiAddress, callback)` (`sdk-decompiled-reference.md:1007-1017`) does Wi-Fi P2P + multipart POST to `http://<glasses_ip>:8848`. **Critical bug**: WiFi-P2P stale-status, workaround via reflection on hidden `WifiP2pManager.deletePersistentGroup` (`sdk-decompiled-reference.md:817-893`).
- **Pairing / SN verification** (`sdk-decompiled-reference.md:2168-2192`): `connectBluetooth()` reconnect requires `snEncryptContent: byte[]` and `clientSecret: String`; AES/CBC/PKCS5Padding with key = clientSecret bytes (dashes stripped), IV = first 16 bytes of clientSecret bytes. First-connection `initBluetooth()` skips SN check.
- **Audio**: 4 built-in mics (16-bit/16 kHz, accessible via standard `AudioSource.MIC`); earpiece + stereo speaker (48 kHz PCM). Wake-word/AEC runs on a separate NXP RT600 co-processor (`yodaos/docs/hardware/audio.md`). For our MVP we use standard Android `MediaRecorder` (Opus encoder, API 29+) and `MediaPlayer` on the glasses; we do **not** wire the on-board KWS pipeline.
- **Display**: 480×640 software canvas (always downscaled to this size regardless of mode, per `screen-record.md:333`). Refresh thermally throttled across 60-180 Hz. Brightness 0-15. Screen sleep coordinated via `setScreenOffTimeout(ms)`, `notifyGlassScreenOff()`, and `ScreenStatusUpdateListener.onScreenStatusUpdated(boolean)`. JBD JBD4020 micro-LED is right-eye only; the docs do **not** assert the panel is monochrome green — that is panel-vendor lore from outside the docs, so we hedge in the spec text.
- **Touchpad**: capacitive sensor on the right temple (`yodaos/docs/kernel/modules.md:93`), surfaced as standard Android `KeyEvent`s (DPAD_LEFT/RIGHT/UP/DOWN/CENTER, BACK, KEYCODE_CAMERA, KEYCODE_VOLUME_UP/DOWN per `apps/camera2.md:327-337`). No documented gesture taxonomy beyond key events.
- **App constraints**: minSdk ≥ 28, arm64-v8a. targetSdk ≤ 32 confirmed safe (CXR-S is built against 28).

### Topology constraints from the user

- Hermes runs on a Mac mini at home. Reachable from the phone over Tailscale via MagicDNS hostname `mac`.
- Glasses are Rokid Glasses — Android-based AOSP, on-board mics, speakers, camera, capacitive touchpad on right temple.
- Phone runs Termux only as a connectivity convenience for the user's own SSH/shell access — the Android *app* itself does not depend on Termux.

## Goals / Non-Goals

**Goals:**

- Build a wearable Hermes client where the glasses-and-phone pair is a *single Hermes channel*, not a custom REST client.
- Mirror Telegram's voice pattern: glasses captures Opus, server-side STT, server-side TTS, glasses plays the result. No phone-side audio engine.
- Phone-app participates in the chat (history view, typed input from the phone) — not a transparent bridge.
- Surface incremental Hermes deltas as glasses chat-stream chunks; render `hermes.tool.progress` as a HUD subline.

**Non-Goals:**

- No `/v1/responses` REST client on the phone.
- No phone-side STT/TTS (no `SpeechRecognizer`, no Edge/ElevenLabs on Android). The phone is not a voice device.
- No webhook-based push from Hermes to the phone (would require the phone to host an inbound HTTP server — brittle on Doze/OEM-killers).
- No Discord/Telegram/Slack mirroring on the glasses (those are independent Hermes channels).
- No public-internet exposure of Hermes. Tailscale or LAN only.
- No fork or vendoring of clawsses code.
- No on-device LLM. Glasses thermal/battery budget makes this a bad MVP choice.
- No use of Rokid's on-board KWS / wake-word pipeline. Long-press-to-talk only.
- No SLAM, no eye tracking — neither is exposed by the Rokid SDK.

## Decisions

### D1: Glasses-and-phone pair is a single Hermes channel

**Choice:** Ship a Python `BasePlatformAdapter` subclass (`hermes-channel-adapter/`) that registers a channel named `glasses`. All chat traffic — user→agent, agent→user, cron pushes, run-completions, agent-nudges — flows through this one adapter.

**Delivery via the plugin path** (verified spike 1.1): the adapter ships as a Hermes plugin under `~/.hermes/plugins/hermes-channel-adapter/` with `plugin.yaml` + `adapter.py` + `__init__.py` (re-exports `register`). The `register(ctx)` entry point calls `ctx.register_platform(name="glasses", adapter_factory=lambda cfg: GlassesAdapter(cfg), ...)`. Zero core Hermes code changes — bypasses the 16-step built-in checklist. Reference: `plugins/platforms/irc/adapter.py` in `hermes-agent`. Built-in checklist (`gateway/run.py`, `gateway/config.py`, `toolsets.py`, `cron/scheduler.py`, etc.) is not relevant to this project.

**Rationale:** Idiomatic to Hermes (`gateway/platforms/` already hosts ~20 channels). Single source of truth for sessions and voice. Cron/run/nudge pushes share the same delivery path as user-driven turns; no second transport.

**Alternatives considered:** REST client calling `/v1/responses` (rejected — splits push and pull paths, requires reproducing session management, doesn't compose with cron); MCP (rejected — wrong abstraction; MCP is for tools, not channels); built-in adapter (rejected — would require modifying core Hermes; plugin path is the documented community route).

### D2: WebSocket transport, outbound from phone, Bearer-on-upgrade

**Choice:** Phone connects outbound to `ws://mac:<port>/glasses` over Tailscale, with `Authorization: Bearer <SHARED_SECRET>` on the upgrade request. Persistent connection, JSON frames type-discriminated by a `type` field, our own envelope (not OpenAI Responses API events).

**Rationale:** Bidirectional, full-duplex; phone doesn't host an inbound HTTP server (Tailscale-on-Android can in principle but Doze + OEM battery-killers make it brittle for "ship to friends" reliability); matches the `discord.py` / `matrix.py` adapter pattern (persistent socket). Tailscale's WireGuard tunnel is already encrypted; Bearer-on-upgrade is sufficient defense-in-depth.

**Alternatives considered:** Webhook (rejected — `webhook.py` is ingress-only and inbound-Android-HTTP brittle); HTTP+SSE (one-way, would need a second channel for upstream — strictly worse than WS); gRPC streaming (overkill, generates code we don't need).

### D3: Voice on the server (Telegram pattern), no phone-side STT/TTS

**Choice:** Long-press-to-talk records Opus on the glasses. Bytes flow glasses → phone (CXR `sendStream` or `startAudioStream`) → WS → channel adapter. Adapter writes bytes via `cache_audio_from_bytes(..., ext=".ogg")`, sets `event.message_type = MessageType.VOICE` and `event.media_urls = [path]`, calls `handle_message`. The agent loop runs the STT tool itself. On reply, `base.py:2792-2823` invokes `text_to_speech_tool`; our adapter overrides `send_voice` / `play_tts` to push the rendered Opus down the WS to the phone, which forwards it via CXR to the glasses, which plays it via the local 48 kHz speaker. Phone never touches PCM.

**Rationale:** Voice handling is a shared service in Hermes — `tools/tts_tool.py` and `tools/transcription_tools.py` are called from `base.py`, not per-channel. Telegram's adapter does only two things: download audio to cache and override `send_voice`/`play_tts`. We do exactly the same. Wins: consistent voice across all Hermes channels (same voice on glasses, Telegram, Discord); single config surface (`~/.hermes/config.yaml`); zero TTS/STT keys on the Android side; `/voice on/off` plus the `voice_only` / `all` modes for free; STT default = local faster-whisper (no API keys required at all).

**Costs accepted:** ~1-2s STT lag after release-to-send (no live partial transcription) — acceptable for hold-to-talk with a recording overlay. TTS is render-then-play (no streaming TTS to channels yet) — long replies wait for full render. Mitigation deferred; protocol leaves frame headroom for streaming chunks.

**No fallback to phone-side voice.** A phone-local STT/TTS path would share the WebSocket failure mode (no server → no agent → nothing to transcribe *for* and no reply *to* speak); it would be theater. If the WS is down, voice is down. Acceptable.

**Alternatives considered:** Phone-local STT + phone-local TTS (rejected — inconsistent voice across channels, key duplication, defeats the channel-unification thesis); phone-local STT only with server TTS (rejected — phone still maintains an STT engine just to lose live partial-results UX once it goes server-side anyway).

### D4: Hermes owns sessions, not the phone

**Choice:** No client-side session list, no `glasses-default` bookkeeping. Sessions are managed by Hermes (its native session model in the channel framework). The channel adapter exposes the session list over the WS via a `session_list` frame; the glasses session picker and the phone chat UI render whatever the adapter sends.

**Rationale:** Once we are a real channel, Hermes already maintains chat sessions per `chat_id`. The earlier draft's "phone-app maintains canonical list to work around 100-response LRU" was a workaround for `/v1/responses`; that workaround becomes obsolete once we're a channel. The 100-response LRU concern only applied to the Responses API.

**Alternatives considered:** Phone-side session list (rejected — duplicates Hermes' state).

### D5: Phone is a participating chat client, not a transparent bridge

**Choice:** Phone-app shows the active conversation's chat history in a Compose UI, has a typed-input affordance, and reflects the same conversation the glasses see. Mirrors `clawsses MainScreen.kt:603,765-789`. Both surfaces (phone UI + glasses HUD) are views of one Hermes session.

**Rationale:** User-stated requirement. Useful for debugging (look at the phone when the HUD looks wrong) and for soft-fallback UX when the glasses are off or unreachable.

**Alternatives considered:** Phone-as-bridge-only (rejected — user's intent is explicit on this).

### D6: Glasses-app uses CXR-S + CXR-L

**Choice:** Glasses-app depends on both `cxr-s` (bidirectional message wire to the phone via `CXRServiceBridge.subscribe(name, MsgCallback | MsgReplyCallback)`) and `cxr-l` (camera, audio, structured view APIs from `IMediaStreamService`). Phone-app depends on `cxr-m` only.

**Rationale:** CXR-S handles RPC framing including `MsgReplyCallback` (`cxr-s/message-subscription.md`), giving us request/reply semantics over Caps that `sendCustomCmd` doesn't offer. CXR-L is the documented surface for camera (`takePhoto(width, height, quality)`), audio I/O (`startAudioStream(codecType)`), and structured HUD push (`openCustomView(json)`). The earlier draft only listed CXR-S; that omitted the media APIs we need.

**Alternatives considered:** CXR-S only with custom CameraX + standard `MediaRecorder` (rejected — `takePhoto` is documented and integrates with Rokid's camera lifecycle; bypassing it risks pipeline conflicts).

### D7: Caps + sendStream, no 500-char chunking

**Choice:** Use Caps (binary) directly for control frames over `CXRServiceBridge`. Use `sendStream(CxrStreamType, byte[], fileName, cb)` for bulk media (Opus audio, JPEG images). No artificial size cap on Caps frames; chunk only if a probe reveals a real MTU limit.

**Rationale:** The 500-char truncation in `RokidSdkManager.kt:600` is a clawsses-imposed convention, not a Rokid SDK limit. Caps natively supports binary and nested `byte[]`. `sendStream` is the documented bulk path. Truncating control frames at 500 chars would silently drop streaming chunks past the cap.

**Risks:** If a real per-frame limit exists (e.g., RFCOMM MTU values in `cxr-service.json` we couldn't inspect), some control frames could fail. Mitigation: probe early in task 6.x and add fragmenting only if needed.

### D8: Streaming text deltas through unchanged

**Choice:** Phone forwards each `assistant_chunk { id, chunk, parentId }` WS frame from the channel into a phone→glasses `chat_stream { id, chunk }` envelope. Glasses appends. On `assistant_complete { id }` from the channel, phone sends `chat_stream_end { id }`.

**Adapter-side mapping** (verified spike 1.5): the framework's `GatewayStreamConsumer` (`gateway/stream_consumer.py`) drives streaming via the **edit-message transport** — there is no per-token outbound hook. It calls `adapter.send(chat_id, first_chunk)` for the initial visible message, then `adapter.edit_message(chat_id, message_id, accumulated_text, finalize=...)` repeatedly with rate limiting (1s edit interval, 40-char buffer threshold). On the final edit, `finalize=True` is set. We map this to our WS frames by overriding both:
- `send(chat_id, content, ...)` → emit `assistant_chunk { id: <generated>, chunk: content, parentId: ... }`, return `SendResult(success=True, message_id=<generated>)` so the framework has an ID to edit.
- `edit_message(chat_id, message_id, content, finalize)` → compute delta = `content[len(last_sent_for_id):]`, emit `assistant_chunk { id: message_id, chunk: <delta> }`. On `finalize=True`, emit `assistant_complete { id: message_id }`. Track per-message-id last-sent state to compute deltas. (Granularity is the framework's, not per-token.)

**Rationale:** Same as the previous draft's D5 — Hermes deltas are already incremental, and the edit-stream pattern preserves that. Phone↔glasses semantics stay clean.

### D9: Tool-progress as a HUD subline (unchanged)

**Choice:** Adapter forwards `hermes.tool.progress` events as `tool_progress { messageId, toolName, phase }` WS frames. Phone re-emits as a phone→glasses envelope of the same shape. HUD renders a single subline ("⚙ web_search…") under the streaming message.

### D10: Camera capture as image content-part

**Choice:** Glasses captures JPEG via CXR-L `takePhoto(width, height, quality)`; downscales until JPEG ≤ 256 KB; sends `user_input { text, imageBase64 }` to phone over CXR. Phone constructs a multimodal `user_message` WS frame whose payload includes both the text part and the image as a `data:image/jpeg;base64,...` URL. Channel adapter receives the frame and synthesizes an inbound `event` with the appropriate `media_urls` / multimodal content for `handle_message`.

**Rationale:** Hermes' multimodal pipeline accepts data: URLs and uploaded files cached locally. The channel adapter does the format mapping; we don't expose Hermes' content-part schema on the WS wire.

### D11: Wake-on-message preserved, with channel-origin metadata

**Choice:** Adapter tags pushed messages with `origin ∈ {cron-job, run-completion, agent-nudge}` in the frame metadata. Phone routes through `WakeSignalManager` before delivery; sends `wake_signal { reason }` to the glasses; waits for `wake_ack` (or 3s timeout); then delivers. Display state coordinated via CXR `notifyGlassScreenOff()` and `ScreenStatusUpdateListener.onScreenStatusUpdated`.

**Rationale:** Same UX as the previous draft, now natively driven by the channel rather than a separate webhook delivery target.

### D12: Touchpad gestures consume Android KeyEvents, not raw touch

**Choice:** Glasses-app listens for `KeyEvent`s (DPAD_LEFT/RIGHT/UP/DOWN/CENTER, BACK, KEYCODE_CAMERA, KEYCODE_VOLUME_UP/DOWN) per the Rokid touchpad's documented behavior. Long-press-to-talk = long DPAD_CENTER. No raw `MotionEvent` parsing.

**Rationale:** `buildwithfenna/rokid-docs` documents only KeyEvent surfacing for the right-temple capacitive sensor; there is no documented gesture taxonomy beyond key codes.

### D13: APK sideload via documented `startUploadApk`

**Choice:** Phone-app bundles the glasses APK at build time and sideloads it on first launch via `CxrApi.startUploadApk(wifiAddress, ApkStatusCallback)`. This wraps Wi-Fi P2P discovery, multipart upload to `http://<glasses_ip>:8848`, install, and `openApp`.

**Rationale:** Documented Rokid API; same pattern clawsses uses but officially on-spec. Includes the WiFi-P2P stale-status workaround Rokid documents (reflection on `WifiP2pManager.deletePersistentGroup`).

### D14: Module layout — three Gradle + one Python

**Choice:**

```
hermes-on-glass/
├── shared/                    # Kotlin: protocol DTOs (both wires)
├── phone-app/                 # Android: WS↔Hermes + CXR-M bridge + chat UI
├── glasses-app/               # Android: HUD on Rokid (CXR-S + CXR-L)
└── hermes-channel-adapter/    # Python: BasePlatformAdapter + WS server
```

The phone-app's `preBuild` task bundles the glasses APK into `phone-app/src/main/assets/glasses-app-release.apk`. The Python adapter is a separate folder with its own `pyproject.toml`, installed under `~/.hermes/plugins/hermes-channel-adapter/`.

## Risks / Trade-offs

- **`BasePlatformAdapter` token-streaming surface unverified.** We expect to forward `assistant_chunk` deltas, but whether the framework gives outbound-streaming hooks at adapter level (vs. only complete messages or `edit_message` updates) needs a spike. Worst case: adapter coalesces and uses `edit_message` for visible progress, with the phone↔glasses `chat_stream` envelope unchanged. (OQ2.)
- **No streaming TTS to channels.** `tts_tool.py` writes complete audio files before delivery. Long replies wait for full render. Mitigation: keep replies short via instructions; consider contributing streaming TTS upstream later.
- **WebSocket route binding into the gateway's `aiohttp` app.** `webhook.py` shows `app.router.add_post(...)` works for adapters; we expect `add_get` with WS upgrade to work the same way, but spike before locking in. (OQ4.)
- **Single chat_id per phone+glasses pair, or one each?** The framework treats `chat_id` as the session boundary. Both phone-UI and glasses-HUD need to be the *same* chat (otherwise the user's typed phone message and spoken glasses message would land in separate Hermes sessions). We use a single `chat_id` per device pairing and route both surfaces to it. (OQ7.)
- **CXR Caps frame-size limit unknown.** Probe early in task 6.x; only add fragmenting if a real MTU surfaces. (OQ8.)
- **WiFi-P2P stale-status bug** in `CxrApi.startUploadApk` requires the Rokid-documented reflection workaround (`sdk-decompiled-reference.md:817-893`). We apply it from day one rather than after we hit the bug.
- **AuthorizationHelper requires `com.rokid.sprite.aiapp` ≥ versionCode 100000** for CXR-L. We document this as a glasses prerequisite; first-run flow checks the package and surfaces a setup screen if missing.
- **Tailscale dependency.** If Tailscale is down or the phone is off-tailnet, the WS doesn't connect. Mitigation: explicit "offline" state in HUD (`connection_update { connected: false }`); LAN-fallback config (`ws://<lan-ip>:<port>/glasses`).
- **Phone foreground-service requirement.** Persistent WS + CXR Bluetooth need a foreground service. Same as clawsses' `GlassesConnectionService`; standard pattern, request battery-optimization exemption on first run.
- **Rokid SDK is closed binary.** Pin SDK versions in `build.gradle.kts`; document upgrade procedure.
- **Display "monochrome green" is panel lore, not docs.** We say "480×640 portrait, single-color render" in the spec; no contrast-tuning assumption that depends on a specific hue.

## Migration Plan

This is a greenfield project; no existing system to migrate. Build order matches `tasks.md`:

1. **Hermes side first**: spike `BasePlatformAdapter` lifecycle, attachment handling, WS route binding, streaming hooks (resolves OQ2/OQ4). Confirm `cache_audio_from_bytes` import path. Get `glasses` showing in `hermes channels list` with a no-op echo adapter.
2. **Hardware bring-up**: Rokid pairing, `local.properties`, Tailscale up, Hermes gateway running.
3. **Protocol module**: `shared/` DTOs for both wires (WS frames + CXR Caps envelopes).
4. **Phone-app skeleton**: WS client (OkHttp WebSocket), settings screen, chat UI mirror, no glasses integration yet. Verifiable by typing a message from the phone and seeing a streaming reply from Hermes.
5. **Glasses-app skeleton**: HUD with text rendering, KeyEvent gesture routing, CXR-S subscribe, CXR-L `AuthorizationHelper` init. Sideload via `startUploadApk` and verify on hardware.
6. **Phone↔glasses bridge**: CXR-M ↔ CXR-S wired up. End-to-end text loop works (typed phone message → glasses HUD streaming reply).
7. **Voice loop**: glasses captures Opus on long-press, ships via CXR `sendStream` to phone, phone forwards via WS, adapter calls `cache_audio_from_bytes` + `handle_message`. Reply path: adapter overrides `send_voice` to push Opus down the WS, phone forwards to glasses, glasses plays via local speaker. Verify hands-free chat.
8. **Camera path**: CXR-L `takePhoto` → 256 KB JPEG → CXR Caps to phone → WS → channel adapter → multimodal `event`. Verify "what am I looking at?" works.
9. **Session picker**: Adapter exposes session list over WS; glasses picker + phone settings render it.
10. **Tool-progress indicator**: HUD subline.
11. **Wake-on-message**: cron job pushes through adapter; `wake_signal` flow on glasses.
12. **Hardening**: foreground-service notification copy, raw-events debug log, README docs.

There is no rollback strategy — the user can simply not install the APKs.

## Open Questions

### Resolved

- **OQ1 — clawsses license: AGPL-3.0** (verified via `gh api repos/dweddepohl/clawsses`). Forking or vendoring any clawsses code would force this entire project to AGPL. The proposal's existing non-goal "no fork or vendoring of clawsses code" is therefore a *legal* constraint, not just an aesthetic one. Clean-room reimplementation of architecture/UX patterns is fine — AGPL covers code expression, not ideas.
- **OQ2 — Outbound streaming hook (verified spike 1.5):** Framework streams via the **edit-message transport** (`gateway/stream_consumer.py`), not per-token callbacks. `GatewayStreamConsumer` calls `adapter.send(...)` for the first visible message, then `adapter.edit_message(chat_id, message_id, accumulated_text, finalize=...)` with rate limiting (1s edit interval, 40-char threshold). Mapping is documented in D8.
- **OQ3 — Session model (verified spike 1.4):** `chat_id` is an adapter-controlled string. The framework builds `session_key` from `(platform, chat_id)` plus optional `thread_id` (`gateway/session.py:build_session_key`). One `SessionStore` entry per `session_key`. To expose multiple Hermes sessions to a single phone+glasses pair, the adapter varies the `chat_id` per active conversation: scheme is `glasses:{conn_id}:{session_uuid}`. Switching sessions changes the suffix; the framework treats each suffix as a separate session.
- **OQ4 — aiohttp WS hosting (verified spike 1.2):** Adapters run their own standalone `aiohttp` listener inside `connect()` — `web.Application()` + `app.router.add_get('/glasses', self._handle_ws)` + `web.AppRunner` + `web.TCPSite(host, port)`. Not shared with a gateway-level app router. Pattern matches `gateway/platforms/webhook.py` (which does the same with `add_post`). Port is configurable via `config.yaml`.
- **OQ7 — Single `chat_id` per phone+glasses pair (verified spike 1.4):** The framework does not auto-split based on inbound surface — `chat_id` is whatever the adapter sets on the `MessageEvent.source`. We pick one `chat_id` per active session (per OQ3 resolution). Phone-typed and glasses-spoken messages on the same active session share one Hermes session by construction.

### Open

- **OQ5: TTS provider default.** Hermes' default is `edge` (free, no key). Confirm Edge gives acceptable voice quality on our test hardware; if not, surface ElevenLabs in the adapter config. Hardware-dependent — defer until §9 voice-loop testing.
- **OQ6: Foreground-service notification copy** ("Hermes glasses connected" — UX review). Defer to §13.2.
- **OQ8: CXR Caps per-frame size limit.** Probe with a binary-Caps payload of increasing size in §8.4. (Replaces previous draft's clawsses-derived 500-char assumption.)
- **OQ9: CXR-L audio codec values.** `startAudioStream(codecType)` integer values are not in the docs. Probe in §9.2; default to whatever delivers 16 kHz Opus.
