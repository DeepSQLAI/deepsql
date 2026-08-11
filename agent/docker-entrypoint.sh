#!/usr/bin/env bash
# DeepSQL Agent container entrypoint.
#
# Starts:
#   1. The agent HTTP API on :8787  (Agent tab, dashboards, Slack/CLI)
#   2. The profile provisioner on :8788 (backend POSTs here on Agent-tab open)
#
# Configuration comes from environment variables set by docker-compose /
# Kubernetes. Operator-facing names are DEEPSQL_* and AGENT_*; internal
# runtime paths are set here and never need to appear in docs.
set -euo pipefail

AGENT_ROOT="${DEEPSQL_AGENT_ROOT:-/opt/deepsql-agent}"
AGENT_HOME="${DEEPSQL_AGENT_HOME:-/var/lib/deepsql-agent}"
RUNTIME_DIR="${AGENT_ROOT}/runtime"
API_DIR="${AGENT_ROOT}/api"
VENV_PY="${RUNTIME_DIR}/venv/bin/python"

API_PORT="${DEEPSQL_AGENT_API_PORT:-8787}"
API_HOST="${DEEPSQL_AGENT_API_HOST:-0.0.0.0}"
PROVISIONER_PORT="${DEEPSQL_AGENT_PROVISIONER_PORT:-8788}"
PROVISIONER_HOST="${DEEPSQL_AGENT_PROVISIONER_HOST:-0.0.0.0}"

# Backend on the compose network. Overridable for non-compose deployments.
DEEPSQL_API_BASE_URL="${DEEPSQL_API_BASE_URL:-http://backend:8080/api/}"
# Ensure trailing slash.
[[ "${DEEPSQL_API_BASE_URL}" == */ ]] || DEEPSQL_API_BASE_URL="${DEEPSQL_API_BASE_URL}/"

log() { printf '[deepsql-agent] %s\n' "$*"; }

# ── Validate required config ────────────────────────────────────────────────
API_KEY="${DEEPSQL_CHAT_API_KEY:-${AZURE_OPENAI_KEY:-}}"
ENDPOINT="${DEEPSQL_CHAT_ENDPOINT:-${AZURE_OPENAI_ENDPOINT:-}}"
MODEL="${DEEPSQL_CHAT_MODEL:-gpt-5.4}"

if [[ -z "$API_KEY" ]]; then
  log "ERROR: DEEPSQL_CHAT_API_KEY (or AZURE_OPENAI_KEY) must be set."
  exit 1
fi
if [[ -z "$ENDPOINT" ]]; then
  log "ERROR: DEEPSQL_CHAT_ENDPOINT (or AZURE_OPENAI_ENDPOINT) must be set."
  exit 1
fi
if [[ -z "${AGENT_PROVISION_SECRET:-}" ]]; then
  log "ERROR: AGENT_PROVISION_SECRET must be set (shared with the backend)."
  exit 1
fi

if [[ ! -x "$VENV_PY" ]]; then
  log "ERROR: agent runtime venv missing at $VENV_PY"
  exit 1
fi

# ── Normalize LLM endpoint to an OpenAI-compatible …/v1 base URL ────────────
normalize_base_url() {
  local ep="$1"
  ep="${ep%/}"
  if [[ "$ep" == *"/openai/v1" || "$ep" == *"/v1" ]]; then
    printf '%s' "$ep"
    return
  fi
  if [[ "$ep" == *".cognitiveservices.azure.com"* || "$ep" == *".openai.azure.com"* || "$ep" == *".azure-api.net"* ]]; then
    printf '%s/openai/v1' "$ep"
    return
  fi
  printf '%s/v1' "$ep"
}
BASE_URL="$(normalize_base_url "$ENDPOINT")"

# ── Prepare agent home (persistent volume) ──────────────────────────────────
mkdir -p "$AGENT_HOME/logs" "$AGENT_HOME/profiles" "$AGENT_HOME/webui"

