#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="${DEEPSQL_COMPOSE_FILE:-$ROOT_DIR/docker-compose.yml}"
ENV_FILE="${DEEPSQL_ENV_FILE:-$ROOT_DIR/.env}"
PROJECT_NAME="${DEEPSQL_PROJECT_NAME:-deepsql-selfhost}"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Error: required command '$1' is not installed." >&2
    exit 1
  fi
}

is_placeholder() {
  local value="${1:-}"
  [[ -z "$value" || "$value" == change-me-* || "$value" == replace-with-* || "$value" == your-* ]]
}

require_env_value() {
  local name="$1"
  local value="${!name:-}"
  if is_placeholder "$value"; then
    echo "Error: '$name' must be set in $ENV_FILE." >&2
    exit 1
  fi
}

# Write NAME='value' into $ENV_FILE, single-quoted with embedded quotes escaped.
#
# Every self-host script does `set -a; source .env`, so an unquoted value containing a
# space is executed as a command: answering the company-name prompt with "Acme Corp"
# produced `DEEPSQL_COMPANY_NAME=Acme Corp`, and then `line 216: Corp: command not found`
# from install.sh, status.sh and smoke-test.sh alike — naming neither the variable nor
# the prompt that set it. Docker Compose strips the surrounding quotes when it reads the
# same file, so this is safe for both readers.
write_env_value() {
  local name="$1" value="$2" quoted
  # Close the quote, emit an escaped quote, reopen: ' -> '\''. Built from variables
  # because writing the replacement inline is easy to get subtly wrong -- the first
  # attempt produced O\'\\'\'Brien, which made `source .env` die on an unterminated
  # string, exactly the class of breakage this function exists to prevent.
  local sq="'" esc="'\\''"
  quoted="${sq}${value//${sq}/${esc}}${sq}"
  if grep -q "^${name}=" "$ENV_FILE" 2>/dev/null; then
    # Literal replacement rather than a sed expression: the value may contain |, & or \,
    # each of which sed would otherwise interpret.
    NAME="$name" QUOTED="$quoted" python3 - "$ENV_FILE" <<'PY'
import os, re, sys
path = sys.argv[1]
name, quoted = os.environ["NAME"], os.environ["QUOTED"]
text = open(path).read()
text = re.sub(rf"(?m)^{re.escape(name)}=.*$", lambda _: f"{name}={quoted}", text)
open(path, "w").write(text)
PY
  else
    printf '%s=%s\n' "$name" "$quoted" >> "$ENV_FILE"
  fi
  # No eval: `eval export NAME=$value` re-parses the value, so a password containing
  # $(...) or a backtick would execute rather than be stored.
  export "${name}=${value}"
}

generate_secret() {
  local name="$1"
  local cmd="$2"
  local value="${!name:-}"
  if is_placeholder "$value"; then
    local generated
    generated="$(eval "$cmd")"
    write_env_value "$name" "$generated"
    echo "Auto-generated $name."
  fi
}

prompt_env_value() {
  local name="$1"
  local label="$2"
  local value="${!name:-}"
  if is_placeholder "$value"; then
    printf '%s: ' "$label"
    # `|| true`: read returns non-zero at EOF, and under `set -e` that aborts the
    # script instantly — no message, no diagnosis, and .env already half-written with
    # freshly generated secrets. Let the emptiness check below report it instead.
    # See prompt_optional_env_value for how this was found.
    read -r value || true
    if [[ -z "$value" ]]; then
      echo "Error: '$name' is required." >&2
      exit 1
    fi
    write_env_value "$name" "$value"
  fi
}

prompt_secret_env_value() {
  local name="$1"
  local label="$2"
  local value="${!name:-}"
  if is_placeholder "$value"; then
    printf '%s: ' "$label"
    read -rs value || true
    printf '\n'
    if [[ -z "$value" ]]; then
      echo "Error: '$name' is required." >&2
      exit 1
    fi
    write_env_value "$name" "$value"
  fi
}

