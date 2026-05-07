"""Per-connection state.

A ``Connection`` holds the live WebSocket, its current session selection,
and the text-tracking dicts that let us compute deltas for outbound
``edit_message`` calls (which the framework uses to stream tokens —
see design.md D8).
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from aiohttp import web

try:
    from .frames import SessionInfo
except ImportError:  # standalone (tests put the dir on sys.path)
    from frames import SessionInfo  # type: ignore[no-redef]

logger = logging.getLogger(__name__)


def _new_session_uuid() -> str:
    return uuid.uuid4().hex[:12]


@dataclass
class Connection:
    """One live phone WebSocket plus the sessions visible from it."""

    conn_id: str
    ws: web.WebSocketResponse
    device_id: Optional[str] = None
    current_session_uuid: str = field(default_factory=_new_session_uuid)
    sessions: Dict[str, SessionInfo] = field(default_factory=dict)
    # Per-message-id state needed to fan out edit_message into chunk deltas.
    sent_text: Dict[str, str] = field(default_factory=dict)
    message_kind: Dict[str, str] = field(default_factory=dict)
    # Per-stream voice-note reassembly buffer. Lives on the connection so it
    # is automatically released when the connection closes — partial uploads
    # from a client that disconnected mid-stream can't leak forever.
    voice_streams: Dict[str, Dict[int, bytes]] = field(default_factory=dict)
    # Pending deferred `assistant_complete` tasks keyed by message_id. A task
    # is scheduled when send() emits a one-shot chunk; if edit_message() lands
    # for the same id before the task fires, the task is cancelled so a
    # streaming follow-up doesn't get a premature complete.
    pending_completes: Dict[str, asyncio.Task] = field(default_factory=dict)
    send_lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    def __post_init__(self) -> None:
        if self.current_session_uuid not in self.sessions:
            self.sessions[self.current_session_uuid] = SessionInfo(
                session_key=self.current_session_uuid,
                last_active_at=int(time.time()),
            )

    @property
    def chat_id(self) -> str:
        return f"glasses:{self.conn_id}:{self.current_session_uuid}"

    def chat_id_for_session(self, session_uuid: str) -> str:
        return f"glasses:{self.conn_id}:{session_uuid}"

    def session_list(self) -> List[SessionInfo]:
        return sorted(
            self.sessions.values(),
            key=lambda s: s.last_active_at or 0,
            reverse=True,
        )

    def switch_session(self, session_uuid: str) -> bool:
        if session_uuid not in self.sessions:
            logger.info(
                "conn %s: switch_session to unknown key %r — allocating",
                self.conn_id, session_uuid,
            )
            self.sessions[session_uuid] = SessionInfo(
                session_key=session_uuid,
                last_active_at=int(time.time()),
            )
        self.current_session_uuid = session_uuid
        self.sessions[session_uuid].last_active_at = int(time.time())
        return True

    def new_session(self, label: Optional[str] = None) -> str:
        session_uuid = _new_session_uuid()
        self.sessions[session_uuid] = SessionInfo(
            session_key=session_uuid,
            label=label,
            last_active_at=int(time.time()),
        )
        self.current_session_uuid = session_uuid
        return session_uuid

    def rename_session(self, session_uuid: str, label: Optional[str]) -> bool:
        existing = self.sessions.get(session_uuid)
        if existing is None:
            return False
        # Empty / None label clears it back to "untitled".
        normalized = label.strip() if label else None
        self.sessions[session_uuid] = SessionInfo(
            session_key=session_uuid,
            label=normalized or None,
            last_active_at=existing.last_active_at,
        )
        return True

    def delete_session(self, session_uuid: str) -> Optional[str]:
        """Remove a session. Returns the new current_session_uuid (which may
        differ if the deleted session was the active one). Returns None if
        the session didn't exist."""
        if session_uuid not in self.sessions:
            return None
        self.sessions.pop(session_uuid, None)
        if self.current_session_uuid != session_uuid:
            return self.current_session_uuid
        # Active session was deleted — pick the most recently active remaining
        # session, or allocate a new one if the list is empty.
        next_session = next(iter(self.session_list()), None)
        if next_session is None:
            return self.new_session()
        self.current_session_uuid = next_session.session_key
        return self.current_session_uuid

    def session_uuid_from_chat_id(self, chat_id: str) -> Optional[str]:
        prefix = f"glasses:{self.conn_id}:"
        if not chat_id.startswith(prefix):
            return None
        return chat_id[len(prefix):]

    async def send_json(self, payload: Dict[str, Any]) -> bool:
        if self.ws.closed:
            return False
        async with self.send_lock:
            try:
                await self.ws.send_str(json.dumps(payload, ensure_ascii=False))
                return True
            except (ConnectionResetError, RuntimeError) as e:
                logger.debug("conn %s: send failed: %s", self.conn_id, e)
                return False
