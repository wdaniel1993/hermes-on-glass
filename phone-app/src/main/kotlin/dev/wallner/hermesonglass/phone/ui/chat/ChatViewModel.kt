package dev.wallner.hermesonglass.phone.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.wallner.hermesonglass.phone.HermesApp
import dev.wallner.hermesonglass.phone.data.ws.ConnectionState
import dev.wallner.hermesonglass.phone.domain.ChatRepository
import dev.wallner.hermesonglass.phone.domain.ChatUiState
import kotlinx.coroutines.flow.StateFlow

class ChatViewModel(private val app: HermesApp) : ViewModel() {

    private val repo: ChatRepository get() = app.repository

    val state: StateFlow<ChatUiState> get() = repo.state
    val connectionState: StateFlow<ConnectionState> get() = repo.connectionState

    fun sendUserText(text: String): Boolean = repo.sendUserText(text)

    fun switchSession(sessionKey: String) {
        repo.switchSession(sessionKey)
    }

    fun newSession() {
        repo.newSession()
    }

    companion object {
        fun factory(app: HermesApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(app) as T
            }
    }
}
