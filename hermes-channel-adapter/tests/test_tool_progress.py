"""Heuristic tool-progress parser tests.

The framework formats tool messages like:
  "<emoji> <tool_name>: \"<preview>\"..."
  "<emoji> <tool_name>..."
  "<emoji> <tool_name>([keys])\\n<args_str>"   # verbose mode

We must extract tool name + preview from those, and reject anything that
looks like ordinary assistant content.
"""

from __future__ import annotations

from tool_progress import parse


# ── Positive cases ──────────────────────────────────────────────────────

def test_short_with_preview():
    p = parse("⚙️ web_search: \"openspec proposal\"...")
    assert p is not None
    assert p.tool_name == "web_search"
    assert p.preview == "openspec proposal"


def test_short_no_preview():
    p = parse("\U0001F50D web_search...")
    assert p is not None
    assert p.tool_name == "web_search"
    assert p.preview is None


def test_two_dot_ellipsis_accepted():
    p = parse("\U0001F4DD edit_file..")
    assert p is not None
    assert p.tool_name == "edit_file"


def test_verbose_format():
    body = "⚙️ execute_code(['code'])\n{\"code\": \"print(1)\"}"
    p = parse(body)
    assert p is not None
    assert p.tool_name == "execute_code"


def test_underscore_and_digit_tool_names():
    assert parse("\U0001F310 web_search_v2...").tool_name == "web_search_v2"
    assert parse("\U0001F4BB exec3...").tool_name == "exec3"


# ── Negative cases ──────────────────────────────────────────────────────

def test_plain_text_rejected():
    assert parse("Sure, I can help with that.") is None


def test_no_ellipsis_rejected():
    assert parse("⚙️ web_search: \"hi\"") is None


def test_capitalized_tool_name_rejected():
    # Hermes tool names are lower_snake_case; capitalized text is content.
    assert parse("⚙️ WebSearch...") is None


def test_starts_with_lowercase_rejected():
    # A bare lowercase word with ellipsis is too generic — emoji prefix matters.
    # Our regex requires a non-space prefix character; bare "tool_name..." has
    # nothing in front, so it must come back as None.
    assert parse("web_search...") is None


def test_empty_string():
    assert parse("") is None


def test_multiline_assistant_content():
    text = "Here is a list:\n- item one\n- item two"
    assert parse(text) is None
