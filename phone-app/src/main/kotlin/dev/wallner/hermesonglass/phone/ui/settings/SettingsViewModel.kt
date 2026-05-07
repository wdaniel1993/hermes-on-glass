package dev.wallner.hermesonglass.phone.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.wallner.hermesonglass.phone.HermesApp
import dev.wallner.hermesonglass.phone.data.debug.DebugEventLog
import dev.wallner.hermesonglass.phone.data.prefs.HermesPrefs
import dev.wallner.hermesonglass.phone.data.ws.ConnectionState
import dev.wallner.hermesonglass.phone.domain.ChatUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsUiState(
    val wsUrl: String,
    val sharedSecret: String,
    val secretRevealed: Boolean,
    val debugEventsEnabled: Boolean,
    val deviceId: String,
    val connectionState: ConnectionState,
)

class SettingsViewModel(private val app: HermesApp) : ViewModel() {

    private val prefs: HermesPrefs get() = app.prefs

    init {
        // Sync the in-memory log toggle with persisted prefs at construction.
        DebugEventLog.enabled = prefs.debugEventsEnabled
    }

    private val _state = MutableStateFlow(snapshot(secretRevealed = false))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    val connectionState: StateFlow<ConnectionState> get() = app.repository.connectionState
    val chatState: StateFlow<ChatUiState> get() = app.repository.state

    /**
     * Rename a session via the same `slash_command` channel the glasses
     * picker uses. The adapter intercepts and re-emits `session_list`.
     */
    fun renameSession(sessionKey: String, newLabel: String) {
        val args = if (newLabel.isBlank()) sessionKey else "$sessionKey $newLabel"
        app.repository.sendSlashCommand("/rename-session $args")
    }

    /** Delete a session. The adapter switches to the next remaining session
     *  (or allocates a new one) and re-emits `session_list`. */
    fun deleteSession(sessionKey: String) {
        app.repository.sendSlashCommand("/delete-session $sessionKey")
    }

    fun setWsUrl(value: String) {
        prefs.hermesWsUrl = value
        _state.value = snapshot(_state.value.secretRevealed)
    }

    fun setSharedSecret(value: String) {
        prefs.sharedSecret = value
        _state.value = snapshot(_state.value.secretRevealed)
    }

    fun toggleSecretRevealed() {
        _state.value = _state.value.copy(secretRevealed = !_state.value.secretRevealed)
    }

    fun setDebugEnabled(value: Boolean) {
        prefs.debugEventsEnabled = value
        DebugEventLog.enabled = value
        if (!value) DebugEventLog.clear()
        _state.value = snapshot(_state.value.secretRevealed)
    }

    fun applyAndReconnect() {
        app.rebuildRepository()
    }

    private fun snapshot(secretRevealed: Boolean): SettingsUiState = SettingsUiState(
        wsUrl = prefs.hermesWsUrl,
        sharedSecret = prefs.sharedSecret,
        secretRevealed = secretRevealed,
        debugEventsEnabled = prefs.debugEventsEnabled,
        deviceId = prefs.deviceId,
        connectionState = app.repository.connectionState.value,
    )

    companion object {
        fun factory(app: HermesApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(app) as T
            }
    }
}
