package dev.wallner.hermesonglass.glasses.domain

import dev.wallner.hermesonglass.glasses.data.PhoneLink
import dev.wallner.hermesonglass.glasses.ui.hud.FocusArea
import dev.wallner.hermesonglass.glasses.ui.hud.HudIntent
import dev.wallner.hermesonglass.glasses.ui.hud.HudMessage
import dev.wallner.hermesonglass.glasses.ui.hud.HudPosition
import dev.wallner.hermesonglass.shared.CapsConnectionUpdate
import dev.wallner.hermesonglass.shared.CapsEnvelope
import dev.wallner.hermesonglass.shared.CapsSessionList
import dev.wallner.hermesonglass.shared.CapsToolProgress
import dev.wallner.hermesonglass.shared.ChatMessage
import dev.wallner.hermesonglass.shared.ChatStream
import dev.wallner.hermesonglass.shared.ChatStreamEnd
import dev.wallner.hermesonglass.shared.SessionInfo
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
class HudRepositoryTest {

    private class FakePhoneLink : PhoneLink {
        private val _envelopes = MutableSharedFlow<CapsEnvelope>(extraBufferCapacity = 64)
        override val envelopes: SharedFlow<CapsEnvelope> = _envelopes.asSharedFlow()

        private val _connected = MutableStateFlow(false)
        override val connected: StateFlow<Boolean> = _connected.asStateFlow()

        var started = false
        var stopped = false
        val sent = mutableListOf<CapsEnvelope>()

        override fun start() { started = true }
        override fun stop() { stopped = true }
        override fun send(envelope: CapsEnvelope): Boolean { sent += envelope; return true }

        suspend fun deliver(env: CapsEnvelope) = _envelopes.emit(env)
        fun setConnected(v: Boolean) { _connected.value = v }
    }

    private fun TestScope.newRepo(
        onPhotoRequested: () -> Unit = {},
        onNewSessionRequested: () -> Unit = {},
        onSessionPicked: (String) -> Unit = {},
    ): Pair<HudRepository, FakePhoneLink> {
        val link = FakePhoneLink()
        val repo = HudRepository(
            phone = link,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            onPhotoRequested = onPhotoRequested,
            onNewSessionRequested = onNewSessionRequested,
            onSessionPicked = onSessionPicked,
        )
        repo.start()
        advanceUntilIdle()
        return repo to link
    }

    // ── Wire envelopes to UI state ─────────────────────────────────────

    @Test fun `start subscribes phone link and listens for envelopes`() = runTest {
        val (_, link) = newRepo()
        assertTrue(link.started)
    }

    @Test fun `chat_stream creates and appends streaming message`() = runTest {
        val (repo, link) = newRepo()
        link.deliver(ChatStream(id = "m1", chunk = "hel"))
        advanceUntilIdle()
        assertEquals("hel", repo.state.value.messages[0].text)
        assertTrue(repo.state.value.messages[0].streaming)

        link.deliver(ChatStream(id = "m1", chunk = "lo"))
        advanceUntilIdle()
        assertEquals("hello", repo.state.value.messages[0].text)
    }

    @Test fun `chat_stream_end flips streaming to false`() = runTest {
        val (repo, link) = newRepo()
        link.deliver(ChatStream(id = "m1", chunk = "hi"))
        link.deliver(ChatStreamEnd(id = "m1"))
        advanceUntilIdle()
        assertFalse(repo.state.value.messages[0].streaming)
    }

    @Test fun `chat_message replaces streaming message in place`() = runTest {
        val (repo, link) = newRepo()
        link.deliver(ChatStream(id = "m1", chunk = "thinking..."))
        link.deliver(ChatMessage(messageId = "m1", text = "the answer"))
        advanceUntilIdle()
        assertEquals(1, repo.state.value.messages.size)
        assertEquals("the answer", repo.state.value.messages[0].text)
        assertFalse(repo.state.value.messages[0].streaming)
    }

    @Test fun `tool_progress sets and clears active subline`() = runTest {
        val (repo, link) = newRepo()
        link.deliver(CapsToolProgress(messageId = "m1", toolName = "web_search", phase = "started", preview = "q"))
        advanceUntilIdle()
        assertNotNull(repo.state.value.activeToolProgress)
        assertEquals("web_search", repo.state.value.activeToolProgress!!.toolName)
        link.deliver(CapsToolProgress(messageId = "m1", toolName = "web_search", phase = "completed"))
        advanceUntilIdle()
        assertNull(repo.state.value.activeToolProgress)
    }

