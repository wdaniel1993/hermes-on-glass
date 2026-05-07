package dev.wallner.hermesonglass.phone.domain

import dev.wallner.hermesonglass.phone.data.cxr.CapsLink
import dev.wallner.hermesonglass.phone.data.ws.ConnectionState
import dev.wallner.hermesonglass.phone.data.ws.WsConnection
import dev.wallner.hermesonglass.shared.AssistantAudio
import dev.wallner.hermesonglass.shared.AssistantChunk
import dev.wallner.hermesonglass.shared.AssistantComplete
import dev.wallner.hermesonglass.shared.CapsConnectionUpdate
import dev.wallner.hermesonglass.shared.CapsEnvelope
import dev.wallner.hermesonglass.shared.CapsSessionList
import dev.wallner.hermesonglass.shared.CapsSlashCommand
import dev.wallner.hermesonglass.shared.CapsSwitchSession
import dev.wallner.hermesonglass.shared.CapsToolProgress
import dev.wallner.hermesonglass.shared.ChatStream
import dev.wallner.hermesonglass.shared.ChatStreamEnd
import dev.wallner.hermesonglass.shared.ConnectionUpdate
import dev.wallner.hermesonglass.shared.NewSession
import dev.wallner.hermesonglass.shared.PushMessage
import dev.wallner.hermesonglass.shared.ServerWelcome
import dev.wallner.hermesonglass.shared.SessionInfo
import dev.wallner.hermesonglass.shared.SessionList
import dev.wallner.hermesonglass.shared.SlashCommand
import dev.wallner.hermesonglass.shared.SwitchSession
import dev.wallner.hermesonglass.shared.ToolProgress
import dev.wallner.hermesonglass.shared.UserInput
import dev.wallner.hermesonglass.shared.UserMessage
import dev.wallner.hermesonglass.shared.VoiceData
import dev.wallner.hermesonglass.shared.VoiceNote
import dev.wallner.hermesonglass.shared.VoicePlay
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneToGlassesBridgeTest {

    private class FakeWs : WsConnection {
        private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        override val state: StateFlow<ConnectionState> = _state.asStateFlow()
        private val _frames = MutableSharedFlow<WsFrame>(extraBufferCapacity = 32)
        override val frames: SharedFlow<WsFrame> = _frames.asSharedFlow()
        val sent = mutableListOf<WsFrame>()
        override fun start() = Unit
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun setLastSessionKey(sessionKey: String?) = Unit
        override fun send(frame: WsFrame): Boolean { sent += frame; return true }
        suspend fun deliver(frame: WsFrame) = _frames.emit(frame)
    }

    private class FakeCaps : CapsLink {
        private val _state = MutableStateFlow(true)
        override val connected: StateFlow<Boolean> = _state.asStateFlow()
        private val _inbound = MutableSharedFlow<CapsEnvelope>(extraBufferCapacity = 32)
        override val inbound: SharedFlow<CapsEnvelope> = _inbound.asSharedFlow()
        val sent = mutableListOf<CapsEnvelope>()
        override fun start() = Unit
        override fun stop() = Unit
        override fun send(envelope: CapsEnvelope): Boolean { sent += envelope; return true }
        suspend fun deliver(envelope: CapsEnvelope) = _inbound.emit(envelope)
    }

    // ── Translation: WS -> Caps ────────────────────────────────────────

    private val bridge = PhoneToGlassesBridge(FakeWs(), FakeCaps())

    @Test fun `assistant_chunk maps to chat_stream`() {
        val out = bridge.downstream(AssistantChunk(id = "m1", chunk = "hi", sessionKey = "s")) as ChatStream
        assertEquals("m1", out.id)
        assertEquals("hi", out.chunk)
        assertEquals("s", out.sessionKey)
    }

    @Test fun `assistant_complete maps to chat_stream_end`() {
        val out = bridge.downstream(AssistantComplete(id = "m1")) as ChatStreamEnd
        assertEquals("m1", out.id)
    }

    @Test fun `tool_progress translates fields one to one`() {
        val out = bridge.downstream(ToolProgress(
            messageId = "t1", toolName = "web_search", phase = "started", preview = "hi",
        )) as CapsToolProgress
        assertEquals("web_search", out.toolName)
        assertEquals("started", out.phase)
        assertEquals("hi", out.preview)
    }

    @Test fun `server_welcome and session_list both produce caps_session_list`() {
        val sessions = listOf(SessionInfo("a"), SessionInfo("b"))
        val w = bridge.downstream(ServerWelcome(1, sessions, "a")) as CapsSessionList
        assertEquals(2, w.sessions.size)
        val l = bridge.downstream(SessionList(sessions, "b")) as CapsSessionList
        assertEquals("b", l.currentSessionKey)
    }

    @Test fun `push_message is not handled by the bridge — owned by WakeSignalManager`() {
        // The wake-signal + buffered chat_message coordination needs ack
        // tracking; that lives in WakeSignalManager which subscribes to the
        // ws frames separately. The bridge returns null so it doesn't
        // double-emit.
        assertNull(bridge.downstream(PushMessage(
            origin = "cron-job", sessionKey = "s", messageId = "p1", text = "stand up",
        )))
    }

    @Test fun `connection_update is mirrored`() {
        val out = bridge.downstream(ConnectionUpdate(connected = false)) as CapsConnectionUpdate
        assertEquals(false, out.connected)
    }

    @Test fun `unmapped frames are dropped`() {
        // Ping isn't relevant to glasses; bridge returns null so nothing is sent.
        assertNull(bridge.downstream(dev.wallner.hermesonglass.shared.Ping("p")))
    }

    // ── Translation: Caps -> WS ────────────────────────────────────────

    @Test fun `user_input maps to user_message`() {
        val out = bridge.upstream(UserInput(id = "u1", text = "hi", imageBase64 = "AA==")) as UserMessage
        assertEquals("u1", out.id)
        assertEquals("hi", out.text)
        assertEquals("AA==", out.imageBase64)
    }

    @Test fun `caps switch_session maps to ws switch_session`() {
        val out = bridge.upstream(CapsSwitchSession("s2")) as SwitchSession
        assertEquals("s2", out.sessionKey)
    }

    @Test fun `slash_command new-session is rewritten as new_session`() {
        val out = bridge.upstream(CapsSlashCommand("/new-session")) as NewSession
        assertEquals(null, out.name)
    }

    @Test fun `other slash_command commands forward unchanged`() {
        val out = bridge.upstream(CapsSlashCommand("/voice on")) as SlashCommand
        assertEquals("/voice on", out.command)
    }

    @Test fun `voice_capture announcement is dropped in this section`() {
        // The announcement is glasses-internal — phone-side bridge skips it.
        assertNull(bridge.upstream(
            dev.wallner.hermesonglass.shared.VoiceCapture("s1", ".ogg"),
        ))
    }

    @Test fun `assistant_audio maps to voice_play`() {
        val out = bridge.downstream(AssistantAudio(
            id = "a1", bytesBase64 = "AA==", ext = ".ogg", sessionKey = "s",
        )) as VoicePlay
        assertEquals("a1", out.id)
        assertEquals("AA==", out.bytesBase64)
        assertEquals(".ogg", out.ext)
    }

    @Test fun `voice_data from glasses maps to voice_note WS frame`() {
        val out = bridge.upstream(
            VoiceData(streamId = "s1", bytesBase64 = "AA==", ext = ".ogg"),
        ) as VoiceNote
        assertEquals("s1", out.streamId)
        assertEquals("s1", out.id)
        assertEquals(0, out.chunkIndex)
        assertEquals(1, out.totalChunks)
        assertEquals("AA==", out.audioBase64)
        assertEquals(".ogg", out.ext)
    }

    // ── End-to-end flow over fakes ────────────────────────────────────

    @Test fun `chat stream from WS reaches glasses caps`() = runTest {
        val ws = FakeWs(); val caps = FakeCaps()
        val b = PhoneToGlassesBridge(ws, caps,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)))
        b.start()
        runCurrent()  // let the collectors subscribe before emitting
        ws.deliver(AssistantChunk(id = "m1", chunk = "hi"))
        ws.deliver(AssistantComplete(id = "m1"))
        advanceUntilIdle()
        assertTrue(caps.sent[0] is ChatStream)
        assertTrue(caps.sent[1] is ChatStreamEnd)
        b.shutdown()
    }

    @Test fun `user input from glasses caps reaches WS`() = runTest {
        val ws = FakeWs(); val caps = FakeCaps()
        val b = PhoneToGlassesBridge(ws, caps,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)))
        b.start()
        runCurrent()
        caps.deliver(UserInput(id = "u1", text = "hello"))
        advanceUntilIdle()
        val out = ws.sent.single() as UserMessage
        assertEquals("hello", out.text)
        b.shutdown()
    }
}
