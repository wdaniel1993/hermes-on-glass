package dev.wallner.hermesonglass.phone.data.rokid

/**
 * Lifecycle of the Bluetooth + CXR session to a paired pair of glasses.
 *
 *   Idle -> Pairing      (initBluetooth — first launch of a new device)
 *   Idle -> Connecting   (connectBluetooth — cached SN reconnect)
 *   Pairing/Connecting -> Connected
 *   Connected -> Disconnected
 *   any -> Failed (transport error; manager retries with 3s backoff)
 *
 * Sealed instead of enum so failure variants can carry an error code string
 * forwarded from `BluetoothStatusCallback.onFailed(...)`.
 */
sealed interface GlassesConnectionState {
    data object Idle : GlassesConnectionState
    data object Pairing : GlassesConnectionState
    data object Connecting : GlassesConnectionState
    data class Connected(val deviceAddress: String, val deviceName: String?) : GlassesConnectionState
    data object Disconnected : GlassesConnectionState
    data class Failed(val reason: String) : GlassesConnectionState
}
