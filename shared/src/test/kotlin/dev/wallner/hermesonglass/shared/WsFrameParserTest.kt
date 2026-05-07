package dev.wallner.hermesonglass.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WsFrameParserTest {

    // ── Inbound (phone -> adapter) ──────────────────────────────────────

    @Test fun `client_hello minimal`() {
        val f = FrameParser.parseWsFrame("""{"type":"client_hello","protocolVersion":1}""")
        assertIs<ClientHello>(f)
        assertEquals(1, f.protocolVersion)
        assertNull(f.deviceId)
        assertNull(f.currentSessionKey)
    }

    @Test fun `client_hello full`() {
        val json = """{"type":"client_hello","protocolVersion":1,"deviceId":"phone-42","currentSessionKey":"abc"}"""
        val f = FrameParser.parseWsFrame(json) as ClientHello
        assertEquals("phone-42", f.deviceId)
        assertEquals("abc", f.currentSessionKey)
    }

    @Test fun `user_message text only`() {
        val f = FrameParser.parseWsFrame("""{"type":"user_message","id":"m1","text":"hi"}""")
        assertIs<UserMessage>(f)
        assertEquals("m1", f.id)
        assertEquals("hi", f.text)
        assertNull(f.imageBase64)
    }

    @Test fun `user_message with image`() {
        val json = """{"type":"user_message","id":"m1","text":"what","imageBase64":"AA==","sessionKey":"s1"}"""
        val f = FrameParser.parseWsFrame(json) as UserMessage
        assertEquals("AA==", f.imageBase64)
        assertEquals("s1", f.sessionKey)
    }

    @Test fun `voice_note all fields`() {
        val json = """{"type":"voice_note","id":"v1","streamId":"s1","chunkIndex":0,"totalChunks":2,"audioBase64":"AA==","ext":".ogg"}"""
        val f = FrameParser.parseWsFrame(json) as VoiceNote
        assertEquals("s1", f.streamId)
        assertEquals(0, f.chunkIndex)
        assertEquals(2, f.totalChunks)
        assertEquals(".ogg", f.ext)
    }

    @Test fun `image_attachment`() {
        val f = FrameParser.parseWsFrame("""{"type":"image_attachment","id":"i1","imageBase64":"AA=="}""")
        assertIs<ImageAttachment>(f)
        assertEquals("i1", f.id)
    }

    @Test fun `switch_session`() {
        val f = FrameParser.parseWsFrame("""{"type":"switch_session","sessionKey":"s2"}""")
        assertIs<SwitchSession>(f)
        assertEquals("s2", f.sessionKey)
    }

    @Test fun `new_session with name`() {
        val f = FrameParser.parseWsFrame("""{"type":"new_session","name":"Quick"}""") as NewSession
        assertEquals("Quick", f.name)
    }

    @Test fun `slash_command`() {
        val f = FrameParser.parseWsFrame("""{"type":"slash_command","command":"/voice on"}""") as SlashCommand
        assertEquals("/voice on", f.command)
    }

    @Test fun `display_state`() {
        val f = FrameParser.parseWsFrame("""{"type":"display_state","connected":true,"active":false}""") as DisplayState
        assertTrue(f.connected)
        assertEquals(false, f.active)
    }

    @Test fun `ping pong round trip`() {
        val p = FrameParser.parseWsFrame("""{"type":"ping","id":"p1"}""")
        assertIs<Ping>(p); assertEquals("p1", p.id)
        val q = FrameParser.parseWsFrame("""{"type":"pong","id":"p1"}""")
        assertIs<Pong>(q); assertEquals("p1", q.id)
    }

    // ── Outbound (adapter -> phone) ─────────────────────────────────────

    @Test fun `server_welcome with sessions`() {
        val json = """{"type":"server_welcome","protocolVersion":1,"sessions":[{"sessionKey":"a","label":"L","lastActiveAt":1000}],"currentSessionKey":"a"}"""
        val f = FrameParser.parseWsFrame(json) as ServerWelcome
        assertEquals(1, f.protocolVersion)
        assertEquals("a", f.currentSessionKey)
        assertEquals(1, f.sessions.size)
        assertEquals("L", f.sessions[0].label)
        assertEquals(1000L, f.sessions[0].lastActiveAt)
    }

    @Test fun `assistant_chunk minimal and full`() {
        val min = FrameParser.parseWsFrame("""{"type":"assistant_chunk","id":"m","chunk":"hi"}""") as AssistantChunk
        assertNull(min.parentId)
        val full = FrameParser.parseWsFrame("""{"type":"assistant_chunk","id":"m","chunk":"hi","parentId":"p","sessionKey":"s"}""") as AssistantChunk
        assertEquals("p", full.parentId)
        assertEquals("s", full.sessionKey)
    }

    @Test fun `assistant_complete`() {
        val f = FrameParser.parseWsFrame("""{"type":"assistant_complete","id":"m","sessionKey":"s"}""") as AssistantComplete
        assertEquals("m", f.id)
        assertEquals("s", f.sessionKey)
    }

    @Test fun `assistant_audio`() {
        val f = FrameParser.parseWsFrame("""{"type":"assistant_audio","id":"a","bytesBase64":"AA==","ext":".ogg"}""") as AssistantAudio
        assertEquals("AA==", f.bytesBase64)
    }

    @Test fun `tool_progress full payload`() {
        val json = """{"type":"tool_progress","messageId":"t","toolName":"web_search","phase":"started","preview":"hello"}"""
        val f = FrameParser.parseWsFrame(json) as ToolProgress
        assertEquals("web_search", f.toolName)
        assertEquals("hello", f.preview)
    }

    @Test fun `push_message all origins`() {
        for ((origin, extraJson, check) in listOf(
            Triple("cron-job", ""","jobId":"j1"""", { f: PushMessage -> assertEquals("j1", f.jobId) }),
            Triple("run-completion", ""","runId":"r1"""", { f: PushMessage -> assertEquals("r1", f.runId) }),
            Triple("agent-nudge", "", { _: PushMessage -> }),
        )) {
            val json = """{"type":"push_message","origin":"$origin","sessionKey":"s","messageId":"m","text":"hi"$extraJson}"""
            val f = FrameParser.parseWsFrame(json) as PushMessage
            assertEquals(origin, f.origin)
            check(f)
        }
    }

    @Test fun `session_list`() {
        val json = """{"type":"session_list","sessions":[{"sessionKey":"a"},{"sessionKey":"b","label":"x"}],"currentSessionKey":"a"}"""
        val f = FrameParser.parseWsFrame(json) as SessionList
        assertEquals(2, f.sessions.size)
        assertEquals("x", f.sessions[1].label)
    }

    @Test fun `connection_update`() {
        val f = FrameParser.parseWsFrame("""{"type":"connection_update","connected":false}""") as ConnectionUpdate
        assertEquals(false, f.connected)
    }

    // ── Malformed / unknown returns null ────────────────────────────────

    @Test fun `invalid json returns null`() {
        assertNull(FrameParser.parseWsFrame("not json"))
        assertNull(FrameParser.parseWsFrame(""))
    }

    @Test fun `non object returns null`() {
        assertNull(FrameParser.parseWsFrame("[]"))
        assertNull(FrameParser.parseWsFrame("42"))
    }

    @Test fun `unknown type returns null`() {
        assertNull(FrameParser.parseWsFrame("""{"type":"summon_dragon","id":"x"}"""))
    }

    @Test fun `missing type returns null`() {
        assertNull(FrameParser.parseWsFrame("""{"id":"x","text":"hi"}"""))
    }

    @Test fun `non string type returns null`() {
        assertNull(FrameParser.parseWsFrame("""{"type":42,"id":"x"}"""))
    }
}
