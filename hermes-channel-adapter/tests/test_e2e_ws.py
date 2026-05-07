"""End-to-end WebSocket tests.

Exercise the full handshake, inbound dispatch, and outbound delivery paths
of GlassesAdapter against a running aiohttp server. The gateway runner is
stood in by a stub message handler set via ``set_message_handler`` —
inbound MessageEvents land in a queue we can assert on, and we drive
outbound by calling ``adapter.send`` / ``edit_message`` / ``play_tts``
directly with the chat_id we observed inbound.

These tests substitute for §2.14 once the user installs the adapter and
points it at a real Hermes gateway.
"""

from __future__ import annotations

import asyncio
import base64
import json
from pathlib import Path
from typing import Any, AsyncIterator, Dict, List

import aiohttp
import pytest

import config as cfg_mod
from adapter import GlassesAdapter, PLATFORM_NAME
from gateway.config import PlatformConfig  # type: ignore  # provided by conftest.py
from gateway.platforms.base import MessageEvent, MessageType  # type: ignore


@pytest.fixture
async def adapter(tmp_path) -> AsyncIterator[GlassesAdapter]:
    cfg = cfg_mod.AdapterConfig(
        listen_host="127.0.0.1",
        listen_port=_pick_free_port(),
        shared_secret="test-secret-1234",
        ws_path="/glasses",
    )
    a = GlassesAdapter(PlatformConfig(), adapter_config=cfg)
    inbound: List[MessageEvent] = []
    a._test_inbound = inbound  # type: ignore[attr-defined]

    async def _handler(event: MessageEvent) -> None:
        inbound.append(event)

    a.set_message_handler(_handler)
    ok = await a.connect()
    assert ok, "adapter.connect() should succeed"
    try:
        yield a
    finally:
        await a.disconnect()


def _pick_free_port() -> int:
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


async def _connect_ws(adapter: GlassesAdapter) -> aiohttp.ClientWebSocketResponse:
    cfg = adapter._adapter_config
    assert cfg is not None
    session = aiohttp.ClientSession()
    ws = await session.ws_connect(
        f"http://127.0.0.1:{cfg.listen_port}{cfg.ws_path}",
        headers={"Authorization": f"Bearer {cfg.shared_secret}"},
    )
    ws._owning_session = session  # type: ignore[attr-defined]
    return ws


async def _close_ws(ws: aiohttp.ClientWebSocketResponse) -> None:
    await ws.close()
    sess: aiohttp.ClientSession = ws._owning_session  # type: ignore[attr-defined]
    await sess.close()


async def _recv_json(ws: aiohttp.ClientWebSocketResponse, timeout: float = 2.0) -> Dict[str, Any]:
    msg = await asyncio.wait_for(ws.receive(), timeout=timeout)
    assert msg.type == aiohttp.WSMsgType.TEXT, f"unexpected ws msg type: {msg.type}"
    return json.loads(msg.data)


# ── Auth ────────────────────────────────────────────────────────────────

async def test_unauthorized_upgrade_returns_401(adapter: GlassesAdapter):
    cfg = adapter._adapter_config
    async with aiohttp.ClientSession() as session:
        with pytest.raises(aiohttp.WSServerHandshakeError) as ei:
            await session.ws_connect(
                f"http://127.0.0.1:{cfg.listen_port}{cfg.ws_path}",
            )
        assert ei.value.status == 401


async def test_wrong_bearer_returns_401(adapter: GlassesAdapter):
    cfg = adapter._adapter_config
    async with aiohttp.ClientSession() as session:
        with pytest.raises(aiohttp.WSServerHandshakeError) as ei:
            await session.ws_connect(
                f"http://127.0.0.1:{cfg.listen_port}{cfg.ws_path}",
                headers={"Authorization": "Bearer nope"},
            )
        assert ei.value.status == 401


async def test_health_endpoint_works_without_auth(adapter: GlassesAdapter):
    cfg = adapter._adapter_config
    async with aiohttp.ClientSession() as session:
        async with session.get(f"http://127.0.0.1:{cfg.listen_port}/health") as r:
            assert r.status == 200
            data = await r.json()
            assert data["platform"] == PLATFORM_NAME
            assert data["protocolVersion"] == 1


