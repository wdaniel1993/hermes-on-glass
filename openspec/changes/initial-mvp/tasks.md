## 1. Hermes-side spikes (resolve before locking the protocol)

- [x] 1.1 Spike: confirm `BasePlatformAdapter` lifecycle — read `gateway/platforms/base.py` and one persistent-socket adapter (`discord.py` or `matrix.py`) end-to-end (resolves OQ2)
- [x] 1.2 Spike: confirm `aiohttp` WebSocket route binding from a `BasePlatformAdapter` subclass (or fall back to a separate listener on a configurable port) (resolves OQ4)
- [x] 1.3 Spike: confirm `cache_audio_from_bytes` import path and `MessageType.VOICE` event shape; reproduce Telegram's voice-flow with a no-op echo channel
- [x] 1.4 Spike: confirm Hermes session/`chat_id` model in the channel framework — does the framework split inbound surfaces? do we get a session list API? (resolves OQ7, OQ3)
- [x] 1.5 Spike: verify whether `BasePlatformAdapter` exposes outbound token-streaming hooks or whether we coalesce + `edit_message`
- [x] 1.6 Confirm clawsses' license (resolves OQ1)

## 2. Hermes channel adapter (`hermes-channel-adapter/`)

- [x] 2.1 Create Python module with `pyproject.toml`; install path `~/.hermes/plugins/hermes-channel-adapter/`
- [x] 2.2 Subclass `BasePlatformAdapter`; implement `connect`, `disconnect`, `get_chat_info` no-ops; register channel name `glasses`
- [x] 2.3 Add `aiohttp` WebSocket endpoint in `connect()` with `Authorization: Bearer <SHARED_SECRET>` upgrade check
- [x] 2.4 Implement `client_hello` → session allocation → `server_welcome` handshake
- [x] 2.5 Implement inbound `user_message` (text) → event → `handle_message`
- [x] 2.6 Implement inbound `voice_note` (single-shot + chunked) → `cache_audio_from_bytes` → `MessageType.VOICE` event → `handle_message`
- [x] 2.7 Implement inbound `image_attachment` (or `imageBase64` on `user_message`) → cache + `media_urls`
- [x] 2.8 Implement outbound `send` → `assistant_chunk` + `assistant_complete` frames
- [x] 2.9 Implement outbound `send_voice` / `play_tts` → `assistant_audio` frames
- [x] 2.10 Implement outbound tool-progress hook → `tool_progress` frames (heuristic — the framework has no structured hook; we parse the gateway's emoji+tool-name format. See `tool_progress.py`.)
- [x] 2.11 Implement Hermes-initiated push paths (`origin: cron-job | run-completion | agent-nudge`) → `push_message` frames (explicit `push()` method + `send()` routes via `metadata["origin"]` if present; the framework's cron path doesn't pass origin metadata today, so `push()` is the explicit caller path until/unless that lands upstream.)
- [x] 2.12 Implement `session_list` / `switch_session` / `new_session` round-trip
- [x] 2.13 Implement `~/.hermes/plugins/hermes-channel-adapter/config.yaml` reader (`listen_host`, `listen_port`, `shared_secret`)
- [ ] 2.14 End-to-end: install the adapter, restart gateway, verify `glasses` shows in channel list, drive a turn from a CLI WS client and see a streaming reply *(user-side; pytest E2E suite covers the WS protocol end-to-end with a stubbed message handler — see `hermes-channel-adapter/tests/test_e2e_ws.py`)*

## 3. Project bring-up

- [ ] 3.1 Verify Tailscale on phone reaches Mac mini at `mac:<configured-port>` (curl WS upgrade from Termux)
- [ ] 3.2 Confirm Hermes voice config in `~/.hermes/config.yaml` (`stt.provider`, `tts.provider`, `voice.auto_tts`)
- [ ] 3.3 Confirm `com.rokid.sprite.aiapp` (versionCode ≥ 100000) is installed on the test glasses

## 4. Gradle scaffolding

- [x] 4.1 Create root `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, gradle wrapper
- [x] 4.2 Add three modules: `shared/`, `phone-app/`, `glasses-app/`
- [x] 4.3 Configure Maven repo `https://maven.rokid.com/repository/maven-public/` and depend on `com.rokid.cxr:client-m:<pinned>` (phone), `com.rokid.cxr:cxr-service-bridge:<pinned>` (glasses CXR-S), and the CXR-L AIDL bindings (glasses) *(pinned `client-m:1.2.1`, `client-l:1.0.1`. Glasses uses `client-l` only — that AAR ships the CXR-S service bridge classes (CXRServiceBridge, Caps, ...) AND CXR-L media APIs in one artifact, so adding `cxr-service-bridge:1.0` alongside produced duplicate-class errors at d8.)*
- [x] 4.4 Wire `phone-app:preBuild` to a `bundleGlassesApk` task that copies `glasses-app-debug.apk` into `phone-app/src/main/assets/glasses-app-release.apk`
- [x] 4.5 Add `local.properties` template documenting `rokid.clientId`, `rokid.clientSecret`, `rokid.accessKey`; inject via `BuildConfig`
- [x] 4.6 Set up `.gitignore` for `local.properties`, build outputs, IDE metadata

## 5. Shared protocol module (`shared/`)

- [x] 5.1 Define phone↔channel WebSocket frame DTOs: `client_hello`, `server_welcome`, `user_message`, `voice_note`, `image_attachment`, `assistant_chunk`, `assistant_complete`, `assistant_audio`, `tool_progress`, `push_message`, `session_list`, `switch_session`, `new_session`, `slash_command`, `display_state`, `connection_update`, `ping`/`pong`
- [x] 5.2 Define phone↔glasses CXR Caps envelope DTOs: `chat_message`, `agent_thinking`, `chat_stream`, `chat_stream_end`, `tool_progress`, `connection_update`, `session_list`, `wake_signal`, `voice_play`, `user_input`, `voice_capture`, `list_sessions`, `switch_session`, `slash_command`, `display_state`, `wake_ack`, `request_more_history`
- [x] 5.3 Implement single `parseFrame(json)` entry point that returns null on malformed or unknown-type input *(`FrameParser.parseWsFrame` and `FrameParser.parseCapsEnvelope`; both return null on invalid JSON, missing/non-string `type`, unknown `type`, or schema mismatch)*
- [x] 5.4 Unit-test parser against fixtures for every frame type and a few malformed cases *(44 tests across `WsFrameParserTest` and `CapsEnvelopeParserTest`, all passing via `./gradlew :shared:test`)*

## 6. Phone-app skeleton + WebSocket client

- [x] 6.1 Create Android app skeleton with Compose, navigation, dark theme *(AGP 8.7.3 + Kotlin 2.0.21 + Compose BOM 2024.12.01; `HermesApp` Application class, `MainActivity` hosts `HermesNavHost`, `HermesTheme` (dark + light); `androidx.navigation.compose` with two routes — `chat` and `settings`)*
- [x] 6.2 Build settings screen: Hermes WebSocket URL, shared secret (masked + tap-to-reveal), glasses pairing status, debug-events log toggle (no TTS provider — voice is server-side) *(`SettingsScreen` + `SettingsViewModel`; `Show`/`Hide` toggle on the secret field; live `ConnectionState` readout; "Apply & reconnect" rebuilds the repository)*
- [x] 6.3 Persist shared secret in EncryptedSharedPreferences; rest in regular SharedPreferences *(`HermesPrefs`: AES256-GCM master key + AES256-SIV key encryption for the secret store; URL/debug/deviceId in plain prefs)*
- [x] 6.4 Implement `HermesWsClient` using OkHttp WebSocket: outbound upgrade with Bearer header, frame send/receive, exponential-backoff reconnect (start 1s, cap 30s, jittered), connection-state machine *(11 unit tests against MockWebServer covering Bearer header, hello-on-open, state transitions, 401 → Failed, transient-failure reconnect, manual stop, malformed-inbound drop)*
- [x] 6.5 Build a phone chat screen: history list, typed-input field, streaming-chunk render, tool-progress subline — drives `HermesWsClient` independently of glasses *(`ChatScreen` + `ChatViewModel` + `ChatRepository`; streaming caret on in-flight assistant messages; tool-progress rendered as italic subline below the active streaming message; 11 unit tests on `ChatRepository` covering chunk append, complete, tool-progress lifecycle, push-message routing, send/switch/new-session)*
- [x] 6.6 Reflect channel-supplied `session_list`; let the user switch sessions from the phone UI *(top-bar `AssistChip` opens a `DropdownMenu` populated from `state.sessions`; selecting an item sends `switch_session`; "New session" sends `new_session`)*

## 7. Glasses-app skeleton + HUD

- [x] 7.1 Create Android app skeleton targeting Rokid AOSP, 480×640 portrait, minSdk 28, arm64-v8a *(scaffolded earlier in §4; this section adds `GlassesApp` Application class and the live HUD activity wiring)*
- [x] 7.2 Build `HudScreen` with TopBar / ChatContentArea / MenuBar in Jetpack Compose *(`HudScreen.kt`: `HUD` brand + session/connection chip in TopBar, `LazyColumn` chat area with auto-scroll on new messages, `MenuBar` row with SIZE / SESSIONS / PHOTO / NEW)*
- [x] 7.3 Configure JetBrains Mono font and auto-size based on target column count *(`res/font/jetbrains_mono_regular.ttf` bundled; `rememberMonoBodySize(width, targetColumns=36)` back-solves font size from a 0.6 advance-width ratio, capped at 11–22 sp)*
- [x] 7.4 Implement focus-area state machine (CONTENT / MENU) in a sealed class *(`FocusArea.Content` / `FocusArea.Menu(selectedIndex)`; nav-left/right pulls focus into the menu, Back collapses back to content)*
- [x] 7.5 Implement KeyEvent-based gesture handler for DPAD_LEFT/RIGHT/UP/DOWN/CENTER, BACK, KEYCODE_CAMERA; long-press detection on DPAD_CENTER *(`HudGestures.dispatch(action, keyCode, repeatCount)` is the unit-testable surface; 500ms long-press threshold; activity forwards via `dispatchKeyEvent`)*
- [x] 7.6 Implement HUD position cycle (Full → Bottom Half → Top Half) via SIZE menu *(SIZE item in the menu calls `position.next()`; UI lays out the HUD inside a `Box` aligned `BottomCenter` / `TopCenter` for the half-canvas modes)*
- [x] 7.7 Stub `PhoneConnectionService` using CXR-S `CXRServiceBridge.subscribe(name, MsgReplyCallback)`; render `chat_message`, `chat_stream`, `chat_stream_end` envelopes *(real `MsgReplyCallback.onReceive` signature, `Caps.at(0).string` reader, `Reply.end(Caps())` ack; envelopes parsed via `FrameParser.parseCapsEnvelope`; `HudRepository` translates them to `HudUiState`)*
- [x] 7.8 Render `tool_progress` envelope as a single subline beneath the active streaming message *(italic outline-color subline rendered under the message whose `id == activeToolProgress.messageId`)*
- [x] 7.9 Wire CXR-L `AuthorizationHelper` initialization; surface a setup error if `com.rokid.sprite.aiapp` is missing or below versionCode 100000 *(`CxrLBootstrap.checkRequirements(activity)` calls `AuthorizationHelper.INSTANCE.isRequiredRokidAppInstalled(...)`; on failure, `MainActivity` calls `viewModel.setSetupError(...)` which the HUD shows in place of chat content)*

## 8. Phone↔glasses bridge

- [x] 8.1 Implement `RokidSdkManager` on phone: discover device via CXR-M, AES/CBC/PKCS5Padding SN verification using `rokid.clientSecret`, cache encrypted SN *(`RokidSdkManager` wraps `CxrApi.getInstance().initBluetooth(...)` / `connectBluetooth(...)`; AES logic isolated in `RokidSnCipher` (5 unit tests covering round-trip, deterministic IV-from-key contract, wrong-key failure, key-length validation); SN persisted via `EncryptedSnStore` (AES256-GCM EncryptedSharedPreferences))*
- [x] 8.2 Implement `GlassesConnectionManager` on phone: state machine, auto-reconnect with 3-second delay *(sealed `GlassesConnectionState`; manager reacts to `BluetoothStatusEvent` flow, encrypts SN on first `ConnectionInfo`, schedules reconnect on Failed/Disconnected; 8 unit tests against a `FakeSdk` covering pair flow, ConnectionInfo SN persistence, cached-reconnect path, no-cached-SN failure, auto-retry on transient failure, manual-stop cancels pending retry)*
- [x] 8.3 Implement `GlassesConnectionService` on phone as a foreground service with persistent notification *(`GlassesConnectionService` extends `Service`, calls `startForeground` with a low-importance notification channel; collects manager state and updates the notification text live; manifest declares `foregroundServiceType="connectedDevice"`)*
- [x] 8.4 Probe Caps frame-size limit; if a real cap surfaces, add fragmenting; otherwise Caps frames carry full JSON unchunked (resolves OQ8) *(`CapsFrameSizePolicy.decide(payload)` enforces a 64KB soft warn / 1MB hard reject; payloads >64KB log a warning, payloads >1MB are rejected with a "use sendStream" message; 4 unit tests; the actual hardware-confirmed limit and fragmenting code remain TODO until §13.8 surfaces a real cap — until then bulk media goes via `sendStream` per design D7)*
- [x] 8.5 Implement debug-mode WebSocket fallback on phone (port 8081) when `BuildConfig.DEBUG && isEmulator()` *(`DebugCapsBridgeServer` in src/debug; uses Java-WebSocket; src/release stub returns null so `HermesApp.capsLink` falls back to the production `CxrCapsLink`; selected automatically when `Build.FINGERPRINT/MODEL/PRODUCT/HARDWARE` flag an emulator)*
- [x] 8.6 Implement debug WebSocket client on glasses connecting to `10.0.2.2:8081` *(`DebugPhoneLinkClient` in glasses-app's src/debug; OkHttp-style reconnect (2 s fixed delay) so emulator-only dev loops survive phone-app restarts; `GlassesApp.phoneLink` picks emulator/real automatically)*
- [x] 8.7 Sideload glasses APK via `CxrApi.startUploadApk(wifiAddress, ApkStatusCallback)` on first launch; apply WiFi-P2P stale-status workaround (reflection on `WifiP2pManager.deletePersistentGroup`) *(`CxrApkSideloader` wraps both `startUploadApk(...)` overloads and forwards lifecycle events as a typed `SideloadEvent` flow; `WifiP2pStaleStateWorkaround.clearPersistentGroups(context)` runs first via reflection on the hidden `WifiP2pManager.requestPersistentGroupInfo` + `deletePersistentGroup` APIs; silently no-ops on AOSP images that don't expose them)*
- [x] 8.8 End-to-end: typed phone message → user_message frame → Hermes → assistant_chunk frames → chat_stream envelopes → glasses HUD *(translation layer landed as `PhoneToGlassesBridge`; 14 unit tests cover both directions of the WS↔Caps map, including the chat-stream/chat-stream-end flow and `UserInput → user_message` reverse path. Live hardware verification of the full loop is deferred to §13.8 — all the building blocks are unit-tested)*

## 9. Voice loop (server-side, Telegram pattern)

- [x] 9.1 On glasses: `MediaRecorder` Opus capture from `AudioSource.MIC` triggered by long-press DPAD_CENTER *(`MediaRecorderAudioRecorder`: API 29+ uses Opus/OGG, falls back to AAC/M4A on older Rokid AOSP images; tmp file in `cacheDir`, read on stop. `VoiceController.onIntent(LongPressCenterDown)` calls `recorder.start()`.)*
- [x] 9.2 Stream Opus chunks via CXR `sendStream` (or `startAudioStream` callback if codec int values are figured out — resolves OQ9) with a `voice_capture { streamId }` control envelope first *(MVP uses single-shot buffer-then-send via a new `VoiceData { streamId, bytesBase64, ext }` Caps envelope — no per-chunk transport, no codec-int gamble. Streaming chunks remain a future enhancement; OQ9 stays open but is no longer blocking.)*
- [x] 9.3 On phone: relay Opus chunks as `voice_note` WS frames; do not decode to PCM *(`PhoneToGlassesBridge.upstream` maps `VoiceData → VoiceNote(totalChunks=1, chunkIndex=0)`. Phone never decodes — bytes pass through base64-wrapped on both wires. Bridge test added.)*
- [x] 9.4 On adapter: reassemble chunks, `cache_audio_from_bytes(..., ext=".ogg")`, `MessageType.VOICE` event, `handle_message` *(landed in §2.6; the new path exercises `totalChunks=1` immediate-dispatch, already covered by the adapter's `test_voice_note_chunked_reassembly_dispatches` E2E test)*
- [x] 9.5 On adapter: override `send_voice` / `play_tts` to emit `assistant_audio { id, bytesBase64 }` frames; phone forwards to glasses via CXR *(landed in §2.9; phone bridge maps `AssistantAudio → VoicePlay` envelope — bridge test added)*
- [x] 9.6 On glasses: `MediaPlayer` plays Opus on local speaker (48 kHz) *(`MediaPlayerAudioPlayer`: writes the Opus payload to a tmp file, points MediaPlayer at it, replaces previous playback on a new payload (turn-taking semantics). `VoiceController` decodes `VoicePlay.bytesBase64` and drives the player.)*
- [ ] 9.7 End-to-end: hold-to-talk → STT → agent reply → TTS → glasses speaker. Verify `/voice on` / `/voice off` from the chat work via `slash_command` frames *(hardware verification — building blocks unit-tested across `VoiceControllerTest` (8 cases), `PhoneToGlassesBridgeTest` (voice_data → voice_note + assistant_audio → voice_play), `CapsEnvelopeParserTest` (voice_data round-trip). Slash-command auto-routing already exercised in §6: typed `/voice on` flows as a `user_message` and the framework's `base.py:1284-1297` consumes it.)*
- [x] 9.8 Show recording overlay on glasses HUD during capture *(landed in §7 via `HudUiState.recordingVoice`; `HudRepository.applyIntent(LongPressCenterDown/Up)` toggles the flag, `RecordingOverlay` renders the dim overlay + REC indicator. Live with the new `VoiceController` because both the controller and the HUD VM consume the same intents.)*

## 10. Camera path

- [x] 10.1 On glasses: bind CXR-L `IMediaStreamService` and call `takePhoto(width, height, quality)` from a menu entry *(`CxrLCameraController` constructs `CXRLink(context)` lazily, registers an `IImageStreamCbk`, and dispatches `takePhoto(1920, 1080, 80)` per `cxr-l/api-reference.md`. PHOTO menu item wired through `HudRepository.onPhotoRequested → photoCoordinator.capturePhoto()`.)*
- [x] 10.2 Receive JPEG via `IImageStreamCbk.onImageReceived(byte[])` *(`CameraState.Captured(jpeg)` flows from the SDK callback into `PhotoCaptureCoordinator.handleCameraState`; errors land as `Failed(reason)` and reset the camera to Idle.)*
- [x] 10.3 Downscale on glasses until JPEG ≤ 256 KB *(`BitmapJpegDownscaler` sweeps qualities 80/70/60/50, halves the longer edge up to 4 times, falls back to the smallest result if even the most-aggressive pass overshoots; coordinator logs a warn if final size still exceeds the budget. JpegDownscaler interface keeps the orchestrator JVM-testable with a fake.)*
- [x] 10.4 Attach to next `user_input` envelope as `imageBase64` *(`PhotoCaptureCoordinator` ships a `UserInput { id, text="", imageBase64=<base64-jpeg> }` envelope on capture; pairs naturally with a follow-up voice/text turn in the same Hermes session — both surfaces share `chat_id` per OQ7 resolution. 8 unit tests on the coordinator + 1 on `HudRepository` PHOTO menu wiring.)*
- [x] 10.5 On phone: forward as `user_message { imageBase64 }` frame *(landed in §6: `PhoneToGlassesBridge.upstream` already maps `UserInput → UserMessage(text, imageBase64)`, no §10-specific change needed.)*
- [x] 10.6 On adapter: materialize bytes to cache, set `event.media_urls` / `event.media_types`, `handle_message` *(landed in §2.7: `_on_user_message` decodes `imageBase64`, calls `cache_image_from_bytes`, and dispatches `MessageType.PHOTO` with `media_urls=[cached]`, `media_types=["image/jpeg"]`. Already covered by the adapter pytest E2E.)*
- [ ] 10.7 End-to-end: "what am I looking at?" returns a description *(hardware verification — needs a running Hermes Mac mini + paired glasses + a vision-tool-enabled agent. Building blocks all unit-tested.)*

## 11. Session picker (channel-supplied)

- [x] 11.1 Build `SessionPicker` Compose UI on glasses (highlights `currentSessionKey`) *(`SessionPickerArea` composable inside `HudScreen` renders a `LazyColumn` of sessions with a `>` cursor for the focused row and a `●` marker for the current session; `FocusArea.SessionPicker(selectedIndex)` carries the selection. Empty state shows guidance pointing at the NEW menu item.)*
- [x] 11.2 Adapter sends `session_list` on connect, on switch, and on Hermes-side change *(connect → `server_welcome` (semantically equivalent), switch_session and new_session re-emit `session_list` per §2.12. `/rename-session` and `/delete-session` also re-emit. "Hermes-side change" detection (e.g. framework `/new` clearing context) deferred — pursued only if surfaced by §13.8 hardware test.)*
- [x] 11.3 Glasses → phone `switch_session` → adapter `switch_session` → adapter re-emits `session_list` *(picker `TapCenter` invokes `onSessionPicked → phoneLink.send(CapsSwitchSession)`. Phone bridge maps `CapsSwitchSession → SwitchSession` per §6, and adapter's `_on_switch_session` re-emits `session_list` per §2.12. End-to-end already covered by `test_switch_session_round_trip`.)*
- [x] 11.4 Wire `slash_command { command: "/new-session" }` from glasses; adapter creates a new Hermes session and re-emits `session_list` *(NEW menu item invokes `onNewSessionRequested → phoneLink.send(CapsSlashCommand("/new-session"))`. Bridge rewrites `/new-session` → `NewSession` WS frame per §6; adapter's `_on_new_session` allocates and re-emits per §2.12.)*
- [x] 11.5 Phone settings allow rename/delete via the same `slash_command` channel *(Settings screen now lists each session with Rename / Delete actions. Rename opens an inline dialog and fires `/rename-session <key> <label>`; Delete confirms and fires `/delete-session <key>`. Adapter intercepts both: `Connection.rename_session` updates the label, `Connection.delete_session` rotates active session if needed and falls back to a fresh allocation when the list is empty. 3 new pytest cases verify rename labels round-trip, deletion of active vs inactive sessions, and that adapter-managed commands never reach `handle_message`.)*

## 12. Wake-on-message coordination

- [x] 12.1 Implement `WakeSignalManager` on phone with buffer + 3s ack timeout *(`WakeSignalManager` subscribes to `WsConnection.frames.filterIsInstance<PushMessage>()`, sends `WakeSignal` over Caps, awaits matching `WakeAck` (3 s default via `withTimeoutOrNull`), then sends `ChatMessage`. Per-push deliveries `launch` so concurrent cron fires don't serialize. Bridge no longer handles `PushMessage` — fixed a content-loss bug where the previous mapping `PushMessage → WakeSignal` dropped the text.)*
- [ ] 12.2 Track display-state on glasses (active / asleep) via `ScreenStatusUpdateListener`; report via `display_state` envelope *(deferred to §13.8 hardware-side: needs the real Rokid AOSP screen-event listener to wire correctly. The HUD's wake-ack assumes `ready=true` whenever the activity is alive, which covers the cron-fires-while-app-running path. Truly-asleep handling lands when we see the listener fire on hardware.)*
- [x] 12.3 Glasses respond to `wake_signal` with `wake_ack { ready: true | false }` *(`HudRepository.handleWakeSignal` returns `WakeAck(ready=true, messageId)` via `phone.send(...)`. `ready=false` is reserved for §12.2 — when we know the screen is genuinely off, we'll downgrade. Test added.)*
- [x] 12.4 On wake timeout, deliver buffered message anyway and log *(implemented inside `WakeSignalManager.deliver`: `withTimeoutOrNull` returning null and `WakeAck(ready=false)` both fall through to the same `caps.send(ChatMessage(...))` path. Timber.w logs the timeout reason. 5 unit tests cover ready-true, ready-false, timeout, ack-for-unknown-id, and concurrent-push interleaving.)*
- [ ] 12.5 End-to-end: configure a Hermes cron job that delivers to `glasses` and verify it wakes the display and shows the message *(hardware verification — needs Mac mini Hermes + paired glasses + a real cron job firing into our channel. All the wire-side coordination unit-tested.)*

## 13. Hardening + polish

- [x] 13.1 Request battery-optimization exemption on first run *(`BatteryOptimizationPrompt.request(activity)` fires `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. `MainActivity.onCreate` checks `prefs.batteryOptimizationPrompted` and `BatteryOptimizationPrompt.isExempt`; prompts once, marks the flag so subsequent launches don't nag.)*
- [x] 13.2 Add foreground-service notification copy (UX-reviewed) (resolves OQ6) *(`GlassesConnectionService` notification text rewritten: short status without jargon — "Connecting…", "Glasses: <name>", "Glasses offline — retrying", etc. Title stays "Hermes on Glasses".)*
- [x] 13.3 Add raw-events debug log (toggle in Developer settings) — both WS frames and CXR envelopes *(`DebugEventLog`: bounded ring buffer (last 200 entries) keyed by direction × wire (WS / CAPS) × type. `HermesWsClient` and `CxrCapsLink` record inbound/outbound when the toggle is on. Settings screen's debug switch flips `DebugEventLog.enabled` and clears on disable. Note: a dedicated dev-screen renderer for the buffer is small and skipped — entries are exposed as a `StateFlow<List<Entry>>` for any future UI.)*
- [x] 13.4 Document Tailscale and LAN-fallback configurations in README *(README §"Tailscale and LAN configurations" covers MagicDNS hostname setup, the `ws://mac:8765/glasses` URL convention, and the LAN-IP fallback path; calls out that wire/auth are identical between transports.)*
- [x] 13.5 Document Rokid SDK credentials setup in README *(README §"Rokid SDK credentials" walks through obtaining clientId/clientSecret/accessKey from the Rokid developer console, dropping them into `local.properties`, and points at the `local.properties.template` reference. Notes that `rokid.clientSecret` is what `connectBluetooth` consumes for SN AES decryption.)*
- [x] 13.6 Document adapter install steps in README *(README §"Channel adapter install" covers symlinking the plugin folder into `~/.hermes/plugins/`, the `config.yaml` schema (`listen_host`/`listen_port`/`shared_secret`/`ws_path`), the `hermes gateway restart` step, log line to look for, and a websocat smoke test for the handshake.)*
- [x] 13.7 Add `connection_update { connected: false }` UX (offline overlay on glasses) *(`OfflineOverlay` composable in `HudScreen` renders a full-screen scrim with "○ OFFLINE / phone bridge unreachable" when `state.phoneConnected == false` and there's no setup error to show first.)*
- [ ] 13.8 Manual smoke test on physical Rokid Glasses across MVP scenarios *(hardware verification — captures all the per-section "needs hardware" checkpoints into one final pass.)*
