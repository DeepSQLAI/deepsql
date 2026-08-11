#!/usr/bin/env bash
# Install and start the DeepSQL Agent (Hermes) for self-host.
#
# The four Compose services alone are NOT enough for the Agent tab or AI
# dashboards — those need a host Hermes webui on :8787 with:
#   - Python MCP SDK installed in the webui's interpreter
#   - DeepSQL MCP wired to localhost:8080 with a per-user MCP token
#   - Binding 0.0.0.0 (so Docker nginx/backend can reach it)
#   - No HERMES_WEBUI_PASSWORD (DeepSQL's /agent-api proxy has no Hermes password)
#
# Idempotent. Safe to re-run after `git pull` or credential rotation.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="${DEEPSQL_ENV_FILE:-$ROOT_DIR/.env}"
# Never inherit a nested profile home from a prior Hermes turn
# (e.g. HERMES_HOME=~/.hermes/profiles/u-admin) — that nests clones/config.
if [[ "${HERMES_HOME:-}" == */profiles/* ]]; then
  unset HERMES_HOME
fi
HERMES_HOME="${DEEPSQL_HERMES_HOME:-${HERMES_HOME:-$HOME/.hermes}}"
AGENT_DIR="${HERMES_AGENT_DIR:-$HERMES_HOME/hermes-agent}"
WEBUI_DIR="${HERMES_WEBUI_DIR:-$HERMES_HOME/hermes-webui}"
AGENT_REPO="${HERMES_AGENT_REPO:-https://github.com/NousResearch/hermes-agent.git}"
WEBUI_REPO="${HERMES_WEBUI_REPO:-https://github.com/nesquena/hermes-webui.git}"
WEBUI_PORT="${HERMES_WEBUI_PORT:-8787}"
WEBUI_HOST="${HERMES_WEBUI_HOST:-0.0.0.0}"
PID_FILE="${HERMES_HOME}/webui.pid"
LOG_FILE="${HERMES_HOME}/logs/webui.log"
BACKEND_PORT="${DEEPSQL_BACKEND_PORT:-8080}"
FRONTEND_PORT="${DEEPSQL_FRONTEND_PORT:-3000}"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Error: required command '$1' is not installed." >&2
    exit 1
  fi
}

resolve_venv_python() {
  # Prefer the canonical `venv` (webui's lookup order); fall back to `.venv`.
  if [[ -x "$AGENT_DIR/venv/bin/python" ]]; then
    echo "$AGENT_DIR/venv/bin/python"
  elif [[ -x "$AGENT_DIR/.venv/bin/python" ]]; then
    echo "$AGENT_DIR/.venv/bin/python"
  else
    return 1
  fi
}

ensure_clone() {
  local dir="$1" repo="$2" label="$3"
  if [[ -d "$dir/.git" ]]; then
    echo "✓ $label already present at $dir"
    return 0
  fi
  echo "→ Cloning $label into $dir"
  mkdir -p "$(dirname "$dir")"
  git clone --depth 1 "$repo" "$dir"
}

ensure_agent_venv() {
  if resolve_venv_python >/dev/null; then
    return 0
  fi
  echo "→ Creating Hermes agent venv"
  if command -v uv >/dev/null 2>&1; then
    ( cd "$AGENT_DIR" && UV_NO_CONFIG=1 uv sync )
  else
    python3 -m venv "$AGENT_DIR/venv"
    "$AGENT_DIR/venv/bin/pip" install -U pip
    if [[ -f "$AGENT_DIR/pyproject.toml" ]]; then
      "$AGENT_DIR/venv/bin/pip" install -e "$AGENT_DIR"
    fi
  fi
  resolve_venv_python >/dev/null || {
    echo "Error: could not create a Hermes agent venv under $AGENT_DIR" >&2
    exit 1
  }
}

# Importable is not the same as usable. This probe additionally asserts that
# CallToolResult still carries `isError`, because that is the field hermes-agent
# actually reads. A presence-only check reports "✓ SDK available" on an SDK 2.x
# install where every tool call fails at runtime — which is exactly how a broken
# agent shipped while setup-agent.sh printed a tick.
MCP_PROBE='
import sys
try:
    from tools.mcp_tool import _MCP_AVAILABLE
except Exception:
    sys.exit(1)
if not _MCP_AVAILABLE:
    sys.exit(1)
try:
    from mcp.types import CallToolResult
except Exception:
    sys.exit(2)
sys.exit(0 if "isError" in getattr(CallToolResult, "model_fields", {}) else 2)
'

ensure_mcp_sdk() {
  local py rc
  py="$(resolve_venv_python)"
  # `|| rc=$?` rather than `if`: under set -e a bare call would abort the script,
  # and rc 2 (incompatible) must be told apart from rc 1 (absent).
  rc=0
  "$py" -c "$MCP_PROBE" 2>/dev/null || rc=$?
  if [[ $rc -eq 0 ]]; then
    echo "✓ Python MCP SDK available ($py)"
    return 0
  fi
  if [[ $rc -eq 2 ]]; then
    echo "→ Installed MCP SDK is incompatible with hermes-agent (CallToolResult has no"
    echo "  'isError' — SDK 2.x renamed it to 'is_error'). Reinstalling a 1.x SDK."
  fi
  # Read by start_webui: a running webui holds the old SDK in memory and must be
  # restarted, or this reinstall has no effect on the agent at all.
  MCP_SDK_REINSTALLED=1
  echo "→ Installing Python MCP SDK into $py"
  # The upper bound is load-bearing, not caution. MCP SDK 2.0.0 renamed
  # CallToolResult.isError -> .is_error (mcp_types/_types.py:1480) and split the
  # models out into a separate mcp-types package. hermes-agent 0.20.0 still reads
  # result.isError (tools/mcp_tool.py:5222), so an unpinned 'mcp>=1.0' silently
  # began resolving to 2.0.0 the day it was published, and every DeepSQL tool call
  # started failing with
  #   AttributeError: 'CallToolResult' object has no attribute 'isError'
  # Three of those trip the client's circuit breaker, after which the remaining
  # tools are refused with "MCP server 'deepsql' is unreachable" — blaming a healthy
  # server for a client-side parse failure. In the UI it surfaces only as
  # "The agent run ended early."
  #
  # Raise this ceiling when hermes-agent reads .is_error (or accepts both), not before.
  if command -v uv >/dev/null 2>&1; then
    UV_NO_CONFIG=1 uv pip install --python "$py" 'mcp>=1.0,<2'
  else
    "$py" -m pip install 'mcp>=1.0,<2'
  fi
  rc=0
  "$py" -c "$MCP_PROBE" 2>/dev/null || rc=$?
  if [[ $rc -eq 2 ]]; then
    echo "Error: the installed MCP SDK is still incompatible with hermes-agent" >&2
    echo "       (CallToolResult has no 'isError'). Every agent tool call would fail" >&2
    echo "       with AttributeError and the UI would show only 'The agent run ended" >&2
    echo "       early.' Check that '$py' resolved mcp<2." >&2
    exit 1
  fi
  if [[ $rc -ne 0 ]]; then
    echo "Error: MCP SDK still unavailable after install. Agent tools will not load." >&2
    exit 1
  fi
  echo "✓ Python MCP SDK installed and compatible ($py)"
}

wait_for_http() {
  local url="$1" label="$2" retries="${3:-60}" delay="${4:-2}"
  for ((i=1; i<=retries; i++)); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      echo "✓ $label is healthy: $url"
      return 0
    fi
    sleep "$delay"
  done
  echo "Error: timed out waiting for $label at $url" >&2
  return 1
}

provision_user_profile() {
  # Mint an MCP token for the admin and write ~/.hermes/profiles/u-<user>/
  # so dashboard generation + Agent tab can call DeepSQL MCP as that user.
  # Replaces the missing compose provisioner (deepsql-agent:8788).
  if [[ ! -f "$ENV_FILE" ]]; then
    echo "Warning: no $ENV_FILE — skipping per-user profile provisioning." >&2
    return 0
  fi
  # shellcheck disable=SC1090
  set -a; source "$ENV_FILE"; set +a
  local email="${DEEPSQL_INITIAL_ADMIN_EMAIL:-}"
  local password="${DEEPSQL_INITIAL_ADMIN_PASSWORD:-}"
  if [[ -z "$email" || -z "$password" ]]; then
    echo "Warning: admin email/password unset — skipping profile provisioning."
    echo "         After login, re-run this script to write the MCP token into Hermes."
    return 0
  fi

  local cookie jar base login_json me_json username profile token_json token
  jar="$(mktemp)"
  trap 'rm -f "$jar"' RETURN
  base="http://127.0.0.1:${BACKEND_PORT}/api"

  if ! wait_for_http "$base/actuator/health" "Backend" 30 2; then
    echo "Warning: backend not up — skipping profile provisioning." >&2
    return 0
  fi

  login_json="$(curl -fsS -c "$jar" -H 'Content-Type: application/json' \
    -X POST "$base/auth/login" \
    -d "{\"email\":\"${email}\",\"password\":\"${password}\"}" || true)"
  if [[ "$login_json" != *"\"email\""* && "$login_json" != *"\"username\""* ]]; then
    echo "Warning: admin login failed — skipping profile provisioning." >&2
    return 0
  fi

  me_json="$(curl -fsS -b "$jar" "$base/auth/me")"
  username="$(printf '%s' "$me_json" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("username") or d.get("name") or "")' 2>/dev/null || true)"
  if [[ -z "$username" ]]; then
    username="$(printf '%s' "$email" | cut -d@ -f1)"
  fi
  profile="u-$(printf '%s' "$username" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//')"

  token_json="$(curl -fsS -b "$jar" -H 'Content-Type: application/json' \
    -X POST "$base/auth/mcp-tokens" \
    -d '{"name":"self-host-agent"}')"
  token="$(printf '%s' "$token_json" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("token") or "")')"
  if [[ -z "$token" ]]; then
    echo "Warning: could not mint MCP token — Agent MCP calls will fail until one is configured." >&2
    return 0
  fi

  local profile_home="$HERMES_HOME/profiles/$profile"
  mkdir -p "$profile_home"
  # Copy root DBA config into the profile, then inject the MCP token.
  if [[ -f "$HERMES_HOME/config.yaml" ]]; then
    cp "$HERMES_HOME/config.yaml" "$profile_home/config.yaml"
  fi
  if [[ -f "$HERMES_HOME/SOUL.md" ]]; then
    cp "$HERMES_HOME/SOUL.md" "$profile_home/SOUL.md"
  fi

  local py
  py="$(resolve_venv_python)"
  HERMES_HOME="$HERMES_HOME" PROFILE="$profile" TOKEN="$token" REPO_ROOT="$ROOT_DIR" \
  BACKEND_PORT="$BACKEND_PORT" "$py" - <<'PY'
import os, pathlib, yaml
home = pathlib.Path(os.environ["HERMES_HOME"]) / "profiles" / os.environ["PROFILE"]
cfg_path = home / "config.yaml"
cfg = yaml.safe_load(cfg_path.read_text()) if cfg_path.exists() else {}
cfg = cfg or {}
repo = os.environ["REPO_ROOT"]
port = os.environ["BACKEND_PORT"]
token = os.environ["TOKEN"]
cfg.setdefault("mcp_servers", {})["deepsql"] = {
    "command": "node",
    "args": [f"{repo}/mcp/deepsql-phase1-server.js"],
    "env": {
        "DEEPSQL_API_BASE_URL": f"http://localhost:{port}/api/",
        "DEEPSQL_AUTH_TOKEN": token,
        "DEEPSQL_MCP_USER_ID": os.environ["PROFILE"],
        "DEEPSQL_MCP_PROJECT_ID": os.environ["PROFILE"],
    },
}
cfg.setdefault("skills", {})["external_dirs"] = [f"{repo}/agent/skills"]
cfg.setdefault("approvals", {})["mode"] = "smart"
cfg_path.write_text(yaml.safe_dump(cfg, sort_keys=False))
env_path = home / ".env"
env_path.write_text(
    f"DEEPSQL_API_BASE_URL=http://localhost:{port}/api/\n"
    f"DEEPSQL_AUTH_TOKEN={token}\n"
    f"DEEPSQL_MCP_USER_ID={os.environ['PROFILE']}\n"
    f"DEEPSQL_MCP_PROJECT_ID={os.environ['PROFILE']}\n"
)
env_path.chmod(0o600)
print(f"  profile {os.environ['PROFILE']} written ({cfg_path})")
PY
  echo "✓ Provisioned Hermes profile $profile with a fresh MCP token"
}

start_webui() {
  local py
  py="$(resolve_venv_python)"
  mkdir -p "$(dirname "$LOG_FILE")" "$HERMES_HOME/logs"

  if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    # A live process holds its interpreter's modules in memory, so it keeps using
    # the SDK that was installed when it started. Re-running this script to repair
    # a broken SDK otherwise reports "✓ already running" and changes nothing —
    # every tool call keeps failing exactly as before, under a full set of ticks.
    if [[ "${MCP_SDK_REINSTALLED:-0}" == "1" ]]; then
      echo "→ MCP SDK changed on disk; restarting Hermes webui (pid $(cat "$PID_FILE"))"
      kill "$(cat "$PID_FILE")" 2>/dev/null || true
      for _ in $(seq 1 20); do
        kill -0 "$(cat "$PID_FILE")" 2>/dev/null || break
        sleep 0.5
      done
      kill -9 "$(cat "$PID_FILE")" 2>/dev/null || true
      rm -f "$PID_FILE"
    else
      echo "✓ Hermes webui already running (pid $(cat "$PID_FILE"))"
      return 0
    fi
  fi

  # Free a stale listener on the port if our pid file is gone.
  if lsof -iTCP:"$WEBUI_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    if [[ "${MCP_SDK_REINSTALLED:-0}" == "1" ]]; then
      echo "Error: the MCP SDK was reinstalled but something else is listening on" >&2
      echo "       port $WEBUI_PORT and this script does not own it. Stop it and re-run," >&2
      echo "       or the agent will keep using the old SDK." >&2
      exit 1
    fi
    echo "→ Port $WEBUI_PORT already in use; assuming an existing webui and skipping start."
    return 0
  fi

  local certifi
  certifi="$("$py" -c 'import certifi; print(certifi.where())' 2>/dev/null || true)"

  # CORS for browser → nginx → /agent-api (must include the frontend origin:port).
  local allowed_origins="${HERMES_WEBUI_ALLOWED_ORIGINS:-http://localhost:${FRONTEND_PORT},http://127.0.0.1:${FRONTEND_PORT}}"

  echo "→ Starting Hermes webui on ${WEBUI_HOST}:${WEBUI_PORT}"

  # On macOS, prefer a LaunchAgent. Shell-spawned nohup children get SIGKILL'd when
  # some IDE/agent terminals tear down the process group — the Agent tab then
  # immediately reports "runtime unavailable".
  if [[ "$(uname -s)" == "Darwin" ]] && command -v launchctl >/dev/null 2>&1; then
    local label="com.deepsql.hermes-webui"
    local plist="$HOME/Library/LaunchAgents/${label}.plist"
    local uid
    uid="$(id -u)"
    mkdir -p "$HOME/Library/LaunchAgents"
    launchctl bootout "gui/${uid}/${label}" 2>/dev/null || true
    pkill -f "${WEBUI_DIR}/server.py" 2>/dev/null || true
    cat >"$plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${label}</string>
  <key>ProgramArguments</key>
  <array>
    <string>${py}</string>
    <string>${WEBUI_DIR}/server.py</string>
  </array>
  <key>WorkingDirectory</key><string>${WEBUI_DIR}</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>HERMES_HOME</key><string>${HERMES_HOME}</string>
    <key>HERMES_WEBUI_HOST</key><string>${WEBUI_HOST}</string>
    <key>HERMES_WEBUI_PORT</key><string>${WEBUI_PORT}</string>
    <key>HERMES_WEBUI_ALLOWED_ORIGINS</key><string>${allowed_origins}</string>
    <key>PATH</key><string>/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin</string>
$(if [[ -n "$certifi" ]]; then
  printf '    <key>SSL_CERT_FILE</key><string>%s</string>\n' "$certifi"
  printf '    <key>REQUESTS_CA_BUNDLE</key><string>%s</string>\n' "$certifi"
  printf '    <key>CURL_CA_BUNDLE</key><string>%s</string>\n' "$certifi"
fi)
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>${HERMES_HOME}/logs/webui.launchd.out.log</string>
  <key>StandardErrorPath</key><string>${HERMES_HOME}/logs/webui.launchd.err.log</string>
</dict>
</plist>
PLIST
    launchctl bootstrap "gui/${uid}" "$plist" 2>/dev/null \
      || launchctl load -w "$plist"
    wait_for_http "http://127.0.0.1:${WEBUI_PORT}/api/mcp/servers" "Hermes webui" 30 1
    return 0
  fi

  # Linux / non-launchd: detach from this script's process group.
  rm -f "$PID_FILE"
  (
    cd "$WEBUI_DIR" || exit 1
    export HERMES_HOME
    export HERMES_WEBUI_HOST="$WEBUI_HOST"
    export HERMES_WEBUI_PORT="$WEBUI_PORT"
    export HERMES_WEBUI_ALLOWED_ORIGINS="$allowed_origins"
    unset HERMES_WEBUI_PASSWORD
    if [[ -n "$certifi" ]]; then
      export SSL_CERT_FILE="$certifi"
      export REQUESTS_CA_BUNDLE="$certifi"
      export CURL_CA_BUNDLE="$certifi"
    fi
    if command -v setsid >/dev/null 2>&1; then
      setsid nohup "$py" server.py >>"$LOG_FILE" 2>&1 </dev/null &
    else
      nohup "$py" server.py >>"$LOG_FILE" 2>&1 </dev/null &
    fi
    echo $! >"$PID_FILE"
    disown $! 2>/dev/null || true
  )
  wait_for_http "http://127.0.0.1:${WEBUI_PORT}/api/mcp/servers" "Hermes webui" 30 1
}

# ── main ────────────────────────────────────────────────────────────────────
require_command git
require_command curl
require_command node
require_command python3

mkdir -p "$HERMES_HOME"
ensure_clone "$AGENT_DIR" "$AGENT_REPO" "hermes-agent"
ensure_clone "$WEBUI_DIR" "$WEBUI_REPO" "hermes-webui"
ensure_agent_venv
ensure_mcp_sdk

# DBA persona / model / MCP / skills into ~/.hermes
"$ROOT_DIR/agent/install.sh"
# DeepSQL skin on the webui (idempotent)
"$ROOT_DIR/agent/webui/apply-overlay.sh" "$WEBUI_DIR" || true

provision_user_profile
start_webui

echo
echo "DeepSQL Agent is ready."
echo "  Hermes webui:  http://127.0.0.1:${WEBUI_PORT}"
echo "  Frontend uses: http://localhost:${FRONTEND_PORT}/agent-api/ → webui"
echo "  Backend uses:  AGENT_WEBUI_URL=http://host.docker.internal:${WEBUI_PORT}"
echo "  Logs:          $LOG_FILE"
echo
echo "UI paths that need this process:"
echo "  • Agent tab (chat)"
echo "  • Dashboards → AI generate"
echo "  • Slack (when slack.brain=agent) / CLI deepsql agent"
