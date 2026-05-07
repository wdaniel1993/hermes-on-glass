package dev.wallner.hermesonglass.glasses.data.voice

import dev.wallner.hermesonglass.glasses.data.PhoneLink
import dev.wallner.hermesonglass.glasses.ui.hud.HudIntent
import dev.wallner.hermesonglass.shared.CapsEnvelope
import dev.wallner.hermesonglass.shared.VoiceData
import dev.wallner.hermesonglass.shared.VoicePlay
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
class VoiceControllerTest {

    private class FakeRecorder(val payload: ByteArray? = byteArrayOf(1, 2, 3, 4)) : AudioRecorder {
        var startCalls = 0
        var stopCalls = 0
        @Volatile override var isRecording: Boolean = false
            private set

        override fun start(): Boolean {
            startCalls += 1
            isRecording = true
            return true
        }
        override fun stop(): ByteArray? {
            stopCalls += 1
            isRecording = false
            return payload
        }
    }

    private class FakePlayer : AudioPlayer {
        val plays = mutableListOf<Pair<ByteArray, String>>()
        var stopCalls = 0
        override fun play(bytes: ByteArray, ext: String): Boolean {
            plays += bytes to ext
            return true
        }
        override fun stop() { stopCalls += 1 }
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
        suspend fun deliver(envelope: CapsEnvelope) = _envelopes.emit(envelope)
    }

    // ── Recording: long-press → start; release → stop + send VoiceData ────

    @Test fun `LongPressCenterDown starts the recorder`() {
        val rec = FakeRecorder(); val pl = FakePlayer(); val link = FakePhoneLink()
        val vc = VoiceController(rec, pl, link)
        val consumed = vc.onIntent(HudIntent.LongPressCenterDown)
        assertTrue(consumed)
        assertEquals(1, rec.startCalls)
        assertTrue(rec.isRecording)
    }

    @Test fun `LongPressCenterUp stops the recorder and sends VoiceData`() {
        val rec = FakeRecorder(payload = byteArrayOf(10, 20, 30))
        val pl = FakePlayer(); val link = FakePhoneLink()
        val vc = VoiceController(rec, pl, link)
        vc.onIntent(HudIntent.LongPressCenterDown)
        val consumed = vc.onIntent(HudIntent.LongPressCenterUp)
        assertTrue(consumed)
        assertEquals(1, rec.stopCalls)
        val sent = link.sent.single() as VoiceData
        assertArrayEquals(byteArrayOf(10, 20, 30), Base64.getDecoder().decode(sent.bytesBase64))
        assertEquals(".ogg", sent.ext)
        assertNotNull(sent.streamId)
    }

    @Test fun `LongPressCenterUp with no captured bytes does not send`() {
        val rec = FakeRecorder(payload = null)
        val vc = VoiceController(rec, FakePlayer(), FakePhoneLink().also { /* sent stays empty */ })
        vc.onIntent(HudIntent.LongPressCenterDown)
        val consumed = vc.onIntent(HudIntent.LongPressCenterUp)
        assertTrue(consumed)
    }

    @Test fun `LongPressCenterUp with empty bytes does not send`() {
        val rec = FakeRecorder(payload = ByteArray(0))
        val link = FakePhoneLink()
        val vc = VoiceController(rec, FakePlayer(), link)
        vc.onIntent(HudIntent.LongPressCenterDown)
        vc.onIntent(HudIntent.LongPressCenterUp)
        assertTrue(link.sent.isEmpty())
    }

    @Test fun `non-voice intents are not consumed`() {
        val vc = VoiceController(FakeRecorder(), FakePlayer(), FakePhoneLink())
        assertFalse(vc.onIntent(HudIntent.NavLeft))
        assertFalse(vc.onIntent(HudIntent.TapCenter))
        assertFalse(vc.onIntent(HudIntent.Camera))
    }

    // ── Playback: VoicePlay envelope → AudioPlayer ────────────────────────

    @Test fun `VoicePlay envelope drives the player with decoded bytes`() = runTest {
        val rec = FakeRecorder(); val pl = FakePlayer(); val link = FakePhoneLink()
        val vc = VoiceController(rec, pl, link,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)))
        vc.start()
        runCurrent()
        val ttsBytes = byteArrayOf(0, 1, 2, 3, 4, 5)
        val b64 = Base64.getEncoder().encodeToString(ttsBytes)
        link.deliver(VoicePlay(id = "a1", bytesBase64 = b64, ext = ".ogg"))
        advanceUntilIdle()
        val (played, ext) = pl.plays.single()
        assertArrayEquals(ttsBytes, played)
        assertEquals(".ogg", ext)
        vc.shutdown()
    }

    @Test fun `malformed VoicePlay base64 does not crash`() = runTest {
        val rec = FakeRecorder(); val pl = FakePlayer(); val link = FakePhoneLink()
        val vc = VoiceController(rec, pl, link,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)))
        vc.start()
        runCurrent()
        link.deliver(VoicePlay(id = "a1", bytesBase64 = "!!!not base64!!!", ext = ".ogg"))
        advanceUntilIdle()
        assertTrue(pl.plays.isEmpty())
        vc.shutdown()
    }

    @Test fun `non-VoicePlay envelopes are ignored by the controller`() = runTest {
        val pl = FakePlayer(); val link = FakePhoneLink()
        val vc = VoiceController(FakeRecorder(), pl, link,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)))
        vc.start()
        runCurrent()
        link.deliver(dev.wallner.hermesonglass.shared.AgentThinking(active = true))
        advanceUntilIdle()
        assertTrue(pl.plays.isEmpty())
        vc.shutdown()
    }

    @Test fun `stop releases the recorder and player`() {
        val rec = FakeRecorder(); val pl = FakePlayer(); val link = FakePhoneLink()
        val vc = VoiceController(rec, pl, link)
        vc.onIntent(HudIntent.LongPressCenterDown)
        vc.stop()
        // stop() flushes the recorder and stops the player.
        assertEquals(1, rec.stopCalls)
        assertEquals(1, pl.stopCalls)
    }
}
