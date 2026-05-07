package dev.wallner.hermesonglass.phone.domain

import dev.wallner.hermesonglass.phone.data.cxr.CapsLink
import dev.wallner.hermesonglass.phone.data.ws.WsConnection
import dev.wallner.hermesonglass.shared.AssistantAudio
import dev.wallner.hermesonglass.shared.AssistantChunk
import dev.wallner.hermesonglass.shared.AssistantComplete
import dev.wallner.hermesonglass.shared.CapsConnectionUpdate
import dev.wallner.hermesonglass.shared.CapsEnvelope
import dev.wallner.hermesonglass.shared.CapsSessionList
import dev.wallner.hermesonglass.shared.CapsSlashCommand
import dev.wallner.hermesonglass.shared.CapsSwitchSession
import dev.wallner.hermesonglass.shared.CapsToolProgress
import dev.wallner.hermesonglass.shared.ChatStream
import dev.wallner.hermesonglass.shared.ChatStreamEnd
import dev.wallner.hermesonglass.shared.ConnectionUpdate
import dev.wallner.hermesonglass.shared.NewSession
import dev.wallner.hermesonglass.shared.PushMessage
import dev.wallner.hermesonglass.shared.ServerWelcome
import dev.wallner.hermesonglass.shared.SessionList
import dev.wallner.hermesonglass.shared.SlashCommand
import dev.wallner.hermesonglass.shared.SwitchSession
import dev.wallner.hermesonglass.shared.ToolProgress
import dev.wallner.hermesonglass.shared.UserInput
import dev.wallner.hermesonglass.shared.UserMessage
import dev.wallner.hermesonglass.shared.VoiceData
import dev.wallner.hermesonglass.shared.VoiceNote
import dev.wallner.hermesonglass.shared.VoicePlay
import dev.wallner.hermesonglass.shared.WsFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Translates between the Hermes WebSocket frame protocol (phone <-> channel
 * adapter) and the CXR Caps envelope protocol (phone <-> glasses HUD).
 *
 * Direction summary:
 *   adapter -> phone (WsFrame)        ===>  phone -> glasses (CapsEnvelope)
 *   - AssistantChunk                       ChatStream
 *   - AssistantComplete                    ChatStreamEnd
 *   - ToolProgress                         CapsToolProgress
 *   - SessionList / ServerWelcome          CapsSessionList
 *   - PushMessage (cron / nudge)           handled by WakeSignalManager,
 *                                          which sends WakeSignal + ChatMessage
 *                                          with a 3 s ack timeout (§12)
 *   - ConnectionUpdate                     CapsConnectionUpdate
 *
 *   glasses -> phone (CapsEnvelope)   ===>  phone -> adapter (WsFrame)
 *   - UserInput { text, imageBase64 }      UserMessage
 *   - CapsSwitchSession                    SwitchSession
 *   - CapsSlashCommand                     SlashCommand (incl. "/new-session")
 *
 * Voice (CapsVoiceCapture, AudioStream) is wired in §9; bulk image upload
 * is wired in §10. Anything not in the maps above is dropped (logged at
 * debug level) so the bridge stays small and the failure mode is "feature
 * not implemented yet" rather than crash.
 */
class PhoneToGlassesBridge(
    private val ws: WsConnection,
    private val caps: CapsLink,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    private var wsJob: Job? = null
    private var capsJob: Job? = null

    fun start() {
        if (wsJob?.isActive == true) return
        wsJob = scope.launch {
            ws.frames.collect { downstream(it)?.let(caps::send) }
        }
        capsJob = scope.launch {
            caps.inbound.collect { upstream(it)?.let(ws::send) }
        }
    }

    fun stop() {
        wsJob?.cancel()
        capsJob?.cancel()
        wsJob = null
        capsJob = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    /** WS -> Caps. Public for unit testing the translation in isolation. */
    fun downstream(frame: WsFrame): CapsEnvelope? = when (frame) {
        is AssistantChunk -> ChatStream(
            id = frame.id,
            chunk = frame.chunk,
            sessionKey = frame.sessionKey,
        )
        is AssistantComplete -> ChatStreamEnd(id = frame.id)
        is ToolProgress -> CapsToolProgress(
            messageId = frame.messageId,
            toolName = frame.toolName,
            phase = frame.phase,
            preview = frame.preview,
        )
        is ServerWelcome -> CapsSessionList(
            sessions = frame.sessions,
            currentSessionKey = frame.currentSessionKey,
        )
        is SessionList -> CapsSessionList(
            sessions = frame.sessions,
            currentSessionKey = frame.currentSessionKey,
        )
        // PushMessage is handled by WakeSignalManager — needs the wake-ack
        // dance + buffered delivery. Returning null here so the bridge
        // doesn't double-emit and clobber the manager's coordinated send.
        is PushMessage -> null
        is ConnectionUpdate -> CapsConnectionUpdate(connected = frame.connected)
        is AssistantAudio -> VoicePlay(
            id = frame.id,
            bytesBase64 = frame.bytesBase64,
            ext = frame.ext,
        )
        else -> null
    }

    /** Caps -> WS. Public for unit testing the translation in isolation. */
    fun upstream(envelope: CapsEnvelope): WsFrame? = when (envelope) {
        is UserInput -> UserMessage(
            id = envelope.id,
            text = envelope.text,
            imageBase64 = envelope.imageBase64,
        )
        is CapsSwitchSession -> SwitchSession(sessionKey = envelope.sessionKey)
        is CapsSlashCommand -> if (envelope.command == "/new-session") {
            NewSession(name = null)
        } else {
            SlashCommand(command = envelope.command, sessionKey = envelope.sessionKey)
        }
        is VoiceData -> VoiceNote(
            id = envelope.streamId,
            streamId = envelope.streamId,
            chunkIndex = 0,
            totalChunks = 1,
            audioBase64 = envelope.bytesBase64,
            ext = envelope.ext,
        )
        else -> null
    }
}
