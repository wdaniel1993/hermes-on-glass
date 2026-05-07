package dev.wallner.hermesonglass.shared

import com.google.gson.annotations.SerializedName

/**
 * Phone <-> Hermes channel adapter WebSocket frames.
 *
 * Wire format mirrors hermes-channel-adapter/frames.py exactly:
 * a JSON object with a string `type` discriminator plus camelCased payload
 * fields. Inbound (channel -> phone) and outbound (phone -> channel) are
 * unioned under [WsFrame] for routing convenience; [FrameParser.parseWsFrame]
 * is the only entry point and returns null for unknown / malformed input.
 */
sealed interface WsFrame {
    val type: String
}

const val PROTOCOL_VERSION: Int = 1

// ─── phone -> adapter ───────────────────────────────────────────────────

data class ClientHello(
    @SerializedName("protocolVersion") val protocolVersion: Int,
    @SerializedName("deviceId") val deviceId: String? = null,
    @SerializedName("currentSessionKey") val currentSessionKey: String? = null,
) : WsFrame {
    override val type: String = "client_hello"
}

data class UserMessage(
    @SerializedName("id") val id: String,
    @SerializedName("text") val text: String,
    @SerializedName("sessionKey") val sessionKey: String? = null,
    @SerializedName("imageBase64") val imageBase64: String? = null,
) : WsFrame {
    override val type: String = "user_message"
}

data class VoiceNote(
    @SerializedName("id") val id: String,
    @SerializedName("streamId") val streamId: String,
    @SerializedName("chunkIndex") val chunkIndex: Int,
    @SerializedName("totalChunks") val totalChunks: Int,
    @SerializedName("audioBase64") val audioBase64: String,
    @SerializedName("ext") val ext: String = ".ogg",
    @SerializedName("sessionKey") val sessionKey: String? = null,
) : WsFrame {
    override val type: String = "voice_note"
}

data class ImageAttachment(
    @SerializedName("id") val id: String,
    @SerializedName("imageBase64") val imageBase64: String,
    @SerializedName("sessionKey") val sessionKey: String? = null,
) : WsFrame {
    override val type: String = "image_attachment"
}

data class SwitchSession(
    @SerializedName("sessionKey") val sessionKey: String,
) : WsFrame {
    override val type: String = "switch_session"
}

data class NewSession(
    @SerializedName("name") val name: String? = null,
) : WsFrame {
    override val type: String = "new_session"
}

data class SlashCommand(
    @SerializedName("command") val command: String,
    @SerializedName("sessionKey") val sessionKey: String? = null,
) : WsFrame {
    override val type: String = "slash_command"
}

data class DisplayState(
    @SerializedName("connected") val connected: Boolean,
    @SerializedName("active") val active: Boolean? = null,
) : WsFrame {
    override val type: String = "display_state"
}

data class Ping(
    @SerializedName("id") val id: String,
) : WsFrame {
    override val type: String = "ping"
}

data class Pong(
    @SerializedName("id") val id: String,
) : WsFrame {
    override val type: String = "pong"
}

// ─── adapter -> phone ───────────────────────────────────────────────────

data class SessionInfo(
    @SerializedName("sessionKey") val sessionKey: String,
    @SerializedName("label") val label: String? = null,
    @SerializedName("lastActiveAt") val lastActiveAt: Long? = null,
)

data class ServerWelcome(
    @SerializedName("protocolVersion") val protocolVersion: Int,
    @SerializedName("sessions") val sessions: List<SessionInfo>,
    @SerializedName("currentSessionKey") val currentSessionKey: String,
) : WsFrame {
    override val type: String = "server_welcome"
}

data class AssistantChunk(
    @SerializedName("id") val id: String,
    @SerializedName("chunk") val chunk: String,
    @SerializedName("parentId") val parentId: String? = null,
    @SerializedName("sessionKey") val sessionKey: String? = null,
) : WsFrame {
    override val type: String = "assistant_chunk"
}

data class AssistantComplete(
    @SerializedName("id") val id: String,
    @SerializedName("sessionKey") val sessionKey: String? = null,
) : WsFrame {
    override val type: String = "assistant_complete"
}

data class AssistantAudio(
    @SerializedName("id") val id: String,
    @SerializedName("bytesBase64") val bytesBase64: String,
    @SerializedName("ext") val ext: String = ".ogg",
    @SerializedName("sessionKey") val sessionKey: String? = null,
) : WsFrame {
    override val type: String = "assistant_audio"
}

data class ToolProgress(
    @SerializedName("messageId") val messageId: String,
    @SerializedName("toolName") val toolName: String,
    @SerializedName("phase") val phase: String,
    @SerializedName("preview") val preview: String? = null,
    @SerializedName("sessionKey") val sessionKey: String? = null,
) : WsFrame {
    override val type: String = "tool_progress"
}

data class PushMessage(
    @SerializedName("origin") val origin: String,
    @SerializedName("sessionKey") val sessionKey: String,
    @SerializedName("messageId") val messageId: String,
    @SerializedName("text") val text: String,
    @SerializedName("jobId") val jobId: String? = null,
    @SerializedName("runId") val runId: String? = null,
    @SerializedName("conversation") val conversation: String? = null,
) : WsFrame {
    override val type: String = "push_message"
}

data class SessionList(
    @SerializedName("sessions") val sessions: List<SessionInfo>,
    @SerializedName("currentSessionKey") val currentSessionKey: String,
) : WsFrame {
    override val type: String = "session_list"
}

data class ConnectionUpdate(
    @SerializedName("connected") val connected: Boolean,
) : WsFrame {
    override val type: String = "connection_update"
}
