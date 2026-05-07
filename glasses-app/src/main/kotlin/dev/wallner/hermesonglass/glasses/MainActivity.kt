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

class MainActivity : ComponentActivity() {

    private lateinit var gestures: HudGestures
    private val viewModel: HudViewModel by viewModels {
        HudViewModel.factory(application as GlassesApp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as GlassesApp
        gestures = HudGestures(emit = { intent ->
            // Voice subsystem first — long-press is hold-to-talk; if the
            // controller consumes the intent we still let the HUD VM see
            // it so the recording overlay flips on/off via [HudUiState.recordingVoice].
            app.voiceController.onIntent(intent)
            viewModel.applyIntent(intent)
        })

        // Surface a setup error before the HUD if the Rokid AI app is missing.
        viewModel.setSetupError(app.cxrL.checkRequirements(this))

        setContent {
            HudTheme {
                HudScreen(viewModel)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gestures.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }
}
