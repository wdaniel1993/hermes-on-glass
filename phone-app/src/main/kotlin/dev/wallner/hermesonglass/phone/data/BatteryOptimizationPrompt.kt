package dev.wallner.hermesonglass.phone.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers for the standard "request to ignore battery optimizations" prompt.
 *
 * Why we want it: the WS-to-Hermes connection plus the CXR-M Bluetooth
 * link both need to stay alive when the phone screen is off. Doze and OEM
 * battery managers will kill the foreground service otherwise. The
 * standard `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent shows the
 * system dialog so the user can grant the exemption explicitly.
 */
object BatteryOptimizationPrompt {

    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Launch the system prompt. Activity-scoped because the action
     * requires `FLAG_ACTIVITY_NEW_TASK` only when started from a non-
     * Activity context, and we want the result-callback flow when
     * available.
     */
    fun request(activity: Activity) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${activity.packageName}"))
        activity.startActivity(intent)
    }
}
