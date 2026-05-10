package dev.wallner.hermesonglass.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import dev.wallner.hermesonglass.phone.data.BatteryOptimizationPrompt
import dev.wallner.hermesonglass.phone.data.BluetoothPermissions
import dev.wallner.hermesonglass.phone.data.rokid.HiRokidInstallLauncher
import dev.wallner.hermesonglass.phone.data.rokid.HiRokidPresence
import dev.wallner.hermesonglass.phone.data.rokid.RokidAuthLauncher
import dev.wallner.hermesonglass.phone.service.GlassesConnectionService
import dev.wallner.hermesonglass.phone.ui.nav.HermesNavHost
import dev.wallner.hermesonglass.phone.ui.theme.HermesTheme
import timber.log.Timber

private const val ROKID_AUTH_REQUEST_CODE = 1001

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.all { it }
        if (!granted) {
            // Without these the foreground service can still run; Hi Rokid
            // owns BLE itself but the OS asks for our intent.
            Timber.w("BLE permissions denied: %s",
                result.filterValues { !it }.keys.joinToString())
        }
        startGlassesService()
    }

    private val app: HermesApp by lazy { application as HermesApp }

    private var hiRokidInstalled by mutableStateOf(false)
    private var hasToken by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybePromptBatteryOptimization()
        ensureBluetoothPermissionsThenStartService()
        refreshAuthState()
        setContent {
            HermesTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!hiRokidInstalled) {
                            AuthBanner(
                                title = "Hi Rokid AI app required",
                                detail = "Install the Hi Rokid AI app to connect to your glasses.",
                                actionLabel = "Install Hi Rokid",
                                onAction = {
                                    HiRokidInstallLauncher.launch(this@MainActivity)
                                },
                                secondaryLabel = "Recheck",
                                onSecondary = ::refreshAuthState,
                            )
                        } else if (!hasToken) {
                            AuthBanner(
                                title = "Authorize Hi Rokid",
                                detail = "Grant the Hermes app permission to talk to your glasses through Hi Rokid.",
                                actionLabel = "Authorize",
                                onAction = {
                                    val ok = RokidAuthLauncher.requestAuthorization(
                                        this@MainActivity,
                                        ROKID_AUTH_REQUEST_CODE,
                                    )
                                    if (!ok) {
                                        Timber.w("No Hi Rokid auth activity resolved on this device")
                                    }
                                },
                            )
                        }
                        HermesNavHost(app)
                    }
                }
            }
        }
    }

    @Deprecated("Required by AuthorizationHelper which routes the auth result through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ROKID_AUTH_REQUEST_CODE) return
        val result = AuthorizationHelper.INSTANCE.parseAuthorizationResult(resultCode, data)
        when (result) {
            is AuthResult.AuthSuccess -> {
                app.prefs.rokidAuthToken = result.token
                hasToken = true
                // Token unlocks construction of cxrLink/capsLink. Rebuild the
                // WS↔Caps stack so the bridges hold the real CapsLink, then
                // restart the foreground service so its `capsLink.start()`
                // and `connected.collect` re-bind to the live instance
                // (the old service was holding a NullCapsLink reference
                // because it started before auth completed).
                app.rebuildRepository()
                GlassesConnectionService.stop(this@MainActivity)
                GlassesConnectionService.start(this@MainActivity)
                Timber.i("Hi Rokid authorization succeeded")
            }
            is AuthResult.AuthFail -> Timber.w("Hi Rokid authorization failed")
            is AuthResult.AuthCancel -> Timber.i("Hi Rokid authorization cancelled by user")
            null -> Timber.w("Hi Rokid authorization returned null result")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAuthState()
    }

    private fun refreshAuthState() {
        // The SDK's AuthorizationHelper.isRequiredRokidAppInstalled only
        // recognises the China package (com.rokid.sprite.aiapp) and gives a
        // false negative for users on the global build from Google Play
        // (com.rokid.sprite.global.aiapp). Use our own PackageManager check
        // that accepts either.
        hiRokidInstalled = HiRokidPresence.isInstalled(this)
        hasToken = app.prefs.hasRokidAuthToken()
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

    private fun maybePromptBatteryOptimization() {
        if (app.prefs.batteryOptimizationPrompted) return
        if (BatteryOptimizationPrompt.isExempt(this)) {
            app.prefs.batteryOptimizationPrompted = true
            return
        }
        app.prefs.batteryOptimizationPrompted = true
        BatteryOptimizationPrompt.request(this)
    }
}

@Composable
private fun AuthBanner(
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(actionLabel) }
            if (secondaryLabel != null && onSecondary != null) {
                Button(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) {
                    Text(secondaryLabel)
                }
            }
        }
    }
}
