#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="${DEEPSQL_ENV_FILE:-$ROOT_DIR/.env}"
COMPOSE_FILE="${DEEPSQL_COMPOSE_FILE:-$ROOT_DIR/docker-compose.yml}"
PROJECT_NAME="${DEEPSQL_PROJECT_NAME:-deepsql-selfhost}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Error: missing env file $ENV_FILE" >&2
  exit 1
fi

# shellcheck disable=SC1090
set -a
source "$ENV_FILE"
set +a

: "${DEEPSQL_BACKEND_PORT:=8080}"
: "${DB_PASSWORD:=postgres}"
: "${DEEPSQL_INITIAL_ADMIN_EMAIL:=}"
: "${DEEPSQL_INITIAL_ADMIN_PASSWORD:=}"
: "${DEEPSQL_SMOKE_EMAIL:=${DEEPSQL_INITIAL_ADMIN_EMAIL}}"
: "${DEEPSQL_SMOKE_PASSWORD:=${DEEPSQL_INITIAL_ADMIN_PASSWORD}}"
: "${DEEPSQL_SMOKE_CONNECTION_NAME:=Self-Host Vault Postgres Smoke $(date +%s)}"
: "${VECTOR_STORE_TYPE:=pgvector}"
: "${DEEPSQL_SMOKE_WAIT_FOR_INIT:=true}"
: "${DEEPSQL_SMOKE_INIT_TIMEOUT_SECONDS:=1200}"

compose() {
  DEEPSQL_RUNTIME_ENV_FILE="$ENV_FILE" docker compose \
    --project-name "$PROJECT_NAME" \
    --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" \
    "$@"
}

if [[ -z "$DEEPSQL_SMOKE_PASSWORD" || -z "$DEEPSQL_SMOKE_EMAIL" ]]; then
  echo "Error: set DEEPSQL_INITIAL_ADMIN_EMAIL / DEEPSQL_INITIAL_ADMIN_PASSWORD or DEEPSQL_SMOKE_EMAIL / DEEPSQL_SMOKE_PASSWORD in the environment." >&2
  exit 1
fi