# Optional prompt — accepts blank Enter without exiting. Used for values
# the backend can sensibly derive on its own (e.g. company name fallback
# to admin email domain). If a non-blank value is provided it is persisted
# to $ENV_FILE and exported; blank leaves the variable unset.
#
# The `|| true` is what makes "optional" true. Without it this prompt was the most
# likely place for the whole installer to die: `read` returns non-zero at EOF, and
# under `set -euo pipefail` that exits 1 with nothing printed. Any non-interactive
# run (`install.sh </dev/null`, CI, a piped shell) reached exactly here — after the
# secrets were generated and written — and stopped, looking like a successful config
# step followed by silence. Interactively it is no better: this prompt says "press
# Enter to skip", and Ctrl-D is the other thing people press at a skippable prompt.
prompt_optional_env_value() {
  local name="$1"
  local label="$2"
  local value="${!name:-}"
  if [[ -z "$value" || "$value" == *change-me-* || "$value" == *replace-with-* ]]; then
    printf '%s: ' "$label"
    read -r value || true
    if [[ -n "$value" ]]; then
      # This is the prompt that first exposed the quoting bug: "Company / organization
      # name" invites an answer with a space, and almost every real one has one.
      write_env_value "$name" "$value"
    fi
  fi
}

sed_inplace() {
  if [[ "$(uname)" == "Darwin" ]]; then
    sed -i '' "$@"
  else
    sed -i "$@"
  fi
}

wait_for_http() {
  local url="$1"
  local label="$2"
  local retries="${3:-90}"
  local delay="${4:-2}"
  for ((i=1; i<=retries; i++)); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      echo "$label is healthy: $url"
      return 0
    fi
    sleep "$delay"
  done
  echo "Error: timed out waiting for $label at $url" >&2
  return 1
}

ensure_scheduler_table() {
  local sql_file="$ROOT_DIR/docker/postgres/init/01_create_scheduled_tasks.sql"
  if [[ ! -f "$sql_file" ]]; then
    echo "Error: missing scheduler bootstrap SQL at $sql_file" >&2
    exit 1
  fi

  compose exec -T postgres psql -U postgres -d dba_agent -v ON_ERROR_STOP=1 < "$sql_file" >/dev/null
  echo "Ensured db-scheduler table exists in the vault database."
}

ensure_pg_stat_statements() {
  compose exec -T postgres psql -U postgres -d dba_agent -v ON_ERROR_STOP=1 \
    -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" >/dev/null
  echo "Ensured pg_stat_statements extension exists in the vault database."
}

ensure_pgvector_store() {
  if [[ "${VECTOR_STORE_TYPE:-pgvector}" != "pgvector" ]]; then
    return 0
  fi

  local expected_dims="${VECTOR_STORE_EMBEDDING_DIMENSIONS:-3072}"

  local result
  result="$(compose exec -T postgres psql -U postgres -d dba_agent -At -c "
    SELECT EXISTS(SELECT 1 FROM pg_extension WHERE extname = 'vector');
    SELECT EXISTS(
      SELECT 1
      FROM information_schema.tables
      WHERE table_schema = 'public' AND table_name = 'rag_documents'
    );
    SELECT COALESCE(
      (
        SELECT pg_catalog.format_type(a.atttypid, a.atttypmod)
        FROM pg_attribute a
        JOIN pg_class c ON c.oid = a.attrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public'
          AND c.relname = 'rag_documents'
          AND a.attname = 'embedding'
          AND a.attnum > 0
          AND NOT a.attisdropped
      ),
      ''
    );
    SELECT EXISTS(
      SELECT 1
      FROM pg_indexes
      WHERE schemaname = 'public'
        AND tablename = 'rag_documents'
        AND indexname = 'idx_rag_docs_embedding'
    );
  ")"

  local has_vector has_table embedding_type has_ann_index
  has_vector="$(printf '%s\n' "$result" | sed -n '1p')"
  has_table="$(printf '%s\n' "$result" | sed -n '2p')"
  embedding_type="$(printf '%s\n' "$result" | sed -n '3p')"
  has_ann_index="$(printf '%s\n' "$result" | sed -n '4p')"

  if [[ "$has_table" != "t" ]]; then
    echo "Error: local pgvector RAG store was not initialized (rag_documents table missing)." >&2
    exit 1
  fi

  if [[ "$has_vector" != "t" ]]; then
    echo "Error: VECTOR_STORE_TYPE=pgvector but the PostgreSQL 'vector' extension is not installed." >&2
    exit 1
  fi

  if [[ "$embedding_type" != "vector(${expected_dims})" ]]; then
    echo "Error: rag_documents.embedding is '$embedding_type' instead of 'vector(${expected_dims})'." >&2
    exit 1
  fi

  if [[ "$has_ann_index" != "t" ]]; then
    echo "Error: local pgvector ANN index idx_rag_docs_embedding is missing." >&2
    exit 1
  fi

  echo "Verified local pgvector RAG store in the vault database."
}