    @Test fun `chat_stream_end clears tool subline for that message`() = runTest {
        val (repo, link) = newRepo()
        link.deliver(ChatStream(id = "m1", chunk = "x"))
        link.deliver(CapsToolProgress(messageId = "m1", toolName = "web_search", phase = "started"))
        link.deliver(ChatStreamEnd(id = "m1"))
        advanceUntilIdle()
        assertNull(repo.state.value.activeToolProgress)
    }

    @Test fun `connection_update toggles phoneConnected flag`() = runTest {
        val (repo, link) = newRepo()
        link.deliver(CapsConnectionUpdate(connected = true))
        advanceUntilIdle()
        assertTrue(repo.state.value.phoneConnected)
        link.deliver(CapsConnectionUpdate(connected = false))
        advanceUntilIdle()
        assertFalse(repo.state.value.phoneConnected)
    }

    @Test fun `session_list updates sessions and current key`() = runTest {
        val (repo, link) = newRepo()
        link.deliver(CapsSessionList(
            sessions = listOf(SessionInfo("a"), SessionInfo("b")),
            currentSessionKey = "b",
        ))
        advanceUntilIdle()
        assertEquals(2, repo.state.value.sessions.size)
        assertEquals("b", repo.state.value.currentSessionKey)
    }

    @Test fun `phone link connected flow updates state`() = runTest {
        val (repo, link) = newRepo()
        link.setConnected(true)
        advanceUntilIdle()
        assertTrue(repo.state.value.phoneConnected)
    }

    // ── Focus state machine + position cycle ───────────────────────────

    @Test fun `nav-right pulls focus into menu and selects`() = runTest {
        val (repo, _) = newRepo()
        assertEquals(FocusArea.Content, repo.state.value.focus)
        repo.applyIntent(HudIntent.NavRight)
        advanceUntilIdle()
        val f = repo.state.value.focus as FocusArea.Menu
        assertEquals(0, f.selectedIndex)

        repo.applyIntent(HudIntent.NavRight)
        advanceUntilIdle()
        val f2 = repo.state.value.focus as FocusArea.Menu
        assertEquals(1, f2.selectedIndex)
    }

    @Test fun `nav-left clamps at 0 and nav-right clamps at last item`() = runTest {
        val (repo, _) = newRepo()
        repo.applyIntent(HudIntent.NavRight)
        repo.applyIntent(HudIntent.NavLeft)
        repo.applyIntent(HudIntent.NavLeft)
        advanceUntilIdle()
        val f = repo.state.value.focus as FocusArea.Menu
        assertEquals(0, f.selectedIndex)

        repeat(HudRepository.MENU_ITEMS.size + 3) { repo.applyIntent(HudIntent.NavRight) }
        advanceUntilIdle()
        val last = repo.state.value.focus as FocusArea.Menu
        assertEquals(HudRepository.MENU_ITEMS.lastIndex, last.selectedIndex)
    }

    @Test fun `Back collapses menu focus to content`() = runTest {
        val (repo, _) = newRepo()
        repo.applyIntent(HudIntent.NavRight)
        repo.applyIntent(HudIntent.Back)
        advanceUntilIdle()
        assertEquals(FocusArea.Content, repo.state.value.focus)
    }

    @Test fun `TapCenter on SIZE menu item cycles position`() = runTest {
        val (repo, _) = newRepo()
        // Open menu to SIZE (index 0).
        repo.applyIntent(HudIntent.NavRight)
        assertEquals(HudPosition.FULL, repo.state.value.position)
        repo.applyIntent(HudIntent.TapCenter)
        advanceUntilIdle()
        assertEquals(HudPosition.BOTTOM_HALF, repo.state.value.position)
        repo.applyIntent(HudIntent.TapCenter)
        advanceUntilIdle()
        assertEquals(HudPosition.TOP_HALF, repo.state.value.position)
        repo.applyIntent(HudIntent.TapCenter)
        advanceUntilIdle()
        assertEquals(HudPosition.FULL, repo.state.value.position)
    }

    @Test fun `LongPressCenter toggles recordingVoice`() = runTest {
        val (repo, _) = newRepo()
        repo.applyIntent(HudIntent.LongPressCenterDown)
        advanceUntilIdle()
        assertTrue(repo.state.value.recordingVoice)
        repo.applyIntent(HudIntent.LongPressCenterUp)
        advanceUntilIdle()
        assertFalse(repo.state.value.recordingVoice)
    }

    @Test fun `setSetupError surfaces error string`() = runTest {
        val (repo, _) = newRepo()
        repo.setSetupError("Rokid AI app missing")
        advanceUntilIdle()
        assertEquals("Rokid AI app missing", repo.state.value.setupError)
    }

