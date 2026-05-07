package dev.wallner.hermesonglass.phone.data.rokid

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GlassesConnectionManagerTest {

    private class FakeSdk : RokidSdkClient {
        private val _events = MutableSharedFlow<BluetoothStatusEvent>(extraBufferCapacity = 32)
        override val statusEvents: SharedFlow<BluetoothStatusEvent> = _events.asSharedFlow()
        override var isConnected: Boolean = false

        var initCalls = mutableListOf<Pair<String, String?>>()
        var connectCalls = mutableListOf<ConnectCall>()
        var disconnectCalls = 0

        data class ConnectCall(
            val address: String,
            val name: String?,
            val sn: ByteArray,
            val secret: String,
        )

        override fun initBluetooth(deviceAddress: String, deviceName: String?) {
            initCalls += deviceAddress to deviceName
        }
        override fun connectBluetooth(
            deviceAddress: String,
            deviceName: String?,
            snEncrypted: ByteArray,
            clientSecret: String,
        ) {
            connectCalls += ConnectCall(deviceAddress, deviceName, snEncrypted, clientSecret)
        }
        override fun disconnect() {
            disconnectCalls += 1
            isConnected = false
        }
        suspend fun emit(event: BluetoothStatusEvent) {
            _events.emit(event)
        }
    }

    private fun TestScope.newManager(
        snStore: SnStore = InMemorySnStore(),
        clientSecret: String = "1234-5678-90ab-cdef",
    ): Triple<GlassesConnectionManager, FakeSdk, SnStore> {
        val sdk = FakeSdk()
        val mgr = GlassesConnectionManager(
            sdk = sdk,
            snStore = snStore,
            clientSecret = clientSecret,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            reconnectDelayMs = 50,
        )
        mgr.start()
        advanceUntilIdle()
        return Triple(mgr, sdk, snStore)
    }

    // ── pair / first-time ──────────────────────────────────────────────

    @Test fun `pair calls initBluetooth and transitions to Pairing`() = runTest {
        val (mgr, sdk, _) = newManager()
        mgr.pair("AA:BB:CC", "MyGlasses")
        advanceUntilIdle()
        assertEquals(GlassesConnectionState.Pairing, mgr.state.value)
        assertEquals(listOf("AA:BB:CC" to "MyGlasses"), sdk.initCalls)
    }

    @Test fun `connection_info encrypts SN and persists`() = runTest {
        val (mgr, sdk, store) = newManager()
        mgr.pair("AA:BB:CC", "MyGlasses")
        sdk.emit(BluetoothStatusEvent.ConnectionInfo(
            sn = "RKID0001",
            name = "MyGlasses",
            address = "AA:BB:CC",
            deviceType = 0,
        ))
        advanceUntilIdle()
        val cached = store.snEncrypted("AA:BB:CC")
        assertNotNull(cached)
        // Decryptable by the same secret.
        val recovered = RokidSnCipher.decryptSn(cached!!, "1234-5678-90ab-cdef")
        assertEquals("RKID0001", recovered)
        mgr.shutdown()
    }

    @Test fun `connected event transitions state with device info`() = runTest {
        val (mgr, sdk, _) = newManager()
        mgr.pair("AA:BB:CC", "MyGlasses")
        sdk.emit(BluetoothStatusEvent.Connected)
        advanceUntilIdle()
        val s = mgr.state.value as GlassesConnectionState.Connected
        assertEquals("AA:BB:CC", s.deviceAddress)
        assertEquals("MyGlasses", s.deviceName)
    }

    // ── reconnect / cached path ────────────────────────────────────────

    @Test fun `reconnect uses cached SN and transitions to Connecting`() = runTest {
        val store = InMemorySnStore()
        store.putSnEncrypted(
            "AA:BB:CC",
            RokidSnCipher.encryptSn("RKID0002", "1234-5678-90ab-cdef"),
            "MyGlasses",
        )
        val (mgr, sdk, _) = newManager(snStore = store)
        val ok = mgr.reconnect("AA:BB:CC", "MyGlasses")
        advanceUntilIdle()
        assertTrue(ok)
        assertEquals(GlassesConnectionState.Connecting, mgr.state.value)
        assertEquals(1, sdk.connectCalls.size)
        val call = sdk.connectCalls[0]
        assertEquals("AA:BB:CC", call.address)
        assertEquals("MyGlasses", call.name)
        assertEquals("1234-5678-90ab-cdef", call.secret)
        // SN bytes round-trip-decrypt to the same value we stored.
        assertEquals("RKID0002", RokidSnCipher.decryptSn(call.sn, "1234-5678-90ab-cdef"))
    }

    @Test fun `reconnect fails fast when no cached SN exists`() = runTest {
        val (mgr, sdk, _) = newManager()
        val ok = mgr.reconnect("UN:KN:OW")
        advanceUntilIdle()
        assert(!ok)
        val state = mgr.state.value as GlassesConnectionState.Failed
        assert(state.reason.contains("no cached SN"))
        assertEquals(0, sdk.connectCalls.size)
    }

    // ── auto-reconnect ─────────────────────────────────────────────────

    @Test fun `failed event schedules reconnect after delay`() = runTest {
        val store = InMemorySnStore()
        store.putSnEncrypted(
            "AA:BB:CC",
            RokidSnCipher.encryptSn("RKID0003", "1234-5678-90ab-cdef"),
            "MyGlasses",
        )
        val (mgr, sdk, _) = newManager(snStore = store)
        mgr.reconnect("AA:BB:CC", "MyGlasses")
        advanceUntilIdle()
        assertEquals(1, sdk.connectCalls.size)

        sdk.emit(BluetoothStatusEvent.Failed("transport error"))
        advanceUntilIdle()
        // Failed → schedules reconnect 50 ms later.
        advanceTimeBy(60)
        advanceUntilIdle()
        assertEquals("expected a 2nd connectBluetooth call after backoff",
            2, sdk.connectCalls.size)
    }

    @Test fun `disconnected event schedules reconnect`() = runTest {
        val store = InMemorySnStore()
        store.putSnEncrypted(
            "AA:BB:CC",
            RokidSnCipher.encryptSn("RKID0004", "1234-5678-90ab-cdef"),
            "MyGlasses",
        )
        val (mgr, sdk, _) = newManager(snStore = store)
        mgr.reconnect("AA:BB:CC", "MyGlasses")
        sdk.emit(BluetoothStatusEvent.Connected)
        advanceUntilIdle()
        sdk.emit(BluetoothStatusEvent.Disconnected)
        advanceTimeBy(60)
        advanceUntilIdle()
        // Reconnect attempted → at least one extra connectBluetooth call.
        assertTrue(sdk.connectCalls.size >= 2)
    }

    @Test fun `manual stop cancels pending reconnect`() = runTest {
        val store = InMemorySnStore()
        store.putSnEncrypted(
            "AA:BB:CC",
            RokidSnCipher.encryptSn("RKID0005", "1234-5678-90ab-cdef"),
            "MyGlasses",
        )
        val (mgr, sdk, _) = newManager(snStore = store)
        mgr.reconnect("AA:BB:CC", "MyGlasses")
        sdk.emit(BluetoothStatusEvent.Failed("oops"))
        // Drain only the synchronous work — do NOT advance past the
        // reconnect delay yet, otherwise the retry fires before we can
        // cancel.
        runCurrent()
        mgr.stop()
        advanceTimeBy(500)
        advanceUntilIdle()
        // Only the initial connectBluetooth call — no auto-retry after stop.
        assertEquals(1, sdk.connectCalls.size)
        assertEquals(GlassesConnectionState.Disconnected, mgr.state.value)
    }
}
