"""Test bootstrap.

The plugin folder is the package — push it onto sys.path so flat imports
("import frames", "import adapter") resolve. We also install lightweight
stand-ins for the ``gateway.*`` modules the adapter imports from Hermes,
so tests run without a full Hermes checkout.

Stubs only need to expose the names ``adapter.py`` actually pulls in:
``BasePlatformAdapter`` (with the lifecycle hooks it relies on), the
``MessageEvent`` / ``MessageType`` / ``SendResult`` types, and the
two cache helpers. ``Platform`` is shimmed as ``str``-enum-ish so the
constructor accepts our "glasses" string.
"""

from __future__ import annotations

import sys
import types
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any, Dict, List, Optional

PLUGIN_ROOT = Path(__file__).resolve().parents[1]
if str(PLUGIN_ROOT) not in sys.path:
    sys.path.insert(0, str(PLUGIN_ROOT))


# ---------------------------------------------------------------------------
# Fake gateway.* modules
# ---------------------------------------------------------------------------

def _install_fake_gateway() -> None:
    if "gateway" in sys.modules:
        return

    gateway = types.ModuleType("gateway")
    gateway_config = types.ModuleType("gateway.config")
    gateway_platforms = types.ModuleType("gateway.platforms")
    gateway_platforms_base = types.ModuleType("gateway.platforms.base")

    class Platform(str):
        def __new__(cls, value: str):
            inst = str.__new__(cls, value)
            inst.value = value
            return inst

    @dataclass
    class PlatformConfig:
        extra: Dict[str, Any] = field(default_factory=dict)

    @dataclass
    class SessionSource:
        platform: Any = None
        chat_id: str = ""
        chat_name: Optional[str] = None
        chat_type: str = "dm"
        user_id: Optional[str] = None
        user_name: Optional[str] = None
        thread_id: Optional[str] = None

    class MessageType(Enum):
        TEXT = "text"
        VOICE = "voice"
        PHOTO = "photo"
        COMMAND = "command"

    @dataclass
    class MessageEvent:
        text: str
        message_type: MessageType = MessageType.TEXT
        source: Any = None
        message_id: Optional[str] = None
        media_urls: List[str] = field(default_factory=list)
        media_types: List[str] = field(default_factory=list)
        timestamp: datetime = field(default_factory=datetime.now)
        raw_message: Any = None
        reply_to_message_id: Optional[str] = None

    @dataclass
    class SendResult:
        success: bool
        message_id: Optional[str] = None
        error: Optional[str] = None

    class BasePlatformAdapter:
        def __init__(self, config: Any, platform: Any) -> None:
            self.config = config
            self.platform = platform
            self._running = False
            self._fatal_error_message: Optional[str] = None
            self._fatal_error_code: Optional[str] = None
            self._message_handler = None

        def _mark_connected(self) -> None:
            self._running = True

        def _mark_disconnected(self) -> None:
            self._running = False

        def _set_fatal_error(self, code: str, message: str, *, retryable: bool = True) -> None:
            self._fatal_error_message = message
            self._fatal_error_code = code

        def set_message_handler(self, handler) -> None:
            self._message_handler = handler

        async def handle_message(self, event: MessageEvent) -> None:
            if self._message_handler is not None:
                await self._message_handler(event)

        def build_source(
            self,
            chat_id: str,
            chat_name: Optional[str] = None,
            chat_type: str = "dm",
            user_id: Optional[str] = None,
            user_name: Optional[str] = None,
            **kwargs: Any,
        ) -> SessionSource:
            return SessionSource(
                platform=self.platform,
                chat_id=chat_id,
                chat_name=chat_name,
                chat_type=chat_type,
                user_id=user_id,
                user_name=user_name,
            )

    def cache_audio_from_bytes(data: bytes, ext: str = ".ogg") -> str:
        import tempfile
        f = tempfile.NamedTemporaryFile(suffix=ext, delete=False)
        f.write(data)
        f.close()
        return f.name

    def cache_image_from_bytes(data: bytes, ext: str = ".jpg") -> str:
        import tempfile
        f = tempfile.NamedTemporaryFile(suffix=ext, delete=False)
        f.write(data)
        f.close()
        return f.name

    gateway_config.Platform = Platform
    gateway_config.PlatformConfig = PlatformConfig
    gateway_platforms_base.BasePlatformAdapter = BasePlatformAdapter
    gateway_platforms_base.MessageEvent = MessageEvent
    gateway_platforms_base.MessageType = MessageType
    gateway_platforms_base.SendResult = SendResult
    gateway_platforms_base.cache_audio_from_bytes = cache_audio_from_bytes
    gateway_platforms_base.cache_image_from_bytes = cache_image_from_bytes
    gateway_platforms.base = gateway_platforms_base
    gateway.config = gateway_config
    gateway.platforms = gateway_platforms

    sys.modules["gateway"] = gateway
    sys.modules["gateway.config"] = gateway_config
    sys.modules["gateway.platforms"] = gateway_platforms
    sys.modules["gateway.platforms.base"] = gateway_platforms_base


_install_fake_gateway()
