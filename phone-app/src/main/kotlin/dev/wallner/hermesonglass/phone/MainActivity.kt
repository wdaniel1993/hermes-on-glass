package dev.wallner.hermesonglass.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.wallner.hermesonglass.phone.data.BatteryOptimizationPrompt
import dev.wallner.hermesonglass.phone.data.BluetoothPermissions
import dev.wallner.hermesonglass.phone.service.GlassesConnectionService
import dev.wallner.hermesonglass.phone.ui.nav.HermesNavHost
import dev.wallner.hermesonglass.phone.ui.theme.HermesTheme
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.all { it }
        if (granted) {
            startGlassesService()
        } else {
            // The user can re-grant from system settings; we don't pester
            // them on every launch. Without these the BLE flow won't get
            // off the ground — the HUD's offline overlay will say so.
            Timber.w("BLE/location permissions denied: %s",
                result.filterValues { !it }.keys.joinToString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HermesApp
        maybePromptBatteryOptimization(app)
        ensureBluetoothPermissionsThenStartService()
        setContent {
            HermesTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HermesNavHost(app)
                }
            }
        }
    }

    private fun ensureBluetoothPermissionsThenStartService() {
        if (BluetoothPermissions.allGranted(this)) {
            startGlassesService()
        } else {
            permissionLauncher.launch(BluetoothPermissions.required())
        }
    }

    private fun startGlassesService() {
        GlassesConnectionService.start(this)
    }

    private fun maybePromptBatteryOptimization(app: HermesApp) {
        if (app.prefs.batteryOptimizationPrompted) return
        if (BatteryOptimizationPrompt.isExempt(this)) {
            // Already exempt (rare on stock Android; common after the user
            // already granted earlier). Mark prompted so we don't ask again.
            app.prefs.batteryOptimizationPrompted = true
            return
        }
        // Mark before launching so the user is never re-prompted on subsequent
        // cold starts even if they tap "Don't allow".
        app.prefs.batteryOptimizationPrompted = true
        BatteryOptimizationPrompt.request(this)
    }
}
