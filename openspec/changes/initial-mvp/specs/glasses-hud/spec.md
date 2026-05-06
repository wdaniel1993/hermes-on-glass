## ADDED Requirements

### Requirement: HUD renders chat content on the Rokid display

The glasses-app SHALL render a chat HUD on the Rokid 480×640 monochrome green micro-LED display using Jetpack Compose, with JetBrains Mono font and font size auto-calculated to hit a target column count.

#### Scenario: Initial render

- **WHEN** the HUD activity launches with no chat history
- **THEN** the display shows an empty chat area, a top bar with connection status, and a menu bar with Session / Size / Font / More items

#### Scenario: Streaming assistant message

- **WHEN** the phone sends a sequence of `chat_stream { id, chunk }` envelopes followed by `chat_stream_end { id }`
- **THEN** the HUD appends each chunk to the message with that id and removes the streaming cursor when `chat_stream_end` arrives

#### Scenario: Display position cycle

- **WHEN** the user activates the SIZE menu and confirms
- **THEN** the HUD position cycles Full → Bottom Half → Top Half and the SIZE icon previews the next position

### Requirement: Touchpad gestures route between CONTENT and MENU focus areas

The glasses-app SHALL route touchpad gestures to one of two focus areas (CONTENT or MENU) and SHALL provide an unconditional long-press-to-talk gesture in both areas.

#### Scenario: Content scroll and menu push-through

- **WHEN** focus is CONTENT and the user swipes backward at the top of the chat
- **THEN** focus moves to MENU

#### Scenario: Voice trigger from any focus

- **WHEN** the user long-presses the touchpad while focus is either CONTENT or MENU
- **THEN** the glasses-app sends `start_voice` to the phone and shows a voice-recording overlay

#### Scenario: Menu execute

- **WHEN** focus is MENU on a menu item and the user taps
- **THEN** the menu item executes (e.g., opens the session picker, cycles font size)

### Requirement: Camera capture is attachable to the next user message

The glasses-app SHALL allow the user to capture a photo via the on-board camera and attach it to the next outgoing user message as a base64-encoded JPEG no larger than 256 KB before base64 encoding.

#### Scenario: Capture and attach

- **WHEN** the user triggers camera capture from the menu and then sends a voice message
- **THEN** the resulting `user_input` envelope contains both `text` and `imageBase64` fields and the JPEG payload is at most 256 KB

#### Scenario: Oversize source

- **WHEN** the camera produces a frame larger than 256 KB after JPEG encoding
- **THEN** the glasses-app downscales the image until the JPEG is at most 256 KB before base64-encoding

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
- **THEN** the glasses-app wakes the display and sends `wake_ack { ready: true }` within 1 second

#### Scenario: Wake fails

- **WHEN** the display cannot be woken (hardware failure, system lock)
- **THEN** the glasses-app sends `wake_ack { ready: false }`

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
