package dev.wallner.hermesonglass.phone.data.rokid

import android.content.Context
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.ApkStatusCallback
import dev.wallner.hermesonglass.phone.data.rokid.SideloadEvent.UploadFailed
import dev.wallner.hermesonglass.phone.data.rokid.SideloadEvent.UploadSucceeded
import dev.wallner.hermesonglass.phone.data.rokid.SideloadEvent.InstallSucceeded
import dev.wallner.hermesonglass.phone.data.rokid.SideloadEvent.InstallFailed
import dev.wallner.hermesonglass.phone.data.rokid.SideloadEvent.OpenAppSucceeded
import dev.wallner.hermesonglass.phone.data.rokid.SideloadEvent.OpenAppFailed
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Lifecycle events the [ApkSideloader] surfaces to the UI / repository
 * during an upload-install-launch flow. Mirror the Rokid
 * [com.rokid.cxr.client.extend.callbacks.ApkStatusCallback] vocabulary.
 */
sealed interface SideloadEvent {
    data object UploadSucceeded : SideloadEvent
    data object UploadFailed : SideloadEvent
    data object InstallSucceeded : SideloadEvent
    data object InstallFailed : SideloadEvent
    data object OpenAppSucceeded : SideloadEvent
    data object OpenAppFailed : SideloadEvent
    data class StopAppResult(val ok: Boolean) : SideloadEvent
    data class GlassAppResume(val packageName: String) : SideloadEvent
    data class QueryAppResult(val packageName: String, val installed: Boolean) : SideloadEvent
}

interface ApkSideloader {
    val events: SharedFlow<SideloadEvent>
    fun start(wifiAddress: String): Boolean
    fun start(wifiAddress: String, apkPath: String): Boolean
    fun stop()
}

/**
 * Real impl: wraps `CxrApi.startUploadApk(wifiAddress, callback)`. Applies
 * the [WifiP2pStaleStateWorkaround] before [start] so we don't trip the
 * documented stale-formed-state bug.
 *
 * The `wifiAddress` argument names the Wi-Fi P2P interface to use; for
 * Rokid Glasses pairing it's the IP the SDK has discovered for the device
 * (typically pulled from the prior CXR-M handshake).
 */
class CxrApkSideloader(
    private val context: Context,
    private val cxrApi: CxrApi = CxrApi.getInstance(),
) : ApkSideloader {

    private val _events = MutableSharedFlow<SideloadEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<SideloadEvent> = _events.asSharedFlow()

    override fun start(wifiAddress: String): Boolean {
        WifiP2pStaleStateWorkaround.clearPersistentGroups(context)
        return cxrApi.startUploadApk(wifiAddress, callback)
    }

    override fun start(wifiAddress: String, apkPath: String): Boolean {
        WifiP2pStaleStateWorkaround.clearPersistentGroups(context)
        return cxrApi.startUploadApk(wifiAddress, apkPath, callback)
    }

    override fun stop() {
        cxrApi.stopUploadApk()
    }

    private val callback: ApkStatusCallback = object : ApkStatusCallback {
        override fun onUploadApkSucceed() { _events.tryEmit(UploadSucceeded) }
        override fun onUploadApkFailed() { _events.tryEmit(UploadFailed) }
        override fun onInstallApkSucceed() { _events.tryEmit(InstallSucceeded) }
        override fun onInstallApkFailed() { _events.tryEmit(InstallFailed) }
        override fun onUninstallApkSucceed() = Unit
        override fun onUninstallApkFailed() = Unit
        override fun onOpenAppSucceed() { _events.tryEmit(OpenAppSucceeded) }
        override fun onOpenAppFailed() { _events.tryEmit(OpenAppFailed) }
        override fun onStopAppResult(ok: Boolean) {
            _events.tryEmit(SideloadEvent.StopAppResult(ok))
        }
        override fun onGlassAppResume(packageName: String) {
            _events.tryEmit(SideloadEvent.GlassAppResume(packageName))
        }
        override fun onQueryAppResult(packageName: String, installed: Boolean) {
            _events.tryEmit(SideloadEvent.QueryAppResult(packageName, installed))
        }
    }
}
