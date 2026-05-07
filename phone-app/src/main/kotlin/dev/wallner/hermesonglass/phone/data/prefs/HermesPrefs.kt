package dev.wallner.hermesonglass.phone.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * App-level settings store. Two backing stores:
 *
 *  - Plain `SharedPreferences` for non-sensitive settings (URL, debug toggle).
 *  - `EncryptedSharedPreferences` for the Hermes shared secret (D2 — Bearer
 *    token over Tailscale; loss of device storage shouldn't leak it).
 *
 * Two stores rather than one keeps a casual `adb shell run-as ... cat` of the
 * plain prefs harmless — only the encrypted store hides the secret blob.
 */
class HermesPrefs(context: Context) {

    private val plain: SharedPreferences =
        context.applicationContext.getSharedPreferences(PLAIN_NAME, Context.MODE_PRIVATE)

    private val secret: SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        SECRET_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var hermesWsUrl: String
        get() = plain.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) {
            plain.edit().putString(KEY_URL, value.trim()).apply()
        }

    var debugEventsEnabled: Boolean
        get() = plain.getBoolean(KEY_DEBUG, false)
        set(value) {
            plain.edit().putBoolean(KEY_DEBUG, value).apply()
        }

    var deviceId: String
        get() = plain.getString(KEY_DEVICE_ID, "") ?: ""
        set(value) {
            plain.edit().putString(KEY_DEVICE_ID, value).apply()
        }

    var sharedSecret: String
        get() = secret.getString(KEY_SECRET, "") ?: ""
        set(value) {
            secret.edit().putString(KEY_SECRET, value).apply()
        }

    /**
     * One-shot flag set after the user has seen the battery-optimization
     * exemption prompt — we don't want to nag every launch.
     */
    var batteryOptimizationPrompted: Boolean
        get() = plain.getBoolean(KEY_BATTERY_PROMPTED, false)
        set(value) {
            plain.edit().putBoolean(KEY_BATTERY_PROMPTED, value).apply()
        }

    fun hasSharedSecret(): Boolean = sharedSecret.isNotEmpty()

    companion object {
        const val DEFAULT_URL: String = "ws://mac:8765/glasses"
        private const val PLAIN_NAME = "hermes_prefs"
        private const val SECRET_NAME = "hermes_secret"
        private const val KEY_URL = "ws_url"
        private const val KEY_DEBUG = "debug_events"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SECRET = "shared_secret"
        private const val KEY_BATTERY_PROMPTED = "battery_prompted"
    }
}
