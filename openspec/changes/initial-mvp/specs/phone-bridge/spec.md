## ADDED Requirements

### Requirement: Phone-app connects to Hermes via Tailscale + Bearer auth

The phone-app SHALL issue HTTPS-or-HTTP requests to the user-configured Hermes base URL with an `Authorization: Bearer <API_SERVER_KEY>` header on every request, and SHALL store the API key in Android EncryptedSharedPreferences.

#### Scenario: Default Tailscale base URL

- **WHEN** the phone-app launches with default settings
- **THEN** the configured base URL is `http://mac:8642` (Tailscale MagicDNS) and is editable from the settings screen

#### Scenario: Missing API key

- **WHEN** the user opens the chat screen with no API key configured
- **THEN** the phone-app shows a settings prompt and does not issue Hermes requests

#### Scenario: Server unreachable

- **WHEN** a Hermes request fails with a network error
- **THEN** the phone-app surfaces an "offline" state to the glasses via `connection_update { connected: false }` within 5 seconds

### Requirement: Phone-app uses /v1/responses with named conversation for chat

The phone-app SHALL send chat turns as `POST /v1/responses` with `model`, `input`, `conversation: <currentSessionKey>`, and `stream: true`, and SHALL parse the SSE stream to forward token deltas to the glasses.

#### Scenario: Forward output_text.delta

- **WHEN** the SSE stream emits `response.output_text.delta` with `delta: "<chunk>"`
- **THEN** the phone-app sends `chat_stream { id: <responseId>, chunk: "<chunk>" }` to the glasses

#### Scenario: Stream completes

- **WHEN** the SSE stream emits `response.completed`
- **THEN** the phone-app sends `chat_stream_end { id: <responseId> }` to the glasses and persists the final assistant text in local history

#### Scenario: Tool progress passthrough

- **WHEN** the SSE stream emits a `hermes.tool.progress` event
- **THEN** the phone-app sends `tool_progress { messageId, toolName, phase }` to the glasses

### Requirement: Phone-app maintains the canonical conversation list

The phone-app SHALL maintain a local list of named Hermes conversations (the unit of "session"), SHALL allow create/rename/delete from the settings screen, and SHALL sync the list to the glasses via `session_list` whenever the list or active selection changes.

#### Scenario: First-run default session

- **WHEN** the phone-app launches for the first time
- **THEN** a single conversation named `glasses-default` exists and is the active session

#### Scenario: Glasses-initiated new session

- **WHEN** the phone receives `slash_command { command: "/new-session" }` from the glasses
- **THEN** the phone-app creates a new conversation named `glasses-<unix-timestamp>`, sets it as active, and sends an updated `session_list` to the glasses

#### Scenario: Switch session

- **WHEN** the phone receives `switch_session { sessionKey }` from the glasses
- **THEN** the phone-app sets that conversation as active, persists the choice, and uses that name in the `conversation` parameter on subsequent `/v1/responses` calls

### Requirement: Phone-app bridges to glasses via Rokid CXR-M with debug WebSocket fallback

The phone-app SHALL connect to glasses via Rokid CXR-M Bluetooth in production builds and SHALL fall back to a localhost WebSocket server on port 8081 when running in a debug emulator build.

#### Scenario: Production CXR-M pairing

- **WHEN** a Rokid Glasses device is in pairing range and `BuildConfig.DEBUG` is false
- **THEN** the phone-app discovers the glasses via CXR-M, performs SN verification using the AES-encrypted client secret, caches the encrypted SN in SharedPreferences, and reaches the `Connected` state

#### Scenario: Debug emulator fallback

- **WHEN** `BuildConfig.DEBUG` is true and the build runs on an emulator
- **THEN** the phone-app starts a WebSocket server on `127.0.0.1:8081` and accepts connections from the glasses-app debug client

### Requirement: Phone-app handles voice input via Android SpeechRecognizer

The phone-app SHALL initiate Android `SpeechRecognizer` upon receiving `start_voice` from the glasses, SHALL stream partial results to the glasses as `voice_state { listening, partialText }`, and SHALL submit the final transcript as a Hermes turn upon detection of speech end or after a 3-second silence.

#### Scenario: Long-press dictation

- **WHEN** the phone receives `start_voice` from the glasses
- **THEN** the phone starts `SpeechRecognizer`, streams partial results, and on final result issues a `/v1/responses` call with the transcript as `input`

#### Scenario: User cancels mid-listen

- **WHEN** the phone receives `cancel_voice` from the glasses while listening
- **THEN** the phone stops the recognizer, discards any partial result, and sends `voice_state { listening: false }`

### Requirement: Phone-app plays TTS for assistant replies when enabled

The phone-app SHALL play assistant replies through a configurable TTS engine (default Edge TTS, optional ElevenLabs) when the user has enabled TTS via the More menu on the glasses or via settings.

#### Scenario: TTS enabled and stream completes

- **WHEN** TTS is enabled and `response.completed` arrives
- **THEN** the phone-app submits the final assistant text to the configured TTS engine and plays the resulting audio through the phone speaker

#### Scenario: TTS toggle from glasses

- **WHEN** the phone receives `tts_toggle { enabled: <bool> }` from the glasses
- **THEN** the phone-app updates the persisted TTS-enabled setting, replies with `tts_state { enabled, voiceName }`, and applies the change to the next reply

### Requirement: Phone-app constructs image_url content parts for camera attachments

The phone-app SHALL convert `user_input { text, imageBase64 }` into a Hermes Responses API request whose `input` field is a multimodal content array containing the text part and an image part referencing the base64 JPEG as a `data:` URL.

#### Scenario: Photo + text turn

- **WHEN** the phone receives `user_input { text: "what is this?", imageBase64: "<b64>" }`
- **THEN** the outgoing `/v1/responses` request body's `input` includes both a text content part with `"what is this?"` and an image content part referencing `data:image/jpeg;base64,<b64>`

#### Scenario: Photo without text

- **WHEN** the phone receives `user_input { text: "", imageBase64: "<b64>" }`
- **THEN** the outgoing request includes only the image content part

### Requirement: Phone-app coordinates wake-on-message before delivering pushes

The phone-app SHALL send a `wake_signal` to the glasses before delivering any message that originated from a Hermes-side push (cron, plugin) and SHALL hold the message in a buffer until it receives `wake_ack { ready: true }` or 3 seconds elapse.

#### Scenario: Cron push while glasses asleep

- **WHEN** the phone receives a push from the channel-plugin and the last `connection_update.displayState` was `asleep`
- **THEN** the phone sends `wake_signal { reason: "cron_message", bufferedCount: 1, messageId }` first, waits for `wake_ack { ready: true }`, then sends the buffered `chat_message`

#### Scenario: Wake timeout

- **WHEN** no `wake_ack` arrives within 3 seconds of `wake_signal`
- **THEN** the phone delivers the buffered message anyway and logs a wake-failure metric

### Requirement: Phone-app exposes chat-mirror and settings screens

The phone-app SHALL provide a chat-mirror screen showing the active conversation's history and a settings screen with: Hermes server URL, API key (masked), TTS provider + voice + key, glasses pairing status, and session list management (create/rename/delete).

#### Scenario: Edit Hermes URL

- **WHEN** the user edits the Hermes URL field in settings and saves
- **THEN** the phone-app validates that the URL is well-formed, persists it, and re-establishes any open SSE connection against the new URL

#### Scenario: API key visibility

- **WHEN** the user views the settings screen
- **THEN** the API key is shown masked by default with a tap-to-reveal control
