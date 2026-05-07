"""Frame parser unit tests.

Covers each inbound frame type once on the happy path plus a handful of
malformed-input cases. Outbound builders are smoke-tested for shape
(camelCased keys, type discriminator present) — exhaustive structural
checks would just mirror the implementation.
"""

from __future__ import annotations

import json

import frames as fr


# ── Inbound: parse_frame ────────────────────────────────────────────────

def test_parse_client_hello_minimal():
    raw = json.dumps({"type": "client_hello", "protocolVersion": 1})
    frame = fr.parse_frame(raw)
    assert isinstance(frame, fr.ClientHello)
    assert frame.protocol_version == 1
    assert frame.device_id is None
    assert frame.current_session_key is None


def test_parse_client_hello_full():
    raw = json.dumps({
        "type": "client_hello",
        "protocolVersion": 1,
        "deviceId": "phone-42",
        "currentSessionKey": "abc",
    })
    frame = fr.parse_frame(raw)
    assert isinstance(frame, fr.ClientHello)
    assert frame.device_id == "phone-42"
    assert frame.current_session_key == "abc"


def test_parse_user_message_text_only():
    raw = json.dumps({"type": "user_message", "id": "m1", "text": "hi"})
    frame = fr.parse_frame(raw)
    assert isinstance(frame, fr.UserMessage)
    assert frame.id == "m1"
    assert frame.text == "hi"
    assert frame.image_base64 is None


def test_parse_user_message_with_image():
    raw = json.dumps({
        "type": "user_message",
        "id": "m1",
        "text": "what is this?",
        "imageBase64": "ZmFrZQ==",
        "sessionKey": "sess-1",
    })
    frame = fr.parse_frame(raw)
    assert isinstance(frame, fr.UserMessage)
    assert frame.image_base64 == "ZmFrZQ=="
    assert frame.session_key == "sess-1"


def test_parse_voice_note():
    raw = json.dumps({
        "type": "voice_note",
        "id": "v1",
        "streamId": "s1",
        "chunkIndex": 0,
        "totalChunks": 2,
        "audioBase64": "AA==",
        "ext": ".ogg",
    })
    frame = fr.parse_frame(raw)
    assert isinstance(frame, fr.VoiceNote)
    assert frame.stream_id == "s1"
    assert frame.chunk_index == 0
    assert frame.total_chunks == 2


def test_parse_image_attachment():
    raw = json.dumps({"type": "image_attachment", "id": "i1", "imageBase64": "AA=="})
    frame = fr.parse_frame(raw)
    assert isinstance(frame, fr.ImageAttachment)
    assert frame.id == "i1"


def test_parse_switch_session():
    raw = json.dumps({"type": "switch_session", "sessionKey": "s2"})
    frame = fr.parse_frame(raw)
    assert isinstance(frame, fr.SwitchSession)
    assert frame.session_key == "s2"


def test_parse_new_session_with_name():
    raw = json.dumps({"type": "new_session", "name": "Quick chat"})
    frame = fr.parse_frame(raw)
    assert isinstance(frame, fr.NewSession)
    assert frame.name == "Quick chat"


def test_parse_slash_command():
    raw = json.dumps({"type": "slash_command", "command": "/voice on"})
    frame = fr.parse_frame(raw)
    assert isinstance(frame, fr.SlashCommand)
    assert frame.command == "/voice on"


def test_parse_display_state():
    raw = json.dumps({"type": "display_state", "connected": True, "active": False})
    frame = fr.parse_frame(raw)
    assert isinstance(frame, fr.DisplayState)
    assert frame.connected is True
    assert frame.active is False


def test_parse_ping_pong_round_trip():
    p = fr.parse_frame(json.dumps({"type": "ping", "id": "p1"}))
    assert isinstance(p, fr.Ping) and p.id == "p1"
    q = fr.parse_frame(json.dumps({"type": "pong", "id": "p1"}))
    assert isinstance(q, fr.Pong) and q.id == "p1"


# ── Inbound: malformed/unknown returns None ─────────────────────────────

def test_parse_invalid_json_returns_none():
    assert fr.parse_frame("not json") is None
    assert fr.parse_frame("") is None


