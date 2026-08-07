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

ensure_mcp_sdk() {
  local py
  py="$(resolve_venv_python)"
  if "$py" -c "from tools.mcp_tool import _MCP_AVAILABLE; import sys; sys.exit(0 if _MCP_AVAILABLE else 1)" \
      2>/dev/null; then
    echo "✓ Python MCP SDK available ($py)"
    return 0
  fi
  echo "→ Installing Python MCP SDK into $py"
  if command -v uv >/dev/null 2>&1; then
    UV_NO_CONFIG=1 uv pip install --python "$py" 'mcp>=1.0'
  else
    "$py" -m pip install 'mcp>=1.0'
  fi
  "$py" -c "from tools.mcp_tool import _MCP_AVAILABLE; import sys; sys.exit(0 if _MCP_AVAILABLE else 1)" || {
    echo "Error: MCP SDK still unavailable after install. Agent tools will not load." >&2
    exit 1
  }
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
    echo "✓ Hermes webui already running (pid $(cat "$PID_FILE"))"
    return 0
  fi

  # Free a stale listener on the port if our pid file is gone.
  if lsof -iTCP:"$WEBUI_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "→ Port $WEBUI_PORT already in use; assuming an existing webui and skipping start."
    return 0
  fi

  local certifi
  certifi="$("$py" -c 'import certifi; print(certifi.where())' 2>/dev/null || true)"

  echo "→ Starting Hermes webui on ${WEBUI_HOST}:${WEBUI_PORT}"
  (
    export HERMES_HOME
    export HERMES_WEBUI_HOST="$WEBUI_HOST"
    export HERMES_WEBUI_PORT="$WEBUI_PORT"
    unset HERMES_WEBUI_PASSWORD
    if [[ -n "$certifi" ]]; then
      export SSL_CERT_FILE="$certifi"
      export REQUESTS_CA_BUNDLE="$certifi"
      export CURL_CA_BUNDLE="$certifi"
    fi
    cd "$WEBUI_DIR"
    # Prefer venv/python for the process itself (has MCP when installed there).
    nohup "$py" server.py >>"$LOG_FILE" 2>&1 &
    echo $! >"$PID_FILE"
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
