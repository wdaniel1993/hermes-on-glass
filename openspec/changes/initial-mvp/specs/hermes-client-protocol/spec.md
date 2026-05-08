## ADDED Requirements

### Requirement: Shared module defines phone↔channel WebSocket frame DTOs

The `shared/` module SHALL define Kotlin data classes (with Gson `@SerializedName` annotations) representing every JSON frame exchanged on the WebSocket between the phone-app and the Hermes channel adapter, with a `type` discriminator field on each frame.

#### Scenario: Phone-to-channel frames

- **WHEN** the phone-app sends a frame to the channel adapter
- **THEN** the frame's `type` is one of `client_hello`, `user_message`, `voice_note`, `image_attachment`, `switch_session`, `new_session`, `slash_command`, `display_state`, `pong`

#### Scenario: Channel-to-phone frames

- **WHEN** the channel adapter sends a frame to the phone-app
- **THEN** the frame's `type` is one of `server_welcome`, `assistant_chunk`, `assistant_complete`, `tool_progress`, `assistant_audio`, `push_message`, `session_list`, `connection_update`, `ping`

#### Scenario: User message frame carries text and optional media

- **WHEN** the phone-app sends a `user_message` frame
- **THEN** the frame includes `id` (client-side message id), `text`, optional `imageBase64` (≤256 KB JPEG), and optional `audioRef` (handle to a previously uploaded `voice_note`)

#### Scenario: Voice note frame carries Opus bytes

- **WHEN** the phone-app sends a `voice_note` frame
- **THEN** the frame includes `id` and either `bytesBase64` (single-shot) or `streamId` + `seq` + `final` (chunked); the channel adapter reassembles and writes the bytes to the cache directory

#### Scenario: Assistant chunk carries incremental text

- **WHEN** the channel adapter sends an `assistant_chunk` frame
- **THEN** the frame includes `id` (parent assistant message id) and `chunk` (a single token-or-string delta), and consumers SHALL append rather than replace

#### Scenario: Assistant audio frame carries rendered TTS

- **WHEN** the channel adapter sends an `assistant_audio` frame
- **THEN** the frame includes `id` (parent assistant message id) and `bytesBase64` of the rendered Opus file

#### Scenario: Push message preserves origin

- **WHEN** the channel adapter sends a `push_message` frame for a Hermes-initiated push
- **THEN** the frame includes `origin` ∈ {`cron-job`, `run-completion`, `agent-nudge`}, the originating `conversation`, a stable `messageId`, and the rendered `text`

### Requirement: Shared module defines phone↔glasses Bluetooth envelopes

The `shared/` module SHALL define DTOs for every JSON envelope exchanged between the phone-app (CXR-M) and the glasses-app (CXR-S) over Caps, with a `type` discriminator field on each envelope.

#### Scenario: Phone-to-glasses envelopes

- **WHEN** the phone-app sends an envelope to the glasses
- **THEN** the envelope's `type` is one of `chat_message`, `agent_thinking`, `chat_stream`, `chat_stream_end`, `tool_progress`, `connection_update`, `session_list`, `wake_signal`, `voice_play` (carries an Opus audio reference for `MediaPlayer`)

#### Scenario: Glasses-to-phone envelopes

- **WHEN** the glasses-app sends an envelope to the phone
- **THEN** the envelope's `type` is one of `user_input`, `voice_capture` (carries an Opus stream id), `list_sessions`, `switch_session`, `slash_command`, `display_state`, `wake_ack`, `request_more_history`

### Requirement: Shared module provides a single frame-parsing entry point

The `shared/` module SHALL expose a single function that takes a JSON string and returns either a typed envelope object or null when the frame is not recognized.

#### Scenario: Recognized frame

- **WHEN** `parseFrame(json)` is called with a string whose `type` field matches a known envelope or WS frame
- **THEN** the function returns the corresponding typed object

#### Scenario: Malformed frame

- **WHEN** `parseFrame(json)` is called with a string that is not valid JSON or has no `type` field
- **THEN** the function returns null and does not throw

#### Scenario: Unknown type tolerated

- **WHEN** `parseFrame(json)` is called with a well-formed JSON whose `type` is not recognized (e.g., a future frame type)
- **THEN** the function returns null and the caller logs and ignores the frame

### Requirement: Streaming envelopes carry incremental chunks, not accumulated text

The phone↔glasses `chat_stream` envelope and the phone↔channel `assistant_chunk` frame SHALL carry a single incremental token chunk in their `chunk` field; consumers SHALL append rather than replace.

#### Scenario: Incremental append semantics

- **WHEN** the glasses-app receives `chat_stream { id: "r1", chunk: "Hello " }` followed by `chat_stream { id: "r1", chunk: "world" }`
- **THEN** the rendered message with id `r1` reads "Hello world"

### Requirement: Tool-progress frames carry message id and phase

The `tool_progress` WS frame and the `tool_progress` phone↔glasses envelope SHALL carry `messageId` (matching the streaming response id), `toolName`, and `phase` ∈ {`started`, `finished`}.

#### Scenario: Progress frame round-trip

- **WHEN** the channel adapter emits a `tool_progress` frame in response to a Hermes tool-call event
- **THEN** the frame's `messageId` equals the parent assistant message id, `toolName` is the tool's name, and `phase` is one of the two enumerated values

### Requirement: WebSocket auth uses a shared secret on the upgrade request

The phone-app SHALL include `Authorization: Bearer <SHARED_SECRET>` on the WebSocket upgrade request; the channel adapter SHALL reject upgrades whose header does not match the configured `shared_secret`.

#### Scenario: Authorized upgrade

- **WHEN** the phone-app initiates a WebSocket upgrade with a matching Bearer header
- **THEN** the channel adapter completes the handshake and emits a `server_welcome` frame

#### Scenario: Missing or wrong secret

- **WHEN** the phone-app initiates a WebSocket upgrade with a missing or incorrect Bearer header
- **THEN** the channel adapter rejects the upgrade with HTTP 401 and does not allocate a session
