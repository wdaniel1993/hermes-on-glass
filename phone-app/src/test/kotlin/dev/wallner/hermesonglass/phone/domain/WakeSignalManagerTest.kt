package dev.wallner.hermesonglass.phone.domain

import dev.wallner.hermesonglass.phone.data.cxr.CapsLink
import dev.wallner.hermesonglass.phone.data.ws.ConnectionState
import dev.wallner.hermesonglass.phone.data.ws.WsConnection
import dev.wallner.hermesonglass.shared.CapsEnvelope
import dev.wallner.hermesonglass.shared.ChatMessage
import dev.wallner.hermesonglass.shared.PushMessage
import dev.wallner.hermesonglass.shared.WakeAck
import dev.wallner.hermesonglass.shared.WakeSignal
import dev.wallner.hermesonglass.shared.WsFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WakeSignalManagerTest {

    private class FakeWs : WsConnection {
        override val state: StateFlow<ConnectionState> =
            MutableStateFlow(ConnectionState.Connected).asStateFlow()
        private val _frames = MutableSharedFlow<WsFrame>(extraBufferCapacity = 32)
        override val frames: SharedFlow<WsFrame> = _frames.asSharedFlow()
        override fun start() = Unit
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun setLastSessionKey(sessionKey: String?) = Unit
        override fun send(frame: WsFrame): Boolean = true
        suspend fun deliver(frame: WsFrame) = _frames.emit(frame)
    }

    private class FakeCaps : CapsLink {
        override val connected: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
        private val _inbound = MutableSharedFlow<CapsEnvelope>(extraBufferCapacity = 32)
        override val inbound: SharedFlow<CapsEnvelope> = _inbound.asSharedFlow()
        val sent = mutableListOf<CapsEnvelope>()
        override fun start() = Unit
        override fun stop() = Unit
        override fun send(envelope: CapsEnvelope): Boolean { sent += envelope; return true }
        suspend fun deliver(envelope: CapsEnvelope) = _inbound.emit(envelope)
    }

    private fun build(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        timeoutMs: Long = 1_000,
    ): Triple<WakeSignalManager, FakeWs, FakeCaps> {
        val ws = FakeWs(); val caps = FakeCaps()
        val mgr = WakeSignalManager(
            ws = ws,
            caps = caps,
            scope = CoroutineScope(StandardTestDispatcher(scheduler)),
            timeoutMs = timeoutMs,
        )
        return Triple(mgr, ws, caps)
    }

    @Test fun `WakeSignal goes out before ChatMessage when ack arrives ready=true`() = runTest {
        val (mgr, ws, caps) = build(testScheduler)
        mgr.start()
        runCurrent()
        ws.deliver(PushMessage(
            origin = "cron-job",
            sessionKey = "s1",
            messageId = "p1",
            text = "stand up",
            jobId = "j1",
        ))
        // Let the deliver() coroutine run up to the await — it sends WakeSignal
        // and then suspends.
        runCurrent()
        assertEquals(1, caps.sent.size)
        val wake = caps.sent[0] as WakeSignal
        assertEquals("cron-job", wake.reason)
        assertEquals("p1", wake.messageId)

        // Glasses ack as ready.
        caps.deliver(WakeAck(ready = true, messageId = "p1"))
        advanceUntilIdle()

        val chat = caps.sent[1] as ChatMessage
        assertEquals("p1", chat.messageId)
        assertTrue(chat.text.contains("stand up"))
        assertTrue(chat.text.contains("cron-job"))
        assertEquals("s1", chat.sessionKey)
        mgr.shutdown()
    }

    @Test fun `WakeSignal still delivers ChatMessage when ack reports ready=false`() = runTest {
        val (mgr, ws, caps) = build(testScheduler)
        mgr.start()
        runCurrent()
        ws.deliver(PushMessage(
            origin = "agent-nudge", sessionKey = "s", messageId = "p2", text = "hello",
        ))
        runCurrent()
        caps.deliver(WakeAck(ready = false, messageId = "p2"))
        advanceUntilIdle()
        // Two outbound: WakeSignal + ChatMessage even though the HUD said it
        // wasn't ready — losing the message would be worse than rendering it
        // late.
        assertEquals(2, caps.sent.size)
        assertTrue(caps.sent[1] is ChatMessage)
        mgr.shutdown()
    }

    @Test fun `timeout still delivers ChatMessage and the wake signal logs warn`() = runTest {
        val (mgr, ws, caps) = build(testScheduler, timeoutMs = 200)
        mgr.start()
        runCurrent()
        ws.deliver(PushMessage(
            origin = "run-completion", sessionKey = "s", messageId = "p3", text = "done",
        ))
        runCurrent()
        // No ack — advance past timeout.
        advanceTimeBy(250)
        advanceUntilIdle()
        assertEquals(2, caps.sent.size)
        val chat = caps.sent[1] as ChatMessage
        assertTrue(chat.text.contains("done"))
        assertTrue(chat.text.contains("run-completion"))
        mgr.shutdown()
    }

    @Test fun `acks for unknown messageIds are ignored`() = runTest {
        val (mgr, _, caps) = build(testScheduler, timeoutMs = 100)
        mgr.start()
        runCurrent()
        // No outstanding push; this ack should be a silent no-op.
        caps.deliver(WakeAck(ready = true, messageId = "ghost"))
        advanceUntilIdle()
        assertEquals(0, caps.sent.size)
        mgr.shutdown()
    }

    @Test fun `concurrent pushes each get their own ack track`() = runTest {
        // Long timeout so we can verify ack-driven ordering without the
        // virtual clock firing a wake-timeout for the second push.
        val (mgr, ws, caps) = build(testScheduler, timeoutMs = 60_000)
        mgr.start()
        runCurrent()
        ws.deliver(PushMessage(origin = "cron-job", sessionKey = "s", messageId = "a", text = "A"))
        ws.deliver(PushMessage(origin = "cron-job", sessionKey = "s", messageId = "b", text = "B"))
        runCurrent()
        // Two WakeSignals out before any ack.
        val signals = caps.sent.filterIsInstance<WakeSignal>()
        assertEquals(2, signals.size)

        caps.deliver(WakeAck(ready = true, messageId = "b"))
        runCurrent()  // drain the b deliver-coroutine without firing a's timeout
        val chats1 = caps.sent.filterIsInstance<ChatMessage>().map { it.messageId }
        assertEquals(listOf("b"), chats1)

        caps.deliver(WakeAck(ready = true, messageId = "a"))
        runCurrent()
        val chats2 = caps.sent.filterIsInstance<ChatMessage>().map { it.messageId }
        assertEquals(listOf("b", "a"), chats2)
        mgr.shutdown()
    }
}
