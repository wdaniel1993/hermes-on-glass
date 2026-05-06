## 1. Project bring-up

- [ ] 1.1 Verify Tailscale on phone reaches Mac mini at `mac:8642` (curl `/health` from phone or via Termux)
- [ ] 1.2 Configure Hermes on Mac mini: set `API_SERVER_ENABLED=true` and a strong `API_SERVER_KEY` in `~/.hermes/.env`, restart `hermes gateway`
- [ ] 1.3 Confirm Hermes Responses API responds to a hand-crafted curl with Bearer auth and named conversation, including SSE streaming
- [ ] 1.4 Spike: confirm exact Responses API content-part field names for `image_url` (resolves OQ2)
- [ ] 1.5 Spike: confirm whether `/v1/responses` 100-response LRU is per-conversation or global (resolves OQ3)
- [ ] 1.6 Confirm clawsses' license (resolves OQ1)

## 2. Gradle scaffolding

- [ ] 2.1 Create root `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, gradle wrapper
- [ ] 2.2 Add three modules: `shared/`, `phone-app/`, `glasses-app/`
- [ ] 2.3 Configure Maven repo `https://maven.rokid.com/repository/maven-public/` and depend on `com.rokid.cxr:client-m:1.0.8` (phone) and `com.rokid.cxr:cxr-service-bridge:1.0` (glasses)
- [ ] 2.4 Wire `phone-app:preBuild` to a `bundleGlassesApk` task that copies `glasses-app-debug.apk` into `phone-app/src/main/assets/glasses-app-release.apk`
- [ ] 2.5 Add `local.properties` template documenting `rokid.clientId`, `rokid.clientSecret`, `rokid.accessKey`; inject via `BuildConfig`
- [ ] 2.6 Set up `.gitignore` for `local.properties`, build outputs, IDE metadata

## 3. Shared protocol module (`shared/`)

- [ ] 3.1 Define Hermes Responses API request DTOs (`model`, `input` as string-or-content-array, `conversation`, `instructions`, `stream`, `previous_response_id`)
- [ ] 3.2 Define Hermes content-part DTOs (`input_text`, `input_image` with `image_url` string)
- [ ] 3.3 Define Hermes SSE event DTOs and a sealed class hierarchy (`response.created`, `response.output_text.delta`, `response.output_item.added`, `response.output_item.done`, `response.completed`, `hermes.tool.progress`, `Unknown`)
- [ ] 3.4 Implement SSE event parser that tolerates unknown event types
- [ ] 3.5 Define phone→glasses envelopes: `chat_message`, `agent_thinking`, `chat_stream`, `chat_stream_end`, `tool_progress`, `connection_update`, `session_list` (with `SessionInfo`), `voice_state`, `voice_result`, `wake_signal`, `tts_state`
- [ ] 3.6 Define glasses→phone envelopes: `user_input`, `list_sessions`, `switch_session`, `slash_command`, `start_voice`, `cancel_voice`, `wake_ack`, `tts_toggle`, `request_more_history`
- [ ] 3.7 Implement single `parseFrame(json)` entry point that returns null on malformed input

## 4. Phone-app skeleton + Hermes client

- [ ] 4.1 Create Android app skeleton with Compose, navigation, dark theme
- [ ] 4.2 Create settings screen: Hermes URL, API key (masked + tap-to-reveal), TTS provider/voice/key, glasses pairing status, conversation list management
- [ ] 4.3 Persist settings via EncryptedSharedPreferences for the API key, regular SharedPreferences for the rest
- [ ] 4.4 Implement `HermesClient` using OkHttp + okhttp-sse: `POST /v1/responses` with Bearer header, SSE stream parsing, connection-state machine
- [ ] 4.5 Implement `ConversationManager`: local SQLite (or DataStore) of named conversations, CRUD operations, active-session pointer
- [ ] 4.6 Build a chat-mirror screen on the phone that drives `HermesClient` end-to-end (type a message, see streaming reply) — independent of glasses
- [ ] 4.7 Surface tool-progress events in the chat mirror as a subline

## 5. Glasses-app skeleton + HUD

- [ ] 5.1 Create Android app skeleton targeting Rokid AOSP, 480×640 portrait
- [ ] 5.2 Build `HudScreen` with TopBar / ChatContentArea / MenuBar in Jetpack Compose
- [ ] 5.3 Configure JetBrains Mono font and auto-size based on target column count (Compact 70 / Normal 60 / Comfortable 50 / Large 40)
- [ ] 5.4 Implement focus-area state machine (CONTENT / MENU) in a sealed class
- [ ] 5.5 Implement `GestureHandler` for swipe / tap / double-tap / long-press detection on the touchpad
- [ ] 5.6 Implement HUD position cycle (Full → Bottom Half → Top Half) via SIZE menu
- [ ] 5.7 Stub `PhoneConnectionService` to receive frames; render `chat_message`, `chat_stream`, `chat_stream_end` envelopes
- [ ] 5.8 Render `tool_progress` envelope as a single subline beneath the active streaming message

## 6. Phone↔glasses bridge

