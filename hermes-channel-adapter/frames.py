"""Phone <-> adapter WebSocket frame DTOs.

All frames are JSON objects with a string ``type`` discriminator. ``parse_frame``
is the only inbound entry point; it returns ``None`` for malformed or unknown
input so callers can drop the frame without crashing the connection. Outbound
helpers return plain dicts ready for ``json.dumps``.
"""

from __future__ import annotations

import dataclasses
import json
import logging
from dataclasses import asdict, dataclass, field
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

PROTOCOL_VERSION = 1


# ---------------------------------------------------------------------------
# Inbound frames (phone -> adapter)
# ---------------------------------------------------------------------------

@dataclass
class ClientHello:
    protocol_version: int
    device_id: Optional[str] = None
    current_session_key: Optional[str] = None


@dataclass
class UserMessage:
    id: str
    text: str
    session_key: Optional[str] = None
    image_base64: Optional[str] = None


@dataclass
class VoiceNote:
    id: str
    stream_id: str
    chunk_index: int
    total_chunks: int
    audio_base64: str
    ext: str = ".ogg"
    session_key: Optional[str] = None


@dataclass
class ImageAttachment:
    id: str
    image_base64: str
    session_key: Optional[str] = None


@dataclass
class SwitchSession:
    session_key: str


@dataclass
class NewSession:
    name: Optional[str] = None


@dataclass
class SlashCommand:
    command: str
    session_key: Optional[str] = None


@dataclass
class DisplayState:
    connected: bool
    active: Optional[bool] = None


@dataclass
class Ping:
    id: str


@dataclass
class Pong:
    id: str


_INBOUND_TYPES = {
    "client_hello": ClientHello,
    "user_message": UserMessage,
    "voice_note": VoiceNote,
    "image_attachment": ImageAttachment,
    "switch_session": SwitchSession,
    "new_session": NewSession,
    "slash_command": SlashCommand,
    "display_state": DisplayState,
    "ping": Ping,
    "pong": Pong,
}


# Raw key -> dataclass field name. Wire uses camelCase to keep the
# Kotlin/Android side idiomatic; Python uses snake_case internally.
_FIELD_ALIAS: Dict[str, str] = {
    "protocolVersion": "protocol_version",
    "deviceId": "device_id",
    "currentSessionKey": "current_session_key",
    "sessionKey": "session_key",
    "imageBase64": "image_base64",
    "streamId": "stream_id",
    "chunkIndex": "chunk_index",
    "totalChunks": "total_chunks",
    "audioBase64": "audio_base64",
}

_FIELD_ALIAS_REVERSE: Dict[str, str] = {v: k for k, v in _FIELD_ALIAS.items()}


def _normalize_keys(payload: Dict[str, Any]) -> Dict[str, Any]:
    return {_FIELD_ALIAS.get(k, k): v for k, v in payload.items()}


def parse_frame(raw: str) -> Optional[Any]:
    """Parse a raw JSON string into a typed frame.

    Returns ``None`` for malformed JSON, missing/unknown ``type``, or schema
    mismatches. The connection layer drops ``None`` and logs at debug level.
    """
    try:
        obj = json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        logger.debug("parse_frame: invalid JSON")
        return None
    if not isinstance(obj, dict):
        return None
    type_name = obj.get("type")
    cls = _INBOUND_TYPES.get(type_name)
    if cls is None:
        logger.debug("parse_frame: unknown type %r", type_name)
        return None
    payload = {k: v for k, v in obj.items() if k != "type"}
    payload = _normalize_keys(payload)
    # Forward-compat: drop fields the dataclass doesn't know about so a newer
    # phone build with an extra field doesn't make older adapters reject
    # otherwise-valid frames. Mirrors the Kotlin/Gson-side leniency.
    known = {f.name for f in dataclasses.fields(cls)}
    unknown = set(payload) - known
    if unknown:
        logger.debug("parse_frame: ignoring unknown fields on %s: %s", type_name, sorted(unknown))
        payload = {k: v for k, v in payload.items() if k in known}
    try:
        return cls(**payload)
    except TypeError as e:
        logger.debug("parse_frame: schema mismatch for %s: %s", type_name, e)
        return None


