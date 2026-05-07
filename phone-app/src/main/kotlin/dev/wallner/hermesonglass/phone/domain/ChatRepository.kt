package dev.wallner.hermesonglass.phone.domain

import dev.wallner.hermesonglass.phone.data.ws.ConnectionState
import dev.wallner.hermesonglass.phone.data.ws.WsConnection
import dev.wallner.hermesonglass.shared.AssistantAudio
import dev.wallner.hermesonglass.shared.AssistantChunk
import dev.wallner.hermesonglass.shared.AssistantComplete
import dev.wallner.hermesonglass.shared.ConnectionUpdate
import dev.wallner.hermesonglass.shared.NewSession
import dev.wallner.hermesonglass.shared.PushMessage
import dev.wallner.hermesonglass.shared.ServerWelcome
import dev.wallner.hermesonglass.shared.SessionList
import dev.wallner.hermesonglass.shared.SwitchSession
import dev.wallner.hermesonglass.shared.ToolProgress
import dev.wallner.hermesonglass.shared.UserMessage
import dev.wallner.hermesonglass.shared.WsFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Owns chat state across the active connection. Reads inbound [WsFrame]s
 * and produces a [ChatUiState] snapshot the UI binds to.
 *
 * Built so the WS client is injectable — tests can drive frames through a
 * fake client without spinning up MockWebServer.
 */
class ChatRepository(
    private val wsClient: WsConnection,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = wsClient.state

    private var collectorJob: Job? = null

    fun start() {
        if (collectorJob?.isActive == true) return
        collectorJob = scope.launch {
            wsClient.frames.collect { handleFrame(it) }
        }
        wsClient.start()
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
        wsClient.stop()
    }

    fun shutdown() {
        stop()
        scope.cancel()
        wsClient.shutdown()
    }

    fun sendUserText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val id = newId()
        val sessionKey = _state.value.currentSessionKey
        val sent = wsClient.send(
            UserMessage(id = id, text = trimmed, sessionKey = sessionKey),
        )
        if (sent) {
            _state.update { s ->
                s.copy(messages = s.messages + ChatMessage(
                    id = id,
                    role = ChatMessage.Role.USER,
                    text = trimmed,
                ))
            }
        }
        return sent
    }

    fun switchSession(sessionKey: String): Boolean {
        wsClient.setLastSessionKey(sessionKey)
        return wsClient.send(SwitchSession(sessionKey))
    }

    fun newSession(name: String? = null): Boolean = wsClient.send(NewSession(name))

    /**
     * Send an adapter-managed slash command (e.g. /rename-session,
     * /delete-session). Goes via [dev.wallner.hermesonglass.shared.SlashCommand]
     * so the adapter sees a typed frame, not free-form text.
     */
    fun sendSlashCommand(command: String): Boolean {
        val sessionKey = _state.value.currentSessionKey
        return wsClient.send(
            dev.wallner.hermesonglass.shared.SlashCommand(
                command = command,
                sessionKey = sessionKey,
            ),
        )
    }

    private fun handleFrame(frame: WsFrame) {
        when (frame) {
            is ServerWelcome -> {
                wsClient.setLastSessionKey(frame.currentSessionKey)
                _state.update {
                    it.copy(
                        sessions = frame.sessions,
                        currentSessionKey = frame.currentSessionKey,
                    )
                }
            }
            is SessionList -> {
                wsClient.setLastSessionKey(frame.currentSessionKey)
                _state.update {
                    it.copy(
                        sessions = frame.sessions,
                        currentSessionKey = frame.currentSessionKey,
                    )
                }
            }
            is AssistantChunk -> applyAssistantChunk(frame)
            is AssistantComplete -> applyAssistantComplete(frame)
            is ToolProgress -> applyToolProgress(frame)
            is PushMessage -> applyPushMessage(frame)
            is AssistantAudio -> {
                // Audio playback is the glasses-app's job; phone-side renders
                // the chat transcript only. No-op here.
            }
            is ConnectionUpdate -> {
                // Adapter-side connectivity hint; UI primarily watches our
                // own [connectionState]. No-op for MVP.
            }
            else -> Unit
        }
    }

    private fun applyAssistantChunk(frame: AssistantChunk) {
        _state.update { s ->
            val existingIdx = s.messages.indexOfFirst { it.id == frame.id }
            val updated = if (existingIdx >= 0) {
                val current = s.messages[existingIdx]
                s.messages.toMutableList().apply {
                    set(existingIdx, current.copy(
                        text = current.text + frame.chunk,
                        state = ChatMessage.State.STREAMING,
                    ))
                }
            } else {
                s.messages + ChatMessage(
                    id = frame.id,
                    role = ChatMessage.Role.ASSISTANT,
                    text = frame.chunk,
                    state = ChatMessage.State.STREAMING,
                    parentId = frame.parentId,
                )
            }
            s.copy(messages = updated)
        }
    }

    private fun applyAssistantComplete(frame: AssistantComplete) {
        _state.update { s ->
            val idx = s.messages.indexOfFirst { it.id == frame.id }
            if (idx < 0) return@update s
            val current = s.messages[idx]
            val updated = s.messages.toMutableList().apply {
                set(idx, current.copy(state = ChatMessage.State.COMPLETE))
            }
            val activeTool = s.activeToolProgress
                ?.takeUnless { it.messageId == frame.id }
            s.copy(messages = updated, activeToolProgress = activeTool)
        }
    }

    private fun applyToolProgress(frame: ToolProgress) {
        _state.update { s ->
            // Drop the subline once a tool reports completed; otherwise it
            // becomes the new active tool indicator.
            val next = if (frame.phase.equals("completed", ignoreCase = true)) null
            else ToolProgressLine(
                messageId = frame.messageId,
                toolName = frame.toolName,
                phase = frame.phase,
                preview = frame.preview,
            )
            s.copy(activeToolProgress = next)
        }
    }

    private fun applyPushMessage(frame: PushMessage) {
        _state.update { s ->
            s.copy(messages = s.messages + ChatMessage(
                id = frame.messageId,
                role = ChatMessage.Role.SYSTEM,
                text = "[${frame.origin}] ${frame.text}",
            ))
        }
    }

    private fun newId(): String = UUID.randomUUID().toString().take(12)
}
