package dev.wallner.hermesonglass.phone.data.rokid

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Launches the install path for the Hi Rokid AI app — `market://` first
 * (Google Play on Pixel; OEM stores otherwise), web fallback if no store
 * intent resolves.
 */
object HiRokidInstallLauncher {
    private const val DOWNLOAD_URL = "https://static.rokidcdn.com/web_assets/site/downloadAI.html"

    fun launch(context: Context, packageName: String) {
        val marketIntent = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(marketIntent)
            return
        } catch (_: ActivityNotFoundException) {
            // No store app — fall back to web download.
        }
        val webIntent = Intent(Intent.ACTION_VIEW, DOWNLOAD_URL.toUri()).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(webIntent) }
    }
}