# Map the product home onto the upstream runtime's expected home path.
# Operators never set this; the entrypoint owns it.
export HERMES_HOME="$AGENT_HOME"
export HERMES_AGENT_DIR="$RUNTIME_DIR"
# Some upstream paths hard-code $HERMES_HOME/hermes-agent — keep a symlink.
ln -sfn "$RUNTIME_DIR" "$AGENT_HOME/hermes-agent"
ln -sfn "$API_DIR" "$AGENT_HOME/hermes-webui"
# No password — DeepSQL's nginx /agent-api gate already requires a session.
unset HERMES_WEBUI_PASSWORD || true
export HERMES_WEBUI_HOST="$API_HOST"
export HERMES_WEBUI_PORT="$API_PORT"
# Allow the frontend Origin through CSRF checks.
export HERMES_WEBUI_ALLOWED_ORIGINS="${DEEPSQL_AGENT_ALLOWED_ORIGINS:-${HERMES_WEBUI_ALLOWED_ORIGINS:-http://localhost:3000,http://127.0.0.1:3000,http://frontend}}"

# Trust DeepSQL nginx as an auth gateway (X-Remote-User header).
export HERMES_WEBUI_TRUSTED_AUTH_HEADER="${DEEPSQL_AGENT_TRUSTED_AUTH_HEADER:-${HERMES_WEBUI_TRUSTED_AUTH_HEADER:-X-Remote-User}}"

log "home=$AGENT_HOME"
log "model=$MODEL @ $BASE_URL"
log "backend=$DEEPSQL_API_BASE_URL"
log "api=${API_HOST}:${API_PORT}  provisioner=${PROVISIONER_HOST}:${PROVISIONER_PORT}"

# ── Write / refresh config.yaml ─────────────────────────────────────────────
AGENT_ROOT="$AGENT_ROOT" AGENT_HOME="$AGENT_HOME" \
BASE_URL="$BASE_URL" API_KEY="$API_KEY" MODEL="$MODEL" \
DEEPSQL_API_BASE_URL="$DEEPSQL_API_BASE_URL" \
"$VENV_PY" - <<'PY'
import os, pathlib, yaml

home = pathlib.Path(os.environ["AGENT_HOME"])
root = pathlib.Path(os.environ["AGENT_ROOT"])
cfg_path = home / "config.yaml"
cfg = yaml.safe_load(cfg_path.read_text()) if cfg_path.exists() else {}
cfg = cfg or {}

cfg["model"] = {
    "default": os.environ["MODEL"],
    "provider": "custom",
    "base_url": os.environ["BASE_URL"],
    "api_key": os.environ["API_KEY"],
    "api_mode": "chat_completions",
    "context_length": 272000,
}
cfg.setdefault("providers", {})["custom"] = {
    "base_url": os.environ["BASE_URL"],
    "api_key": os.environ["API_KEY"],
}

# Strip any dashboard password — DeepSQL authenticates at nginx.
dashboard = cfg.setdefault("dashboard", {})
dashboard.pop("password_hash", None)
dashboard.pop("password", None)

existing_env = ((cfg.get("mcp_servers") or {}).get("deepsql") or {}).get("env") or {}
mcp_env = {
    "DEEPSQL_API_BASE_URL": os.environ["DEEPSQL_API_BASE_URL"],
    "DEEPSQL_MCP_USER_ID": existing_env.get("DEEPSQL_MCP_USER_ID", "deepsql-agent"),
    "DEEPSQL_MCP_PROJECT_ID": existing_env.get("DEEPSQL_MCP_PROJECT_ID", "deepsql-agent"),
}
if existing_env.get("DEEPSQL_AUTH_TOKEN"):
    mcp_env["DEEPSQL_AUTH_TOKEN"] = existing_env["DEEPSQL_AUTH_TOKEN"]

