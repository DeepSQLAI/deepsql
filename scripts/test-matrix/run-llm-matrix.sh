#!/usr/bin/env bash
# Runs the engine acceptance matrix once per LLM provider, so provider becomes a
# second axis: engines x models.
#
# Kept separate from run-matrix.sh on purpose. Engines are per-connection and
# stateless; switching provider is global — it rewrites the backend's environment
# and restarts it. Mixing the two would make the engine runner stateful, and a
# failed run could leave the stack pointing somewhere unexpected. Here the backup
# and restore live in one place, on an EXIT trap.
#
#   MATRIX_OPENAI_KEY=... ./run-llm-matrix.sh --llm openai --engines pg18,my84
#   ./run-llm-matrix.sh --llm openai,litellm
#
# Keys are read from the environment and never appear in argv (where `ps` would
# show them), in a repo file, or in the printed output.
#
# Providers:
#   current    the stack's existing configuration; no reconfiguration
#   openai     api.openai.com       MATRIX_OPENAI_KEY
#   anthropic  api.anthropic.com    MATRIX_ANTHROPIC_KEY and MATRIX_OPENAI_KEY
#   litellm    a LiteLLM proxy      MATRIX_LITELLM_KEY, MATRIX_LITELLM_ENDPOINT,
#                                   MATRIX_LITELLM_CHAT_MODEL,
#                                   MATRIX_LITELLM_EMBED_MODEL
#
# Anthropic needs no gateway. It serves an OpenAI-compatible /v1/chat/completions
# with Bearer auth, so the shipped `openai` provider drives it on configuration
# alone. What it does not serve is embeddings, so those must come from somewhere
# else — the `anthropic` profile pairs Claude chat with OpenAI embeddings. That
# split is a supported configuration rather than a workaround, because chat and
# embeddings resolve as independent bundles.
#
# Running `anthropic` and `litellm` against the same Claude model is deliberate:
# the two differ only by the gateway, so a disagreement between them isolates the
# gateway as the cause.
#
# Name any gateway alias something unlike an OpenAI model. An alias beginning
# gpt-5, o1, o3, o4 or codex makes use-responses-api="auto" select /v1/responses,
# which a gateway fronting Anthropic will not serve.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="${DEEPSQL_ENV_FILE:-$ROOT_DIR/.env}"
COMPOSE_FILE="${DEEPSQL_COMPOSE_FILE:-$ROOT_DIR/docker-compose.yml}"
HEALTH_URL="${DEEPSQL_HEALTH_URL:-http://localhost:8080/api/actuator/health}"

LLM_PROFILES="current"
ENGINE_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --llm)     LLM_PROFILES="$2"; shift 2 ;;
    --engines) ENGINE_ARGS+=(--engines "$2"); shift 2 ;;
    -h|--help) sed -n '2,38p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)         echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Error: missing env file $ENV_FILE" >&2
  exit 1
fi

ENV_BACKUP=""
RESULTS=()

restart_backend() {
  docker compose -f "$COMPOSE_FILE" up -d --no-build backend >/dev/null 2>&1 || return 1
  local waited=0
  while (( waited < 180 )); do
    curl -sf -o /dev/null "$HEALTH_URL" 2>/dev/null && return 0
    sleep 5; waited=$((waited + 5))
  done
  echo "Error: backend did not become healthy within 180s." >&2
  return 1
}

restore_env() {
  # The stack must end how it started, whatever happened in between.
  if [[ -n "$ENV_BACKUP" && -f "$ENV_BACKUP" ]]; then
    echo ""
    echo "Restoring original LLM configuration ..."
    cp "$ENV_BACKUP" "$ENV_FILE"
    rm -f "$ENV_BACKUP"
    restart_backend || true
  fi
}
trap restore_env EXIT

set_env_var() {
  # Replace in place when present, append when not, so a profile can introduce a
  # variable the current .env has never carried.
  local key="$1" value="$2"
  if grep -q "^${key}=" "$ENV_FILE"; then
    local tmp; tmp="$(mktemp)"
    awk -v k="$key" -v v="$value" '
      { if (index($0, k "=") == 1) { print k "=" v } else { print } }
    ' "$ENV_FILE" > "$tmp" && mv "$tmp" "$ENV_FILE"
  else
    printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
  fi
}

require_var() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Error: $name is not set. Export it; do not pass keys as arguments." >&2
    return 1
  fi
}

