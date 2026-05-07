package dev.wallner.hermesonglass.glasses.domain

import dev.wallner.hermesonglass.glasses.data.PhoneLink
import dev.wallner.hermesonglass.glasses.ui.hud.FocusArea
import dev.wallner.hermesonglass.glasses.ui.hud.HudIntent
import dev.wallner.hermesonglass.glasses.ui.hud.HudMessage
import dev.wallner.hermesonglass.glasses.ui.hud.HudPosition
import dev.wallner.hermesonglass.glasses.ui.hud.HudToolProgress
import dev.wallner.hermesonglass.glasses.ui.hud.HudUiState
import dev.wallner.hermesonglass.shared.AgentThinking
import dev.wallner.hermesonglass.shared.CapsConnectionUpdate
import dev.wallner.hermesonglass.shared.CapsEnvelope
import dev.wallner.hermesonglass.shared.CapsSessionList
import dev.wallner.hermesonglass.shared.CapsToolProgress
import dev.wallner.hermesonglass.shared.ChatMessage
import dev.wallner.hermesonglass.shared.ChatStream
import dev.wallner.hermesonglass.shared.ChatStreamEnd
import dev.wallner.hermesonglass.shared.WakeAck
import dev.wallner.hermesonglass.shared.WakeSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds the HUD UI state. Listens to [PhoneConnectionService.envelopes]
 * and translates each Caps envelope into a state mutation.
 */
