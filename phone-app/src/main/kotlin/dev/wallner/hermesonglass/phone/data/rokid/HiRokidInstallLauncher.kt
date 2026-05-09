package dev.wallner.hermesonglass.phone.data.rokid

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import timber.log.Timber

/**
 * Hi Rokid AI app discovery and install helpers.
 *
 * The Hi Rokid AI app ships under two package names depending on region:
 *  - `com.rokid.sprite.aiapp`        — China build (Hi Rokid)
 *  - `com.rokid.sprite.global.aiapp` — Global build, hosted on Google Play
 *
 * The SDK's bundled `AuthorizationHelper.isRequiredRokidAppInstalled` only
 * recognises the China package, which gives a false negative for users on
 * the global build. We do our own [PackageManager] check that accepts
 * either, and target the global package for installs since this build is
 * for non-China use (Google Play / Pixel).
 */
object HiRokidPresence {
    const val PACKAGE_GLOBAL: String = "com.rokid.sprite.global.aiapp"
    const val PACKAGE_CHINA: String = "com.rokid.sprite.aiapp"

    /** True if either the global or China Hi Rokid AI build is installed. */
    fun isInstalled(context: Context): Boolean =
        isPackageInstalled(context, PACKAGE_GLOBAL) ||
            isPackageInstalled(context, PACKAGE_CHINA)

    private fun isPackageInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrElse { false }
}

/**
 * Launches the Hi Rokid authorization flow ourselves rather than via the
 * SDK's `AuthorizationHelper.requestAuthorization`, which is hardcoded to
 * the China package class name and fails with `ActivityNotFoundException`
 * on devices that have the global build (`com.rokid.sprite.global.aiapp`).
 *
 * We try a few candidate (package, class) combinations in order of decreasing
 * likelihood and fall back through them when [ActivityNotFoundException]
 * fires. Once we know which one Rokid ships, the others can be pruned.
 */
object RokidAuthLauncher {
    private const val SDK_AUTH_CLASS = "com.rokid.sprite.aiapp.externalapp.auth.AuthorizationActivity"
    private const val GLOBAL_AUTH_CLASS = "com.rokid.sprite.global.aiapp.externalapp.auth.AuthorizationActivity"

    private val candidates: List<Pair<String, String>> = listOf(
        // Global build, SDK class FQN reused (common pattern when only the
        // app package is rebranded).
        HiRokidPresence.PACKAGE_GLOBAL to SDK_AUTH_CLASS,
        // Global build, fully rebranded class name.
        HiRokidPresence.PACKAGE_GLOBAL to GLOBAL_AUTH_CLASS,
        // China build (what the bundled SDK helper targets).
        HiRokidPresence.PACKAGE_CHINA to SDK_AUTH_CLASS,
    )

    /**
     * Starts whichever auth activity resolves first. Returns true if one was
     * dispatched; false if every candidate threw [ActivityNotFoundException].
     */
    fun requestAuthorization(activity: Activity, requestCode: Int): Boolean {
        for ((pkg, cls) in candidates) {
            val intent = Intent().apply { component = ComponentName(pkg, cls) }
            try {
                activity.startActivityForResult(intent, requestCode)
                Timber.i("Hi Rokid auth dispatched to %s/%s", pkg, cls)
                return true
            } catch (e: ActivityNotFoundException) {
                Timber.d("Hi Rokid auth not found: %s/%s — trying next", pkg, cls)
            } catch (e: SecurityException) {
                Timber.w(e, "Hi Rokid auth refused: %s/%s — trying next", pkg, cls)
            }
        }
        Timber.w("No Hi Rokid auth activity resolved against any candidate")
        return false
    }
}

/**
 * Launches the install path for the Hi Rokid AI app — `market://` first
 * (Google Play on Pixel; OEM stores otherwise), web fallback if no store
 * intent resolves.
 */
object HiRokidInstallLauncher {
    private const val DOWNLOAD_URL = "https://static.rokidcdn.com/web_assets/site/downloadAI.html"

    fun launch(context: Context, packageName: String = HiRokidPresence.PACKAGE_GLOBAL) {
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
