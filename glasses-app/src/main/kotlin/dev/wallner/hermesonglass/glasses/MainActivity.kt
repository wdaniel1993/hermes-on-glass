package dev.wallner.hermesonglass.glasses

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.wallner.hermesonglass.glasses.ui.hud.HudGestures
import dev.wallner.hermesonglass.glasses.ui.hud.HudScreen
import dev.wallner.hermesonglass.glasses.ui.hud.HudViewModel
import dev.wallner.hermesonglass.glasses.ui.theme.HudTheme
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private lateinit var gestures: HudGestures
    private val viewModel: HudViewModel by viewModels {
        HudViewModel.factory(application as GlassesApp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("MainActivity.onCreate")
        val app = application as GlassesApp
        gestures = HudGestures(emit = { intent ->
            // Voice subsystem first — long-press is hold-to-talk; if the
            // controller consumes the intent we still let the HUD VM see
            // it so the recording overlay flips on/off via [HudUiState.recordingVoice].
            runCatching { app.voiceController.onIntent(intent) }
                .onFailure { Timber.w(it, "voiceController.onIntent threw") }
            runCatching { viewModel.applyIntent(intent) }
                .onFailure { Timber.w(it, "viewModel.applyIntent threw") }
        })

        // Surface a setup error before the HUD if the Rokid AI app is missing.
        // Guard so a SDK throw doesn't take the activity down before render.
        val setupError = runCatching { app.cxrL.checkRequirements(this) }
            .onFailure { Timber.w(it, "cxrL.checkRequirements threw") }
            .getOrNull()
        viewModel.setSetupError(setupError)

        setContent {
            HudTheme {
                HudScreen(viewModel)
            }
        }
        Timber.i("MainActivity.onCreate — done, content set")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gestures.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
