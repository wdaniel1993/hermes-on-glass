## ADDED Requirements

### Requirement: Shared module defines Hermes wire DTOs for the Responses API subset

The `shared/` module SHALL define Kotlin data classes (with Gson `@SerializedName` annotations) representing the request body and SSE event subset of Hermes' `/v1/responses` API that the phone-app consumes.

#### Scenario: Request body shape

- **WHEN** the phone-app constructs a Responses API request
- **THEN** the request DTO includes fields for `model`, `input` (string or content-part array), `conversation`, `instructions`, `stream`, and `previous_response_id`

#### Scenario: SSE event taxonomy

- **WHEN** the phone-app parses a Hermes SSE event
- **THEN** the parser recognizes `response.created`, `response.output_text.delta`, `response.output_item.added`, `response.output_item.done`, `response.completed`, and `hermes.tool.progress`, and emits an `Unknown` variant for any other event type without failing

#### Scenario: Multimodal content parts

- **WHEN** the phone-app constructs an `input` array with both text and image
- **THEN** the DTO supports a discriminated union of `input_text { text }` and `input_image { image_url }` content parts where `image_url` accepts both remote `http(s)://` URLs and `data:image/...;base64,...` URLs

### Requirement: Shared module defines phone↔glasses envelopes used by both apps

The `shared/` module SHALL define DTOs for every JSON envelope exchanged between the phone-app and the glasses-app, with a `type` discriminator field on each envelope.

#### Scenario: Phone-to-glasses envelopes

- **WHEN** the phone-app sends an envelope to the glasses
- **THEN** the envelope is one of `chat_message`, `agent_thinking`, `chat_stream`, `chat_stream_end`, `tool_progress`, `connection_update`, `session_list`, `voice_state`, `voice_result`, `wake_signal`, `tts_state`

#### Scenario: Glasses-to-phone envelopes

- **WHEN** the glasses-app sends an envelope to the phone
- **THEN** the envelope is one of `user_input`, `list_sessions`, `switch_session`, `slash_command`, `start_voice`, `cancel_voice`, `wake_ack`, `tts_toggle`, `request_more_history`

### Requirement: Shared module provides a frame-parsing entry point

The `shared/` module SHALL expose a single function that takes a JSON string and returns either a typed envelope object or null when the frame is not recognized.

#### Scenario: Recognized frame

- **WHEN** `parseFrame(json)` is called with a string whose `type` field matches a known envelope
- **THEN** the function returns the corresponding typed object

#### Scenario: Malformed frame

- **WHEN** `parseFrame(json)` is called with a string that is not valid JSON or has no `type` field
- **THEN** the function returns null and does not throw

### Requirement: Streaming envelopes carry incremental chunks, not accumulated text

The `chat_stream` envelope SHALL carry a single incremental token chunk in its `chunk` field, and consumers SHALL append rather than replace.

#### Scenario: Incremental append semantics

- **WHEN** the glasses-app receives two `chat_stream { id: "r1", chunk: "Hello " }` and `chat_stream { id: "r1", chunk: "world" }` envelopes in order
- **THEN** the rendered message with id `r1` reads "Hello world"

### Requirement: Tool-progress envelopes carry the message id and a phase

The `tool_progress` envelope SHALL carry `messageId` (matching the streaming response id), `toolName`, and `phase` ∈ {`started`, `finished`}.

#### Scenario: Progress envelope round-trip

- **WHEN** the phone-app constructs a `tool_progress` envelope from a `hermes.tool.progress` SSE event
- **THEN** the envelope's `messageId` equals the parent response id, `toolName` equals the SSE event's tool name field, and `phase` equals one of the two enumerated values
