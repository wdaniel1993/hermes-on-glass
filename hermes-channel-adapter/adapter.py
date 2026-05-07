"""Glasses channel adapter.

Subclasses Hermes' ``BasePlatformAdapter`` and registers under the
``glasses`` platform name via the plugin system. Hosts an aiohttp WebSocket
endpoint; the phone connects outbound and bridges everything to/from the
glasses HUD over Rokid CXR.

Flow summary (verified spike 1.1, 1.3, 1.5):
  - Phone -> WS -> ``handle_message(MessageEvent)`` (text, voice, image).
  - ``send`` and ``edit_message`` are overridden to fan out as
    ``assistant_chunk`` / ``assistant_complete`` frames; the framework's
    ``GatewayStreamConsumer`` drives them.
  - ``send_voice`` / ``play_tts`` are overridden to ship rendered Opus as
    ``assistant_audio`` frames.
  - Tool-progress text from the gateway's progress task is parsed and
    re-emitted as ``tool_progress`` frames (heuristic — see tool_progress.py).
"""

from __future__ import annotations

import asyncio
import base64
import logging
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

from aiohttp import WSMsgType, web

from gateway.config import Platform, PlatformConfig
from gateway.platforms.base import (
    BasePlatformAdapter,
    MessageEvent,
    MessageType,
    SendResult,
    cache_audio_from_bytes,
    cache_image_from_bytes,
)

from config import AdapterConfig, load_config
from connection import Connection
from frames import (
    PROTOCOL_VERSION,
    ClientHello,
    DisplayState,
    ImageAttachment,
    NewSession,
    Ping,
    Pong,
    SessionInfo,
    SlashCommand,
    SwitchSession,
    UserMessage,
    VoiceNote,
    assistant_audio,
    assistant_chunk,
    assistant_complete,
    connection_update,
    parse_frame,
    pong,
    push_message,
    server_welcome,
    session_list,
    tool_progress,
)
from tool_progress import parse as parse_tool_progress

logger = logging.getLogger(__name__)

PLATFORM_NAME = "glasses"