class HudRepository(
    private val phone: PhoneLink,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val onPhotoRequested: () -> Unit = {},
    private val onNewSessionRequested: () -> Unit = {},
    private val onSessionPicked: (sessionKey: String) -> Unit = {},
) {
    private val _state = MutableStateFlow(HudUiState())
    val state: StateFlow<HudUiState> = _state.asStateFlow()

    private var collectorJob: Job? = null

    fun start() {
        if (collectorJob?.isActive == true) return
        collectorJob = scope.launch {
            phone.envelopes.collect { handle(it) }
        }
        scope.launch {
            phone.connected.collect { c -> _state.update { it.copy(phoneConnected = c) } }
        }
        phone.start()
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
        phone.stop()
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    fun setSetupError(error: String?) {
        _state.update { it.copy(setupError = error) }
    }

    /**
     * Apply a HUD intent (key-event-driven). Returns true if the intent
     * affected state.
     */
    fun applyIntent(intent: HudIntent): Boolean {
        return when (intent) {
            HudIntent.NavLeft -> moveMenuFocus(-1)
            HudIntent.NavRight -> moveMenuFocus(+1)
            HudIntent.NavUp -> moveSessionPicker(-1)
            HudIntent.NavDown -> moveSessionPicker(+1)
            HudIntent.TapCenter -> activateFocused()
            HudIntent.LongPressCenterDown -> {
                _state.update { it.copy(recordingVoice = true) }
                true
            }
            HudIntent.LongPressCenterUp -> {
                _state.update { it.copy(recordingVoice = false) }
                true
            }
            HudIntent.Back -> collapseFocus()
            HudIntent.Camera -> false  // future: hardware camera key fast-path
        }
    }

    private fun moveMenuFocus(delta: Int): Boolean {
        val focus = _state.value.focus
        if (focus is FocusArea.SessionPicker) {
            // Left/right inside the picker is meaningless; ignore.
            return false
        }
        if (focus !is FocusArea.Menu) {
            // Pull focus to the menu on first nav-left/right.
            _state.update { it.copy(focus = FocusArea.Menu(0)) }
            return true
        }
        val next = (focus.selectedIndex + delta).coerceIn(0, MENU_ITEMS.lastIndex)
        if (next == focus.selectedIndex) return false
        _state.update { it.copy(focus = FocusArea.Menu(next)) }
        return true
    }

    private fun moveSessionPicker(delta: Int): Boolean {
        val focus = _state.value.focus as? FocusArea.SessionPicker ?: return false
        val sessions = _state.value.sessions
        if (sessions.isEmpty()) return false
        val next = (focus.selectedIndex + delta).coerceIn(0, sessions.lastIndex)
        if (next == focus.selectedIndex) return false
        _state.update { it.copy(focus = FocusArea.SessionPicker(next)) }
        return true
    }

    private fun activateFocused(): Boolean {
        val focus = _state.value.focus
        return when (focus) {
            is FocusArea.Content -> {
                // Tap with content focus = open menu by selecting first item.
                _state.update { it.copy(focus = FocusArea.Menu(0)) }
                true
            }
            is FocusArea.Menu -> {
                val item = MENU_ITEMS.getOrNull(focus.selectedIndex) ?: return false
                executeMenuItem(item)
                true
            }
            is FocusArea.SessionPicker -> {
                val session = _state.value.sessions.getOrNull(focus.selectedIndex)
                if (session != null) {
                    onSessionPicked(session.sessionKey)
                    _state.update { it.copy(focus = FocusArea.Content) }
                }
                true
            }
        }
    }

    private fun executeMenuItem(item: MenuItem) {
        when (item) {
            MenuItem.SIZE -> _state.update { it.copy(position = it.position.next()) }
            MenuItem.SESSIONS -> openSessionPicker()
            MenuItem.PHOTO -> onPhotoRequested()
            MenuItem.NEW_CHAT -> onNewSessionRequested()
        }
    }

    private fun openSessionPicker() {
        val sessions = _state.value.sessions
        val initialIndex = sessions
            .indexOfFirst { it.sessionKey == _state.value.currentSessionKey }
            .coerceAtLeast(0)
        _state.update { it.copy(focus = FocusArea.SessionPicker(initialIndex)) }
    }

    private fun collapseFocus(): Boolean {
        val focus = _state.value.focus
        return when (focus) {
            is FocusArea.Menu -> {
                _state.update { it.copy(focus = FocusArea.Content) }
                true
            }
            is FocusArea.SessionPicker -> {
                _state.update { it.copy(focus = FocusArea.Content) }
                true
            }
            else -> false
        }
    }

    private fun handle(envelope: CapsEnvelope) {
        when (envelope) {
            is ChatMessage -> applyChatMessage(envelope)
            is ChatStream -> applyChatStream(envelope)
            is ChatStreamEnd -> applyChatStreamEnd(envelope)
            is CapsToolProgress -> applyToolProgress(envelope)
            is CapsConnectionUpdate -> {
                _state.update { it.copy(phoneConnected = envelope.connected) }
            }
            is CapsSessionList -> {
                _state.update { it.copy(
                    sessions = envelope.sessions,
                    currentSessionKey = envelope.currentSessionKey,
                ) }
            }
            is WakeSignal -> handleWakeSignal(envelope)
            is AgentThinking -> Unit  // could surface as a typing indicator
            else -> Unit
        }
    }

    private fun applyChatMessage(env: ChatMessage) {
        _state.update { s ->
            val idx = s.messages.indexOfFirst { it.id == env.messageId }
            val msg = HudMessage(
                id = env.messageId,
                role = HudMessage.Role.ASSISTANT,
                text = env.text,
                streaming = false,
            )
            val list = if (idx >= 0) {
                s.messages.toMutableList().apply { set(idx, msg) }
            } else {
                s.messages + msg
            }
            s.copy(messages = list)
        }
    }

    private fun applyChatStream(env: ChatStream) {
        _state.update { s ->
            val idx = s.messages.indexOfFirst { it.id == env.id }
            val list = if (idx >= 0) {
                val cur = s.messages[idx]
                s.messages.toMutableList().apply {
                    set(idx, cur.copy(text = cur.text + env.chunk, streaming = true))
                }
            } else {
                s.messages + HudMessage(
                    id = env.id,
                    role = HudMessage.Role.ASSISTANT,
                    text = env.chunk,
                    streaming = true,
                )
            }
            s.copy(messages = list)
        }
    }

    private fun applyChatStreamEnd(env: ChatStreamEnd) {
        _state.update { s ->
            val idx = s.messages.indexOfFirst { it.id == env.id }
            if (idx < 0) return@update s
            val cur = s.messages[idx]
            val list = s.messages.toMutableList().apply {
                set(idx, cur.copy(streaming = false))
            }
            val activeTool = s.activeToolProgress
                ?.takeUnless { it.messageId == env.id }
            s.copy(messages = list, activeToolProgress = activeTool)
        }
    }

    private fun handleWakeSignal(env: WakeSignal) {
        // §12: ack the wake so the phone-side WakeSignalManager can release
        // its buffered chat_message. We always report ready=true because the
        // HUD activity being alive (this code is running) is good enough
        // signal — the OS-level screen-on is documented as the SCREEN_OFF
        // listener path that lands when §12.2 is wired against real hardware.
        phone.send(WakeAck(ready = true, messageId = env.messageId))
    }

    private fun applyToolProgress(env: CapsToolProgress) {
        _state.update { s ->
            val next = if (env.phase.equals("completed", ignoreCase = true)) null
            else HudToolProgress(
                messageId = env.messageId,
                toolName = env.toolName,
                phase = env.phase,
                preview = env.preview,
            )
            s.copy(activeToolProgress = next)
        }
    }

    enum class MenuItem { SIZE, SESSIONS, PHOTO, NEW_CHAT }

    companion object {
        val MENU_ITEMS: List<MenuItem> = listOf(
            MenuItem.SIZE, MenuItem.SESSIONS, MenuItem.PHOTO, MenuItem.NEW_CHAT,
        )
        // Public accessor for the UI layer.
        @JvmField val MenuLabels: Map<MenuItem, String> = mapOf(
            MenuItem.SIZE to "SIZE",
            MenuItem.SESSIONS to "SESSIONS",
            MenuItem.PHOTO to "PHOTO",
            MenuItem.NEW_CHAT to "NEW",
        )
    }
}
