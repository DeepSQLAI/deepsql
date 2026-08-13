#!/usr/bin/env python3
"""Local DeepSQL Agent profile provisioner (native/dev stand-in for the agent container).

Production Compose runs an agent-side secret-gated provisioner on :8788 that
AgentBridgeService POSTs to (see agent.provisioner-url). That binary is not in
this OSS checkout. For Cursor Cloud / native local dev, this script provides the
same contract so /api/agent/session can create `u-<user>` agent profiles with
MCP credentials before the Agent tab opens.

Contract (matches AgentBridgeService.callProvisioner / revokeAgentTokens):
  POST /provision
  Header: X-Provision-Secret: <AGENT_PROVISION_SECRET>
  Body:   { "user": "<username>", "token": "<mcp-or-jwt>", "connectionId": "<uuid>" }

  POST /revoke
  Header: X-Provision-Secret: <AGENT_PROVISION_SECRET>
  Body:   { "user": "<username>" }
  Clears the on-disk token file and blanks DEEPSQL_AUTH_TOKEN in both the MCP
  server env and the profile .env — called after a DB-side token revoke so no
  stale plaintext credential keeps the agent working post-logout.

Idempotent: creates the profile on first call (cloning default), then refreshes
DEEPSQL_TOKEN_FILE / DEEPSQL_AUTH_TOKEN / DEEPSQL_API_BASE_URL /
DEEPSQL_MCP_USER_ID in the profile .env and MCP server env.
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


def token_file_for(home: Path) -> Path:
    return home / "deepsql.token"


def write_token_file(home: Path, token: str) -> Path:
    """Write the MCP token atomically (temp file + rename) so the long-lived
    MCP subprocess (which re-reads this file on every request, see
    deepsql-phase1-lib.js readTokenFile) never observes a partially-written
    token mid-rotation. 0600 — same secrecy bar as the profile .env."""
    path = token_file_for(home)
    tmp = path.with_suffix(f".tmp-{os.getpid()}")
    tmp.write_text((token or "") + "\n")
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)
    return path


def ensure_profile(name: str) -> Path:
    home = HERMES_HOME / "profiles" / name
    if home.exists():
        return home
    cmd = [HERMES_BIN, "profile", "create", name, "--clone", "--no-alias",
           "--description", f"DeepSQL Agent profile for {name}"]
    subprocess.run(cmd, check=True, env={**os.environ, "PATH": f"{Path.home()}/.local/bin:{os.environ.get('PATH','')}"})
    return home


def write_profile_env(home: Path, *, user: str, token: str, token_file: Path) -> None:
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
    # DEEPSQL_TOKEN_FILE is read live (mtime-checked) by the MCP subprocess, so
    # a rotated token takes effect without restarting Hermes. DEEPSQL_AUTH_TOKEN
    # stays as a fallback for any consumer that only reads the env snapshot.
    keys["DEEPSQL_TOKEN_FILE"] = str(token_file)
    keys["DEEPSQL_AUTH_TOKEN"] = token or ""
    keys["DEEPSQL_MCP_USER_ID"] = user
    keys["DEEPSQL_MCP_PROJECT_ID"] = "deepsql-agent"
    env_path.write_text("\n".join(f"{k}={v}" for k, v in keys.items()) + "\n")
    os.chmod(env_path, 0o600)


def _load_profile_config(home: Path):
    """Load profile config.yaml, recovering from a corrupt file by cloning the default.

    A prior dump race (or a partial write) can leave personas/model keys mangled so
    PyYAML refuses to parse. Without recovery, every subsequent /provision 500s and
    the agent profile never gets a fresh MCP token — which surfaces to CLI users as
    empty agent turns, not as a clear provisioner error.
    """
    import yaml  # agent venv / system PyYAML

    cfg_path = home / "config.yaml"
    default_path = HERMES_HOME / "config.yaml"
    if cfg_path.exists():
        try:
            cfg = yaml.safe_load(cfg_path.read_text())
            if isinstance(cfg, dict) and cfg.get("model"):
                return cfg
        except Exception as e:
            sys.stderr.write(f"[agent-provisioner] corrupt {cfg_path}: {e}; restoring from default\n")
    if default_path.exists():
        cfg = yaml.safe_load(default_path.read_text()) or {}
    else:
        cfg = {}
    return cfg if isinstance(cfg, dict) else {}


def write_profile_mcp(home: Path, *, user: str, token: str, token_file: Path) -> None:
    import yaml  # agent venv / system PyYAML

    cfg_path = home / "config.yaml"
    cfg = _load_profile_config(home)
    # Token must live on the MCP subprocess env — the agent runtime does not auto-forward
    # the profile .env into mcp_servers.*.env. DEEPSQL_TOKEN_FILE lets the MCP
    # process pick up a rotated token live (mtime-checked re-read) without
    # Hermes respawning the subprocess; DEEPSQL_AUTH_TOKEN is kept as a
    # fallback for the env-snapshot path.
    cfg.setdefault("mcp_servers", {})["deepsql"] = {
        "command": "node",
        "args": [str(REPO_ROOT / "mcp" / "deepsql-phase1-server.js")],
        "env": {
            "DEEPSQL_API_BASE_URL": API_BASE,
            "DEEPSQL_MCP_USER_ID": user,
            "DEEPSQL_MCP_PROJECT_ID": "deepsql-agent",
            "DEEPSQL_TOKEN_FILE": str(token_file),
            "DEEPSQL_AUTH_TOKEN": token or "",
        },
    }
    cfg.setdefault("skills", {})["external_dirs"] = [str(REPO_ROOT / "agent" / "skills")]
    cfg.setdefault("approvals", {})["mode"] = "smart"
    dumped = yaml.safe_dump(cfg, sort_keys=False, allow_unicode=True)
    # Refuse to write unparseable YAML — better a loud 500 than a silent corrupt profile.
    yaml.safe_load(dumped)
    cfg_path.write_text(dumped)
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
            return self._send(200, {"ok": True, "service": "deepsql-agent-provisioner"})
        return self._send(404, {"error": "not found"})

    def do_POST(self):
        path = self.path.rstrip("/")
        if path == "/provision":
            return self._handle_provision()
        if path == "/revoke":
            return self._handle_revoke()
        return self._send(404, {"error": "not found"})

    def _handle_provision(self):
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
            token_file = write_token_file(home, token)
            write_profile_mcp(home, user=user, token=token, token_file=token_file)
            write_profile_env(home, user=user, token=token, token_file=token_file)
        except Exception as e:
            return self._send(500, {"error": str(e)})
        return self._send(200, {"ok": True, "profile": profile, "home": str(home)})

    def _handle_revoke(self):
        """Best-effort disk cleanup on logout: blank the token file and the
        DEEPSQL_AUTH_TOKEN fallback in both the MCP env and the profile .env
        so a revoked DB token doesn't keep working via a stale plaintext copy
        on disk. Does not delete the profile itself — just its credential."""
        if not SECRET:
            return self._send(500, {"error": "AGENT_PROVISION_SECRET unset"})
        if self.headers.get("X-Provision-Secret") != SECRET:
            return self._send(401, {"error": "unauthorized"})
        try:
            body = self._read_json()
        except Exception:
            return self._send(400, {"error": "invalid json"})
        user = str(body.get("user") or "").strip()
        if not user:
            return self._send(400, {"error": "user required"})
        profile = profile_for(user)
        home = HERMES_HOME / "profiles" / profile
        if not home.exists():
            return self._send(200, {"ok": True, "profile": profile, "note": "no profile on disk"})
        try:
            write_token_file(home, "")
            write_profile_mcp(home, user=user, token="", token_file=token_file_for(home))
            write_profile_env(home, user=user, token="", token_file=token_file_for(home))
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
