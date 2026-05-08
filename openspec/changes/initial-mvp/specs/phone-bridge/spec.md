## ADDED Requirements

### Requirement: Phone-app holds a single outbound WebSocket to the Hermes channel adapter

The phone-app SHALL maintain a single outbound WebSocket connection to the user-configured Hermes channel adapter URL with `Authorization: Bearer <SHARED_SECRET>` on the upgrade request. The shared secret SHALL be stored in Android EncryptedSharedPreferences. The phone-app SHALL NOT call any Hermes REST endpoint, including `/v1/responses`.

#### Scenario: Default WebSocket URL

- **WHEN** the phone-app launches with default settings
- **THEN** the configured WebSocket URL is `ws://mac:<configured-port>/glasses` (Tailscale MagicDNS) and is editable from the settings screen

#### Scenario: Missing shared secret

- **WHEN** the phone-app starts with no shared secret configured
- **THEN** the phone-app shows a settings prompt and does not attempt the WebSocket upgrade

#### Scenario: Server unreachable

- **WHEN** the WebSocket connection fails or drops
- **THEN** the phone-app surfaces an "offline" state to the glasses via `connection_update { connected: false }` within 5 seconds, and reconnects with exponential backoff (start 1s, cap 30s, jittered)

### Requirement: Phone-app forwards user turns as user_message frames

The phone-app SHALL convert glasses-originated `user_input` envelopes and phone-typed messages into `user_message` WebSocket frames; SHALL include text and optional image attachments in the frame; and SHALL emit a `voice_note` frame separately when audio is captured.

#### Scenario: Text turn from glasses

- **WHEN** the phone receives `user_input { text }` from the glasses
- **THEN** the phone-app sends a `user_message { id, text }` frame on the WebSocket

#### Scenario: Photo + text turn from glasses

- **WHEN** the phone receives `user_input { text, imageBase64 }` from the glasses
- **THEN** the phone-app sends a `user_message { id, text, imageBase64 }` frame whose `imageBase64` is at most 256 KB after JPEG encoding

#### Scenario: Phone-typed turn

- **WHEN** the user types and submits a message in the phone chat UI
- **THEN** the phone-app sends a `user_message { id, text }` frame and renders the message locally as pending in the chat history

### Requirement: Phone-app relays voice bytes between glasses and channel adapter

The phone-app SHALL relay Opus audio bytes between the glasses (via CXR `startAudioStream` / `sendStream`) and the channel adapter (via `voice_note` and `assistant_audio` WebSocket frames) without decoding to PCM and without invoking any phone-side STT or TTS engine.

#### Scenario: Long-press capture

- **WHEN** the glasses send a `voice_capture { streamId }` envelope followed by Opus chunks via CXR
- **THEN** the phone-app forwards the chunks as `voice_note { id, streamId, seq, bytesBase64, final }` frames on the WebSocket

#### Scenario: Assistant audio playback

- **WHEN** the WebSocket receives an `assistant_audio { id, bytesBase64 }` frame
- **THEN** the phone-app forwards the Opus bytes to the glasses via CXR (using `sendStream`) and emits a `voice_play { id, audioRef }` envelope so the glasses can play it via local `MediaPlayer`

### Requirement: Phone-app forwards assistant deltas to the glasses

The phone-app SHALL forward `assistant_chunk` WebSocket frames as `chat_stream` phone↔glasses envelopes, and SHALL emit `chat_stream_end` upon receiving `assistant_complete`.

#### Scenario: Forward assistant chunk

- **WHEN** the WebSocket emits `assistant_chunk { id: "r1", chunk: "<text>" }`
- **THEN** the phone-app sends `chat_stream { id: "r1", chunk: "<text>" }` to the glasses and appends to its own chat-mirror UI

#### Scenario: Stream completes

- **WHEN** the WebSocket emits `assistant_complete { id: "r1" }`
- **THEN** the phone-app sends `chat_stream_end { id: "r1" }` to the glasses and persists the full assistant message in local history

#### Scenario: Tool progress passthrough

- **WHEN** the WebSocket emits a `tool_progress { messageId, toolName, phase }` frame
- **THEN** the phone-app sends an envelope of identical shape to the glasses

### Requirement: Phone-app reflects the channel-supplied session list

The phone-app SHALL render the session list provided by the channel adapter via `session_list` frames and SHALL forward `switch_session` / `new_session` user actions back to the channel. The phone-app SHALL NOT maintain its own canonical session store.

#### Scenario: Session list arrives

