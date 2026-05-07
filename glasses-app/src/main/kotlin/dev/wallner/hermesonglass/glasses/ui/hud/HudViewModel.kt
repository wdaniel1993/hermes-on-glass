package dev.wallner.hermesonglass.glasses.ui.hud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.wallner.hermesonglass.glasses.GlassesApp
import dev.wallner.hermesonglass.glasses.domain.HudRepository
import kotlinx.coroutines.flow.StateFlow

class HudViewModel(private val app: GlassesApp) : ViewModel() {

    private val repo: HudRepository get() = app.hudRepository

    val state: StateFlow<HudUiState> get() = repo.state

    fun applyIntent(intent: HudIntent): Boolean = repo.applyIntent(intent)

    fun setSetupError(error: String?) = repo.setSetupError(error)

    companion object {
        fun factory(app: GlassesApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HudViewModel(app) as T
            }
    }
}
