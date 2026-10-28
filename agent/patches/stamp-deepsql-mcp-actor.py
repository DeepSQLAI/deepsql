#!/usr/bin/env python3
"""Patch Hermes mcp_tool.py to stamp the active profile before each deepsql MCP call.

The DeepSQL MCP stdio server is process-global; it reads
DEEPSQL_ACTOR_PROFILE_FILE on every tool call to pick the correct
profiles/u-<user>/deepsql.token. Hermes must write that file immediately
before invoking tools on server ``deepsql``.
"""
from __future__ import annotations

import sys
from pathlib import Path

MARKER = "deepsql_stamp_active_mcp_profile"
NEEDLE = "        gate_error = _trust_gate_check(server_name, tool_name)"
INSERT = f"""        gate_error = _trust_gate_check(server_name, tool_name)
        # {MARKER}: per-request profile for shared DeepSQL MCP auth
        if server_name == "deepsql":
            _stamp_deepsql_active_mcp_profile()
"""


def patch(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    if MARKER in text:
        print(f"already patched: {path}")
        return 0
    if NEEDLE not in text:
        print(f"ERROR: needle not found in {path}", file=sys.stderr)
        return 1

    helper = '''

def _stamp_deepsql_active_mcp_profile() -> None:
    """Write the active Hermes profile id for the shared DeepSQL MCP subprocess."""
    import os
    from pathlib import Path as _Path

    actor_file = (os.environ.get("DEEPSQL_ACTOR_PROFILE_FILE") or "").strip()
    if not actor_file:
        return
    profile = None
    try:
        from api.profiles import get_active_profile_name as _webui_active
        profile = _webui_active()
    except Exception:
        try:
            from hermes_cli.profiles import get_active_profile_name as _cli_active
            profile = _cli_active()
        except Exception:
            profile = None
    if not profile or profile == "default":
        return
    try:
        p = _Path(actor_file)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(str(profile).strip() + "\\n", encoding="utf-8")
    except Exception:
        pass
'''
    text = text.replace(NEEDLE, INSERT, 1)
    anchor = "def _make_tool_handler(server_name: str, tool_name: str, tool_timeout: float):"
    if anchor not in text:
        print(f"ERROR: handler anchor not found in {path}", file=sys.stderr)
        return 1
    text = text.replace(anchor, helper + "\n" + anchor, 1)
    path.write_text(text, encoding="utf-8")
    print(f"patched {path}")
    return 0


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: stamp-deepsql-mcp-actor.py <path/to/mcp_tool.py>", file=sys.stderr)
        return 2
    return patch(Path(sys.argv[1]))


if __name__ == "__main__":
    raise SystemExit(main())