# ── Handshake ───────────────────────────────────────────────────────────

async def test_client_hello_returns_server_welcome(adapter: GlassesAdapter):
    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({
            "type": "client_hello",
            "protocolVersion": 1,
            "deviceId": "phone-test",
        })
        welcome = await _recv_json(ws)
        assert welcome["type"] == "server_welcome"
        assert welcome["protocolVersion"] == 1
        assert isinstance(welcome["currentSessionKey"], str)
        assert len(welcome["sessions"]) >= 1
        assert welcome["sessions"][0]["sessionKey"] == welcome["currentSessionKey"]
    finally:
        await _close_ws(ws)


# ── Inbound dispatch ────────────────────────────────────────────────────

async def test_user_message_text_dispatches_event(adapter: GlassesAdapter):
    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        await _recv_json(ws)
        await ws.send_json({"type": "user_message", "id": "m1", "text": "hello"})
        await _wait_for(lambda: len(adapter._test_inbound) >= 1)  # type: ignore[attr-defined]
        event = adapter._test_inbound[0]  # type: ignore[attr-defined]
        assert event.text == "hello"
        assert event.message_type == MessageType.TEXT
        assert event.message_id == "m1"
        assert event.source.chat_id.startswith("glasses:")
    finally:
        await _close_ws(ws)


async def test_user_message_with_image_lands_as_photo(adapter: GlassesAdapter):
    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        await _recv_json(ws)
        b64 = base64.b64encode(b"\xff\xd8\xff\xd9").decode("ascii")
        await ws.send_json({
            "type": "user_message",
            "id": "m2",
            "text": "what is this?",
            "imageBase64": b64,
        })
        await _wait_for(lambda: len(adapter._test_inbound) >= 1)  # type: ignore[attr-defined]
        event = adapter._test_inbound[-1]  # type: ignore[attr-defined]
        assert event.message_type == MessageType.PHOTO
        assert len(event.media_urls) == 1
        assert Path(event.media_urls[0]).read_bytes() == b"\xff\xd8\xff\xd9"
    finally:
        await _close_ws(ws)


async def test_voice_note_chunked_reassembly_dispatches(adapter: GlassesAdapter):
    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        await _recv_json(ws)
        chunks = [b"opus-aaa", b"opus-bbb", b"opus-ccc"]
        for i, c in enumerate(chunks):
            await ws.send_json({
                "type": "voice_note",
                "id": "v1",
                "streamId": "stream-1",
                "chunkIndex": i,
                "totalChunks": len(chunks),
                "audioBase64": base64.b64encode(c).decode("ascii"),
                "ext": ".ogg",
            })
        await _wait_for(lambda: len(adapter._test_inbound) >= 1)  # type: ignore[attr-defined]
        event = adapter._test_inbound[-1]  # type: ignore[attr-defined]
        assert event.message_type == MessageType.VOICE
        assert len(event.media_urls) == 1
        assert Path(event.media_urls[0]).read_bytes() == b"".join(chunks)
    finally:
        await _close_ws(ws)


async def test_voice_note_partial_does_not_dispatch(adapter: GlassesAdapter):
    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        await _recv_json(ws)
        await ws.send_json({
            "type": "voice_note",
            "id": "v2",
            "streamId": "stream-2",
            "chunkIndex": 0,
            "totalChunks": 2,
            "audioBase64": base64.b64encode(b"x").decode("ascii"),
        })
        await asyncio.sleep(0.1)
        assert adapter._test_inbound == []  # type: ignore[attr-defined]
    finally:
        await _close_ws(ws)


async def test_slash_command_event_carries_command_text(adapter: GlassesAdapter):
    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        await _recv_json(ws)
        await ws.send_json({"type": "slash_command", "command": "/voice on"})
        await _wait_for(lambda: len(adapter._test_inbound) >= 1)  # type: ignore[attr-defined]
        event = adapter._test_inbound[-1]  # type: ignore[attr-defined]
        assert event.message_type == MessageType.COMMAND
        assert event.text == "/voice on"
    finally:
        await _close_ws(ws)


