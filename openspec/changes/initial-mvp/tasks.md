## 1. Hermes-side spikes (resolve before locking the protocol)

- [ ] 1.1 Spike: confirm `BasePlatformAdapter` lifecycle — read `gateway/platforms/base.py` and one persistent-socket adapter (`discord.py` or `matrix.py`) end-to-end (resolves OQ2)
- [ ] 1.2 Spike: confirm `aiohttp` WebSocket route binding from a `BasePlatformAdapter` subclass (or fall back to a separate listener on a configurable port) (resolves OQ4)
- [ ] 1.3 Spike: confirm `cache_audio_from_bytes` import path and `MessageType.VOICE` event shape; reproduce Telegram's voice-flow with a no-op echo channel
- [ ] 1.4 Spike: confirm Hermes session/`chat_id` model in the channel framework — does the framework split inbound surfaces? do we get a session list API? (resolves OQ7, OQ3)
- [ ] 1.5 Spike: verify whether `BasePlatformAdapter` exposes outbound token-streaming hooks or whether we coalesce + `edit_message`
- [ ] 1.6 Confirm clawsses' license (resolves OQ1)

## 2. Hermes channel adapter (`hermes-channel-adapter/`)

- [ ] 2.1 Create Python module with `pyproject.toml`; install path `~/.hermes/plugins/hermes-channel-adapter/`
- [ ] 2.2 Subclass `BasePlatformAdapter`; implement `connect`, `disconnect`, `get_chat_info` no-ops; register channel name `glasses`
- [ ] 2.3 Add `aiohttp` WebSocket endpoint in `connect()` with `Authorization: Bearer <SHARED_SECRET>` upgrade check
- [ ] 2.4 Implement `client_hello` → session allocation → `server_welcome` handshake
- [ ] 2.5 Implement inbound `user_message` (text) → event → `handle_message`
- [ ] 2.6 Implement inbound `voice_note` (single-shot + chunked) → `cache_audio_from_bytes` → `MessageType.VOICE` event → `handle_message`
- [ ] 2.7 Implement inbound `image_attachment` (or `imageBase64` on `user_message`) → cache + `media_urls`
- [ ] 2.8 Implement outbound `send` → `assistant_chunk` + `assistant_complete` frames
- [ ] 2.9 Implement outbound `send_voice` / `play_tts` → `assistant_audio` frames
- [ ] 2.10 Implement outbound tool-progress hook → `tool_progress` frames
- [ ] 2.11 Implement Hermes-initiated push paths (`origin: cron-job | run-completion | agent-nudge`) → `push_message` frames
- [ ] 2.12 Implement `session_list` / `switch_session` / `new_session` round-trip
- [ ] 2.13 Implement `~/.hermes/plugins/hermes-channel-adapter/config.yaml` reader (`listen_host`, `listen_port`, `shared_secret`)
- [ ] 2.14 End-to-end: install the adapter, restart gateway, verify `glasses` shows in channel list, drive a turn from a CLI WS client and see a streaming reply

## 3. Project bring-up

- [ ] 3.1 Verify Tailscale on phone reaches Mac mini at `mac:<configured-port>` (curl WS upgrade from Termux)
- [ ] 3.2 Confirm Hermes voice config in `~/.hermes/config.yaml` (`stt.provider`, `tts.provider`, `voice.auto_tts`)
- [ ] 3.3 Confirm `com.rokid.sprite.aiapp` (versionCode ≥ 100000) is installed on the test glasses

## 4. Gradle scaffolding

- [ ] 4.1 Create root `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, gradle wrapper
- [ ] 4.2 Add three modules: `shared/`, `phone-app/`, `glasses-app/`
- [ ] 4.3 Configure Maven repo `https://maven.rokid.com/repository/maven-public/` and depend on `com.rokid.cxr:client-m:<pinned>` (phone), `com.rokid.cxr:cxr-service-bridge:<pinned>` (glasses CXR-S), and the CXR-L AIDL bindings (glasses)
- [ ] 4.4 Wire `phone-app:preBuild` to a `bundleGlassesApk` task that copies `glasses-app-debug.apk` into `phone-app/src/main/assets/glasses-app-release.apk`
- [ ] 4.5 Add `local.properties` template documenting `rokid.clientId`, `rokid.clientSecret`, `rokid.accessKey`; inject via `BuildConfig`
- [ ] 4.6 Set up `.gitignore` for `local.properties`, build outputs, IDE metadata

