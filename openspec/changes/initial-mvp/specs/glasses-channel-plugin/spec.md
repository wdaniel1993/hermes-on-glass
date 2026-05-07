## ADDED Requirements

### Requirement: Adapter registers as a Hermes channel by subclassing BasePlatformAdapter

The `hermes-channel-adapter` Python module SHALL be installable via Hermes' plugin discovery (`~/.hermes/plugins/hermes-channel-adapter/`) and SHALL register a channel named `glasses` by subclassing `gateway/platforms/base.py:BasePlatformAdapter` and implementing the abstract methods `connect`, `disconnect`, `send`, and `get_chat_info`.

#### Scenario: Channel registration

- **WHEN** the user installs the adapter under `~/.hermes/plugins/hermes-channel-adapter/` and restarts the Hermes gateway
- **THEN** Hermes' channel registry includes `glasses` as an available delivery target and `connect()` is called by the gateway lifecycle

#### Scenario: Cron delivery

- **WHEN** a Hermes cron job is configured with delivery target `glasses` and fires
- **THEN** the framework calls `send(chat_id, content, ...)` on the adapter and the adapter pushes a `push_message` frame on the active WebSocket session for that `chat_id`

### Requirement: Adapter hosts the WebSocket endpoint

The adapter SHALL host a WebSocket endpoint that the phone-app connects to, accept upgrades only when the `Authorization: Bearer <SHARED_SECRET>` header matches the configured `shared_secret`, and reject all other upgrades with HTTP 401.

#### Scenario: Authorized client

- **WHEN** the phone-app initiates a WebSocket upgrade with a matching Bearer header
- **THEN** the adapter completes the handshake, allocates a session for that `chat_id`, and emits a `server_welcome` frame containing the current session list

#### Scenario: Unauthorized client

- **WHEN** any client initiates a WebSocket upgrade without or with an incorrect Bearer header
- **THEN** the adapter responds 401 and does not allocate a session

### Requirement: Adapter dispatches inbound text and voice via handle_message

The adapter SHALL convert inbound `user_message` frames into Hermes events and call `await self.handle_message(event)`. For `voice_note` frames, the adapter SHALL write the reassembled Opus bytes via `cache_audio_from_bytes(..., ext=".ogg")`, set `event.message_type = MessageType.VOICE` and `event.media_urls = [cached_path]`, then call `handle_message`. The adapter SHALL NOT transcribe inline — the agent loop calls the STT tool itself.

#### Scenario: Text-only user message

- **WHEN** the adapter receives a `user_message { id, text }` frame on the WebSocket
- **THEN** the adapter constructs an event with `message_type = MessageType.TEXT`, `text = <text>`, and `chat_id` derived from the session, then awaits `handle_message(event)`

#### Scenario: Voice-only user message

- **WHEN** the adapter receives a `voice_note` (single-shot or fully reassembled chunked) frame
- **THEN** the adapter writes the Opus bytes via `cache_audio_from_bytes`, sets `event.message_type = MessageType.VOICE` and `event.media_urls = [cached_path]`, and awaits `handle_message(event)`

#### Scenario: Image attachment

- **WHEN** the adapter receives a `user_message` frame with `imageBase64` set
- **THEN** the adapter materializes the image bytes to the cache, populates `event.media_urls` and `event.media_types` accordingly, and awaits `handle_message(event)`

### Requirement: Adapter pushes assistant output as channel-to-phone WS frames

The adapter SHALL override `send`, `send_voice`, and `play_tts` (and override or wrap `edit_message` for streaming-style updates) to push outbound content as WebSocket frames to the phone-app for the relevant `chat_id`.

#### Scenario: Text reply

- **WHEN** the framework calls `await adapter.send(chat_id, content, reply_to, metadata)`
- **THEN** the adapter emits an `assistant_chunk` frame (or a sequence of chunks if the framework provides streaming) followed by `assistant_complete { id }` on the WebSocket session matching `chat_id`

#### Scenario: Auto-TTS voice reply

- **WHEN** Hermes' shared response pipeline (`base.py:2792-2823`) renders a TTS file and calls `play_tts(chat_id, audio_path, ...)`
- **THEN** the adapter reads the rendered Opus file and emits an `assistant_audio { id, bytesBase64 }` frame on the WebSocket session matching `chat_id`

#### Scenario: Tool progress event

- **WHEN** the framework dispatches a tool-progress message via `send` / `edit_message` (Hermes formats these as `"<emoji> <tool_name>: \"<preview>\"..."` text — see `gateway/run.py` around line 12860; there is no structured per-tool adapter hook today)
- **THEN** the adapter heuristically parses the formatted text, extracts `tool_name` and optional `preview`, and emits a `tool_progress { messageId, toolName, phase, preview }` frame instead of an `assistant_chunk`
- **AND** if the parse fails, the message falls through as a regular `assistant_chunk` (no false positives from ordinary assistant content)

### Requirement: Adapter exposes Hermes-initiated pushes with origin metadata

The adapter SHALL convert Hermes-initiated deliveries (cron jobs, run completions, agent nudges) into `push_message` frames with the originating type encoded in the `origin` field. The adapter exposes an explicit `push(chat_id, text, origin, ...)` helper for callers that know the origin, AND `send(chat_id, content, metadata)` SHALL route to a `push_message` frame whenever `metadata["origin"]` is one of `cron-job`, `run-completion`, or `agent-nudge`. (Hermes' cron / run-completion paths today call `send` without origin metadata; this design lets the adapter degrade gracefully — frames arrive as regular `assistant_chunk` until the framework grows origin-aware metadata.)

#### Scenario: Cron-origin push

- **WHEN** a caller invokes `push(chat_id, text, origin="cron-job", job_id=...)` or `send(chat_id, text, metadata={"origin": "cron-job", "job_id": ...})`
- **THEN** the adapter emits `push_message { origin: "cron-job", jobId, conversation, messageId, text }` on the WebSocket session matching `chat_id`

#### Scenario: Run-completion push

- **WHEN** a long-running task completes and is configured to notify the channel via `send` with `metadata["origin"] == "run-completion"` (or `push(..., origin="run-completion", run_id=...)`)
- **THEN** the adapter emits `push_message { origin: "run-completion", runId, conversation, messageId, text }`

#### Scenario: Agent-nudge push

- **WHEN** the agent itself originates an unsolicited message to the channel via `send` with `metadata["origin"] == "agent-nudge"` (or `push(..., origin="agent-nudge")`)
- **THEN** the adapter emits `push_message { origin: "agent-nudge", conversation, messageId, text }`

### Requirement: Adapter is configurable via a single YAML file

The adapter SHALL read its configuration from `~/.hermes/plugins/hermes-channel-adapter/config.yaml`, including `listen_host`, `listen_port`, and `shared_secret`. Voice-pipeline configuration (`stt.provider`, `tts.provider`, `voice.auto_tts`) SHALL be inherited from Hermes' `~/.hermes/config.yaml` and not duplicated in the adapter config.

#### Scenario: Missing config

- **WHEN** the adapter loads with no config file
- **THEN** the adapter logs a configuration error, does not register the channel, and the Hermes gateway continues to start

#### Scenario: Inherits voice settings

- **WHEN** the user configures `tts.provider: edge` in `~/.hermes/config.yaml`
- **THEN** the adapter renders TTS via Edge for glasses replies without any adapter-side TTS configuration