cfg.setdefault("mcp_servers", {})["deepsql"] = {
    "command": "node",
    "args": [str(root / "mcp" / "deepsql-phase1-server.js")],
    "env": mcp_env,
}
cfg.setdefault("skills", {})["external_dirs"] = [str(root / "skills")]
cfg.setdefault("approvals", {})["mode"] = "smart"
cfg_path.write_text(yaml.safe_dump(cfg, sort_keys=False))
print(f"[deepsql-agent] wrote {cfg_path}")
PY

# Persona
cp -f "$AGENT_ROOT/SOUL.md" "$AGENT_HOME/SOUL.md"

# Disable host-affecting toolsets (read-only DeepSQL sandbox). Best-effort —
# a missing CLI subcommand must not prevent boot.
(
  cd "$RUNTIME_DIR"
  UV_NO_CONFIG=1 "$VENV_PY" -m hermes_cli.main tools disable \
    terminal file code_execution browser computer_use image_gen tts vision web delegation cronjob \
    >/dev/null 2>&1 || true
)

# Re-apply branding overlay (idempotent; needed if the API tree was updated).
if [[ -x "$AGENT_ROOT/webui-overlay/apply-overlay.sh" ]]; then
  HERMES_HOME="$AGENT_HOME" bash "$AGENT_ROOT/webui-overlay/apply-overlay.sh" "$API_DIR" >/dev/null 2>&1 || true
fi

# ── Start provisioner (:8788) ───────────────────────────────────────────────
export AGENT_PROVISION_SECRET
export AGENT_PROVISIONER_HOST="$PROVISIONER_HOST"
export AGENT_PROVISIONER_PORT="$PROVISIONER_PORT"
export DEEPSQL_REPO_ROOT="$AGENT_ROOT"
# provisioner.py looks for mcp/ and agent/skills relative to repo root;
# map them onto the container layout.
export DEEPSQL_API_BASE_URL
# Point HERMES_BIN at the venv CLI if present.
export HERMES_BIN="${RUNTIME_DIR}/venv/bin/hermes"
export PATH="${RUNTIME_DIR}/venv/bin:${PATH}"

# Adapt paths the provisioner expects (REPO_ROOT/mcp, REPO_ROOT/agent/skills).
# Create a thin layout so scripts/local-agent-provisioner.py works unchanged.
mkdir -p "$AGENT_ROOT/agent"
ln -sfn "$AGENT_ROOT/skills" "$AGENT_ROOT/agent/skills"
ln -sfn "$AGENT_ROOT/SOUL.md" "$AGENT_ROOT/agent/SOUL.md"

log "starting profile provisioner on ${PROVISIONER_HOST}:${PROVISIONER_PORT}"
"$VENV_PY" "$AGENT_ROOT/provisioner.py" \
  >>"$AGENT_HOME/logs/provisioner.log" 2>&1 &
PROVISIONER_PID=$!

# ── Start agent API (:8787) ─────────────────────────────────────────────────
log "starting agent API on ${API_HOST}:${API_PORT}"
cd "$API_DIR"

# Prefer server.py (webui) — that is what the Agent tab / nginx expect.
# Fall back to `hermes serve` only if server.py is missing.
cleanup() {
  log "shutting down"
  kill "$PROVISIONER_PID" 2>/dev/null || true
  if [[ -n "${API_PID:-}" ]]; then
    kill "$API_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

if [[ -f "$API_DIR/server.py" ]]; then
  "$VENV_PY" server.py >>"$AGENT_HOME/logs/api.log" 2>&1 &
  API_PID=$!
else
  "$VENV_PY" -m hermes_cli.main serve \
    --host "$API_HOST" --port "$API_PORT" \
    >>"$AGENT_HOME/logs/api.log" 2>&1 &
  API_PID=$!
fi

# Wait for either process to exit (then restart policy handles the rest).
log "ready — API pid=$API_PID provisioner pid=$PROVISIONER_PID"
wait -n "$API_PID" "$PROVISIONER_PID"
EXIT_CODE=$?
log "a child exited with code $EXIT_CODE — stopping"
exit "$EXIT_CODE"