async def test_rename_session_re_emits_session_list_with_label(adapter: GlassesAdapter):
    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        welcome = await _recv_json(ws)
        sk = welcome["currentSessionKey"]

        await ws.send_json({"type": "slash_command", "command": "/rename-session Travel"})
        listing = await _recv_json(ws)
        assert listing["type"] == "session_list"
        assert listing["currentSessionKey"] == sk
        assert listing["sessions"][0]["sessionKey"] == sk
        assert listing["sessions"][0]["label"] == "Travel"

        # Adapter-managed commands MUST NOT be forwarded to handle_message.
        assert adapter._test_inbound == []  # type: ignore[attr-defined]
    finally:
        await _close_ws(ws)


async def test_delete_session_switches_active_and_re_emits(adapter: GlassesAdapter):
    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        first_welcome = await _recv_json(ws)
        first_sk = first_welcome["currentSessionKey"]

        await ws.send_json({"type": "new_session", "name": "Second"})
        listing = await _recv_json(ws)
        second_sk = listing["currentSessionKey"]
        assert second_sk != first_sk

        # Delete the first (non-active) session — listing shrinks but
        # currentSessionKey stays on second.
        await ws.send_json({"type": "slash_command", "command": f"/delete-session {first_sk}"})
        after_delete_first = await _recv_json(ws)
        keys = [s["sessionKey"] for s in after_delete_first["sessions"]]
        assert first_sk not in keys
        assert after_delete_first["currentSessionKey"] == second_sk

        # Now delete the active session — adapter must rotate to a different
        # session key (a fresh allocation since list will be empty after
        # removal).
        await ws.send_json({"type": "slash_command", "command": "/delete-session"})
        after_delete_second = await _recv_json(ws)
        new_active = after_delete_second["currentSessionKey"]
        assert new_active != second_sk
        keys2 = [s["sessionKey"] for s in after_delete_second["sessions"]]
        assert second_sk not in keys2

        assert adapter._test_inbound == []  # type: ignore[attr-defined]
    finally:
        await _close_ws(ws)


async def test_unknown_slash_command_still_forwards_to_handle_message(adapter: GlassesAdapter):
    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        await _recv_json(ws)
        await ws.send_json({"type": "slash_command", "command": "/voice on"})
        await _wait_for(lambda: len(adapter._test_inbound) >= 1)  # type: ignore[attr-defined]
        event = adapter._test_inbound[-1]  # type: ignore[attr-defined]
        assert event.text == "/voice on"
    finally:
        await _close_ws(ws)


async def test_ping_returns_pong(adapter: GlassesAdapter):
    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        await _recv_json(ws)
        await ws.send_json({"type": "ping", "id": "ping-1"})
        reply = await _recv_json(ws)
        assert reply == {"type": "pong", "id": "ping-1"}
    finally:
        await _close_ws(ws)


# ── Sessions ────────────────────────────────────────────────────────────

async def test_new_session_emits_session_list(adapter: GlassesAdapter):
    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        welcome = await _recv_json(ws)
        first_session = welcome["currentSessionKey"]
        await ws.send_json({"type": "new_session", "name": "Quick chat"})
        listing = await _recv_json(ws)
        assert listing["type"] == "session_list"
        assert listing["currentSessionKey"] != first_session
        keys = [s["sessionKey"] for s in listing["sessions"]]
        assert listing["currentSessionKey"] in keys
        assert first_session in keys
    finally:
        await _close_ws(ws)


async def test_switch_session_round_trip(adapter: GlassesAdapter):
    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        first = (await _recv_json(ws))["currentSessionKey"]
        await ws.send_json({"type": "new_session"})
        second_listing = await _recv_json(ws)
        second = second_listing["currentSessionKey"]
        assert first != second

        await ws.send_json({"type": "switch_session", "sessionKey": first})
        switched = await _recv_json(ws)
        assert switched["currentSessionKey"] == first
    finally:
        await _close_ws(ws)


# ── Outbound: streaming via send + edit_message ─────────────────────────