# ---------------------------------------------------------------------------
# Outbound frames (adapter -> phone)
#
# Outbound is dict-based on purpose: we serialize once with json.dumps and
# never round-trip back through dataclasses. Shape is enforced by the
# constructor functions below.
# ---------------------------------------------------------------------------

def _camel(snake: str) -> str:
    if snake in _FIELD_ALIAS_REVERSE:
        return _FIELD_ALIAS_REVERSE[snake]
    if "_" not in snake:
        return snake
    head, *tail = snake.split("_")
    return head + "".join(p.title() for p in tail)


def _camelize(payload: Dict[str, Any]) -> Dict[str, Any]:
    return {_camel(k): v for k, v in payload.items() if v is not None}


def server_welcome(
    sessions: List["SessionInfo"],
    current_session_key: str,
) -> Dict[str, Any]:
    return {
        "type": "server_welcome",
        "protocolVersion": PROTOCOL_VERSION,
        "sessions": [s.to_dict() for s in sessions],
        "currentSessionKey": current_session_key,
    }


def assistant_chunk(
    message_id: str,
    chunk: str,
    parent_id: Optional[str] = None,
    session_key: Optional[str] = None,
) -> Dict[str, Any]:
    return _camelize({
        "type": "assistant_chunk",
        "id": message_id,
        "chunk": chunk,
        "parent_id": parent_id,
        "session_key": session_key,
    })


def assistant_complete(
    message_id: str,
    session_key: Optional[str] = None,
) -> Dict[str, Any]:
    return _camelize({
        "type": "assistant_complete",
        "id": message_id,
        "session_key": session_key,
    })


def assistant_audio(
    message_id: str,
    bytes_base64: str,
    ext: str = ".ogg",
    session_key: Optional[str] = None,
) -> Dict[str, Any]:
    return {
        "type": "assistant_audio",
        "id": message_id,
        "bytesBase64": bytes_base64,
        "ext": ext,
        **({"sessionKey": session_key} if session_key else {}),
    }


def tool_progress(
    message_id: str,
    tool_name: str,
    phase: str = "started",
    preview: Optional[str] = None,
    session_key: Optional[str] = None,
) -> Dict[str, Any]:
    return _camelize({
        "type": "tool_progress",
        "messageId": message_id,
        "toolName": tool_name,
        "phase": phase,
        "preview": preview,
        "session_key": session_key,
    })


def push_message(
    origin: str,
    session_key: str,
    message_id: str,
    text: str,
    job_id: Optional[str] = None,
    run_id: Optional[str] = None,
    conversation: Optional[str] = None,
) -> Dict[str, Any]:
    out: Dict[str, Any] = {
        "type": "push_message",
        "origin": origin,
        "sessionKey": session_key,
        "messageId": message_id,
        "text": text,
    }
    if job_id is not None:
        out["jobId"] = job_id
    if run_id is not None:
        out["runId"] = run_id
    if conversation is not None:
        out["conversation"] = conversation
    return out


def session_list(
    sessions: List["SessionInfo"],
    current_session_key: str,
) -> Dict[str, Any]:
    return {
        "type": "session_list",
        "sessions": [s.to_dict() for s in sessions],
        "currentSessionKey": current_session_key,
    }


def connection_update(connected: bool) -> Dict[str, Any]:
    return {"type": "connection_update", "connected": connected}


def pong(id_: str) -> Dict[str, Any]:
    return {"type": "pong", "id": id_}


@dataclass
class SessionInfo:
    session_key: str
    label: Optional[str] = None
    last_active_at: Optional[int] = None

    def to_dict(self) -> Dict[str, Any]:
        out: Dict[str, Any] = {"sessionKey": self.session_key}
        if self.label is not None:
            out["label"] = self.label
        if self.last_active_at is not None:
            out["lastActiveAt"] = self.last_active_at
        return out
