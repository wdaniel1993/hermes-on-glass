package dev.wallner.hermesonglass.phone.data.rokid

import kotlinx.coroutines.flow.SharedFlow

/**
 * Public surface of the Rokid CXR-M SDK we use. Lifted into an interface so
 * the connection state-machine is testable without loading the native Rokid
 * libraries on a JVM.
 *
 * Mirrors the relevant entry points on `com.rokid.cxr.client.extend.CxrApi`:
 *   - [initBluetooth]      — first-time pairing (no cached SN)
 *   - [connectBluetooth]   — reconnect using cached AES-encrypted SN + clientSecret
 *   - [disconnect]         — explicit disconnect / deinit
 *   - [statusEvents]       — flow of [BluetoothStatusEvent] forwarded from
 *                            the SDK's [BluetoothStatusCallback].
 */
interface RokidSdkClient {
    val statusEvents: SharedFlow<BluetoothStatusEvent>

    fun initBluetooth(deviceAddress: String, deviceName: String?)
    fun connectBluetooth(
        deviceAddress: String,
        deviceName: String?,
        snEncrypted: ByteArray,
        clientSecret: String,
    )
    fun disconnect()
    val isConnected: Boolean
}

/**
 * Normalised view of [com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback]
 * callbacks. The SDK calls back on its own thread; the SDK-client impl
 * forwards onto the manager's coroutine via [statusEvents].
 */
sealed interface BluetoothStatusEvent {
    /** First handshake: SDK reveals the SN of the paired glasses. We AES-encrypt and cache. */
    data class ConnectionInfo(
        val sn: String,
        val name: String,
        val address: String,
        val deviceType: Int,
    ) : BluetoothStatusEvent

    data object Connected : BluetoothStatusEvent

    /** Background BLE reconnect succeeded (existing SN). Glasses are usable again. */
    data class InActiveConnected(val name: String, val address: String) : BluetoothStatusEvent

    data object Disconnected : BluetoothStatusEvent

    data class Failed(val reason: String) : BluetoothStatusEvent
}
