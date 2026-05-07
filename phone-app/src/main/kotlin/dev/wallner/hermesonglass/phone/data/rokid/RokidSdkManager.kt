package dev.wallner.hermesonglass.phone.data.rokid

import android.content.Context
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.utils.ValueUtil
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber

/**
 * Production [RokidSdkClient] wrapping `CxrApi.getInstance()`.
 *
 * The first launch after pairing calls [initBluetooth] (no SN yet); the SDK
 * fires `BluetoothStatusCallback.onConnectionInfo(...)` once it's negotiated
 * the SN, which we forward as [BluetoothStatusEvent.ConnectionInfo] so
 * [GlassesConnectionManager] can persist the encrypted blob.
 *
 * Subsequent launches call [connectBluetooth] with the cached encrypted SN
 * + clientSecret and skip the SN handshake entirely.
 */
class RokidSdkManager(
    private val context: Context,
    private val cxrApi: CxrApi = CxrApi.getInstance(),
) : RokidSdkClient {

    private val _events = MutableSharedFlow<BluetoothStatusEvent>(extraBufferCapacity = 16)
    override val statusEvents: SharedFlow<BluetoothStatusEvent> = _events.asSharedFlow()

    override val isConnected: Boolean get() = cxrApi.isBluetoothConnected

    override fun initBluetooth(deviceAddress: String, deviceName: String?) {
        // Resolve a BluetoothDevice via the system manager — initBluetooth
        // requires the device handle, not just the MAC. We accept the
        // address as the canonical id throughout the manager surface.
        val device = runCatching {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as android.bluetooth.BluetoothManager
            btManager.adapter.getRemoteDevice(deviceAddress)
        }.getOrElse {
            Timber.w(it, "could not resolve BluetoothDevice for %s", deviceAddress)
            _events.tryEmit(BluetoothStatusEvent.Failed("bad address $deviceAddress"))
            return
        }
        cxrApi.initBluetooth(context, device, callback)
    }

    @Suppress("DEPRECATION")
    override fun connectBluetooth(
        deviceAddress: String,
        deviceName: String?,
        snEncrypted: ByteArray,
        clientSecret: String,
    ) {
        // The 6-arg overload is marked @Deprecated in the Rokid SDK but is
        // still the only one that takes the AES-encrypted SN + clientSecret
        // pair our reconnect path needs. Revisit when Rokid publishes a
        // replacement that exposes the same envelope.
        cxrApi.connectBluetooth(
            context,
            deviceAddress,
            deviceName.orEmpty(),
            /* statusCallback = */ callback,
            snEncrypted,
            clientSecret,
        )
    }

    override fun disconnect() {
        cxrApi.deinitBluetooth()
    }

    private val callback: BluetoothStatusCallback = object : BluetoothStatusCallback {
        override fun onConnectionInfo(sn: String, name: String, address: String, deviceType: Int) {
            _events.tryEmit(
                BluetoothStatusEvent.ConnectionInfo(sn, name, address, deviceType),
            )
        }
        override fun onConnected() { _events.tryEmit(BluetoothStatusEvent.Connected) }
        override fun onInActiveConnected(name: String, address: String) {
            _events.tryEmit(BluetoothStatusEvent.InActiveConnected(name, address))
        }
        override fun onDisconnected() { _events.tryEmit(BluetoothStatusEvent.Disconnected) }
        override fun onFailed(error: ValueUtil.CxrBluetoothErrorCode) {
            _events.tryEmit(BluetoothStatusEvent.Failed(error.name))
        }
    }
}