- [ ] 6.1 Implement `RokidSdkManager` on phone: discover device via CXR-M, AES SN verification using `rokid.clientSecret`, cache encrypted SN
- [ ] 6.2 Implement `GlassesConnectionManager` on phone: state machine, auto-reconnect with 3-second delay
- [ ] 6.3 Implement `GlassesConnectionService` on phone as a foreground service with persistent notification
- [ ] 6.4 Implement debug-mode WebSocket server on phone (port 8081) when `BuildConfig.DEBUG && isEmulator()`
- [ ] 6.5 Implement `PhoneConnectionService` on glasses using CXR-S
- [ ] 6.6 Implement debug WebSocket client on glasses connecting to `10.0.2.2:8081`
- [ ] 6.7 End-to-end: send a typed message from glasses → phone → Hermes → stream back to glasses HUD
- [ ] 6.8 Sideload glasses APK via Rokid WiFi P2P from phone-app on first launch

## 7. Voice loop

- [ ] 7.1 Implement `VoiceRecognitionManager` on phone wrapping Android `SpeechRecognizer`
- [ ] 7.2 Wire long-press on glasses → `start_voice` → phone listens → `voice_state` partial results → `voice_result` final
- [ ] 7.3 Auto-submit voice result as a Hermes turn (no manual confirm)
- [ ] 7.4 Implement `cancel_voice` flow
- [ ] 7.5 Implement Edge TTS client (default) and ElevenLabs client (optional, key-gated)
- [ ] 7.6 Implement `TtsPlaybackManager` that plays final assistant text on `response.completed`
- [ ] 7.7 Wire `tts_toggle` from glasses More menu and persist
- [ ] 7.8 Show recording overlay on glasses HUD during listen

## 8. Camera path

- [ ] 8.1 Implement `CameraCapture` on glasses using CameraX, capture JPEG
- [ ] 8.2 Downscale on glasses until JPEG ≤ 256 KB before base64
- [ ] 8.3 Wire camera-capture menu entry → glasses captures → attaches to next `user_input`
- [ ] 8.4 Phone-side: convert `user_input { text, imageBase64 }` into Responses API content-part array (text + `data:image/jpeg;base64,…`)
- [ ] 8.5 End-to-end test: "what am I looking at?" returns a description

## 9. Session picker

- [ ] 9.1 Build `SessionPicker` Compose UI on glasses (highlights `currentSessionKey`)
- [ ] 9.2 Phone sends `session_list` on connect, on list change, and on switch
- [ ] 9.3 Glasses → phone `switch_session` updates active conversation
- [ ] 9.4 Wire `slash_command { command: "/new-session" }` to create `glasses-<unix-timestamp>` and switch
- [ ] 9.5 Phone settings allow rename/delete of conversations
- [ ] 9.6 On unknown stored response (404 from Hermes), phone re-creates the conversation transparently

## 10. Wake-on-message coordination

- [ ] 10.1 Implement `WakeSignalManager` on phone with buffer + ack timeout
- [ ] 10.2 Track display-state on glasses (active / asleep) and report via `connection_update.displayState`
- [ ] 10.3 Glasses respond to `wake_signal` with `wake_ack { ready: true | false }`
- [ ] 10.4 On wake timeout (3s), deliver buffered message anyway and log

## 11. MVP+1: Hermes channel plugin (`glasses-channel-plugin/`)

- [ ] 11.1 Spike: read `gateway/platforms/` source to confirm channel-extension API (resolves OQ4)
- [ ] 11.2 Create Python module with `pyproject.toml`, install path `~/.hermes/plugins/glasses-channel/`
- [ ] 11.3 Implement channel registration so `glasses` shows in Hermes' channel list
- [ ] 11.4 Implement webhook forwarder with Bearer auth, exponential-backoff retry (3 attempts)
- [ ] 11.5 Define payload schema with `origin`, `messageId`, `conversation`, `text`, plus origin-specific fields
- [ ] 11.6 Read config from `~/.hermes/plugins/glasses-channel/config.yaml`
- [ ] 11.7 Phone-app: implement local HTTP server (foreground-service-hosted, listens only on Tailscale interface) accepting webhook POSTs with shared-secret check
- [ ] 11.8 Phone-app: convert webhook POST into a `chat_message` envelope, route through `WakeSignalManager`
- [ ] 11.9 End-to-end: configure a Hermes cron job that delivers to `glasses` and verify it wakes the display and shows the message

## 12. Hardening + polish

- [ ] 12.1 Request battery-optimization exemption on first run
- [ ] 12.2 Add foreground-service notification copy (UX-reviewed)
- [ ] 12.3 Add raw-events debug log (toggle in Developer settings)
- [ ] 12.4 Document Tailscale and LAN-fallback configurations in README
- [ ] 12.5 Document Rokid SDK credentials setup in README
- [ ] 12.6 Document plugin install steps in README
- [ ] 12.7 Add a `connection_update { connected: false }` path with UX (offline overlay on glasses)
- [ ] 12.8 Manual smoke test on physical Rokid Glasses across MVP scenarios
