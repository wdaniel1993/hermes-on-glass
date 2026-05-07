"""Heuristic parser for Hermes' built-in tool-progress text format.

The framework formats tool progress as text and dispatches via the standard
``adapter.send`` path (see ``gateway/run.py`` around line 12860). It does
not provide a structured tool-progress hook for adapters. To meet the
``tool_progress`` spec scenario we parse the formatted text back into
``(tool_name, preview)`` so we can emit the dedicated frame on the wire.

Format we accept (one bubble per call, gateway-side):
  "<emoji> <tool_name>: \"<preview>\"..."        # with preview
  "<emoji> <tool_name>..."                      # no preview
  "<emoji> <tool_name>([keys]) \\n <args_str>"   # verbose mode

False negatives are fine — they fall through to a regular assistant_chunk.
False positives must be guarded: a real assistant message that happens to
start with an emoji is rare but possible, so we require the trailing
ellipsis as a strong signal.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Optional

# tool_name in Hermes is always lower_snake_case (see tools/registry.py).
_PATTERN_SHORT = re.compile(
    r"^(?P<emoji>\S+)\s+(?P<tool>[a-z][a-z0-9_]*)"
    r"(?:\s*:\s*\"(?P<preview>.*?)\")?\s*\.{2,3}\s*$",
    re.DOTALL,
)

_PATTERN_VERBOSE = re.compile(
    r"^(?P<emoji>\S+)\s+(?P<tool>[a-z][a-z0-9_]*)\s*\([^)]*\)\s*$",
)


@dataclass
class ToolProgress:
    tool_name: str
    preview: Optional[str] = None


def parse(text: str) -> Optional[ToolProgress]:
    if not text:
        return None
    first_line = text.split("\n", 1)[0]
    m = _PATTERN_SHORT.match(first_line)
    if m:
        return ToolProgress(
            tool_name=m.group("tool"),
            preview=m.group("preview") or None,
        )
    m = _PATTERN_VERBOSE.match(first_line)
    if m:
        return ToolProgress(tool_name=m.group("tool"))
    return None
