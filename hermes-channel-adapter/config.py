"""Adapter config loaded from ``~/.hermes/plugins/hermes-channel-adapter/config.yaml``.

Voice settings live in ``~/.hermes/config.yaml`` and are inherited via
the shared response pipeline; this file only carries WebSocket bind info
and the shared secret.
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import yaml

logger = logging.getLogger(__name__)

DEFAULT_LISTEN_HOST = "0.0.0.0"
DEFAULT_LISTEN_PORT = 8765
DEFAULT_WS_PATH = "/glasses"


@dataclass
class AdapterConfig:
    listen_host: str
    listen_port: int
    shared_secret: str
    ws_path: str = DEFAULT_WS_PATH

    @property
    def is_loopback_only(self) -> bool:
        return self.listen_host in ("127.0.0.1", "::1", "localhost")


def _config_path() -> Path:
    home = os.environ.get("HERMES_HOME") or str(Path.home() / ".hermes")
    return Path(home) / "plugins" / "hermes-channel-adapter" / "config.yaml"


def load_config(path: Optional[Path] = None) -> Optional[AdapterConfig]:
    """Load the adapter config from disk.

    Returns ``None`` when the file is missing or malformed; the adapter's
    ``connect()`` then logs a configuration error and refuses to register.
    The gateway keeps starting either way so other channels stay up.
    """
    cfg_path = path or _config_path()
    if not cfg_path.exists():
        logger.error("hermes-channel-adapter: config not found at %s", cfg_path)
        return None
    try:
        data = yaml.safe_load(cfg_path.read_text(encoding="utf-8")) or {}
    except yaml.YAMLError as e:
        logger.error("hermes-channel-adapter: invalid YAML in %s: %s", cfg_path, e)
        return None
    if not isinstance(data, dict):
        logger.error("hermes-channel-adapter: config root must be a mapping")
        return None
    secret = str(data.get("shared_secret") or "").strip()
    if not secret:
        logger.error("hermes-channel-adapter: shared_secret is required")
        return None
    return AdapterConfig(
        listen_host=str(data.get("listen_host") or DEFAULT_LISTEN_HOST),
        listen_port=int(data.get("listen_port") or DEFAULT_LISTEN_PORT),
        shared_secret=secret,
        ws_path=str(data.get("ws_path") or DEFAULT_WS_PATH),
    )