compose() {
  DEEPSQL_RUNTIME_ENV_FILE="$ENV_FILE" docker compose \
    --project-name "$PROJECT_NAME" \
    --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" \
    "$@"
}

bootstrap_admin() {
  if [[ "${SECURITY_ADMIN_BOOTSTRAP_ENABLED:-false}" != "true" ]]; then
    return 0
  fi

  if [[ -z "${ADMIN_BOOTSTRAP_SECRET:-}" || -z "${DEEPSQL_INITIAL_ADMIN_PASSWORD:-}" || -z "${DEEPSQL_INITIAL_ADMIN_EMAIL:-}" ]]; then
    echo "Admin bootstrap enabled, but DEEPSQL_INITIAL_ADMIN_EMAIL / DEEPSQL_INITIAL_ADMIN_PASSWORD / ADMIN_BOOTSTRAP_SECRET are not all set. Skipping bootstrap."
    return 0
  fi

  local payload
  payload="$(printf '{\"email\":\"%s\",\"password\":\"%s\"}' \
    "${DEEPSQL_INITIAL_ADMIN_EMAIL}" \
    "${DEEPSQL_INITIAL_ADMIN_PASSWORD}")"
  local response
  response="$(printf '%s' "$payload" | compose exec -T \
    -e ADMIN_BOOTSTRAP_SECRET="${ADMIN_BOOTSTRAP_SECRET}" \
    backend sh -lc \
    'curl -fsS -H "Content-Type: application/json" -H "X-Admin-Bootstrap-Secret: ${ADMIN_BOOTSTRAP_SECRET}" -X POST http://localhost:8080/api/users/admin/reset --data @-' || true)"

  if [[ "$response" == *"Admin reset successfully"* || "$response" == *"Admin created successfully"* ]]; then
    echo "Admin bootstrap complete. Login username: admin"
  else
    # Previously a warning that the install continued past, so install.sh exited 0 while
    # leaving no account to log in with. An installer that cannot create the only user
    # has not succeeded, and saying so here beats an opaque 401 from the next command.
    echo "Error: admin bootstrap did not return a success message." >&2
    echo "$response" >&2
    return 1
  fi
}

# Poll until the credentials just created actually authenticate.
#
# Health being UP is not the same as being able to log in: install.sh flips
# SECURITY_ADMIN_BOOTSTRAP_ENABLED back to false and restarts the backend afterwards, and
# a login issued in the seconds after that restart returns 401. That is what made
# smoke-test.sh -- the very next command install.sh recommends -- fail on a good install.
wait_for_login() {
  local url="http://localhost:${DEEPSQL_BACKEND_PORT:-8080}/api/auth/login"
  local payload deadline=$((SECONDS + 120))
  payload="$(printf '{"email":"%s","password":"%s"}' \
    "${DEEPSQL_INITIAL_ADMIN_EMAIL}" "${DEEPSQL_INITIAL_ADMIN_PASSWORD}")"
  while (( SECONDS < deadline )); do
    if curl -fsS -o /dev/null -H 'Content-Type: application/json' \
         -X POST "$url" --data "$payload" 2>/dev/null; then
      echo "Login verified for ${DEEPSQL_INITIAL_ADMIN_EMAIL}."
      return 0
    fi
    sleep 5
  done
  echo "Error: the admin account was created but could not log in within 120s." >&2
  echo "Check 'docker compose logs backend' before running smoke-test.sh." >&2
  return 1
}

build_application_images() {
  echo "Building the DeepSQL backend and frontend from source..."
  echo "The first build compiles the Java backend and bundles the frontend; expect"
  echo "several minutes. Subsequent runs reuse the Docker layer cache and are quick."
  compose build backend frontend
}

require_command docker
require_command curl

docker compose version >/dev/null 2>&1 || {
  echo "Error: docker compose is required." >&2
  exit 1
}

if [[ ! -f "$ENV_FILE" ]]; then
  cp "$ROOT_DIR/.env.example" "$ENV_FILE"
  echo "Created $ENV_FILE from .env.example. Fill in the required values and rerun this script."
  exit 1
fi

# shellcheck disable=SC1090
set -a
source "$ENV_FILE"
set +a