def test_parse_non_object_returns_none():
    assert fr.parse_frame("[]") is None
    assert fr.parse_frame("42") is None


def test_parse_unknown_type_returns_none():
    raw = json.dumps({"type": "summon_dragon", "id": "x"})
    assert fr.parse_frame(raw) is None


def test_parse_missing_type_returns_none():
    raw = json.dumps({"id": "x", "text": "hi"})
    assert fr.parse_frame(raw) is None


def test_parse_schema_mismatch_returns_none():
    raw = json.dumps({"type": "user_message"})
    assert fr.parse_frame(raw) is None


def test_parse_extra_unknown_field_is_ignored():
    # Forward-compat: a newer phone build may include fields the adapter
    # doesn't know yet. Drop the unknowns and parse the rest, mirroring the
    # Kotlin/Gson side's leniency.
    raw = json.dumps({
        "type": "user_message",
        "id": "m1",
        "text": "hi",
        "mystery": True,
    })
    frame = fr.parse_frame(raw)
    assert isinstance(frame, fr.UserMessage)
    assert frame.id == "m1"
    assert frame.text == "hi"


# ── Outbound: shape smoke checks ────────────────────────────────────────

def test_server_welcome_shape():
    s = [fr.SessionInfo(session_key="abc", label="default", last_active_at=10)]
    out = fr.server_welcome(s, current_session_key="abc")
    assert out["type"] == "server_welcome"
    assert out["protocolVersion"] == 1
    assert out["currentSessionKey"] == "abc"
    assert out["sessions"] == [{"sessionKey": "abc", "label": "default", "lastActiveAt": 10}]


def test_assistant_chunk_skips_none_optional():
    out = fr.assistant_chunk(message_id="m1", chunk="hello")
    assert out["type"] == "assistant_chunk"
    assert out["id"] == "m1"
    assert out["chunk"] == "hello"
    assert "parentId" not in out
    assert "sessionKey" not in out


def test_assistant_chunk_with_parent_and_session():
    out = fr.assistant_chunk(message_id="m1", chunk=" more", parent_id="p1", session_key="s")
    assert out["parentId"] == "p1"
    assert out["sessionKey"] == "s"


def test_assistant_complete_shape():
    out = fr.assistant_complete(message_id="m1", session_key="s")
    assert out == {"type": "assistant_complete", "id": "m1", "sessionKey": "s"}


def test_assistant_audio_shape():
    out = fr.assistant_audio(message_id="a1", bytes_base64="AA==", ext=".ogg")
    assert out["type"] == "assistant_audio"
    assert out["bytesBase64"] == "AA=="
    assert out["ext"] == ".ogg"


def test_tool_progress_shape():
    out = fr.tool_progress(message_id="t1", tool_name="web_search", phase="started", preview="hello")
    assert out == {
        "type": "tool_progress",
        "messageId": "t1",
        "toolName": "web_search",
        "phase": "started",
        "preview": "hello",
    }


def test_push_message_origins():
    for origin, extra in (
        ("cron-job", {"job_id": "j1"}),
        ("run-completion", {"run_id": "r1"}),
        ("agent-nudge", {}),
    ):
        out = fr.push_message(
            origin=origin,
            session_key="s",
            message_id="m",
            text="hi",
            **extra,
        )
        assert out["type"] == "push_message"
        assert out["origin"] == origin
        assert out["sessionKey"] == "s"
        assert out["text"] == "hi"


def test_session_list_shape():
    s = [fr.SessionInfo(session_key="a"), fr.SessionInfo(session_key="b", label="lab")]
    out = fr.session_list(s, current_session_key="a")
    assert out["type"] == "session_list"
    assert out["currentSessionKey"] == "a"
    assert out["sessions"][0] == {"sessionKey": "a"}
    assert out["sessions"][1] == {"sessionKey": "b", "label": "lab"}


def test_connection_update_shape():
    assert fr.connection_update(False) == {"type": "connection_update", "connected": False}


def test_pong_shape():
    assert fr.pong("xyz") == {"type": "pong", "id": "xyz"}