class GlassesAdapter(BasePlatformAdapter):
    """Hermes channel adapter for Rokid Glasses via a phone bridge."""

    def __init__(self, config: PlatformConfig, *, adapter_config: Optional[AdapterConfig] = None):
        super().__init__(config, Platform(PLATFORM_NAME))
        self._adapter_config: Optional[AdapterConfig] = adapter_config
        self._runner: Optional[web.AppRunner] = None
        self._site: Optional[web.BaseSite] = None
        self._connections: Dict[str, Connection] = {}
        self._chat_id_index: Dict[str, str] = {}

    @property
    def name(self) -> str:
        return "Glasses"

    # ── Lifecycle ──────────────────────────────────────────────────────────

    async def connect(self) -> bool:
        if self._adapter_config is None:
            self._adapter_config = load_config()
        if self._adapter_config is None:
            self._set_fatal_error(
                "config_missing",
                "hermes-channel-adapter: config.yaml missing or invalid",
                retryable=False,
            )
            return False

        cfg = self._adapter_config
        app = web.Application()
        app.router.add_get(cfg.ws_path, self._handle_ws_upgrade)
        app.router.add_get("/health", self._handle_health)

        self._runner = web.AppRunner(app)
        await self._runner.setup()
        self._site = web.TCPSite(self._runner, cfg.listen_host, cfg.listen_port)
        try:
            await self._site.start()
        except OSError as e:
            await self._runner.cleanup()
            self._runner = None
            self._site = None
            self._set_fatal_error(
                "bind_failed",
                f"could not bind {cfg.listen_host}:{cfg.listen_port}: {e}",
                retryable=True,
            )
            return False

        self._mark_connected()
        logger.info(
            "[glasses] listening on %s:%d%s",
            cfg.listen_host,
            cfg.listen_port,
            cfg.ws_path,
        )
        return True

    async def disconnect(self) -> None:
        for conn in list(self._connections.values()):
            try:
                await conn.ws.close()
            except Exception:
                pass
        self._connections.clear()
        self._chat_id_index.clear()
        if self._site is not None:
            await self._site.stop()
            self._site = None
        if self._runner is not None:
            await self._runner.cleanup()
            self._runner = None
        self._mark_disconnected()

    # ── HTTP handlers ──────────────────────────────────────────────────────

    async def _handle_health(self, request: web.Request) -> web.Response:
        return web.json_response({
            "status": "ok",
            "platform": PLATFORM_NAME,
            "protocolVersion": PROTOCOL_VERSION,
            "connections": len(self._connections),
        })

    async def _handle_ws_upgrade(self, request: web.Request) -> web.WebSocketResponse:
        secret = self._adapter_config.shared_secret if self._adapter_config else ""
        auth = request.headers.get("Authorization", "")
        expected = f"Bearer {secret}"
        if not secret or auth != expected:
            return web.Response(status=401, text="unauthorized")
        ws = web.WebSocketResponse(heartbeat=30.0)
        await ws.prepare(request)
        conn_id = uuid.uuid4().hex[:12]
        conn = Connection(conn_id=conn_id, ws=ws)
        self._connections[conn_id] = conn
        self._chat_id_index[conn.chat_id] = conn_id
        logger.info("[glasses] conn %s opened", conn_id)
        try:
            await self._run_connection(conn)
        finally:
            await self._cleanup_connection(conn)
        return ws

    async def _cleanup_connection(self, conn: Connection) -> None:
        for chat_id in [k for k, v in self._chat_id_index.items() if v == conn.conn_id]:
            self._chat_id_index.pop(chat_id, None)
        self._connections.pop(conn.conn_id, None)
        try:
            if not conn.ws.closed:
                await conn.ws.close()
        except Exception:
            pass
        logger.info("[glasses] conn %s closed", conn.conn_id)

    async def _run_connection(self, conn: Connection) -> None:
        async for msg in conn.ws:
            if msg.type == WSMsgType.TEXT:
                await self._handle_text(conn, msg.data)
            elif msg.type == WSMsgType.ERROR:
                logger.warning("[glasses] conn %s ws error: %s", conn.conn_id, conn.ws.exception())
                break

    async def _handle_text(self, conn: Connection, raw: str) -> None:
        frame = parse_frame(raw)
        if frame is None:
            return
        if isinstance(frame, ClientHello):
            await self._on_client_hello(conn, frame)
        elif isinstance(frame, UserMessage):
            await self._on_user_message(conn, frame)
        elif isinstance(frame, VoiceNote):
            await self._on_voice_note(conn, frame)
        elif isinstance(frame, ImageAttachment):
            await self._on_image_attachment(conn, frame)
        elif isinstance(frame, SwitchSession):
            await self._on_switch_session(conn, frame)
        elif isinstance(frame, NewSession):
            await self._on_new_session(conn, frame)
        elif isinstance(frame, SlashCommand):
            await self._on_slash_command(conn, frame)
        elif isinstance(frame, DisplayState):
            await self._on_display_state(conn, frame)
        elif isinstance(frame, Ping):
            await conn.send_json(pong(frame.id))
        elif isinstance(frame, Pong):
            pass

    # ── Inbound frame handlers ─────────────────────────────────────────────

    async def _on_client_hello(self, conn: Connection, frame: ClientHello) -> None:
        if frame.protocol_version != PROTOCOL_VERSION:
            logger.warning(
                "[glasses] conn %s protocol mismatch: client=%s server=%s",
                conn.conn_id,
                frame.protocol_version,
                PROTOCOL_VERSION,
            )
        conn.device_id = frame.device_id
        if frame.current_session_key and frame.current_session_key in conn.sessions:
            conn.switch_session(frame.current_session_key)
        await self._index_chat_id(conn)
        await conn.send_json(
            server_welcome(conn.session_list(), conn.current_session_uuid)
        )

    async def _index_chat_id(self, conn: Connection) -> None:
        for k in [k for k, v in self._chat_id_index.items() if v == conn.conn_id]:
            self._chat_id_index.pop(k, None)
        for s in conn.sessions:
            self._chat_id_index[conn.chat_id_for_session(s)] = conn.conn_id

    async def _on_user_message(self, conn: Connection, frame: UserMessage) -> None:
        target_session = frame.session_key or conn.current_session_uuid
        if target_session != conn.current_session_uuid:
            conn.switch_session(target_session)
            await self._index_chat_id(conn)
        media_urls: List[str] = []
        media_types: List[str] = []
        if frame.image_base64:
            try:
                image_bytes = base64.b64decode(frame.image_base64)
                cached = cache_image_from_bytes(image_bytes, ext=".jpg")
                media_urls.append(cached)
                media_types.append("image/jpeg")
            except Exception as e:
                logger.warning("[glasses] decode image failed: %s", e)
        event = self._build_event(
            conn=conn,
            text=frame.text,
            message_type=MessageType.PHOTO if media_urls else MessageType.TEXT,
            message_id=frame.id,
            media_urls=media_urls,
            media_types=media_types,
        )
        await self.handle_message(event)

    async def _on_voice_note(self, conn: Connection, frame: VoiceNote) -> None:
        if frame.session_key and frame.session_key != conn.current_session_uuid:
            conn.switch_session(frame.session_key)
            await self._index_chat_id(conn)
        if frame.total_chunks <= 0:
            return
        chunks = conn.voice_streams.setdefault(frame.stream_id, {})
        try:
            chunks[frame.chunk_index] = base64.b64decode(frame.audio_base64)
        except Exception as e:
            logger.warning("[glasses] voice chunk decode failed: %s", e)
            conn.voice_streams.pop(frame.stream_id, None)
            return
        if len(chunks) < frame.total_chunks:
            return
        ordered = b"".join(chunks[i] for i in sorted(chunks))
        conn.voice_streams.pop(frame.stream_id, None)
        cached = cache_audio_from_bytes(ordered, ext=frame.ext or ".ogg")
        event = self._build_event(
            conn=conn,
            text="",
            message_type=MessageType.VOICE,
            message_id=frame.id,
            media_urls=[cached],
            media_types=["audio/ogg"],
        )
        await self.handle_message(event)

    async def _on_image_attachment(self, conn: Connection, frame: ImageAttachment) -> None:
        if frame.session_key and frame.session_key != conn.current_session_uuid:
            conn.switch_session(frame.session_key)
            await self._index_chat_id(conn)
        try:
            image_bytes = base64.b64decode(frame.image_base64)
            cached = cache_image_from_bytes(image_bytes, ext=".jpg")
        except Exception as e:
            logger.warning("[glasses] image decode failed: %s", e)
            return
        event = self._build_event(
            conn=conn,
            text="",
            message_type=MessageType.PHOTO,
            message_id=frame.id,
            media_urls=[cached],
            media_types=["image/jpeg"],
        )
        await self.handle_message(event)

    async def _on_switch_session(self, conn: Connection, frame: SwitchSession) -> None:
        conn.switch_session(frame.session_key)
        await self._index_chat_id(conn)
        await conn.send_json(
            session_list(conn.session_list(), conn.current_session_uuid)
        )

    async def _on_new_session(self, conn: Connection, frame: NewSession) -> None:
        conn.new_session(label=frame.name)
        await self._index_chat_id(conn)
        await conn.send_json(
            session_list(conn.session_list(), conn.current_session_uuid)
        )

    async def _on_slash_command(self, conn: Connection, frame: SlashCommand) -> None:
        target_session = frame.session_key or conn.current_session_uuid
        if target_session != conn.current_session_uuid:
            conn.switch_session(target_session)
            await self._index_chat_id(conn)
        text = frame.command if frame.command.startswith("/") else f"/{frame.command}"

        # Adapter-managed commands — handled locally and never forwarded
        # to the agent loop. The matching `/new-session` is already handled
        # at the bridge layer (CapsSlashCommand -> NewSession WS frame).
        if await self._maybe_handle_adapter_command(conn, text):
            return

        event = self._build_event(
            conn=conn,
            text=text,
            message_type=MessageType.COMMAND,
            message_id=uuid.uuid4().hex[:12],
        )
        await self.handle_message(event)

    async def _maybe_handle_adapter_command(self, conn: Connection, text: str) -> bool:
        """Returns True if the command was an adapter-managed one we
        consumed. False means the caller should forward to the agent."""
        parts = text.strip().split(maxsplit=1)
        if not parts:
            return False
        command = parts[0]
        argument = parts[1] if len(parts) > 1 else ""

        if command == "/rename-session":
            target = argument.split(maxsplit=1)
            session_key = target[0] if target else conn.current_session_uuid
            label = target[1] if len(target) > 1 else None
            # Convention: /rename-session <label>      → rename current session
            #             /rename-session <key> <label> → rename a specific session
            if session_key not in conn.sessions:
                session_key, label = conn.current_session_uuid, argument or None
            conn.rename_session(session_key, label)
            await conn.send_json(
                session_list(conn.session_list(), conn.current_session_uuid),
            )
            return True

        if command == "/delete-session":
            session_key = argument.strip() or conn.current_session_uuid
            new_active = conn.delete_session(session_key)
            if new_active is None:
                return True  # session didn't exist; nothing to do
            await self._index_chat_id(conn)
            await conn.send_json(
                session_list(conn.session_list(), conn.current_session_uuid),
            )
            return True

        return False

    async def _on_display_state(self, conn: Connection, frame: DisplayState) -> None:
        # Glasses display state is informational only at the adapter layer;
        # wake-on-message coordination lives on the phone (§12 in tasks.md).
        logger.debug("[glasses] conn %s display_state: %s", conn.conn_id, frame)

    def _build_event(
        self,
        *,
        conn: Connection,
        text: str,
        message_type: MessageType,
        message_id: str,
        media_urls: Optional[List[str]] = None,
        media_types: Optional[List[str]] = None,
    ) -> MessageEvent:
        chat_id = conn.chat_id
        source = self.build_source(
            chat_id=chat_id,
            chat_name=f"glasses/{conn.conn_id}",
            chat_type="dm",
            user_id=conn.device_id or conn.conn_id,
            user_name=conn.device_id or "glasses-user",
        )
        return MessageEvent(
            text=text,
            message_type=message_type,
            source=source,
            message_id=message_id,
            media_urls=list(media_urls or []),
            media_types=list(media_types or []),
            timestamp=datetime.now(),
        )

    # ── Outbound (BasePlatformAdapter overrides) ───────────────────────────

    async def send(
        self,
        chat_id: str,
        content: str,
        reply_to: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> SendResult:
        conn = self._conn_for_chat(chat_id)
        if conn is None:
            return SendResult(success=False, error="no live connection for chat_id")
        session_uuid = conn.session_uuid_from_chat_id(chat_id) or conn.current_session_uuid
        message_id = uuid.uuid4().hex[:12]

        origin = (metadata or {}).get("origin") if metadata else None
        if origin in ("cron-job", "run-completion", "agent-nudge"):
            return await self.push(
                chat_id=chat_id,
                text=content,
                origin=origin,
                job_id=metadata.get("job_id") if metadata else None,
                run_id=metadata.get("run_id") if metadata else None,
                conversation=metadata.get("conversation") if metadata else None,
            )

        progress = parse_tool_progress(content)
        if progress is not None:
            conn.message_kind[message_id] = "tool_progress"
            conn.sent_text[message_id] = content
            ok = await conn.send_json(tool_progress(
                message_id=message_id,
                tool_name=progress.tool_name,
                phase="started",
                preview=progress.preview,
                session_key=session_uuid,
            ))
        else:
            conn.message_kind[message_id] = "content"
            conn.sent_text[message_id] = content
            ok = await conn.send_json(assistant_chunk(
                message_id=message_id,
                chunk=content,
                parent_id=reply_to,
                session_key=session_uuid,
            ))
        if not ok:
            return SendResult(success=False, error="ws send failed")
        return SendResult(success=True, message_id=message_id)

    async def edit_message(
        self,
        chat_id: str,
        message_id: str,
        content: str,
        *,
        finalize: bool = False,
    ) -> SendResult:
        conn = self._conn_for_chat(chat_id)
        if conn is None:
            return SendResult(success=False, error="no live connection for chat_id")
        session_uuid = conn.session_uuid_from_chat_id(chat_id) or conn.current_session_uuid
        kind = conn.message_kind.get(message_id, "content")
        prev = conn.sent_text.get(message_id, "")

        if kind == "tool_progress":
            progress = parse_tool_progress(content)
            tool_name = progress.tool_name if progress else "tool"
            preview = progress.preview if progress else None
            phase = "completed" if finalize else "started"
            await conn.send_json(tool_progress(
                message_id=message_id,
                tool_name=tool_name,
                phase=phase,
                preview=preview,
                session_key=session_uuid,
            ))
            conn.sent_text[message_id] = content
            return SendResult(success=True, message_id=message_id)

        delta = content[len(prev):] if content.startswith(prev) else content
        if delta:
            await conn.send_json(assistant_chunk(
                message_id=message_id,
                chunk=delta,
                session_key=session_uuid,
            ))
            conn.sent_text[message_id] = content
        if finalize:
            await conn.send_json(assistant_complete(
                message_id=message_id,
                session_key=session_uuid,
            ))
            conn.sent_text.pop(message_id, None)
            conn.message_kind.pop(message_id, None)
        return SendResult(success=True, message_id=message_id)

    async def send_voice(
        self,
        chat_id: str,
        audio_path: str,
        caption: Optional[str] = None,
        reply_to: Optional[str] = None,
        **kwargs: Any,
    ) -> SendResult:
        conn = self._conn_for_chat(chat_id)
        if conn is None:
            return SendResult(success=False, error="no live connection for chat_id")
        session_uuid = conn.session_uuid_from_chat_id(chat_id) or conn.current_session_uuid
        path = Path(audio_path)
        try:
            data = path.read_bytes()
        except OSError as e:
            return SendResult(success=False, error=f"read audio failed: {e}")
        message_id = uuid.uuid4().hex[:12]
        ok = await conn.send_json(assistant_audio(
            message_id=message_id,
            bytes_base64=base64.b64encode(data).decode("ascii"),
            ext=path.suffix or ".ogg",
            session_key=session_uuid,
        ))
        if not ok:
            return SendResult(success=False, error="ws send failed")
        return SendResult(success=True, message_id=message_id)

    async def play_tts(
        self,
        chat_id: str,
        audio_path: str,
        **kwargs: Any,
    ) -> SendResult:
        return await self.send_voice(chat_id=chat_id, audio_path=audio_path, **kwargs)

    async def get_chat_info(self, chat_id: str) -> Dict[str, Any]:
        return {"name": chat_id, "type": "dm"}

    # ── Hermes-initiated pushes (cron, run-completion, agent-nudge) ────────

    async def push(
        self,
        *,
        chat_id: str,
        text: str,
        origin: str,
        job_id: Optional[str] = None,
        run_id: Optional[str] = None,
        conversation: Optional[str] = None,
    ) -> SendResult:
        """Emit a ``push_message`` frame for a Hermes-initiated delivery.

        Called from the framework's cron / run-completion / agent-nudge
        delivery paths (which today route through ``send``); we surface a
        first-class wrapper so callers can preserve origin metadata on the
        wire. Returns the same ``SendResult`` shape as ``send``.
        """
        conn = self._conn_for_chat(chat_id)
        if conn is None:
            return SendResult(success=False, error="no live connection for chat_id")
        session_uuid = conn.session_uuid_from_chat_id(chat_id) or conn.current_session_uuid
        message_id = uuid.uuid4().hex[:12]
        ok = await conn.send_json(push_message(
            origin=origin,
            session_key=session_uuid,
            message_id=message_id,
            text=text,
            job_id=job_id,
            run_id=run_id,
            conversation=conversation,
        ))
        if not ok:
            return SendResult(success=False, error="ws send failed")
        return SendResult(success=True, message_id=message_id)

    # ── Helpers ────────────────────────────────────────────────────────────

    def _conn_for_chat(self, chat_id: str) -> Optional[Connection]:
        conn_id = self._chat_id_index.get(chat_id)
        if conn_id is None:
            return None
        return self._connections.get(conn_id)


# ---------------------------------------------------------------------------
# Plugin registration entry points
# ---------------------------------------------------------------------------

def check_requirements() -> bool:
    return load_config() is not None


def validate_config(config) -> bool:
    cfg = load_config()
    return cfg is not None


def is_ready_to_register(config) -> bool:
    """Plugin readiness probe — does the on-disk config parse?

    Hermes' plugin context calls this as ``is_connected`` in the registration
    block, but the framework can't actually inspect a live WebSocket session
    here (no adapter instance is in scope), so we report "config valid" and
    rely on the gateway's status surface for live connection state.
    """
    return load_config() is not None


def register(ctx) -> None:
    """Plugin entry point — called by the Hermes plugin system."""
    ctx.register_platform(
        name=PLATFORM_NAME,
        label="Glasses",
        adapter_factory=lambda cfg: GlassesAdapter(cfg),
        check_fn=check_requirements,
        validate_config=validate_config,
        is_connected=is_ready_to_register,
        required_env=[],
        install_hint="Create ~/.hermes/plugins/hermes-channel-adapter/config.yaml",
        max_message_length=0,
        emoji="\U0001F576",
        platform_hint=(
            "You are speaking through a head-up display on Rokid Glasses paired "
            "with a phone bridge. Display area is small (a few short lines on a "
            "480x640 portrait HUD). Keep replies concise and conversational. "
            "Plain text only — no markdown rendering."
        ),
    )