    @Test fun `TapCenter on PHOTO menu item invokes onPhotoRequested`() = runTest {
        var photoCalls = 0
        val (repo, _) = newRepo(onPhotoRequested = { photoCalls += 1 })
        // Open menu, advance to PHOTO (index 2 in MENU_ITEMS).
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.NavRight)
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.NavRight)
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.NavRight)
        advanceUntilIdle()
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.TapCenter)
        advanceUntilIdle()
        assertEquals(1, photoCalls)
    }

    @Test fun `TapCenter on NEW menu item invokes onNewSessionRequested`() = runTest {
        var newCalls = 0
        val (repo, _) = newRepo(onNewSessionRequested = { newCalls += 1 })
        // Open menu, advance to NEW (last index = 3).
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.NavRight)
        repeat(3) { repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.NavRight) }
        advanceUntilIdle()
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.TapCenter)
        advanceUntilIdle()
        assertEquals(1, newCalls)
    }

    // ── Session picker (§11) ───────────────────────────────────────────

    @Test fun `TapCenter on SESSIONS menu opens the picker focused on current`() = runTest {
        val (repo, link) = newRepo()
        // Seed sessions; current = "b" (index 1).
        link.deliver(CapsSessionList(
            sessions = listOf(SessionInfo("a"), SessionInfo("b"), SessionInfo("c")),
            currentSessionKey = "b",
        ))
        advanceUntilIdle()
        // Open menu, navigate to SESSIONS (index 1).
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.NavRight)
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.NavRight)
        advanceUntilIdle()
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.TapCenter)
        advanceUntilIdle()
        val focus = repo.state.value.focus as
            dev.wallner.hermesonglass.glasses.ui.hud.FocusArea.SessionPicker
        assertEquals(1, focus.selectedIndex)  // points at "b"
    }

    @Test fun `picker NavUp NavDown moves selection`() = runTest {
        val (repo, link) = newRepo()
        link.deliver(CapsSessionList(
            sessions = listOf(SessionInfo("a"), SessionInfo("b"), SessionInfo("c")),
            currentSessionKey = "a",
        ))
        advanceUntilIdle()
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.NavRight)
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.NavRight)
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.TapCenter)
        advanceUntilIdle()

        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.NavDown)
        advanceUntilIdle()
        var f = repo.state.value.focus as
            dev.wallner.hermesonglass.glasses.ui.hud.FocusArea.SessionPicker
        assertEquals(1, f.selectedIndex)

        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.NavDown)
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.NavDown)  // clamps
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.NavUp)
        advanceUntilIdle()
        f = repo.state.value.focus as
            dev.wallner.hermesonglass.glasses.ui.hud.FocusArea.SessionPicker
        assertEquals(1, f.selectedIndex)
    }

    @Test fun `picker TapCenter invokes onSessionPicked and returns to Content`() = runTest {
        val picked = mutableListOf<String>()
        val (repo, link) = newRepo(onSessionPicked = { picked += it })
        link.deliver(CapsSessionList(
            sessions = listOf(SessionInfo("a"), SessionInfo("b")),
            currentSessionKey = "a",
        ))
        advanceUntilIdle()
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.NavRight)
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.NavRight)
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.TapCenter)
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.NavDown)
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.TapCenter)
        advanceUntilIdle()
        assertEquals(listOf("b"), picked)
        assertEquals(
            dev.wallner.hermesonglass.glasses.ui.hud.FocusArea.Content,
            repo.state.value.focus,
        )
    }

    @Test fun `wake_signal triggers a wake_ack envelope back to the phone`() = runTest {
        val (_, link) = newRepo()
        link.deliver(dev.wallner.hermesonglass.shared.WakeSignal(
            reason = "cron-job", messageId = "p1",
        ))
        advanceUntilIdle()
        val ack = link.sent.single() as dev.wallner.hermesonglass.shared.WakeAck
        assertTrue(ack.ready)
        assertEquals("p1", ack.messageId)
    }

    @Test fun `picker Back closes without invoking onSessionPicked`() = runTest {
        val picked = mutableListOf<String>()
        val (repo, link) = newRepo(onSessionPicked = { picked += it })
        link.deliver(CapsSessionList(
            sessions = listOf(SessionInfo("a")),
            currentSessionKey = "a",
        ))
        advanceUntilIdle()
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.NavRight)
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.NavRight)
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.TapCenter)
        advanceUntilIdle()
        repo.applyIntent(dev.wallner.hermesonglass.glasses.ui.hud.HudIntent.Back)
        advanceUntilIdle()
        assertTrue(picked.isEmpty())
        assertEquals(
            dev.wallner.hermesonglass.glasses.ui.hud.FocusArea.Content,
            repo.state.value.focus,
        )
    }
}
