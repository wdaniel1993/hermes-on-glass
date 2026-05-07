package dev.wallner.hermesonglass.phone.domain

import dev.wallner.hermesonglass.phone.data.ws.ConnectionState
import dev.wallner.hermesonglass.phone.data.ws.WsConnection
import dev.wallner.hermesonglass.shared.AssistantChunk
import dev.wallner.hermesonglass.shared.AssistantComplete
import dev.wallner.hermesonglass.shared.NewSession
import dev.wallner.hermesonglass.shared.PushMessage
import dev.wallner.hermesonglass.shared.ServerWelcome
import dev.wallner.hermesonglass.shared.SessionInfo
import dev.wallner.hermesonglass.shared.SessionList
import dev.wallner.hermesonglass.shared.SwitchSession
import dev.wallner.hermesonglass.shared.ToolProgress
import dev.wallner.hermesonglass.shared.UserMessage
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositoryTest {

    private class FakeWsConnection : WsConnection {
        private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val state: StateFlow<ConnectionState> = _state.asStateFlow()

        private val _frames = MutableSharedFlow<WsFrame>(extraBufferCapacity = 64)
        override val frames: SharedFlow<WsFrame> = _frames.asSharedFlow()

        val sent = mutableListOf<WsFrame>()
        val sessionKeyHistory = mutableListOf<String?>()
        var connectedSendsSucceed: Boolean = true

        override fun start() {
            _state.value = ConnectionState.Connected
        }

        override fun stop() {
            _state.value = ConnectionState.Disconnected
        }

        override fun shutdown() {
            stop()
        }

        override fun send(frame: WsFrame): Boolean {
            sent.add(frame)
            return connectedSendsSucceed && _state.value is ConnectionState.Connected
        }

        override fun setLastSessionKey(sessionKey: String?) {
            sessionKeyHistory.add(sessionKey)
        }

        suspend fun deliver(frame: WsFrame) {
            _frames.emit(frame)
        }
    }

    private fun TestScope.newRepo(): Pair<ChatRepository, FakeWsConnection> {
        val fake = FakeWsConnection()
        val repo = ChatRepository(
            wsClient = fake,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        )
        repo.start()
        advanceUntilIdle()
        return repo to fake
    }

    @Test fun `assistant_chunk creates a streaming message and appends`() = runTest {
        val (repo, fake) = newRepo()
        fake.deliver(AssistantChunk(id = "m1", chunk = "hel"))
        advanceUntilIdle()
        assertEquals("hel", repo.state.value.messages[0].text)
        assertEquals(ChatMessage.State.STREAMING, repo.state.value.messages[0].state)

        fake.deliver(AssistantChunk(id = "m1", chunk = "lo"))
        advanceUntilIdle()
        assertEquals("hello", repo.state.value.messages[0].text)
        repo.shutdown()
    }

    @Test fun `assistant_complete flips state to COMPLETE`() = runTest {
        val (repo, fake) = newRepo()
        fake.deliver(AssistantChunk(id = "m1", chunk = "hi"))
        fake.deliver(AssistantComplete(id = "m1"))
        advanceUntilIdle()
        assertEquals(ChatMessage.State.COMPLETE, repo.state.value.messages[0].state)
        repo.shutdown()
    }

    @Test fun `tool_progress sets and clears active subline`() = runTest {
        val (repo, fake) = newRepo()
        fake.deliver(ToolProgress(messageId = "m1", toolName = "web_search", phase = "started", preview = "q"))
        advanceUntilIdle()
        assertNotNull(repo.state.value.activeToolProgress)
        assertEquals("web_search", repo.state.value.activeToolProgress!!.toolName)

        fake.deliver(ToolProgress(messageId = "m1", toolName = "web_search", phase = "completed"))
        advanceUntilIdle()
        assertNull(repo.state.value.activeToolProgress)
        repo.shutdown()
    }

    @Test fun `assistant_complete clears tool_progress for that message`() = runTest {
        val (repo, fake) = newRepo()
        fake.deliver(AssistantChunk(id = "m1", chunk = "thinking"))
        fake.deliver(ToolProgress(messageId = "m1", toolName = "web_search", phase = "started"))
        advanceUntilIdle()
        assertNotNull(repo.state.value.activeToolProgress)
        fake.deliver(AssistantComplete(id = "m1"))
        advanceUntilIdle()
        assertNull(repo.state.value.activeToolProgress)
        repo.shutdown()
    }

    @Test fun `server_welcome and session_list update sessions`() = runTest {
        val (repo, fake) = newRepo()
        fake.deliver(ServerWelcome(
            protocolVersion = 1,
            sessions = listOf(SessionInfo("a"), SessionInfo("b")),
            currentSessionKey = "a",
        ))
        advanceUntilIdle()
        assertEquals(2, repo.state.value.sessions.size)
        assertEquals("a", repo.state.value.currentSessionKey)
        // setLastSessionKey is called so reconnect remembers the cursor.
        assertEquals(listOf<String?>("a"), fake.sessionKeyHistory)

        fake.deliver(SessionList(
            sessions = listOf(SessionInfo("a"), SessionInfo("b"), SessionInfo("c")),
            currentSessionKey = "c",
        ))
        advanceUntilIdle()
        assertEquals(3, repo.state.value.sessions.size)
        assertEquals("c", repo.state.value.currentSessionKey)
        assertEquals(listOf<String?>("a", "c"), fake.sessionKeyHistory)
        repo.shutdown()
    }

    @Test fun `push_message lands as system message preserving origin tag`() = runTest {
        val (repo, fake) = newRepo()
        fake.deliver(PushMessage(
            origin = "cron-job",
            sessionKey = "s1",
            messageId = "p1",
            text = "stand up",
            jobId = "j1",
        ))
        advanceUntilIdle()
        val msg = repo.state.value.messages.single()
        assertEquals(ChatMessage.Role.SYSTEM, msg.role)
        assertTrue(msg.text.contains("cron-job"))
        assertTrue(msg.text.contains("stand up"))
        repo.shutdown()
    }

    @Test fun `sendUserText emits user_message and appends local USER row`() = runTest {
        val (repo, fake) = newRepo()
        // Repo seeds currentSessionKey from server_welcome.
        fake.deliver(ServerWelcome(
            protocolVersion = 1,
            sessions = listOf(SessionInfo("s1")),
            currentSessionKey = "s1",
        ))
        advanceUntilIdle()

        val ok = repo.sendUserText("hello")
        assertTrue(ok)
        val sentFrame = fake.sent.last() as UserMessage
        assertEquals("hello", sentFrame.text)
        assertEquals("s1", sentFrame.sessionKey)
        // Local echo
        val msg = repo.state.value.messages.single { it.role == ChatMessage.Role.USER }
        assertEquals("hello", msg.text)
        repo.shutdown()
    }

    @Test fun `sendUserText returns false and does not append when send fails`() = runTest {
        val (repo, fake) = newRepo()
        fake.connectedSendsSucceed = false
        val ok = repo.sendUserText("ignored")
        assertFalse(ok)
        assertTrue(repo.state.value.messages.isEmpty())
        repo.shutdown()
    }

    @Test fun `sendUserText skips empty input`() = runTest {
        val (repo, fake) = newRepo()
        assertFalse(repo.sendUserText("   "))
        assertTrue(fake.sent.isEmpty())
        repo.shutdown()
    }

    @Test fun `switchSession sends switch_session and remembers cursor`() = runTest {
        val (repo, fake) = newRepo()
        repo.switchSession("xyz")
        val frame = fake.sent.last() as SwitchSession
        assertEquals("xyz", frame.sessionKey)
        assertEquals(listOf<String?>("xyz"), fake.sessionKeyHistory)
        repo.shutdown()
    }

    @Test fun `newSession sends new_session frame`() = runTest {
        val (repo, fake) = newRepo()
        repo.newSession()
        assertTrue(fake.sent.last() is NewSession)
        repo.shutdown()
    }
}
