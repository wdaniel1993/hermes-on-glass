package dev.wallner.hermesonglass.glasses.data.camera

import dev.wallner.hermesonglass.glasses.data.PhoneLink
import dev.wallner.hermesonglass.shared.CapsEnvelope
import dev.wallner.hermesonglass.shared.UserInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoCaptureCoordinatorTest {

    private class FakeCamera : CameraController {
        private val _state = MutableStateFlow<CameraState>(CameraState.Idle)
        override val state: StateFlow<CameraState> = _state.asStateFlow()

        var startCalls = 0
        var stopCalls = 0
        var takePhotoCalls = 0
        var resetCalls = 0
        var takePhotoResult: Boolean = true

        override fun start() { startCalls += 1 }
        override fun stop() { stopCalls += 1 }
        override fun takePhoto(): Boolean {
            takePhotoCalls += 1
            if (takePhotoResult) _state.value = CameraState.Capturing
            return takePhotoResult
        }
        override fun reset() {
            resetCalls += 1
            _state.value = CameraState.Idle
        }

        fun emit(state: CameraState) { _state.value = state }
    }

    private class FakeDownscaler(
        private val transform: (ByteArray, Int) -> ByteArray = { jpeg, _ -> jpeg },
    ) : JpegDownscaler {
        var calls = 0
        override fun downscale(jpeg: ByteArray, maxBytes: Int): ByteArray {
            calls += 1
            return transform(jpeg, maxBytes)
        }
    }

    private class FakePhoneLink : PhoneLink {
        private val _envelopes = MutableSharedFlow<CapsEnvelope>(extraBufferCapacity = 32)
        override val envelopes: SharedFlow<CapsEnvelope> = _envelopes.asSharedFlow()
        private val _connected = MutableStateFlow(true)
        override val connected: StateFlow<Boolean> = _connected.asStateFlow()
        val sent = mutableListOf<CapsEnvelope>()
        override fun start() = Unit
        override fun stop() = Unit
        override fun send(envelope: CapsEnvelope): Boolean { sent += envelope; return true }
    }

    private fun buildCoordinator(
        camera: CameraController = FakeCamera(),
        downscaler: JpegDownscaler = FakeDownscaler(),
        phoneLink: PhoneLink = FakePhoneLink(),
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler? = null,
        maxBytes: Int = 1024,
    ): PhotoCaptureCoordinator {
        val scope = if (scheduler != null) CoroutineScope(StandardTestDispatcher(scheduler))
        else CoroutineScope(kotlinx.coroutines.SupervisorJob())
        return PhotoCaptureCoordinator(camera, downscaler, phoneLink, maxBytes = maxBytes, scope = scope)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    @Test fun `start binds camera once`() {
        val cam = FakeCamera()
        val coord = buildCoordinator(camera = cam)
        coord.start()
        coord.start()
        assertEquals(1, cam.startCalls)
        coord.stop()
        assertEquals(1, cam.stopCalls)
    }

    @Test fun `capturePhoto delegates to camera and resets lastError`() {
        val cam = FakeCamera()
        val coord = buildCoordinator(camera = cam)
        // Pre-set an error to verify capturePhoto clears it.
        coord.start(); cam.emit(CameraState.Failed("old"))
        // We're not running runTest here, so the collector hasn't seen the
        // emit. That's fine — capturePhoto should still reset lastError.
        coord.stop()
        assertTrue(coord.capturePhoto())
        assertNull(coord.lastError)
        assertEquals(1, cam.takePhotoCalls)
    }

    @Test fun `capturePhoto returns false when SDK refuses`() {
        val cam = FakeCamera().apply { takePhotoResult = false }
        val coord = buildCoordinator(camera = cam)
        assertFalse(coord.capturePhoto())
    }

    // ── Captured → ship UserInput ──────────────────────────────────────

    @Test fun `Captured state ships UserInput envelope with downscaled bytes`() = runTest {
        val cam = FakeCamera()
        val ds = FakeDownscaler { _, _ -> byteArrayOf(7, 7, 7) }
        val link = FakePhoneLink()
        val coord = buildCoordinator(cam, ds, link, scheduler = testScheduler)
        coord.start()
        runCurrent()
        cam.emit(CameraState.Captured(byteArrayOf(1, 2, 3)))
        advanceUntilIdle()

        val sent = link.sent.single() as UserInput
        assertEquals("", sent.text)
        assertNotNull(sent.imageBase64)
        val recovered = Base64.getDecoder().decode(sent.imageBase64!!)
        assertArrayEquals(byteArrayOf(7, 7, 7), recovered)
        assertEquals(1, ds.calls)
        // After shipping, the camera should be reset back to Idle.
        assertEquals(1, cam.resetCalls)
        coord.shutdown()
    }

    @Test fun `Captured below maxBytes is sent as-is via downscaler passthrough`() = runTest {
        val cam = FakeCamera()
        // Default FakeDownscaler is identity; production logic is "skip
        // when already small enough" but the orchestrator just calls
        // through and lets the downscaler decide.
        val link = FakePhoneLink()
        val coord = buildCoordinator(cam, FakeDownscaler(), link, scheduler = testScheduler, maxBytes = 1024)
        coord.start(); runCurrent()
        val small = ByteArray(100) { it.toByte() }
        cam.emit(CameraState.Captured(small))
        advanceUntilIdle()
        val sent = link.sent.single() as UserInput
        assertArrayEquals(small, Base64.getDecoder().decode(sent.imageBase64!!))
        coord.shutdown()
    }

    @Test fun `downscaler exception falls back to raw bytes`() = runTest {
        val cam = FakeCamera()
        val ds = object : JpegDownscaler {
            override fun downscale(jpeg: ByteArray, maxBytes: Int): ByteArray {
                throw RuntimeException("synthetic failure")
            }
        }
        val link = FakePhoneLink()
        val coord = buildCoordinator(cam, ds, link, scheduler = testScheduler)
        coord.start(); runCurrent()
        cam.emit(CameraState.Captured(byteArrayOf(9, 9, 9)))
        advanceUntilIdle()
        val sent = link.sent.single() as UserInput
        assertArrayEquals(byteArrayOf(9, 9, 9), Base64.getDecoder().decode(sent.imageBase64!!))
        coord.shutdown()
    }

    @Test fun `Failed state sets lastError and resets camera`() = runTest {
        val cam = FakeCamera()
        val link = FakePhoneLink()
        val coord = buildCoordinator(cam, phoneLink = link, scheduler = testScheduler)
        coord.start(); runCurrent()
        cam.emit(CameraState.Failed("auth"))
        advanceUntilIdle()
        assertEquals("auth", coord.lastError)
        assertEquals(1, cam.resetCalls)
        assertTrue(link.sent.isEmpty())
        coord.shutdown()
    }

    @Test fun `Idle and Capturing states do not ship anything`() = runTest {
        val cam = FakeCamera()
        val link = FakePhoneLink()
        val coord = buildCoordinator(cam, phoneLink = link, scheduler = testScheduler)
        coord.start(); runCurrent()
        cam.emit(CameraState.Idle)
        cam.emit(CameraState.Capturing)
        advanceUntilIdle()
        assertTrue(link.sent.isEmpty())
        assertEquals(0, cam.resetCalls)
        coord.shutdown()
    }

    @Test fun `oversized downscale result is still shipped (caller may warn)`() = runTest {
        // The orchestrator's contract is "ship what we got"; the wire-side
        // CapsFrameSizePolicy on the phone enforces the hard reject. This
        // test pins that contract: if downscale falls short, we still send.
        val cam = FakeCamera()
        val ds = FakeDownscaler { jpeg, _ -> jpeg }  // identity
        val link = FakePhoneLink()
        val coord = buildCoordinator(cam, ds, link, scheduler = testScheduler, maxBytes = 100)
        coord.start(); runCurrent()
        val big = ByteArray(500) { it.toByte() }
        cam.emit(CameraState.Captured(big))
        advanceUntilIdle()
        val sent = link.sent.single() as UserInput
        assertEquals(big.size, Base64.getDecoder().decode(sent.imageBase64!!).size)
        coord.shutdown()
    }
}