## 5. Shared protocol module (`shared/`)

- [ ] 5.1 Define phone↔channel WebSocket frame DTOs: `client_hello`, `server_welcome`, `user_message`, `voice_note`, `image_attachment`, `assistant_chunk`, `assistant_complete`, `assistant_audio`, `tool_progress`, `push_message`, `session_list`, `switch_session`, `new_session`, `slash_command`, `display_state`, `connection_update`, `ping`/`pong`
- [ ] 5.2 Define phone↔glasses CXR Caps envelope DTOs: `chat_message`, `agent_thinking`, `chat_stream`, `chat_stream_end`, `tool_progress`, `connection_update`, `session_list`, `wake_signal`, `voice_play`, `user_input`, `voice_capture`, `list_sessions`, `switch_session`, `slash_command`, `display_state`, `wake_ack`, `request_more_history`
- [ ] 5.3 Implement single `parseFrame(json)` entry point that returns null on malformed or unknown-type input
- [ ] 5.4 Unit-test parser against fixtures for every frame type and a few malformed cases

## 6. Phone-app skeleton + WebSocket client

- [ ] 6.1 Create Android app skeleton with Compose, navigation, dark theme
- [ ] 6.2 Build settings screen: Hermes WebSocket URL, shared secret (masked + tap-to-reveal), glasses pairing status, debug-events log toggle (no TTS provider — voice is server-side)
- [ ] 6.3 Persist shared secret in EncryptedSharedPreferences; rest in regular SharedPreferences
- [ ] 6.4 Implement `HermesWsClient` using OkHttp WebSocket: outbound upgrade with Bearer header, frame send/receive, exponential-backoff reconnect (start 1s, cap 30s, jittered), connection-state machine
- [ ] 6.5 Build a phone chat screen: history list, typed-input field, streaming-chunk render, tool-progress subline — drives `HermesWsClient` independently of glasses
- [ ] 6.6 Reflect channel-supplied `session_list`; let the user switch sessions from the phone UI

## 7. Glasses-app skeleton + HUD

- [ ] 7.1 Create Android app skeleton targeting Rokid AOSP, 480×640 portrait, minSdk 28, arm64-v8a
- [ ] 7.2 Build `HudScreen` with TopBar / ChatContentArea / MenuBar in Jetpack Compose
- [ ] 7.3 Configure JetBrains Mono font and auto-size based on target column count
- [ ] 7.4 Implement focus-area state machine (CONTENT / MENU) in a sealed class
- [ ] 7.5 Implement KeyEvent-based gesture handler for DPAD_LEFT/RIGHT/UP/DOWN/CENTER, BACK, KEYCODE_CAMERA; long-press detection on DPAD_CENTER
- [ ] 7.6 Implement HUD position cycle (Full → Bottom Half → Top Half) via SIZE menu
- [ ] 7.7 Stub `PhoneConnectionService` using CXR-S `CXRServiceBridge.subscribe(name, MsgReplyCallback)`; render `chat_message`, `chat_stream`, `chat_stream_end` envelopes
- [ ] 7.8 Render `tool_progress` envelope as a single subline beneath the active streaming message
- [ ] 7.9 Wire CXR-L `AuthorizationHelper` initialization; surface a setup error if `com.rokid.sprite.aiapp` is missing or below versionCode 100000

## 8. Phone↔glasses bridge

- [ ] 8.1 Implement `RokidSdkManager` on phone: discover device via CXR-M, AES/CBC/PKCS5Padding SN verification using `rokid.clientSecret`, cache encrypted SN
- [ ] 8.2 Implement `GlassesConnectionManager` on phone: state machine, auto-reconnect with 3-second delay
- [ ] 8.3 Implement `GlassesConnectionService` on phone as a foreground service with persistent notification
- [ ] 8.4 Probe Caps frame-size limit; if a real cap surfaces, add fragmenting; otherwise Caps frames carry full JSON unchunked (resolves OQ8)
- [ ] 8.5 Implement debug-mode WebSocket fallback on phone (port 8081) when `BuildConfig.DEBUG && isEmulator()`
- [ ] 8.6 Implement debug WebSocket client on glasses connecting to `10.0.2.2:8081`
- [ ] 8.7 Sideload glasses APK via `CxrApi.startUploadApk(wifiAddress, ApkStatusCallback)` on first launch; apply WiFi-P2P stale-status workaround (reflection on `WifiP2pManager.deletePersistentGroup`)
- [ ] 8.8 End-to-end: typed phone message → user_message frame → Hermes → assistant_chunk frames → chat_stream envelopes → glasses HUD

