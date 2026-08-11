#!/usr/bin/env python3
"""End-to-end checks for Agent tab + dashboard generate paths.

Requires a running self-host stack (including the deepsql-agent Compose service)
and admin creds in .env.
Usage (from repo root):
  python3 scripts/self-host/e2e-agent-check.py [connectionId]
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.request import HTTPCookieProcessor, build_opener

ROOT = Path(__file__).resolve().parents[2]
ENV = ROOT / ".env"


def load_env(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    if not path.exists():
        return out
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip().strip('"').strip("'")
    return out


def main() -> int:
    env = {**load_env(ENV), **os.environ}
    email = env.get("DEEPSQL_INITIAL_ADMIN_EMAIL") or env.get("DEEPSQL_SMOKE_EMAIL")
    password = env.get("DEEPSQL_INITIAL_ADMIN_PASSWORD") or env.get("DEEPSQL_SMOKE_PASSWORD")
    if not email or not password:
        print("Missing admin email/password in .env", file=sys.stderr)
        return 1

    frontend = f"http://localhost:{env.get('DEEPSQL_FRONTEND_PORT', '3000')}"
    backend = f"http://localhost:{env.get('DEEPSQL_BACKEND_PORT', '8080')}/api"
    conn = sys.argv[1] if len(sys.argv) > 1 else None

    opener = build_opener(HTTPCookieProcessor(CookieJar()))

    def req(url: str, data=None, *, origin: str | None = None, timeout: int = 180):
        body = None
        headers: dict[str, str] = {}
        if data is not None:
            body = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
        if origin:
            headers["Origin"] = origin
            headers["Referer"] = origin.rstrip("/") + "/"
        r = urllib.request.Request(
            url, data=body, headers=headers, method="POST" if data is not None else "GET"
        )
        with opener.open(r, timeout=timeout) as resp:
            raw = resp.read().decode() or "null"
            return json.loads(raw)

    print("→ login")
    req(f"{backend}/auth/login", {"email": email, "password": password})

    if not conn:
        conns = req(f"{backend}/connections")
        if isinstance(conns, list) and conns:
            conn = conns[0].get("connectionId") or conns[0].get("id")
        elif isinstance(conns, dict):
            items = conns.get("connections") or conns.get("items") or []
            if items:
                conn = items[0].get("connectionId") or items[0].get("id")
    if not conn:
        print("No connectionId available", file=sys.stderr)
        return 1
    print(f"→ connection {conn}")

    # ── Agent tab ──────────────────────────────────────────────────────────
    print("\n=== Agent tab (browser → /agent-api → DeepSQL Agent → MCP) ===")
    bridge = req(f"{backend}/agent/session", {"connectionId": conn})
    profile = bridge["profile"]
    print("profile", profile)
    sw = req(
        f"{frontend}/agent-api/api/profile/switch",
        {"name": profile},
        origin=frontend,
    )
    print("switch active", sw.get("active"))
    sess = req(
        f"{frontend}/agent-api/api/session/new",
        {"profile": profile, "enabled_toolsets": ["deepsql", "skills"]},
        origin=frontend,
    )
    sid = sess["session"]["session_id"]
    print("session", sid)
    try:
        req(
            f"{frontend}/agent-api/api/session/yolo",
            {"session_id": sid, "enabled": True},
            origin=frontend,
        )
    except Exception as e:
        print("yolo (non-fatal)", e)

    msg = (
        f"[Active DeepSQL connection: id {conn}. Use this connection.]\n\n"
        "Call mcp_deepsql_execute_sql with SELECT current_database() AS db_name. "
        "Reply with just the database name."
    )
    start = req(
        f"{frontend}/agent-api/api/chat/start",
        {"session_id": sid, "message": msg},
        origin=frontend,
    )
    stream_id = start["stream_id"]
    print("stream", stream_id)

    tokens: list[str] = []
    tools: list[str] = []
    done = False
    r = urllib.request.Request(
        f"{frontend}/agent-api/api/chat/stream?stream_id={stream_id}",
        headers={"Accept": "text/event-stream"},
    )
    with opener.open(r, timeout=300) as resp:
        buf = ""
        deadline = time.time() + 300
        while time.time() < deadline and not done:
            chunk = resp.read(1024)
            if not chunk:
                break
            buf += chunk.decode("utf-8", "replace")
            while "\n\n" in buf:
                event, buf = buf.split("\n\n", 1)
                et, data = "message", ""
                for line in event.splitlines():
                    if line.startswith("event:"):
                        et = line[6:].strip()
                    elif line.startswith("data:"):
                        data += line[5:].lstrip()
                if et in ("stream_end", "done"):
                    done = True
                elif et == "token":
                    try:
                        tokens.append(json.loads(data).get("text", ""))
                    except Exception:
                        pass
                elif et == "tool":
                    try:
                        name = json.loads(data).get("name", "")
                        tools.append(name)
                        print("TOOL", name)
                    except Exception:
                        pass

    answer = "".join(tokens).strip()
    print("ANSWER", answer[:500])
    print("TOOLS", tools)

    # The verdict used to be:
    #   ("dba_agent" in answer) or any("execute_sql" in t for t in tools)
    # The right-hand side only proves a tool was *attempted*. When the MCP SDK moved
    # to 2.x and every tool call died with
    #   AttributeError: 'CallToolResult' object has no attribute 'isError'
    # the tool names were still recorded, so this printed "✓ All agent UI paths OK"
    # and exited 0 while the agent's own reply said "I'm blocked". A gate that passes
    # over a dead agent is worse than no gate — it is why that breakage reached users
    # instead of CI. The answer is the only honest evidence, so require it, and refuse
    # replies that are visibly reporting tool failure.
    answer_l = answer.lower()
    failure_markers = (
        "attributeerror",
        "mcp call failed",
        "is unreachable",
        "i'm blocked",
        "i am blocked",
        "no attribute",
    )
    seen_failures = [m for m in failure_markers if m in answer_l]
    called_sql = any("execute_sql" in t for t in tools)
    answered = "dba_agent" in answer_l

    agent_ok = answered and called_sql and not seen_failures
    if not agent_ok:
        if not called_sql:
            print("AGENT_FAIL: execute_sql was never called")
        if not answered:
            print("AGENT_FAIL: reply lacks the expected database name 'dba_agent'")
        if seen_failures:
            print(f"AGENT_FAIL: reply reports tool failure {seen_failures}")
    print("AGENT_OK", agent_ok)

    # ── Dashboard generate ─────────────────────────────────────────────────
    print("\n=== Dashboard generate (backend → DeepSQL Agent) ===")
    dash_ok = False
    try:
        dash = req(
            f"{backend}/dashboards/generate",
            {
                "connectionId": conn,
                "prompt": (
                    "Create a minimal self-contained HTML dashboard with an h1 "
                    "'Table Count' and one metric from "
                    "SELECT count(*)::int AS n FROM information_schema.tables "
                    "WHERE table_schema = 'public'. Load the dashboard-design skill. "
                    "Return ONE ```html document only."
                ),
            },
            timeout=420,
        )
        html = ""
        if isinstance(dash, dict):
            html = dash.get("html") or ""
            cfg = dash.get("dashboardConfig") or dash.get("config") or {}
            if not html and isinstance(cfg, dict):
                html = cfg.get("html") or ""
            if not html and dash.get("renderMode") == "artifact":
                html = dash.get("html") or ""
        if isinstance(dash, dict) and not html:
            cfg = dash.get("dashboardConfig") or {}
            if isinstance(cfg, dict):
                html = cfg.get("html") or ""
        print("DASH_KEYS", list(dash.keys())[:15] if isinstance(dash, dict) else type(dash))
        print("HTML_LEN", len(html) if isinstance(html, str) else 0)
        # "It is HTML and it is long" was also true of the artifact produced while
        # every MCP tool was failing: the agent could not read the schema or verify a
        # query, so it emitted a plausible-looking dashboard full of invented numbers
        # and this still reported DASH_OK True. A real artifact fetches its data at
        # runtime through the injected deepsql.query() bridge (that is the artifact
        # contract — see CLAUDE.md), so its absence means the numbers are hardcoded
        # model output rather than anything the database returned.
        html_l = html.lower() if isinstance(html, str) else ""
        has_html = len(html_l) > 50 and "<html" in html_l
        queries_live = "deepsql.query" in html_l
        dash_ok = has_html and queries_live
        if not dash_ok:
            if not has_html:
                print("DASH_FAIL: no HTML document returned")
            elif not queries_live:
                print("DASH_FAIL: artifact never calls deepsql.query() — data is not "
                      "from the database, so the agent likely could not run SQL")
        title = None
        if isinstance(dash, dict):
            cfg = dash.get("dashboardConfig") if isinstance(dash.get("dashboardConfig"), dict) else {}
            title = dash.get("title") or cfg.get("title")
        print("DASH_TITLE", title)
    except urllib.error.HTTPError as e:
        print("DASH_ERR", e.code, e.read().decode()[:800])
    except Exception as e:
        print("DASH_ERR", e)
    print("DASH_OK", dash_ok)

    if agent_ok and dash_ok:
        print("\n✓ All agent UI paths OK")
        return 0
    print("\n✗ Agent path verification failed", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
