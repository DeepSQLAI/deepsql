#!/usr/bin/env python3
"""Local DeepSQL Agent profile provisioner (native/dev stand-in for the agent container).

Production Compose runs an agent-side secret-gated provisioner on :8788 that
AgentBridgeService POSTs to (see agent.provisioner-url). That binary is not in
this OSS checkout. For Cursor Cloud / native local dev, this script provides the
same contract so /api/agent/session can create `u-<user>` agent profiles with
MCP credentials before the Agent tab opens.

Contract (matches AgentBridgeService.callProvisioner):
  POST /provision
  Header: X-Provision-Secret: <AGENT_PROVISION_SECRET>
  Body:   { "user": "<username>", "token": "<mcp-or-jwt>", "connectionId": "<uuid>" }

Idempotent: creates the profile on first call (cloning default), then refreshes
DEEPSQL_AUTH_TOKEN / DEEPSQL_API_BASE_URL / DEEPSQL_MCP_USER_ID in the profile .env.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERMES_HOME = Path(os.environ.get("HERMES_HOME", Path.home() / ".hermes")).expanduser()
REPO_ROOT = Path(os.environ.get("DEEPSQL_REPO_ROOT", Path(__file__).resolve().parents[1]))
API_BASE = os.environ.get("DEEPSQL_API_BASE_URL", "http://localhost:8080/api/")
SECRET = os.environ.get("AGENT_PROVISION_SECRET", "")
HOST = os.environ.get("AGENT_PROVISIONER_HOST", "127.0.0.1")
PORT = int(os.environ.get("AGENT_PROVISIONER_PORT", "8788"))
HERMES_BIN = os.environ.get("HERMES_BIN", str(Path.home() / ".local/bin/hermes"))


def profile_for(username: str) -> str:
    safe = re.sub(r"[^a-z0-9]+", "-", (username or "").lower()).strip("-")
    return f"u-{safe or 'user'}"


def ensure_profile(name: str) -> Path:
    home = HERMES_HOME / "profiles" / name
    if home.exists():
        return home
    cmd = [HERMES_BIN, "profile", "create", name, "--clone", "--no-alias",
           "--description", f"DeepSQL Agent profile for {name}"]
    subprocess.run(cmd, check=True, env={**os.environ, "PATH": f"{Path.home()}/.local/bin:{os.environ.get('PATH','')}"})
    return home


def write_profile_env(home: Path, *, user: str, token: str) -> None:
    env_path = home / ".env"
    keys: dict[str, str] = {}
    if env_path.exists():
        for line in env_path.read_text().splitlines():
            if not line.strip() or line.strip().startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            keys[k.strip()] = v
    # Prefer workspace/.env Azure key if profile has none yet
    if not keys.get("AZURE_OPENAI_KEY") and not keys.get("OPENAI_API_KEY"):
        ws_env = REPO_ROOT / ".env"
        if ws_env.exists():
            for line in ws_env.read_text().splitlines():
                if line.startswith("AZURE_OPENAI_KEY=") or line.startswith("DEEPSQL_CHAT_API_KEY="):
                    keys["AZURE_OPENAI_KEY"] = line.split("=", 1)[1]
                    keys["OPENAI_API_KEY"] = keys["AZURE_OPENAI_KEY"]
    keys["DEEPSQL_API_BASE_URL"] = API_BASE
    keys["DEEPSQL_AUTH_TOKEN"] = token or ""
    keys["DEEPSQL_MCP_USER_ID"] = user
    keys["DEEPSQL_MCP_PROJECT_ID"] = "deepsql-agent"
    env_path.write_text("\n".join(f"{k}={v}" for k, v in keys.items()) + "\n")
    os.chmod(env_path, 0o600)


def write_profile_mcp(home: Path, *, user: str, token: str) -> None:
    import yaml  # agent venv / system PyYAML

    cfg_path = home / "config.yaml"
    cfg = yaml.safe_load(cfg_path.read_text()) if cfg_path.exists() else {}
    cfg = cfg or {}
    # Token must live on the MCP subprocess env — the agent runtime does not auto-forward
    # the profile .env into mcp_servers.*.env.
    cfg.setdefault("mcp_servers", {})["deepsql"] = {
        "command": "node",
        "args": [str(REPO_ROOT / "mcp" / "deepsql-phase1-server.js")],
        "env": {
            "DEEPSQL_API_BASE_URL": API_BASE,
            "DEEPSQL_MCP_USER_ID": user,
            "DEEPSQL_MCP_PROJECT_ID": "deepsql-agent",
            "DEEPSQL_AUTH_TOKEN": token or "",
        },
    }
    cfg.setdefault("skills", {})["external_dirs"] = [str(REPO_ROOT / "agent" / "skills")]
    cfg.setdefault("approvals", {})["mode"] = "smart"
    cfg_path.write_text(yaml.safe_dump(cfg, sort_keys=False))
    soul_src = REPO_ROOT / "agent" / "SOUL.md"
    if soul_src.exists():
        (home / "SOUL.md").write_text(soul_src.read_text())


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write(f"[agent-provisioner] {self.address_string()} - {fmt % args}\n")

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        return json.loads(raw.decode("utf-8") or "{}")

    def _send(self, code: int, body: dict):
        data = json.dumps(body).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path in ("/health", "/"):
            return self._send(200, {"ok": True, "service": "deepsql-local-agent-provisioner"})
        return self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path.rstrip("/") != "/provision":
            return self._send(404, {"error": "not found"})
        if not SECRET:
            return self._send(500, {"error": "AGENT_PROVISION_SECRET unset"})
        if self.headers.get("X-Provision-Secret") != SECRET:
            return self._send(401, {"error": "unauthorized"})
        try:
            body = self._read_json()
        except Exception:
            return self._send(400, {"error": "invalid json"})
        user = str(body.get("user") or "").strip()
        token = str(body.get("token") or "")
        if not user:
            return self._send(400, {"error": "user required"})
        profile = profile_for(user)
        try:
            home = ensure_profile(profile)
            write_profile_mcp(home, user=user, token=token)
            write_profile_env(home, user=user, token=token)
        except Exception as e:
            return self._send(500, {"error": str(e)})
        return self._send(200, {"ok": True, "profile": profile, "home": str(home)})


def main():
    if not SECRET:
        print("AGENT_PROVISION_SECRET is required", file=sys.stderr)
        sys.exit(1)
    # Prefer agent venv PyYAML
    venv_site = HERMES_HOME / "hermes-agent" / "venv" / "lib"
    if venv_site.exists():
        for p in venv_site.glob("python*/site-packages"):
            sys.path.insert(0, str(p))
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"[agent-provisioner] listening on http://{HOST}:{PORT}/provision", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
