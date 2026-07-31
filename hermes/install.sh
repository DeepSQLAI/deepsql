#!/usr/bin/env bash
# Reproducibly install the DBA customization into an agent home (~/.hermes).
# Idempotent: safe to re-run. Source of truth is this repo's hermes/ dir.
#
# Configures:
#   - model: existing Azure OpenAI gpt-5.4 via its OpenAI-compatible v1 endpoint
#   - mcp_servers.deepsql: the repo's DeepSQL MCP server (read-only DBA tools)
#   - skills.external_dirs: this repo's hermes/skills (source of truth)
#   - approvals.mode: smart
#   - SOUL.md: the DBA persona
#   - disables host-affecting toolsets (terminal/file/code/browser/computer_use)
#
# Secrets are read from the environment (or the repo .env), never committed:
#   AZURE_OPENAI_KEY, AZURE_OPENAI_ENDPOINT  (endpoint defaults to the repo value)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
AGENT_DIR="${HERMES_AGENT_DIR:-$HERMES_HOME/hermes-agent}"
VENV_PY="$AGENT_DIR/.venv/bin/python"

# Load repo .env for Azure creds if not already in the environment.
if [[ -f "$REPO_ROOT/.env" ]]; then set -a; . "$REPO_ROOT/.env"; set +a; fi
: "${AZURE_OPENAI_KEY:?Set AZURE_OPENAI_KEY (or add it to repo .env)}"
AZURE_ENDPOINT_HOST="$(printf '%s' "${AZURE_OPENAI_ENDPOINT:-https://your-resource.cognitiveservices.azure.com/}" | sed -E 's#https?://##; s#/.*##; s#\.cognitiveservices\.azure\.com#.openai.azure.com#')"
BASE_URL="https://${AZURE_ENDPOINT_HOST}/openai/v1"

[[ -x "$VENV_PY" ]] || { echo "Agent venv not found at $VENV_PY — install the agent runtime first."; exit 1; }

echo "→ Repo:        $REPO_ROOT"
echo "→ Agent home:  $HERMES_HOME"
echo "→ Model base:  $BASE_URL"

# Deep-merge the DBA config blocks into ~/.hermes/config.yaml (PyYAML ships with the agent).
REPO_ROOT="$REPO_ROOT" BASE_URL="$BASE_URL" AZURE_OPENAI_KEY="$AZURE_OPENAI_KEY" \
HERMES_HOME="$HERMES_HOME" "$VENV_PY" - <<'PY'
import os, yaml, pathlib
home = pathlib.Path(os.environ["HERMES_HOME"]); repo = os.environ["REPO_ROOT"]
cfg_path = home / "config.yaml"
cfg = yaml.safe_load(cfg_path.read_text()) if cfg_path.exists() else {}
cfg = cfg or {}
cfg["model"] = {
    "default": "gpt-5.4", "provider": "custom",
    "base_url": os.environ["BASE_URL"], "api_key": os.environ["AZURE_OPENAI_KEY"],
    "api_mode": "chat_completions", "context_length": 272000,
}
cfg.setdefault("mcp_servers", {})["deepsql"] = {
    "command": "node",
    "args": [f"{repo}/mcp/deepsql-phase1-server.js"],
    "env": {"DEEPSQL_API_BASE_URL": "http://localhost:8080/api/",
            "DEEPSQL_MCP_USER_ID": "deepsql-agent", "DEEPSQL_MCP_PROJECT_ID": "deepsql-agent"},
}
cfg.setdefault("skills", {})["external_dirs"] = [f"{repo}/hermes/skills"]
cfg.setdefault("approvals", {})["mode"] = "smart"
cfg_path.write_text(yaml.safe_dump(cfg, sort_keys=False))
print(f"  config.yaml updated ({cfg_path})")
PY

# Persona
cp "$REPO_ROOT/hermes/SOUL.md" "$HERMES_HOME/SOUL.md"
echo "  SOUL.md installed"

# Scope to a read-only sandbox: disable host-affecting toolsets.
( cd "$AGENT_DIR" && UV_NO_CONFIG=1 "$VENV_PY" -m hermes_cli.main tools disable \
    terminal file code_execution browser computer_use image_gen tts vision web delegation cronjob \
    >/dev/null 2>&1 ) || echo "  (toolset disable skipped — disable manually with 'hermes tools disable ...')"
echo "  host toolsets disabled (read-only deepsql + memory/todo/skills remain)"

echo "✓ DBA customization installed. Verify: (cd $AGENT_DIR && uv run hermes mcp test deepsql)"