apply_profile() {
  local profile="$1"
  case "$profile" in
    current)
      return 0 ;;
    openai)
      require_var MATRIX_OPENAI_KEY || return 1
      set_env_var DEEPSQL_CHAT_PROVIDER      openai
      set_env_var DEEPSQL_CHAT_ENDPOINT      "https://api.openai.com/v1"
      set_env_var DEEPSQL_CHAT_API_KEY       "$MATRIX_OPENAI_KEY"
      # gpt-5.4-nano is cheap and, because use-responses-api="auto" keys off the
      # gpt-5 prefix, it also exercises the Responses API rather than chat
      # completions. Override for the other path: MATRIX_OPENAI_CHAT_MODEL=gpt-4o.
      set_env_var DEEPSQL_CHAT_MODEL         "${MATRIX_OPENAI_CHAT_MODEL:-gpt-5.4-nano}"
      set_env_var DEEPSQL_EMBEDDING_PROVIDER openai
      set_env_var DEEPSQL_EMBEDDING_ENDPOINT "https://api.openai.com/v1"
      set_env_var DEEPSQL_EMBEDDING_API_KEY  "$MATRIX_OPENAI_KEY"
      set_env_var DEEPSQL_EMBEDDING_MODEL    "${MATRIX_OPENAI_EMBED_MODEL:-text-embedding-3-large}"
      ;;
    anthropic)
      # Anthropic serves an OpenAI-compatible /v1/chat/completions with Bearer auth,
      # so chat needs no gateway and no provider of its own. Embeddings are the
      # exception: Anthropic publishes none, so they must come from elsewhere, which
      # is why chat and embedding resolve as independent bundles.
      require_var MATRIX_ANTHROPIC_KEY || return 1
      require_var MATRIX_OPENAI_KEY || return 1
      set_env_var DEEPSQL_CHAT_PROVIDER      openai
      set_env_var DEEPSQL_CHAT_ENDPOINT      "https://api.anthropic.com/v1"
      set_env_var DEEPSQL_CHAT_API_KEY       "$MATRIX_ANTHROPIC_KEY"
      set_env_var DEEPSQL_CHAT_MODEL         "${MATRIX_ANTHROPIC_CHAT_MODEL:-claude-haiku-4-5-20251001}"
      # Claude model names match no gpt-5/o1/o3/o4/codex prefix, so "auto" already
      # resolves to chat completions; pinned anyway because /v1/responses does not
      # exist here and a silent switch would be a confusing failure.
      set_env_var DEEPSQL_CHAT_USE_RESPONSES_API false
      set_env_var DEEPSQL_EMBEDDING_PROVIDER openai
      set_env_var DEEPSQL_EMBEDDING_ENDPOINT "https://api.openai.com/v1"
      set_env_var DEEPSQL_EMBEDDING_API_KEY  "$MATRIX_OPENAI_KEY"
      set_env_var DEEPSQL_EMBEDDING_MODEL    "${MATRIX_OPENAI_EMBED_MODEL:-text-embedding-3-large}"
      ;;
    litellm)
      require_var MATRIX_LITELLM_KEY || return 1
      require_var MATRIX_LITELLM_ENDPOINT || return 1
      require_var MATRIX_LITELLM_CHAT_MODEL || return 1
      set_env_var DEEPSQL_CHAT_PROVIDER      openai
      set_env_var DEEPSQL_CHAT_ENDPOINT      "$MATRIX_LITELLM_ENDPOINT"
      set_env_var DEEPSQL_CHAT_API_KEY       "$MATRIX_LITELLM_KEY"
      set_env_var DEEPSQL_CHAT_MODEL         "$MATRIX_LITELLM_CHAT_MODEL"
      # Embeddings may deliberately point elsewhere — Anthropic has none, so a
      # Claude chat model is normally paired with another provider's embeddings.
      set_env_var DEEPSQL_EMBEDDING_PROVIDER openai
      set_env_var DEEPSQL_EMBEDDING_ENDPOINT "${MATRIX_LITELLM_EMBED_ENDPOINT:-$MATRIX_LITELLM_ENDPOINT}"
      set_env_var DEEPSQL_EMBEDDING_API_KEY  "${MATRIX_LITELLM_EMBED_KEY:-$MATRIX_LITELLM_KEY}"
      set_env_var DEEPSQL_EMBEDDING_MODEL    "${MATRIX_LITELLM_EMBED_MODEL:-text-embedding-3-large}"
      ;;
    *)
      echo "Error: unknown LLM profile '$profile'." >&2
      return 1 ;;
  esac

  # Every profile must embed at the same width. rag_documents.embedding is
  # vector(3072) — one column shared by all connections, not one per connection —
  # so a narrower model is rejected by pgvector for every connection at once, and
  # creating fresh connections does not help. That is the safe failure: the
  # dimension is enforced. text-embedding-3-large is 3072; text-embedding-3-small
  # is 1536 and will not insert. Changing width means migrating the column.
  echo "  applied profile '$profile'; restarting backend ..."
  restart_backend
}

IFS=',' read -ra PROFILES <<<"$LLM_PROFILES"
for profile in "${PROFILES[@]}"; do
  echo ""
  echo "############################################################"
  echo "# LLM profile: $profile"
  echo "############################################################"

  if [[ "$profile" != "current" && -z "$ENV_BACKUP" ]]; then
    ENV_BACKUP="$(mktemp)"
    cp "$ENV_FILE" "$ENV_BACKUP"
  fi

  if ! apply_profile "$profile"; then
    RESULTS+=("$profile|SKIPPED (configuration failed)")
    continue
  fi

  if "$SCRIPT_DIR/run-matrix.sh" "${ENGINE_ARGS[@]:-}"; then
    RESULTS+=("$profile|PASS")
  else
    RESULTS+=("$profile|FAIL")
  fi
done

echo ""
printf '%-16s %s\n' "LLM PROFILE" "RESULT"
printf '%-16s %s\n' "-----------" "------"
for row in "${RESULTS[@]:-}"; do
  IFS='|' read -r p r <<<"$row"
  printf '%-16s %s\n' "$p" "$r"
done

for row in "${RESULTS[@]:-}"; do
  [[ "$row" == *"|PASS" ]] || exit 1
done
echo ""
echo "All LLM profiles passed."
