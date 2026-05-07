package dev.wallner.hermesonglass.phone.data.rokid

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinates Bluetooth pairing + reconnect for a Rokid CXR-M session.
 *
 *   pair()      — first-time `initBluetooth`. We capture the SN from
 *                 [BluetoothStatusEvent.ConnectionInfo], AES-encrypt it via
 *                 [RokidSnCipher], persist via [SnStore], and on success
 *                 transition to [GlassesConnectionState.Connected].
 *   reconnect() — cached path: load encrypted SN from [SnStore] and call
 *                 `connectBluetooth(...)` with `clientSecret`.
 *
 * Failures and unexpected disconnects schedule a single retry after
 * [reconnectDelayMs] (3 s default; [start]/[stop] gate the loop).
 */
class GlassesConnectionManager(
    private val sdk: RokidSdkClient,
    private val snStore: SnStore,
    private val clientSecret: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val reconnectDelayMs: Long = DEFAULT_RECONNECT_DELAY_MS,
) {
    private val _state = MutableStateFlow<GlassesConnectionState>(GlassesConnectionState.Idle)
    val state: StateFlow<GlassesConnectionState> = _state.asStateFlow()

    private var collectorJob: Job? = null
    private var reconnectJob: Job? = null
    private var currentDeviceAddress: String? = null
    private var currentDeviceName: String? = null
    private var manuallyStopped: Boolean = false

    fun start() {
        if (collectorJob?.isActive == true) return
        manuallyStopped = false
        collectorJob = scope.launch {
            sdk.statusEvents.collect { handle(it) }
        }
    }

    fun stop() {
        manuallyStopped = true
        reconnectJob?.cancel()
        collectorJob?.cancel()
        collectorJob = null
        sdk.disconnect()
        _state.value = GlassesConnectionState.Disconnected
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    /** First-time pairing path. Subsequent launches use [reconnect]. */
    fun pair(deviceAddress: String, deviceName: String?) {
        currentDeviceAddress = deviceAddress
        currentDeviceName = deviceName
        _state.value = GlassesConnectionState.Pairing
        sdk.initBluetooth(deviceAddress, deviceName)
    }

    /** Cached SN reconnect path. Fails fast if no SN is on disk for the device. */
    fun reconnect(deviceAddress: String? = null, deviceName: String? = null): Boolean {
        val addr = deviceAddress ?: currentDeviceAddress ?: snStore.lastDeviceAddress()
        if (addr == null) {
            _state.value = GlassesConnectionState.Failed("no paired device")
            return false
        }
        val name = deviceName ?: currentDeviceName ?: snStore.lastDeviceName()
        val sn = snStore.snEncrypted(addr)
        if (sn == null) {
            _state.value = GlassesConnectionState.Failed("no cached SN for $addr; re-pair")
            return false
        }
        currentDeviceAddress = addr
        currentDeviceName = name
        _state.value = GlassesConnectionState.Connecting
        sdk.connectBluetooth(addr, name, sn, clientSecret)
        return true
    }

    private fun handle(event: BluetoothStatusEvent) {
        when (event) {
            is BluetoothStatusEvent.ConnectionInfo -> {
                val encrypted = RokidSnCipher.encryptSn(event.sn, clientSecret)
                snStore.putSnEncrypted(event.address, encrypted, event.name)
                currentDeviceAddress = event.address
                currentDeviceName = event.name
            }
            is BluetoothStatusEvent.Connected -> {
                reconnectJob?.cancel()
                _state.value = GlassesConnectionState.Connected(
                    deviceAddress = currentDeviceAddress.orEmpty(),
                    deviceName = currentDeviceName,
                )
            }
            is BluetoothStatusEvent.InActiveConnected -> {
                reconnectJob?.cancel()
                currentDeviceAddress = event.address
                currentDeviceName = event.name
                _state.value = GlassesConnectionState.Connected(event.address, event.name)
            }
            is BluetoothStatusEvent.Disconnected -> {
                _state.value = GlassesConnectionState.Disconnected
                if (!manuallyStopped) scheduleReconnect()
            }
            is BluetoothStatusEvent.Failed -> {
                _state.value = GlassesConnectionState.Failed(event.reason)
                if (!manuallyStopped) scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            if (!manuallyStopped) reconnect()
        }
    }

    companion object {
        const val DEFAULT_RECONNECT_DELAY_MS: Long = 3_000
    }
}