# Auto-generate security secrets if still placeholders
generate_secret SECURITY_JWT_SECRET "openssl rand -base64 64 | tr -d '\n'"
generate_secret ENCRYPTION_KEY "openssl rand -base64 32 | tr -d '\n'"
generate_secret DB_PASSWORD "openssl rand -base64 16 | tr -d '\n'"
generate_secret ADMIN_BOOTSTRAP_SECRET "openssl rand -base64 32 | tr -d '\n'"

# Prompt for the chat LLM key if still a placeholder. DeepSQL brings no model
# credentials of its own, and AZURE_OPENAI_* no longer configures chat — chat is
# resolved by LlmConfigResolver from DEEPSQL_CHAT_*.
prompt_secret_env_value DEEPSQL_CHAT_API_KEY "LLM API key for chat (e.g. an OpenAI sk-... key)"
# Only when an embedding provider is actually selected — otherwise the operator has
# opted into keyword-only retrieval and should not be forced to supply a key.
if [[ -n "${DEEPSQL_EMBEDDING_PROVIDER:-}" ]]; then
  prompt_secret_env_value DEEPSQL_EMBEDDING_API_KEY "LLM API key for embeddings (may be the same key)"
fi
prompt_env_value DEEPSQL_INITIAL_ADMIN_EMAIL "Initial admin email"
prompt_secret_env_value DEEPSQL_INITIAL_ADMIN_PASSWORD "Initial admin password"

# Optional — labels this install for analytics + support. If blank the
# backend will derive from the admin email domain on first boot. Either
# can be overridden later by editing this value in $ENV_FILE and restarting.
prompt_optional_env_value DEEPSQL_COMPANY_NAME "Company / organization name (optional, press Enter to skip)"

: "${SPRING_PROFILES_ACTIVE:=prod}"
: "${DEEPSQL_FRONTEND_PORT:=3000}"
: "${DEEPSQL_BACKEND_PORT:=8080}"
: "${DEEPSQL_POSTGRES_PORT:=5432}"
: "${DEEPSQL_VALKEY_PORT:=6379}"
: "${CORS_ALLOWED_ORIGINS:=http://localhost:${DEEPSQL_FRONTEND_PORT}}"

if [[ "${VECTOR_STORE_TYPE:-pgvector}" == "pgvector" && -z "${SPRING_AUTOCONFIGURE_EXCLUDE:-}" ]]; then
  SPRING_AUTOCONFIGURE_EXCLUDE="org.springframework.ai.vectorstore.azure.autoconfigure.AzureVectorStoreAutoConfiguration"
fi

export SPRING_PROFILES_ACTIVE
export DEEPSQL_FRONTEND_PORT
export DEEPSQL_BACKEND_PORT
export DEEPSQL_POSTGRES_PORT
export DEEPSQL_VALKEY_PORT
export CORS_ALLOWED_ORIGINS
export SPRING_AUTOCONFIGURE_EXCLUDE
export SECURITY_ADMIN_BOOTSTRAP_ENABLED=true

sed_inplace "s|^SECURITY_ADMIN_BOOTSTRAP_ENABLED=.*|SECURITY_ADMIN_BOOTSTRAP_ENABLED=true|" "$ENV_FILE"

require_env_value SECURITY_JWT_SECRET
require_env_value ENCRYPTION_KEY
require_env_value ENCRYPTION_KEY_ID
require_env_value DB_PASSWORD
# Chat is resolved by LlmConfigResolver from DEEPSQL_CHAT_*. AZURE_OPENAI_KEY /
# _ENDPOINT / _CHAT_DEPLOYMENT used to be required here; they no longer configure chat.
# _CHAT_DEPLOYMENT is read by nothing at all, and _KEY/_ENDPOINT now feed only the
# optional /api/llm/v1 gateway used by the DeepSQL CLI agent — so requiring them
# rejected a perfectly good plain-OpenAI install.
#
# PROVIDER and ENDPOINT are required alongside the key: the resolver ignores every
# other DEEPSQL_CHAT_* value unless PROVIDER is set, and OpenAiCompatibleChatProvider
# reads the endpoint with an empty-string fallback rather than a working default.
require_env_value DEEPSQL_CHAT_PROVIDER
require_env_value DEEPSQL_CHAT_API_KEY
require_env_value DEEPSQL_CHAT_ENDPOINT