async def test_send_emits_assistant_chunk_and_edit_streams_delta(adapter: GlassesAdapter):
    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        welcome = await _recv_json(ws)
        await ws.send_json({"type": "user_message", "id": "in-1", "text": "hi"})
        await _wait_for(lambda: len(adapter._test_inbound) >= 1)  # type: ignore[attr-defined]
        chat_id = adapter._test_inbound[-1].source.chat_id  # type: ignore[attr-defined]

        result = await adapter.send(chat_id, "Hello")
        assert result.success and result.message_id
        first = await _recv_json(ws)
        assert first["type"] == "assistant_chunk"
        assert first["chunk"] == "Hello"
        msg_id = first["id"]

        edit = await adapter.edit_message(chat_id, msg_id, "Hello world")
        assert edit.success
        delta = await _recv_json(ws)
        assert delta["type"] == "assistant_chunk"
        assert delta["chunk"] == " world"
        assert delta["id"] == msg_id

        final = await adapter.edit_message(chat_id, msg_id, "Hello world!", finalize=True)
        assert final.success
        delta2 = await _recv_json(ws)
        assert delta2["chunk"] == "!"
        complete = await _recv_json(ws)
        assert complete == {
            "type": "assistant_complete",
            "id": msg_id,
            "sessionKey": welcome["currentSessionKey"],
        }
    finally:
        await _close_ws(ws)


async def test_one_shot_send_emits_deferred_assistant_complete(adapter: GlassesAdapter):
    """A bare send() with no edit follow-up should still close the turn."""
    # Shorten the deferral so the test waits ~0.1s instead of 0.75s.
    adapter._DEFERRED_COMPLETE_DELAY_S = 0.1  # type: ignore[attr-defined]

    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        welcome = await _recv_json(ws)
        await ws.send_json({"type": "user_message", "id": "in-1", "text": "hi"})
        await _wait_for(lambda: len(adapter._test_inbound) >= 1)  # type: ignore[attr-defined]
        chat_id = adapter._test_inbound[-1].source.chat_id  # type: ignore[attr-defined]

        result = await adapter.send(chat_id, "All done.")
        assert result.success and result.message_id
        chunk = await _recv_json(ws)
        assert chunk["type"] == "assistant_chunk"
        assert chunk["chunk"] == "All done."
        msg_id = chunk["id"]

        complete = await _recv_json(ws)
        assert complete == {
            "type": "assistant_complete",
            "id": msg_id,
            "sessionKey": welcome["currentSessionKey"],
        }
    finally:
        await _close_ws(ws)


async def test_edit_message_after_send_cancels_deferred_complete(adapter: GlassesAdapter):
    """Streaming pattern: edit_message before deferred timer should suppress it."""
    adapter._DEFERRED_COMPLETE_DELAY_S = 0.1  # type: ignore[attr-defined]

    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        await _recv_json(ws)
        await ws.send_json({"type": "user_message", "id": "in-1", "text": "stream"})
        await _wait_for(lambda: len(adapter._test_inbound) >= 1)  # type: ignore[attr-defined]
        chat_id = adapter._test_inbound[-1].source.chat_id  # type: ignore[attr-defined]

        await adapter.send(chat_id, "Hello")
        first = await _recv_json(ws)
        msg_id = first["id"]
        # edit immediately — well within the 100ms deferral window
        await adapter.edit_message(chat_id, msg_id, "Hello world", finalize=True)
        delta = await _recv_json(ws)
        assert delta["chunk"] == " world"
        complete = await _recv_json(ws)
        assert complete["type"] == "assistant_complete"
        # Wait past the deferred-complete window — no second complete should arrive.
        await asyncio.sleep(0.2)
        # Drain any extra frame; assert the queue is quiet.
        try:
            extra = await asyncio.wait_for(ws.receive(), timeout=0.05)
            assert extra.type != aiohttp.WSMsgType.TEXT, (
                f"unexpected extra frame after streaming complete: {extra.data}"
            )
        except asyncio.TimeoutError:
            pass
    finally:
        await _close_ws(ws)


async def test_send_private_notice_emits_system_notice_frame(adapter: GlassesAdapter):
    """Operational notices route through send_private_notice → system_notice."""
    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        welcome = await _recv_json(ws)
        await ws.send_json({"type": "user_message", "id": "in-1", "text": "hi"})
        await _wait_for(lambda: len(adapter._test_inbound) >= 1)  # type: ignore[attr-defined]
        chat_id = adapter._test_inbound[-1].source.chat_id  # type: ignore[attr-defined]

        result = await adapter.send_private_notice(
            chat_id, user_id="device-x", content="📬 Notice text"
        )
        assert result.success
        frame = await _recv_json(ws)
        assert frame["type"] == "system_notice"
        assert frame["text"] == "📬 Notice text"
        assert frame["sessionKey"] == welcome["currentSessionKey"]
    finally:
        await _close_ws(ws)