## 9. Voice loop (server-side, Telegram pattern)

- [ ] 9.1 On glasses: `MediaRecorder` Opus capture from `AudioSource.MIC` triggered by long-press DPAD_CENTER
- [ ] 9.2 Stream Opus chunks via CXR `sendStream` (or `startAudioStream` callback if codec int values are figured out — resolves OQ9) with a `voice_capture { streamId }` control envelope first
- [ ] 9.3 On phone: relay Opus chunks as `voice_note` WS frames; do not decode to PCM
- [ ] 9.4 On adapter: reassemble chunks, `cache_audio_from_bytes(..., ext=".ogg")`, `MessageType.VOICE` event, `handle_message`
- [ ] 9.5 On adapter: override `send_voice` / `play_tts` to emit `assistant_audio { id, bytesBase64 }` frames; phone forwards to glasses via CXR
- [ ] 9.6 On glasses: `MediaPlayer` plays Opus on local speaker (48 kHz)
- [ ] 9.7 End-to-end: hold-to-talk → STT → agent reply → TTS → glasses speaker. Verify `/voice on` / `/voice off` from the chat work via `slash_command` frames
- [ ] 9.8 Show recording overlay on glasses HUD during capture

## 10. Camera path

- [ ] 10.1 On glasses: bind CXR-L `IMediaStreamService` and call `takePhoto(width, height, quality)` from a menu entry
- [ ] 10.2 Receive JPEG via `IImageStreamCbk.onImageReceived(byte[])`
- [ ] 10.3 Downscale on glasses until JPEG ≤ 256 KB
- [ ] 10.4 Attach to next `user_input` envelope as `imageBase64`
- [ ] 10.5 On phone: forward as `user_message { imageBase64 }` frame
- [ ] 10.6 On adapter: materialize bytes to cache, set `event.media_urls` / `event.media_types`, `handle_message`
- [ ] 10.7 End-to-end: "what am I looking at?" returns a description

## 11. Session picker (channel-supplied)

- [ ] 11.1 Build `SessionPicker` Compose UI on glasses (highlights `currentSessionKey`)
- [ ] 11.2 Adapter sends `session_list` on connect, on switch, and on Hermes-side change
- [ ] 11.3 Glasses → phone `switch_session` → adapter `switch_session` → adapter re-emits `session_list`
- [ ] 11.4 Wire `slash_command { command: "/new-session" }` from glasses; adapter creates a new Hermes session and re-emits `session_list`
- [ ] 11.5 Phone settings allow rename/delete via the same `slash_command` channel

## 12. Wake-on-message coordination

- [ ] 12.1 Implement `WakeSignalManager` on phone with buffer + 3s ack timeout
- [ ] 12.2 Track display-state on glasses (active / asleep) via `ScreenStatusUpdateListener`; report via `display_state` envelope
- [ ] 12.3 Glasses respond to `wake_signal` with `wake_ack { ready: true | false }`
- [ ] 12.4 On wake timeout, deliver buffered message anyway and log
- [ ] 12.5 End-to-end: configure a Hermes cron job that delivers to `glasses` and verify it wakes the display and shows the message

## 13. Hardening + polish

- [ ] 13.1 Request battery-optimization exemption on first run
- [ ] 13.2 Add foreground-service notification copy (UX-reviewed) (resolves OQ6)
- [ ] 13.3 Add raw-events debug log (toggle in Developer settings) — both WS frames and CXR envelopes
- [ ] 13.4 Document Tailscale and LAN-fallback configurations in README
- [ ] 13.5 Document Rokid SDK credentials setup in README
- [ ] 13.6 Document adapter install steps in README
- [ ] 13.7 Add `connection_update { connected: false }` UX (offline overlay on glasses)
- [ ] 13.8 Manual smoke test on physical Rokid Glasses across MVP scenarios
