package dev.wallner.hermesonglass.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapsEnvelopeParserTest {

    // ── phone -> glasses ────────────────────────────────────────────────

    @Test fun `chat_message`() {
        val f = FrameParser.parseCapsEnvelope("""{"type":"chat_message","messageId":"m","text":"hi"}""")
        assertIs<ChatMessage>(f)
        assertEquals("m", f.messageId)
    }

    @Test fun `agent_thinking`() {
        val f = FrameParser.parseCapsEnvelope("""{"type":"agent_thinking","active":true}""") as AgentThinking
        assertTrue(f.active)
    }

    @Test fun `chat_stream and chat_stream_end`() {
        val s = FrameParser.parseCapsEnvelope("""{"type":"chat_stream","id":"m","chunk":" world"}""") as ChatStream
        assertEquals(" world", s.chunk)
        val e = FrameParser.parseCapsEnvelope("""{"type":"chat_stream_end","id":"m"}""") as ChatStreamEnd
        assertEquals("m", e.id)
    }

    @Test fun `tool_progress on caps wire`() {
        val f = FrameParser.parseCapsEnvelope(
            """{"type":"tool_progress","messageId":"t","toolName":"web_search","phase":"started","preview":"q"}"""
        ) as CapsToolProgress
        assertEquals("web_search", f.toolName)
        assertEquals("q", f.preview)
    }

    @Test fun `connection_update on caps wire`() {
        val f = FrameParser.parseCapsEnvelope("""{"type":"connection_update","connected":false}""") as CapsConnectionUpdate
        assertEquals(false, f.connected)
    }

    @Test fun `session_list on caps wire`() {
        val json = """{"type":"session_list","sessions":[{"sessionKey":"a"}],"currentSessionKey":"a"}"""
        val f = FrameParser.parseCapsEnvelope(json) as CapsSessionList
        assertEquals(1, f.sessions.size)
        assertEquals("a", f.currentSessionKey)
    }

    @Test fun `wake_signal`() {
        val f = FrameParser.parseCapsEnvelope("""{"type":"wake_signal","reason":"cron-job","messageId":"m"}""") as WakeSignal
        assertEquals("cron-job", f.reason)
        assertEquals("m", f.messageId)
    }

    @Test fun `voice_play`() {
        val f = FrameParser.parseCapsEnvelope("""{"type":"voice_play","id":"a","bytesBase64":"AA==","ext":".ogg"}""") as VoicePlay
        assertEquals("AA==", f.bytesBase64)
    }

    // ── glasses -> phone ────────────────────────────────────────────────

    @Test fun `user_input text`() {
        val f = FrameParser.parseCapsEnvelope("""{"type":"user_input","id":"u1","text":"hello"}""") as UserInput
        assertEquals("hello", f.text)
        assertNull(f.imageBase64)
    }

    @Test fun `user_input with image`() {
        val f = FrameParser.parseCapsEnvelope(
            """{"type":"user_input","id":"u1","text":"what","imageBase64":"AA=="}"""
        ) as UserInput
        assertEquals("AA==", f.imageBase64)
    }

    @Test fun `voice_capture announces stream`() {
        val f = FrameParser.parseCapsEnvelope(
            """{"type":"voice_capture","streamId":"s1","ext":".ogg"}"""
        ) as VoiceCapture
        assertEquals("s1", f.streamId)
        assertEquals(".ogg", f.ext)
    }

    @Test fun `voice_data carries the full opus payload`() {
        val f = FrameParser.parseCapsEnvelope(
            """{"type":"voice_data","streamId":"s1","bytesBase64":"AA==","ext":".ogg"}"""
        ) as VoiceData
        assertEquals("s1", f.streamId)
        assertEquals("AA==", f.bytesBase64)
        assertEquals(".ogg", f.ext)
    }

    @Test fun `list_sessions optional requestId`() {
        val f = FrameParser.parseCapsEnvelope("""{"type":"list_sessions","requestId":"r1"}""") as ListSessions
        assertEquals("r1", f.requestId)
    }

    @Test fun `caps switch_session`() {
        val f = FrameParser.parseCapsEnvelope("""{"type":"switch_session","sessionKey":"sx"}""") as CapsSwitchSession
        assertEquals("sx", f.sessionKey)
    }

    @Test fun `caps slash_command`() {
        val f = FrameParser.parseCapsEnvelope(
            """{"type":"slash_command","command":"/new-session"}"""
        ) as CapsSlashCommand
        assertEquals("/new-session", f.command)
    }

    @Test fun `caps display_state`() {
        val f = FrameParser.parseCapsEnvelope("""{"type":"display_state","active":false}""") as CapsDisplayState
        assertEquals(false, f.active)
    }

    @Test fun `wake_ack`() {
        val f = FrameParser.parseCapsEnvelope("""{"type":"wake_ack","ready":true,"messageId":"m"}""") as WakeAck
        assertTrue(f.ready)
        assertEquals("m", f.messageId)
    }

    @Test fun `request_more_history`() {
        val f = FrameParser.parseCapsEnvelope(
            """{"type":"request_more_history","sessionKey":"s","limit":20,"beforeMessageId":"m9"}"""
        ) as RequestMoreHistory
        assertEquals("s", f.sessionKey)
        assertEquals(20, f.limit)
        assertEquals("m9", f.beforeMessageId)
    }

    // ── Malformed / unknown ─────────────────────────────────────────────

    @Test fun `invalid json returns null`() {
        assertNull(FrameParser.parseCapsEnvelope("not json"))
        assertNull(FrameParser.parseCapsEnvelope(""))
    }

    @Test fun `unknown type returns null`() {
        assertNull(FrameParser.parseCapsEnvelope("""{"type":"levitate","x":1}"""))
    }

    @Test fun `missing type returns null`() {
        assertNull(FrameParser.parseCapsEnvelope("""{"text":"orphan"}"""))
    }
}
