package dev.wallner.hermesonglass.shared

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException

/**
 * Parses both wire formats (phone <-> channel WebSocket frames and phone
 * <-> glasses CXR Caps envelopes) from raw JSON, dispatching on the
 * `type` discriminator. All parse failures (invalid JSON, missing /
 * unknown / malformed `type`, schema mismatch) return null so callers
 * can drop the frame without crashing the connection.
 */
object FrameParser {

    private val gson: Gson = Gson()

    private val wsTypes: Map<String, Class<out WsFrame>> = mapOf(
        "client_hello" to ClientHello::class.java,
        "user_message" to UserMessage::class.java,
        "voice_note" to VoiceNote::class.java,
        "image_attachment" to ImageAttachment::class.java,
        "switch_session" to SwitchSession::class.java,
        "new_session" to NewSession::class.java,
        "slash_command" to SlashCommand::class.java,
        "display_state" to DisplayState::class.java,
        "ping" to Ping::class.java,
        "pong" to Pong::class.java,
        "server_welcome" to ServerWelcome::class.java,
        "assistant_chunk" to AssistantChunk::class.java,
        "assistant_complete" to AssistantComplete::class.java,
        "assistant_audio" to AssistantAudio::class.java,
        "tool_progress" to ToolProgress::class.java,
        "push_message" to PushMessage::class.java,
        "session_list" to SessionList::class.java,
        "connection_update" to ConnectionUpdate::class.java,
    )

    private val capsTypes: Map<String, Class<out CapsEnvelope>> = mapOf(
        "chat_message" to ChatMessage::class.java,
        "agent_thinking" to AgentThinking::class.java,
        "chat_stream" to ChatStream::class.java,
        "chat_stream_end" to ChatStreamEnd::class.java,
        "tool_progress" to CapsToolProgress::class.java,
        "connection_update" to CapsConnectionUpdate::class.java,
        "session_list" to CapsSessionList::class.java,
        "wake_signal" to WakeSignal::class.java,
        "voice_play" to VoicePlay::class.java,
        "user_input" to UserInput::class.java,
        "voice_capture" to VoiceCapture::class.java,
        "voice_data" to VoiceData::class.java,
        "list_sessions" to ListSessions::class.java,
        "switch_session" to CapsSwitchSession::class.java,
        "slash_command" to CapsSlashCommand::class.java,
        "display_state" to CapsDisplayState::class.java,
        "wake_ack" to WakeAck::class.java,
        "request_more_history" to RequestMoreHistory::class.java,
    )

    fun parseWsFrame(json: String): WsFrame? = parse(json, wsTypes)

    fun parseCapsEnvelope(json: String): CapsEnvelope? = parse(json, capsTypes)

    private fun <T> parse(json: String, types: Map<String, Class<out T>>): T? {
        if (json.isBlank()) return null
        val root = runCatching { gson.fromJson(json, JsonElement::class.java) }
            .getOrElse { return null }
        if (root !is JsonObject) return null
        val typeEl = root.get("type") ?: return null
        val typeName = if (typeEl.isJsonPrimitive && typeEl.asJsonPrimitive.isString) {
            typeEl.asString
        } else {
            return null
        }
        val cls = types[typeName] ?: return null
        return try {
            gson.fromJson(root, cls)
        } catch (_: JsonSyntaxException) {
            null
        } catch (_: JsonParseException) {
            null
        }
    }
}
