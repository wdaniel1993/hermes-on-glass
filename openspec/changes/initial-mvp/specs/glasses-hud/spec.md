## ADDED Requirements

### Requirement: HUD renders chat content on the Rokid display

The glasses-app SHALL render a chat HUD on the Rokid 480×640 portrait display using Jetpack Compose, with JetBrains Mono font and font size auto-calculated to hit a target column count.

#### Scenario: Initial render

- **WHEN** the HUD activity launches with no chat history
- **THEN** the display shows an empty chat area, a top bar with connection status, and a menu bar with Session / Size / Font / More items

#### Scenario: Streaming assistant message

- **WHEN** the phone sends a sequence of `chat_stream { id, chunk }` envelopes followed by `chat_stream_end { id }`
- **THEN** the HUD appends each chunk to the message with that id and removes the streaming cursor when `chat_stream_end` arrives

#### Scenario: Display position cycle

- **WHEN** the user activates the SIZE menu and confirms
- **THEN** the HUD position cycles Full → Bottom Half → Top Half and the SIZE icon previews the next position

### Requirement: Touchpad input is consumed via standard Android KeyEvents

The glasses-app SHALL handle touchpad gestures by listening to standard Android `KeyEvent`s on the right-temple touchpad (DPAD_LEFT/RIGHT/UP/DOWN/CENTER, BACK, KEYCODE_CAMERA), and SHALL provide an unconditional long-press-on-DPAD_CENTER for voice capture in any focus area. The glasses-app SHALL NOT depend on a non-standard gesture API.

#### Scenario: Content scroll and menu push-through

- **WHEN** focus is CONTENT and the user presses DPAD_LEFT (back-swipe key) at the top of the chat
- **THEN** focus moves to MENU

#### Scenario: Voice trigger from any focus

- **WHEN** the user long-presses DPAD_CENTER while focus is either CONTENT or MENU
- **THEN** the glasses-app starts a `MediaRecorder` Opus capture session, sends `voice_capture { streamId }` to the phone, streams Opus chunks via CXR, and shows a voice-recording overlay

#### Scenario: Menu execute

- **WHEN** focus is MENU on a menu item and the user taps DPAD_CENTER
- **THEN** the menu item executes (e.g., opens the session picker, cycles font size)

### Requirement: Camera capture uses CXR-L takePhoto and is attachable to the next user message

The glasses-app SHALL capture photos via Rokid CXR-L `takePhoto(width, height, quality)` (binding via `AuthorizationHelper` to `com.rokid.sprite.aiapp`'s `IMediaStreamService`) and SHALL attach the captured JPEG to the next outgoing user message as a base64-encoded payload no larger than 256 KB before base64 encoding.

#### Scenario: Capture and attach

- **WHEN** the user triggers camera capture from the menu and then sends a voice or text message
- **THEN** the resulting `user_input` envelope contains both `text` and `imageBase64` fields and the JPEG payload is at most 256 KB

#### Scenario: Oversize source

- **WHEN** the camera produces a frame larger than 256 KB after JPEG encoding
- **THEN** the glasses-app downscales the image until the JPEG is at most 256 KB before base64-encoding

#### Scenario: Authorization missing

- **WHEN** `AuthorizationHelper` reports that `com.rokid.sprite.aiapp` is missing or below the required versionCode
- **THEN** the glasses-app surfaces a setup error in the HUD and disables camera capture

### Requirement: Voice capture and playback use on-glasses audio

The glasses-app SHALL capture audio locally on the glasses using `MediaRecorder` (Opus encoder, microphone source) and SHALL play assistant audio replies locally via `MediaPlayer` on the on-glasses speaker. The glasses-app SHALL NOT route PCM through the phone speaker.

#### Scenario: Long-press voice capture

- **WHEN** the user long-presses DPAD_CENTER
- **THEN** the glasses-app starts an Opus-encoded `MediaRecorder` session reading from `AudioSource.MIC`, streams encoded chunks to the phone via CXR `sendStream` (or `startAudioStream` callback), and shows a recording overlay until release

#### Scenario: Assistant audio playback

- **WHEN** the phone delivers a `voice_play { id, audioRef }` envelope
- **THEN** the glasses-app plays the referenced Opus bytes via `MediaPlayer` on the local speaker

### Requirement: Session picker switches the active conversation

The glasses-app SHALL display a session picker listing the conversations sent by the phone via `session_list` and SHALL emit `switch_session { sessionKey }` when the user selects one.

#### Scenario: Open picker with current session highlighted

- **WHEN** the user activates the Session menu item
- **THEN** the picker opens and the entry whose `key` equals `currentSessionKey` is visually highlighted

#### Scenario: Create new session

- **WHEN** the user selects the "+ New session" entry in the picker
- **THEN** the glasses-app sends `slash_command { command: "/new-session" }` to the phone

### Requirement: Wake-on-message brings the display out of standby

The glasses-app SHALL respond to `wake_signal` envelopes from the phone by waking the display and replying with `wake_ack { ready: true }` once render is ready.

#### Scenario: Cron-message wake

- **WHEN** the display is in standby and the phone sends `wake_signal { reason: "cron_message" }`
- **THEN** the glasses-app wakes the display (via Activity `turnScreenOn=true`), sends `wake_ack { ready: true }` within 1 second, and reports the new state via `display_state { active }`

#### Scenario: Wake fails

- **WHEN** the display cannot be woken (hardware failure, system lock)
- **THEN** the glasses-app sends `wake_ack { ready: false }`

#### Scenario: Display sleep reported

- **WHEN** the system enters screen-off (via `setScreenOffTimeout` or user action)
- **THEN** the glasses-app emits `display_state { asleep }` to the phone via the `ScreenStatusUpdateListener` callback

### Requirement: Tool-progress events display as a single HUD subline

The glasses-app SHALL render `tool_progress` envelopes as a single subline beneath the currently-streaming assistant message, showing the tool name, and SHALL clear that subline on the matching `finished` event or on `chat_stream_end`.

#### Scenario: Tool starts

- **WHEN** the phone sends `tool_progress { messageId, toolName: "web_search", phase: "started" }`
- **THEN** the HUD shows a subline reading "⚙ web_search…" beneath that message

#### Scenario: Tool finishes before stream ends

- **WHEN** the phone sends `tool_progress { messageId, toolName: "web_search", phase: "finished" }`
- **THEN** the HUD removes the subline within one render frame

#### Scenario: Stream ends with tool still running

- **WHEN** `chat_stream_end { id }` arrives while a tool subline is shown for that id
- **THEN** the HUD removes the subline regardless of whether a `finished` event was received
