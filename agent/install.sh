#!/usr/bin/env bash
# Reproducibly install the DeepSQL Agent customization into an agent home (~/.hermes).
# Idempotent: safe to re-run. Source of truth is this repo's agent/ dir.
#
# Configures:
#   - model: from DEEPSQL_CHAT_* (or legacy AZURE_OPENAI_*) via OpenAI-compatible endpoint
#   - mcp_servers.deepsql: the repo's DeepSQL MCP server (read-only DBA tools)
#   - skills.external_dirs: this repo's agent/skills (source of truth)
#   - approvals.mode: smart
#   - SOUL.md: the DBA persona
#   - disables host-affecting toolsets (terminal/file/code/browser/computer_use)
#
# Secrets are read from the environment (or the repo .env), never committed:
#   DEEPSQL_CHAT_API_KEY, DEEPSQL_CHAT_ENDPOINT, DEEPSQL_CHAT_MODEL
#   (legacy fallback: AZURE_OPENAI_KEY, AZURE_OPENAI_ENDPOINT)
#
# Upstream note: HERMES_HOME / hermes-agent / hermes CLI are contracts of the
# Nous Hermes Agent runtime this customization runs on — do not rename those.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Reject a nested profile home inherited from a live Hermes process.
if [[ "${HERMES_HOME:-}" == */profiles/* ]]; then
  unset HERMES_HOME
fi
HERMES_HOME="${DEEPSQL_HERMES_HOME:-${HERMES_HOME:-$HOME/.hermes}}"
AGENT_DIR="${HERMES_AGENT_DIR:-$HERMES_HOME/hermes-agent}"

# Prefer the same interpreter order as scripts/self-host/setup-agent.sh
if [[ -x "$AGENT_DIR/venv/bin/python" ]]; then
  VENV_PY="$AGENT_DIR/venv/bin/python"
elif [[ -x "$AGENT_DIR/.venv/bin/python" ]]; then
  VENV_PY="$AGENT_DIR/.venv/bin/python"
else
  echo "Agent venv not found under $AGENT_DIR — run scripts/self-host/setup-agent.sh first." >&2
  exit 1
fi

# Load repo .env for LLM creds if not already in the environment.
if [[ -f "$REPO_ROOT/.env" ]]; then set -a; . "$REPO_ROOT/.env"; set +a; fi

# Prefer the same BYO-LLM vars the Spring backend uses. Fall back to legacy
# AZURE_OPENAI_* for older checkouts.
API_KEY="${DEEPSQL_CHAT_API_KEY:-${AZURE_OPENAI_KEY:-}}"
ENDPOINT="${DEEPSQL_CHAT_ENDPOINT:-${AZURE_OPENAI_ENDPOINT:-}}"
MODEL="${DEEPSQL_CHAT_MODEL:-gpt-5.4}"

if [[ -z "$API_KEY" ]]; then
  echo "Error: set DEEPSQL_CHAT_API_KEY (or AZURE_OPENAI_KEY) in the environment or $REPO_ROOT/.env" >&2
  exit 1
fi
if [[ -z "$ENDPOINT" ]]; then
  echo "Error: set DEEPSQL_CHAT_ENDPOINT (or AZURE_OPENAI_ENDPOINT)." >&2
  exit 1
fi

# Normalize to an OpenAI-compatible …/openai/v1 or …/v1 base URL.
# Azure Cognitive Services / Azure OpenAI hosts need /openai/v1; plain OpenAI
# and OpenAI-compatible servers already expose /v1.
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

BACKEND_PORT="${DEEPSQL_BACKEND_PORT:-8080}"

echo "→ Repo:        $REPO_ROOT"
echo "→ Agent home:  $HERMES_HOME"
echo "→ Model:       $MODEL @ $BASE_URL"

REPO_ROOT="$REPO_ROOT" BASE_URL="$BASE_URL" API_KEY="$API_KEY" MODEL="$MODEL" \
BACKEND_PORT="$BACKEND_PORT" HERMES_HOME="$HERMES_HOME" "$VENV_PY" - <<'PY'
import os, yaml, pathlib
home = pathlib.Path(os.environ["HERMES_HOME"]); repo = os.environ["REPO_ROOT"]
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
# Keep an existing DEEPSQL_AUTH_TOKEN if a prior setup-agent run wrote one into
# the root config; otherwise leave token unset — setup-agent.sh provisions the
# per-user profile with a minted token.
existing_env = ((cfg.get("mcp_servers") or {}).get("deepsql") or {}).get("env") or {}
mcp_env = {
    "DEEPSQL_API_BASE_URL": f"http://localhost:{os.environ['BACKEND_PORT']}/api/",
    "DEEPSQL_MCP_USER_ID": existing_env.get("DEEPSQL_MCP_USER_ID", "deepsql-agent"),
    "DEEPSQL_MCP_PROJECT_ID": existing_env.get("DEEPSQL_MCP_PROJECT_ID", "deepsql-agent"),
}
if existing_env.get("DEEPSQL_AUTH_TOKEN"):
    mcp_env["DEEPSQL_AUTH_TOKEN"] = existing_env["DEEPSQL_AUTH_TOKEN"]
cfg.setdefault("mcp_servers", {})["deepsql"] = {
    "command": "node",
    "args": [f"{repo}/mcp/deepsql-phase1-server.js"],
    "env": mcp_env,
}
cfg.setdefault("skills", {})["external_dirs"] = [f"{repo}/agent/skills"]
cfg.setdefault("approvals", {})["mode"] = "smart"
cfg_path.write_text(yaml.safe_dump(cfg, sort_keys=False))
print(f"  config.yaml updated ({cfg_path})")
PY

# Persona
cp "$REPO_ROOT/agent/SOUL.md" "$HERMES_HOME/SOUL.md"
echo "  SOUL.md installed"

( cd "$AGENT_DIR" && UV_NO_CONFIG=1 "$VENV_PY" -m hermes_cli.main tools disable \
    terminal file code_execution browser computer_use image_gen tts vision web delegation cronjob \
    >/dev/null 2>&1 ) || echo "  (toolset disable skipped — disable manually with 'hermes tools disable ...')"
echo "  host toolsets disabled (read-only deepsql + memory/todo/skills remain)"

echo "✓ DeepSQL Agent customization installed."
echo "  Verify: (cd $AGENT_DIR && uv run hermes mcp test deepsql)"
echo "  Or run: scripts/self-host/setup-agent.sh (starts webui + provisions MCP token)"