- **WHEN** the WebSocket emits `session_list { sessions, currentSessionKey }`
- **THEN** the phone-app updates the picker view in both the phone UI and via `session_list` envelope to the glasses

#### Scenario: Switch session

- **WHEN** the user selects a different session in the glasses picker or phone UI
- **THEN** the phone-app sends `switch_session { sessionKey }` to the channel adapter and waits for an updated `session_list` before re-emitting locally

### Requirement: Phone-app bridges to glasses via Rokid CXR-M with debug WebSocket fallback

The phone-app SHALL connect to glasses via Rokid CXR-M Bluetooth in production builds and SHALL fall back to a localhost WebSocket server on port 8081 when running in a debug emulator build.

#### Scenario: Production CXR-M pairing

- **WHEN** a Rokid Glasses device is in pairing range and `BuildConfig.DEBUG` is false
- **THEN** the phone-app discovers the glasses via CXR-M, performs SN verification using AES/CBC/PKCS5Padding with the client secret (per Rokid docs), caches the encrypted SN in SharedPreferences, and reaches the `Connected` state

#### Scenario: Debug emulator fallback

- **WHEN** `BuildConfig.DEBUG` is true and the build runs on an emulator
- **THEN** the phone-app starts a WebSocket server on `127.0.0.1:8081` and accepts connections from the glasses-app debug client

### Requirement: Phone-app coordinates wake-on-message before delivering pushes

The phone-app SHALL send a `wake_signal` to the glasses before delivering any message that originated from a Hermes-side push (any `push_message` frame regardless of `origin`) and SHALL hold the message in a buffer until it receives `wake_ack { ready: true }` or 3 seconds elapse.

#### Scenario: Cron push while glasses asleep

- **WHEN** the WebSocket emits a `push_message { origin: "cron-job", ... }` and the last `display_state` from the glasses was `asleep`
- **THEN** the phone sends `wake_signal { reason: "cron_message", messageId }` first, waits for `wake_ack { ready: true }`, then sends the buffered `chat_message`

#### Scenario: Wake timeout

- **WHEN** no `wake_ack` arrives within 3 seconds of `wake_signal`
- **THEN** the phone delivers the buffered message anyway and logs a wake-failure metric

### Requirement: Phone-app sideloads the glasses APK on first launch

The phone-app SHALL bundle the glasses APK as an asset and SHALL sideload it to the paired glasses on first launch via `CxrApi.startUploadApk(wifiAddress, ApkStatusCallback)`, which performs Wi-Fi P2P discovery and multipart POST to `http://<glasses_ip>:8848`.

#### Scenario: First-launch install

- **WHEN** the phone-app launches and detects no glasses-app version on the paired device
- **THEN** the phone-app initiates Wi-Fi P2P pairing, applies the documented WiFi-P2P stale-status workaround (reflection on hidden `WifiP2pManager.deletePersistentGroup`), uploads the bundled APK, and reports install status to the user via the `ApkStatusCallback`

#### Scenario: Already installed

- **WHEN** the phone-app detects the glasses-app already installed and at the bundled version
- **THEN** the phone-app skips the upload and proceeds directly to CXR-M connection

### Requirement: Phone-app exposes chat-history and settings screens

The phone-app SHALL provide a chat screen showing the active conversation's history (mirroring what the glasses HUD shows), a typed-input affordance, and a settings screen with: Hermes WebSocket URL, shared secret (masked + tap-to-reveal), glasses pairing status, and a debug-events log toggle. The phone-app SHALL NOT expose any TTS provider or voice key — voice configuration lives on the Hermes server.

#### Scenario: Chat history mirrors glasses HUD

- **WHEN** the user opens the chat screen on the phone
- **THEN** the screen shows the same messages the glasses HUD has rendered for the active session, with streaming chunks appearing live as `assistant_chunk` frames arrive

#### Scenario: Phone-typed input

- **WHEN** the user types in the chat-screen input field and taps send
- **THEN** the phone-app sends a `user_message { id, text }` frame on the WebSocket and shows the message as pending in the local history

#### Scenario: Edit WebSocket URL

- **WHEN** the user edits the WebSocket URL in settings and saves
- **THEN** the phone-app validates the URL is well-formed (`ws://` or `wss://`), persists it, and re-establishes the WebSocket connection against the new URL

#### Scenario: Shared secret visibility

- **WHEN** the user views the settings screen
- **THEN** the shared secret is shown masked by default with a tap-to-reveal control
