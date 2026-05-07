package dev.wallner.hermesonglass.shared

import com.google.gson.annotations.SerializedName

/**
 * Phone <-> glasses CXR envelope protocol.
 *
 * Phone sends control frames over Caps via `CXRServiceBridge` and bulk audio
 * via `sendStream`. The control payloads are JSON objects using a `type`
 * discriminator (mirrors [WsFrame] semantically but with HUD-specific shape).
 *
 * Direction (phone -> glasses) and (glasses -> phone) are unioned under
 * [CapsEnvelope] for routing convenience; [FrameParser.parseCapsEnvelope]
 * is the only entry point and returns null for unknown / malformed input.
 */
sealed interface CapsEnvelope {
    val type: String
}

// ─── phone -> glasses ───────────────────────────────────────────────────

/** Final assistant text destined for the chat content area. */
data class ChatMessage(
    @SerializedName("messageId") val messageId: String,
    @SerializedName("text") val text: String,
    @SerializedName("sessionKey") val sessionKey: String? = null,
) : CapsEnvelope {
    override val type: String = "chat_message"
}

/** Lightweight "thinking" / typing indicator. */
data class AgentThinking(
    @SerializedName("active") val active: Boolean,
) : CapsEnvelope {
    override val type: String = "agent_thinking"
}

/** Streaming chunk to append to an in-flight assistant message. */
data class ChatStream(
    @SerializedName("id") val id: String,
    @SerializedName("chunk") val chunk: String,
    @SerializedName("sessionKey") val sessionKey: String? = null,
) : CapsEnvelope {
    override val type: String = "chat_stream"
}

/** Marks the streamed message complete. */
data class ChatStreamEnd(
    @SerializedName("id") val id: String,
) : CapsEnvelope {
    override val type: String = "chat_stream_end"
}

/** Tool-progress subline below the active streaming message. */
data class CapsToolProgress(
    @SerializedName("messageId") val messageId: String,
    @SerializedName("toolName") val toolName: String,
    @SerializedName("phase") val phase: String,
    @SerializedName("preview") val preview: String? = null,
) : CapsEnvelope {
    override val type: String = "tool_progress"
}

/** WebSocket connectivity state (offline overlay on the HUD). */
data class CapsConnectionUpdate(
    @SerializedName("connected") val connected: Boolean,
) : CapsEnvelope {
    override val type: String = "connection_update"
}

/** Channel-supplied list of Hermes sessions for the picker. */
data class CapsSessionList(
    @SerializedName("sessions") val sessions: List<SessionInfo>,
    @SerializedName("currentSessionKey") val currentSessionKey: String,
) : CapsEnvelope {
    override val type: String = "session_list"
}

/** Wake the HUD because a Hermes-initiated push is incoming. */
data class WakeSignal(
    @SerializedName("reason") val reason: String,
    @SerializedName("messageId") val messageId: String? = null,
) : CapsEnvelope {
    override val type: String = "wake_signal"
}

/** Render a TTS audio blob via the local 48kHz speaker. */
data class VoicePlay(
    @SerializedName("id") val id: String,
    @SerializedName("bytesBase64") val bytesBase64: String,
    @SerializedName("ext") val ext: String = ".ogg",
) : CapsEnvelope {
    override val type: String = "voice_play"
}

// ─── glasses -> phone ───────────────────────────────────────────────────

/** User text or text+image input from the HUD. */
data class UserInput(
    @SerializedName("id") val id: String,
    @SerializedName("text") val text: String,
    @SerializedName("imageBase64") val imageBase64: String? = null,
) : CapsEnvelope {
    override val type: String = "user_input"
}

/** Control envelope announcing the start of an Opus stream over `sendStream`. */
data class VoiceCapture(
    @SerializedName("streamId") val streamId: String,
    @SerializedName("ext") val ext: String,
) : CapsEnvelope {
    override val type: String = "voice_capture"
}

/**
 * Full single-shot voice payload from the glasses' MediaRecorder run.
 *
 * MVP uses buffer-then-send rather than streaming chunks (resolves OQ9
 * conservatively): the glasses record to a tmp file on long-press, then
 * emit one [VoiceData] envelope on release. The phone forwards the bytes
 * as a single `voice_note` WS frame (totalChunks = 1) to the channel
 * adapter, which writes them via `cache_audio_from_bytes` and dispatches
 * a `MessageType.VOICE` event.
 *
 * Streaming chunks remain a future enhancement once `startAudioStream`'s
 * codec values are confirmed on hardware.
 */
data class VoiceData(
    @SerializedName("streamId") val streamId: String,
    @SerializedName("bytesBase64") val bytesBase64: String,
    @SerializedName("ext") val ext: String,
) : CapsEnvelope {
    override val type: String = "voice_data"
}

/** Glasses-side request for the channel session list. */
data class ListSessions(
    @SerializedName("requestId") val requestId: String? = null,
) : CapsEnvelope {
    override val type: String = "list_sessions"
}

/** User picked a session in the HUD picker. */
data class CapsSwitchSession(
    @SerializedName("sessionKey") val sessionKey: String,
) : CapsEnvelope {
    override val type: String = "switch_session"
}

/** Slash command originated from the HUD (e.g. /voice on, /new-session). */
data class CapsSlashCommand(
    @SerializedName("command") val command: String,
    @SerializedName("sessionKey") val sessionKey: String? = null,
) : CapsEnvelope {
    override val type: String = "slash_command"
}

/** Glasses report on screen state (active vs asleep). */
data class CapsDisplayState(
    @SerializedName("active") val active: Boolean,
) : CapsEnvelope {
    override val type: String = "display_state"
}

/** Response to wake_signal: was the HUD ready to render in time? */
data class WakeAck(
    @SerializedName("ready") val ready: Boolean,
    @SerializedName("messageId") val messageId: String? = null,
) : CapsEnvelope {
    override val type: String = "wake_ack"
}

/** Glasses-side request for older history (lazy-loaded scrollback). */
data class RequestMoreHistory(
    @SerializedName("sessionKey") val sessionKey: String,
    @SerializedName("limit") val limit: Int,
    @SerializedName("beforeMessageId") val beforeMessageId: String? = null,
) : CapsEnvelope {
    override val type: String = "request_more_history"
}
