package dev.wallner.hermesonglass.phone.data.rokid

import android.content.Context
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import dev.wallner.hermesonglass.phone.data.rokid.SideloadEvent.InstallFailed
import dev.wallner.hermesonglass.phone.data.rokid.SideloadEvent.InstallSucceeded
import dev.wallner.hermesonglass.phone.data.rokid.SideloadEvent.OpenAppFailed
import dev.wallner.hermesonglass.phone.data.rokid.SideloadEvent.OpenAppSucceeded
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.io.File

/**
 * Lifecycle events the [ApkSideloader] surfaces to the UI / repository
 * during an install-launch flow. Mirrors the Rokid
 * [com.rokid.cxr.link.callbacks.IGlassAppCbk] vocabulary.
 */
sealed interface SideloadEvent {
    data object InstallSucceeded : SideloadEvent
    data object InstallFailed : SideloadEvent
    data object OpenAppSucceeded : SideloadEvent
    data object OpenAppFailed : SideloadEvent
    data object UninstallSucceeded : SideloadEvent
    data object UninstallFailed : SideloadEvent
    data class StopAppResult(val ok: Boolean) : SideloadEvent
    data class GlassAppResume(val ok: Boolean) : SideloadEvent
    data class QueryAppResult(val installed: Boolean) : SideloadEvent
}

interface ApkSideloader {
    val events: SharedFlow<SideloadEvent>
    /** Install the bundled glasses APK and launch its main activity on success. */
    fun installAndLaunch()
    /** Query whether the glasses-app is already installed (result on [events]). */
    fun queryInstalled()
}

/**
 * Real impl: stages the bundled APK into app-private external storage and
 * hands the path to `cxrLink.appUploadAndInstall(path, IGlassAppCbk)`. On
 * `onInstallAppResult(true)` we follow up with `cxrLink.appStart(...)` so the
 * glasses-app launches itself.
 *
 * The `CXRLink` instance is shared with [CxrCapsLink] — both APIs are served
 * by the same App-scoped link.
 */
class CxrApkSideloader(
    private val context: Context,
    private val cxrLink: CXRLink,
    private val glassesPackageName: String = GLASSES_PACKAGE_NAME,
    private val glassesMainActivity: String = GLASSES_MAIN_ACTIVITY,
) : ApkSideloader {

    private val _events = MutableSharedFlow<SideloadEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<SideloadEvent> = _events.asSharedFlow()

    override fun installAndLaunch() {
        val apk = stageBundledApk() ?: run {
            Timber.w("glasses APK asset missing; skipping install")
            _events.tryEmit(InstallFailed)
            return
        }
        runCatching {
            cxrLink.appUploadAndInstall(apk.absolutePath, callback)
        }.onFailure {
            Timber.w(it, "appUploadAndInstall threw for %s", apk.absolutePath)
            _events.tryEmit(InstallFailed)
        }
    }

    override fun queryInstalled() {
        runCatching { cxrLink.appIsInstalled(callback) }
            .onFailure { Timber.w(it, "appIsInstalled threw") }
    }

    /**
     * Copy `assets/glasses-app-release.apk` into the app's external-files
     * area at a stable path the SDK can read. `getExternalFilesDir(...)` is
     * preferred over `filesDir` because the SDK reads the path on its own
     * thread without our process credentials in some Android versions.
     */
    private fun stageBundledApk(): File? {
        val out = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            STAGED_APK_NAME,
        )
        return runCatching {
            context.assets.open(BUNDLED_APK_ASSET).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out
        }.getOrElse {
            Timber.w(it, "failed to stage bundled APK")
            null
        }
    }

    private val callback: IGlassAppCbk = object : IGlassAppCbk {
        override fun onInstallAppResult(ok: Boolean) {
            _events.tryEmit(if (ok) InstallSucceeded else InstallFailed)
            if (ok) {
                // The SDK expects the activity's fully-qualified class name as a
                // single string (e.g. "com.example.app.MainActivity"), not the
                // ComponentName "pkg/.Activity" format. Sample concatenates
                // package + leading-dot relative path; we match.
                val activityRef = glassesPackageName + glassesMainActivity
                Timber.i("appStart(%s)", activityRef)
                runCatching { cxrLink.appStart(activityRef, this) }
                    .onFailure { Timber.w(it, "appStart threw for %s", activityRef) }
            }
        }
        override fun onUnInstallAppResult(ok: Boolean) {
            _events.tryEmit(
                if (ok) SideloadEvent.UninstallSucceeded else SideloadEvent.UninstallFailed,
            )
        }
        override fun onOpenAppResult(ok: Boolean) {
            _events.tryEmit(if (ok) OpenAppSucceeded else OpenAppFailed)
        }
        override fun onStopAppResult(ok: Boolean) {
            _events.tryEmit(SideloadEvent.StopAppResult(ok))
        }
        override fun onGlassAppResume(ok: Boolean) {
            _events.tryEmit(SideloadEvent.GlassAppResume(ok))
        }
        override fun onQueryAppResult(installed: Boolean) {
            _events.tryEmit(SideloadEvent.QueryAppResult(installed))
        }
    }

    companion object {
        const val GLASSES_PACKAGE_NAME: String = "dev.wallner.hermesonglass.glasses"
        const val GLASSES_MAIN_ACTIVITY: String = ".MainActivity"
        private const val BUNDLED_APK_ASSET: String = "glasses-app-release.apk"
        private const val STAGED_APK_NAME: String = "glasses-app-release.apk"
    }
}
