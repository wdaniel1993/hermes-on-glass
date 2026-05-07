package dev.wallner.hermesonglass.phone.data.rokid

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import timber.log.Timber

/**
 * Calls the hidden `WifiP2pManager.deletePersistentGroup(...)` via reflection
 * to clear the stale `formed` state Rokid documents in `sdk-decompiled-reference.md:817-893`.
 *
 * Background: Wi-Fi P2P retains "persistent groups" between connections; if
 * a previous P2P group wasn't properly torn down (e.g. because the host
 * crashed), `requestGroupInfo` reports `formed = true` even after a real
 * disconnect, and `CxrApi.startUploadApk` then short-circuits without ever
 * pushing the APK. Rokid's documented mitigation is to clear all persistent
 * groups via the hidden API before kicking off an upload.
 *
 * This entire surface is hidden / `@hide` Android API. We reflect rather
 * than depend on a vendor SDK shim, and silently no-op on any failure —
 * the upload either succeeds without the workaround (best case) or fails
 * with a normal Wi-Fi P2P error the caller already handles.
 */
object WifiP2pStaleStateWorkaround {

    fun clearPersistentGroups(context: Context) {
        val mgr = context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (mgr == null) {
            Timber.d("WIFI_P2P_SERVICE not available; skipping P2P workaround")
            return
        }
        val channel = mgr.initialize(context.applicationContext, context.mainLooper, null)
        if (channel == null) {
            Timber.d("WifiP2pManager.initialize returned null; skipping P2P workaround")
            return
        }
        // The hidden APIs:
        //   void requestPersistentGroupInfo(Channel, PersistentGroupInfoListener)
        //   void deletePersistentGroup(Channel, int netId, ActionListener)
        // The list class is also hidden (`WifiP2pGroupList`), so we pull it
        // out via reflection on the listener result.
        val requestPersistent = runCatching {
            mgr.javaClass.getMethod(
                "requestPersistentGroupInfo",
                WifiP2pManager.Channel::class.java,
                Class.forName("android.net.wifi.p2p.WifiP2pManager\$PersistentGroupInfoListener"),
            )
        }.getOrNull()
        val deletePersistent = runCatching {
            mgr.javaClass.getMethod(
                "deletePersistentGroup",
                WifiP2pManager.Channel::class.java,
                Int::class.javaPrimitiveType,
                WifiP2pManager.ActionListener::class.java,
            )
        }.getOrNull()
        if (requestPersistent == null || deletePersistent == null) {
            Timber.d("hidden P2P APIs not available on this OS image; skipping")
            return
        }

        val listenerClass = Class.forName("android.net.wifi.p2p.WifiP2pManager\$PersistentGroupInfoListener")
        val listener = java.lang.reflect.Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass),
        ) { _, method, args ->
            if (method.name == "onPersistentGroupInfoAvailable" && args != null && args.isNotEmpty()) {
                val groups = args[0]
                runCatching {
                    val getGroupList = groups.javaClass.getMethod("getGroupList")
                    @Suppress("UNCHECKED_CAST")
                    val list = getGroupList.invoke(groups) as? Collection<Any> ?: return@runCatching
                    for (group in list) {
                        val netId = runCatching {
                            group.javaClass.getMethod("getNetworkId").invoke(group) as Int
                        }.getOrNull() ?: continue
                        deletePersistent.invoke(mgr, channel, netId, null)
                    }
                }.onFailure { Timber.d(it, "P2P group iteration failed (best-effort)") }
            }
            null
        }
        runCatching { requestPersistent.invoke(mgr, channel, listener) }
            .onFailure { Timber.d(it, "requestPersistentGroupInfo failed (best-effort)") }
    }
}