# ── Outbound: tool-progress heuristic ───────────────────────────────────

async def test_send_tool_progress_text_emits_tool_progress_frame(adapter: GlassesAdapter):
    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        await _recv_json(ws)
        await ws.send_json({"type": "user_message", "id": "in-1", "text": "go"})
        await _wait_for(lambda: len(adapter._test_inbound) >= 1)  # type: ignore[attr-defined]
        chat_id = adapter._test_inbound[-1].source.chat_id  # type: ignore[attr-defined]

        await adapter.send(chat_id, "⚙️ web_search: \"hello\"...")
        frame = await _recv_json(ws)
        assert frame["type"] == "tool_progress"
        assert frame["toolName"] == "web_search"
        assert frame["preview"] == "hello"
        assert frame["phase"] == "started"
    finally:
        await _close_ws(ws)


# ── Outbound: voice playback ────────────────────────────────────────────

async def test_play_tts_emits_assistant_audio(adapter: GlassesAdapter, tmp_path):
    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        await _recv_json(ws)
        await ws.send_json({"type": "user_message", "id": "in-1", "text": "speak"})
        await _wait_for(lambda: len(adapter._test_inbound) >= 1)  # type: ignore[attr-defined]
        chat_id = adapter._test_inbound[-1].source.chat_id  # type: ignore[attr-defined]

        opus_bytes = b"OggS\x00\x02fake-opus-payload"
        audio_file = tmp_path / "tts.ogg"
        audio_file.write_bytes(opus_bytes)
        result = await adapter.play_tts(chat_id, str(audio_file))
        assert result.success
        frame = await _recv_json(ws)
        assert frame["type"] == "assistant_audio"
        assert frame["ext"] == ".ogg"
        assert base64.b64decode(frame["bytesBase64"]) == opus_bytes
    finally:
        await _close_ws(ws)


# ── Outbound: Hermes-initiated push ─────────────────────────────────────

async def test_push_message_for_cron_origin(adapter: GlassesAdapter):
    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        welcome = await _recv_json(ws)
        chat_id = f"glasses:{list(adapter._connections.keys())[0]}:{welcome['currentSessionKey']}"

        result = await adapter.push(
            chat_id=chat_id,
            text="time to stand up",
            origin="cron-job",
            job_id="standing-reminder",
        )
        assert result.success

        frame = await _recv_json(ws)
        assert frame["type"] == "push_message"
        assert frame["origin"] == "cron-job"
        assert frame["jobId"] == "standing-reminder"
        assert frame["text"] == "time to stand up"
    finally:
        await _close_ws(ws)


async def test_send_with_origin_metadata_routes_to_push(adapter: GlassesAdapter):
    ws = await _connect_ws(adapter)
    try:
        await ws.send_json({"type": "client_hello", "protocolVersion": 1})
        welcome = await _recv_json(ws)
        chat_id = f"glasses:{list(adapter._connections.keys())[0]}:{welcome['currentSessionKey']}"

        result = await adapter.send(
            chat_id,
            "long task done",
            metadata={"origin": "run-completion", "run_id": "r-42"},
        )
        assert result.success
        frame = await _recv_json(ws)
        assert frame["type"] == "push_message"
        assert frame["origin"] == "run-completion"
        assert frame["runId"] == "r-42"
    finally:
        await _close_ws(ws)


# ── Outbound: send to disconnected chat fails cleanly ───────────────────

async def test_send_to_unknown_chat_returns_error(adapter: GlassesAdapter):
    result = await adapter.send("glasses:nope:xxx", "hi")
    assert not result.success
    assert "no live connection" in (result.error or "")


# ── Helpers ─────────────────────────────────────────────────────────────

async def _wait_for(predicate, timeout: float = 2.0, interval: float = 0.02) -> None:
    deadline = asyncio.get_event_loop().time() + timeout
    while asyncio.get_event_loop().time() < deadline:
        if predicate():
            return
        await asyncio.sleep(interval)
    raise AssertionError("predicate did not become true within timeout")