if [[ "$VECTOR_STORE_TYPE" == "pgvector" ]]; then
  pgvector_check="$(compose exec -T postgres psql -U postgres -d dba_agent -At -c "
    SELECT EXISTS(SELECT 1 FROM pg_extension WHERE extname = 'vector');
    SELECT EXISTS(SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements');
    SELECT EXISTS(
      SELECT 1
      FROM information_schema.tables
      WHERE table_schema = 'public' AND table_name = 'rag_documents'
    );
    SELECT EXISTS(
      SELECT 1
      FROM pg_indexes
      WHERE schemaname = 'public'
        AND tablename = 'rag_documents'
        AND indexname = 'idx_rag_docs_embedding'
    );
  ")"
  if [[ "$(printf '%s\n' "$pgvector_check" | sed -n '1p')" != "t" ]]; then
    echo "Error: pgvector extension is not installed in the vault database." >&2
    exit 1
  fi
  if [[ "$(printf '%s\n' "$pgvector_check" | sed -n '2p')" != "t" ]]; then
    echo "Error: pg_stat_statements extension is not installed in the vault database." >&2
    exit 1
  fi
  if [[ "$(printf '%s\n' "$pgvector_check" | sed -n '3p')" != "t" ]]; then
    echo "Error: rag_documents table is missing from the vault database." >&2
    exit 1
  fi
  if [[ "$(printf '%s\n' "$pgvector_check" | sed -n '4p')" != "t" ]]; then
    echo "Error: rag_documents ANN index is missing from the vault database." >&2
    exit 1
  fi
fi

base="http://localhost:${DEEPSQL_BACKEND_PORT}/api"
cookie_jar="$(mktemp)"
trap 'rm -f "$cookie_jar"' EXIT
# Retried rather than attempted once. The backend answers /actuator/health UP before it
# serves logins, so this script -- the command install.sh recommends running next -- used
# to abort on a perfectly good install with a bare `curl: (22) 401`. Because curl runs
# under `set -e` with -f, that exit happened before the error message below could print,
# so the failure named neither the endpoint nor the reason.
login_json=""
login_deadline=$((SECONDS + 120))
while (( SECONDS < login_deadline )); do
  if login_json="$(curl -fsS -c "$cookie_jar" -H 'Content-Type: application/json' -X POST "$base/auth/login" -d "{\"email\":\"${DEEPSQL_SMOKE_EMAIL}\",\"password\":\"${DEEPSQL_SMOKE_PASSWORD}\"}" 2>/dev/null)"; then
    break
  fi
  echo "Waiting for the backend to accept logins..."
  sleep 5
done

if [[ "$login_json" != *"\"email\""* ]]; then
  echo "Error: login failed during smoke test." >&2
  echo "$login_json" >&2
  exit 1
fi

payload=$(cat <<JSON
{
  "connectionName": "${DEEPSQL_SMOKE_CONNECTION_NAME}",
  "dbType": "postgres",
  "host": "postgres",
  "port": 5432,
  "database": "dba_agent",
  "username": "postgres",
  "password": "${DB_PASSWORD}",
  "cloudProvider": "self-hosted",
  "ssl": false,
  "sslMode": "none",
  "sshEnabled": false
}
JSON
)

save_json="$(curl -fsS -b "$cookie_jar" -H 'Content-Type: application/json' -X POST "$base/connections" -d "$payload")"
connection_id="$(printf '%s' "$save_json" | sed -n 's/.*"connectionId":"\([^"]*\)".*/\1/p')"

if [[ -z "$connection_id" ]]; then
  echo "Error: failed to create smoke-test connection." >&2
  echo "$save_json" >&2
  exit 1
fi

connections_json="$(curl -fsS -b "$cookie_jar" "$base/connections")"
if [[ "$connections_json" != *"${DEEPSQL_SMOKE_CONNECTION_NAME}"* ]]; then
  echo "Error: connection list does not contain the smoke-test connection." >&2
  exit 1
fi

schema_json="$(curl -fsS -b "$cookie_jar" "$base/connections/${connection_id}/schema")"
if [[ "$schema_json" != *'"success":true'* ]]; then
  echo "Error: schema introspection failed for smoke-test connection." >&2
  echo "$schema_json" >&2
  exit 1
fi

if [[ "$DEEPSQL_SMOKE_WAIT_FOR_INIT" == "true" ]]; then
  deadline=$((SECONDS + DEEPSQL_SMOKE_INIT_TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    init_json="$(curl -fsS -b "$cookie_jar" "$base/connections/${connection_id}/init-status")"
    init_stage="$(printf '%s' "$init_json" | sed -n 's/.*"currentStage":"\([^"]*\)".*/\1/p')"
    init_progress="$(printf '%s' "$init_json" | sed -n 's/.*"progressPercent":\([0-9][0-9]*\).*/\1/p')"

    if [[ "$init_stage" == "COMPLETED" ]]; then
      echo "Brain init completed for smoke-test connection (${init_progress:-100}%)."
      break
    fi

    if [[ "$init_stage" == "FAILED" ]]; then
      echo "Error: brain init failed for smoke-test connection." >&2
      echo "$init_json" >&2
      exit 1
    fi

    sleep 5
  done

  if [[ "${init_stage:-}" != "COMPLETED" ]]; then
    echo "Error: timed out waiting for brain init to complete for smoke-test connection." >&2
    echo "${init_json:-}" >&2
    exit 1
  fi
fi

if [[ "$VECTOR_STORE_TYPE" == "pgvector" ]]; then
  embedded_docs="$(compose exec -T postgres psql -U postgres -d dba_agent -At -c "
    SELECT COUNT(embedding)
    FROM rag_documents
    WHERE connection_id = '${connection_id}';
  ")"
  if [[ "${embedded_docs:-0}" -le 0 ]]; then
    echo "Error: brain init completed but pgvector has no embedded documents for ${connection_id}." >&2
    exit 1
  fi
fi

echo "Smoke test passed."
echo "Connection ID: ${connection_id}"
