package dev.wallner.hermesonglass.phone.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.wallner.hermesonglass.phone.HermesApp
import dev.wallner.hermesonglass.phone.data.debug.DebugEventLog
import dev.wallner.hermesonglass.phone.data.prefs.HermesPrefs
import dev.wallner.hermesonglass.phone.data.rokid.SideloadEvent
import dev.wallner.hermesonglass.phone.data.ws.ConnectionState
import dev.wallner.hermesonglass.phone.domain.ChatUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val wsUrl: String,
    val sharedSecret: String,
    val secretRevealed: Boolean,
    val debugEventsEnabled: Boolean,
    val deviceId: String,
    val connectionState: ConnectionState,
)

/**
 * UI state for the "Glasses app" section in Settings — drives the install
 * status line and button enablement.
 */
sealed interface GlassesAppState {
    data object Unknown : GlassesAppState
    data object NotInstalled : GlassesAppState
    data object Installed : GlassesAppState
    data object Installing : GlassesAppState
    data object Launching : GlassesAppState
    data object Uninstalling : GlassesAppState
    data class Failed(val reason: String) : GlassesAppState
}

class SettingsViewModel(private val app: HermesApp) : ViewModel() {

    private val prefs: HermesPrefs get() = app.prefs

    private val _state = MutableStateFlow(snapshot(secretRevealed = false))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _glassesAppState = MutableStateFlow<GlassesAppState>(GlassesAppState.Unknown)
    val glassesAppState: StateFlow<GlassesAppState> = _glassesAppState.asStateFlow()

    val capsConnected: StateFlow<Boolean> get() = app.capsLink.connected

    val connectionState: StateFlow<ConnectionState> get() = app.repository.connectionState
    val chatState: StateFlow<ChatUiState> get() = app.repository.state

    init {
        // Sync the in-memory log toggle with persisted prefs at construction.
        DebugEventLog.enabled = prefs.debugEventsEnabled

        // Translate sideload events into UI state.
        app.apkSideloader?.let { sideloader ->
            viewModelScope.launch {
                sideloader.events.collect { event ->
                    when (event) {
                        is SideloadEvent.QueryAppResult ->
                            _glassesAppState.value =
                                if (event.installed) GlassesAppState.Installed
                                else GlassesAppState.NotInstalled
                        SideloadEvent.InstallSucceeded ->
                            _glassesAppState.value = GlassesAppState.Launching
                        SideloadEvent.InstallFailed ->
                            _glassesAppState.value = GlassesAppState.Failed("install failed — check phone Wi-Fi and that Hi Rokid sees the glasses")
                        is SideloadEvent.InstallPrecheckFailed ->
                            _glassesAppState.value = GlassesAppState.Failed(event.reason)
                        SideloadEvent.OpenAppSucceeded ->
                            _glassesAppState.value = GlassesAppState.Installed
                        SideloadEvent.OpenAppFailed ->
                            _glassesAppState.value = GlassesAppState.Failed("launch failed")
                        SideloadEvent.UninstallSucceeded ->
                            _glassesAppState.value = GlassesAppState.NotInstalled
                        SideloadEvent.UninstallFailed ->
                            _glassesAppState.value = GlassesAppState.Failed("uninstall failed")
                        is SideloadEvent.StopAppResult,
                        is SideloadEvent.GlassAppResume -> Unit
                    }
                }
            }
        }

        // Auto-query glasses-app install status the first time the link comes
        // up. Subsequent transitions are user-driven via [triggerInstall].
        viewModelScope.launch {
            app.capsLink.connected.collect { connected ->
                if (connected && _glassesAppState.value is GlassesAppState.Unknown) {
                    queryGlassesApp()
                }
            }
        }
    }

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

    /** Query whether the glasses-app is already installed on the glasses. */
    fun queryGlassesApp() {
        val sideloader = app.apkSideloader ?: run {
            _glassesAppState.value = GlassesAppState.Failed("authorize Hi Rokid first")
            return
        }
        sideloader.queryInstalled()
    }

    /** Stage and install the bundled glasses APK, then auto-launch it. */
    fun triggerInstall() {
        val sideloader = app.apkSideloader ?: run {
            _glassesAppState.value = GlassesAppState.Failed("authorize Hi Rokid first")
            return
        }
        _glassesAppState.value = GlassesAppState.Installing
        sideloader.installAndLaunch()
    }

    /** Uninstall the glasses-app from the glasses. */
    fun triggerUninstall() {
        val sideloader = app.apkSideloader ?: run {
            _glassesAppState.value = GlassesAppState.Failed("authorize Hi Rokid first")
            return
        }
        _glassesAppState.value = GlassesAppState.Uninstalling
        sideloader.uninstall()
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