# Embeddings are NOT configured by AZURE_OPENAI_EMBEDDING_DEPLOYMENT, which this script
# used to require. LlmConfigResolver.resolveEmbedding() reads DEEPSQL_EMBEDDING_*, and
# nothing reads that Azure variable any more — so requiring it passed the install while
# validating nothing real, and the brain-init diagnostic then pointed the operator back
# at it.
#
# Absence is a degraded mode, not a hard error: the app runs with keyword-only retrieval.
# Do not point the operator at the onboarding wizard here — it writes a different, older
# set of config keys that LlmConfigResolver does not read, so it cannot configure this.
if [[ -n "${DEEPSQL_EMBEDDING_PROVIDER:-}" ]]; then
  require_env_value DEEPSQL_EMBEDDING_PROVIDER
  # Only the key is required: the provider defaults the model (text-embedding-3-large) and
  # the endpoint (api.openai.com). Requiring those too would reject a valid plain-OpenAI
  # setup that relies on the defaults.
  require_env_value DEEPSQL_EMBEDDING_API_KEY
else
  echo "Note: DEEPSQL_EMBEDDING_PROVIDER is not set, so no embedding provider is configured."
  echo "      RAG retrieval stays keyword-only until one is."
  echo "      To configure it, set DEEPSQL_EMBEDDING_PROVIDER and DEEPSQL_EMBEDDING_API_KEY"
  echo "      (optionally _MODEL and _ENDPOINT) in $ENV_FILE and re-run this script."
fi

if [[ "${VECTOR_STORE_TYPE:-pgvector}" == "azure" || "${AZURE_SEARCH_ENABLED:-false}" == "true" ]]; then
  require_env_value AZURE_SEARCH_ENDPOINT
  require_env_value AZURE_SEARCH_API_KEY
  require_env_value AZURE_SEARCH_INDEX_NAME
fi

echo "Starting DeepSQL self-hosted stack with project '$PROJECT_NAME'..."
build_application_images
compose up -d

ensure_scheduler_table
ensure_pg_stat_statements
wait_for_http "http://localhost:${DEEPSQL_BACKEND_PORT}/api/actuator/health" "Backend"
wait_for_http "http://localhost:${DEEPSQL_FRONTEND_PORT}" "Frontend"
ensure_pgvector_store

bootstrap_admin

sed_inplace "s|^SECURITY_ADMIN_BOOTSTRAP_ENABLED=.*|SECURITY_ADMIN_BOOTSTRAP_ENABLED=false|" "$ENV_FILE"
export SECURITY_ADMIN_BOOTSTRAP_ENABLED=false
compose up -d backend >/dev/null
wait_for_http "http://localhost:${DEEPSQL_BACKEND_PORT}/api/actuator/health" "Backend"

# The restart above is why this exists: health returns UP before logins are served, so
# without it the installer declares success on a stack that rejects the credentials it
# just printed.
wait_for_login

echo

echo "DeepSQL self-hosted stack is ready."
echo "Frontend: http://localhost:${DEEPSQL_FRONTEND_PORT}"
echo "Backend:  http://localhost:${DEEPSQL_BACKEND_PORT}/api"
echo "Project:  $PROJECT_NAME"
echo "Images:   built from source in this checkout (backend/Dockerfile, ./Dockerfile)."
echo "          After pulling new code, re-run this script to rebuild."
echo

# Agent tab + AI dashboards need Hermes on the host (:8787). Install/start it
# unless the operator explicitly opted out.
if [[ "${DEEPSQL_SKIP_AGENT_SETUP:-0}" != "1" ]]; then
  if [[ -x "$SCRIPT_DIR/setup-agent.sh" ]]; then
    echo "Setting up the DeepSQL Agent (Hermes webui on :8787)…"
    if "$SCRIPT_DIR/setup-agent.sh"; then
      echo "Agent setup complete."
    else
      echo "Warning: agent setup failed. The core UI still works, but the Agent tab" >&2
      echo "         and AI dashboards need: ./scripts/self-host/setup-agent.sh" >&2
    fi
    echo
  fi
else
  echo "Skipped agent setup (DEEPSQL_SKIP_AGENT_SETUP=1)."
  echo "Run ./scripts/self-host/setup-agent.sh when you want the Agent tab / AI dashboards."
  echo
fi

echo "Useful commands:"
echo "  ./scripts/self-host/status.sh"
echo "  ./scripts/self-host/smoke-test.sh"
echo "  ./scripts/self-host/setup-agent.sh          # Hermes webui + MCP profile"
echo "  python3 scripts/self-host/e2e-agent-check.py <connectionId>  # live Agent+dashboard turn"
echo "  ./scripts/self-host/uninstall.sh"
