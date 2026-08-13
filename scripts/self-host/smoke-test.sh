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
: "${DEEPSQL_FRONTEND_PORT:=3000}"
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

# Login through the frontend proxy so the cookie jar matches the Host the
# browser (and /agent-api auth_request) will use. A jar filled against
# :8080 alone has made nginx's auth_request return 401 even when /auth/me
# on the backend would succeed with the same cookie.
# Retried rather than attempted once. The backend answers /actuator/health UP
# before it serves logins, so this script used to abort on a good install with
# a bare `curl: (22) 401` under `set -e` + curl -f.
smoke_login() {
  local deadline=$((SECONDS + "${1:-120}"))
  local body=""
  while (( SECONDS < deadline )); do
    if body="$(curl -fsS -c "$cookie_jar" -b "$cookie_jar" -H 'Content-Type: application/json' \
         -X POST "http://localhost:${DEEPSQL_FRONTEND_PORT}/api/auth/login" \
         -d "{\"email\":\"${DEEPSQL_SMOKE_EMAIL}\",\"password\":\"${DEEPSQL_SMOKE_PASSWORD}\"}" 2>/dev/null)"; then
      printf '%s' "$body"
      return 0
    fi
    if body="$(curl -fsS -c "$cookie_jar" -b "$cookie_jar" -H 'Content-Type: application/json' \
         -X POST "$base/auth/login" \
         -d "{\"email\":\"${DEEPSQL_SMOKE_EMAIL}\",\"password\":\"${DEEPSQL_SMOKE_PASSWORD}\"}" 2>/dev/null)"; then
      printf '%s' "$body"
      return 0
    fi
    echo "Waiting for the backend to accept logins..."
    sleep 5
  done
  return 1
}

login_json="$(smoke_login 120 || true)"
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
  # Brain init calls the LLM once per schema batch and routinely runs for many
  # minutes. Every check up to this point prints only on failure, so without the
  # progress lines below this script sat completely mute for up to
  # DEEPSQL_SMOKE_INIT_TIMEOUT_SECONDS (default 1200) — indistinguishable from a
  # hang, and duly killed by whoever was watching it, well before it would have
  # finished.
  echo "Waiting for brain init (up to ${DEEPSQL_SMOKE_INIT_TIMEOUT_SECONDS}s)."
  echo "This calls the LLM once per schema batch, so several minutes is normal."
  deadline=$((SECONDS + DEEPSQL_SMOKE_INIT_TIMEOUT_SECONDS))
  last_report=""
  init_json=""
  while (( SECONDS < deadline )); do
    # Do not use curl -f here: brain init often outlives the JWT (~15m), and a
    # 401 under set -e aborted the smoke mid-progress with no recovery path.
    init_code="$(curl -sS -o /tmp/deepsql-smoke-init.json -w '%{http_code}' \
      -b "$cookie_jar" -c "$cookie_jar" \
      "$base/connections/${connection_id}/init-status" || echo "000")"
    if [[ "$init_code" == "401" ]]; then
      echo "  [${SECONDS}s] session expired during brain init — re-logging in..."
      if ! smoke_login 60 >/dev/null; then
        echo "Error: re-login failed while waiting for brain init." >&2
        exit 1
      fi
      continue
    fi
    if [[ "$init_code" != "200" ]]; then
      echo "Error: init-status returned HTTP ${init_code}." >&2
      cat /tmp/deepsql-smoke-init.json 2>/dev/null >&2 || true
      exit 1
    fi
    init_json="$(cat /tmp/deepsql-smoke-init.json 2>/dev/null || true)"
    init_stage="$(printf '%s' "$init_json" | sed -n 's/.*"currentStage":"\([^"]*\)".*/\1/p')"
    init_progress="$(printf '%s' "$init_json" | sed -n 's/.*"progressPercent":\([0-9][0-9]*\).*/\1/p')"
    init_message="$(printf '%s' "$init_json" | sed -n 's/.*"stageMessage":"\([^"]*\)".*/\1/p')"

    if [[ "$init_stage" == "COMPLETED" ]]; then
      echo "Brain init completed for smoke-test connection (${init_progress:-100}%)."
      break
    fi

    if [[ "$init_stage" == "FAILED" ]]; then
      echo "Error: brain init failed for smoke-test connection." >&2
      echo "$init_json" >&2
      exit 1
    fi

    # Print only on change: enough to prove the run is alive and advancing,
    # without 240 identical lines scrolling the earlier output away.
    report="${init_stage:-?} ${init_progress:-0}% ${init_message:-}"
    if [[ "$report" != "$last_report" ]]; then
      echo "  [${SECONDS}s] ${init_stage:-unknown} ${init_progress:-0}%${init_message:+ — $init_message}"
      last_report="$report"
    fi

    sleep 5
  done

  if [[ "${init_stage:-}" != "COMPLETED" ]]; then
    echo "Error: timed out waiting for brain init to complete for smoke-test connection." >&2
    echo "${init_json:-}" >&2
    exit 1
  fi
fi

if [[ "$VECTOR_STORE_TYPE" == "pgvector" && "$DEEPSQL_SMOKE_WAIT_FOR_INIT" == "true" ]]; then
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

# ── DeepSQL Agent paths ─────────────────────────────────────────────────────
# Agent tab (browser→/agent-api) and dashboards (backend→AGENT_WEBUI_URL) both
# need the deepsql-agent Compose service. Fail loudly when DEEPSQL_SMOKE_AGENT=1
# (default) so a "green" smoke test means those UI surfaces will work.
: "${DEEPSQL_SMOKE_AGENT:=1}"
: "${DEEPSQL_FRONTEND_PORT:=3000}"
: "${AGENT_WEBUI_URL:=http://deepsql-agent:8787}"
: "${DEEPSQL_AGENT_PORT:=8787}"
: "${DEEPSQL_AGENT_PROVISIONER_PORT:=8788}"

if [[ "$DEEPSQL_SMOKE_AGENT" == "1" ]]; then
  if ! curl -fsS "http://127.0.0.1:${DEEPSQL_AGENT_PROVISIONER_PORT}/health" >/dev/null 2>&1; then
    echo "Error: DeepSQL Agent provisioner is not reachable on :${DEEPSQL_AGENT_PROVISIONER_PORT}." >&2
    echo "       Agent tab and AI dashboards will fail. Check:" >&2
    echo "         docker compose logs deepsql-agent" >&2
    exit 1
  fi

  # Backend container must reach the agent API (dashboard / Slack / CLI path).
  # The API may return 401 without a session — any HTTP response means reachable.
  agent_code="$(compose exec -T backend sh -c \
      "curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 3 '${AGENT_WEBUI_URL}/api/mcp/servers'" \
      || echo "000")"
  if [[ "$agent_code" == "000" || -z "$agent_code" ]]; then
    echo "Error: backend cannot reach AGENT_WEBUI_URL=${AGENT_WEBUI_URL}." >&2
    echo "       Check docker-compose.yml AGENT_WEBUI_URL and that deepsql-agent is up." >&2
    exit 1
  fi

  # Browser path through nginx: profile switch must not 403 (Host/Origin CSRF).
  # Do NOT send Origin here — that trips the agent's browser CSRF gate, which
  # expects X-Hermes-CSRF-Token (the React Agent tab fetches that from
  # /api/auth/status). Smoke validates the nginx auth_request + trusted-header
  # path the way non-browser clients (and our curl diagnostics) do.
  switch_code="$(curl -sS -o /tmp/deepsql-agent-switch.json -w '%{http_code}' \
    -b "$cookie_jar" -c "$cookie_jar" \
    -H 'Content-Type: application/json' \
    -X POST "http://localhost:${DEEPSQL_FRONTEND_PORT}/agent-api/api/profile/switch" \
    -d '{"name":"u-admin"}' || true)"
  if [[ "$switch_code" != "200" ]]; then
    # Profile name may not be u-admin if the smoke user differs — resolve via bridge.
    bridge_json="$(curl -fsS -b "$cookie_jar" -H 'Content-Type: application/json' \
      -X POST "$base/agent/session" -d "{\"connectionId\":\"${connection_id}\"}")"
    profile="$(printf '%s' "$bridge_json" | sed -n 's/.*"profile":"\([^"]*\)".*/\1/p')"
    if [[ -z "$profile" ]]; then
      echo "Error: /api/agent/session did not return a profile." >&2
      echo "$bridge_json" >&2
      exit 1
    fi
    switch_code="$(curl -sS -o /tmp/deepsql-agent-switch.json -w '%{http_code}' \
      -b "$cookie_jar" -c "$cookie_jar" \
      -H 'Content-Type: application/json' \
      -X POST "http://localhost:${DEEPSQL_FRONTEND_PORT}/agent-api/api/profile/switch" \
      -d "{\"name\":\"${profile}\"}" || true)"
  else
    profile="u-admin"
  fi
  if [[ "$switch_code" != "200" ]]; then
    echo "Error: /agent-api/api/profile/switch → HTTP ${switch_code} (expected 200)." >&2
    echo "       Common cause: nginx Host header dropping :${DEEPSQL_FRONTEND_PORT} (CSRF)," >&2
    echo "       or DEEPSQL_AGENT_TRUSTED_PROXY_CIDRS missing the compose bridge." >&2
    cat /tmp/deepsql-agent-switch.json 2>/dev/null >&2 || true
    exit 1
  fi

  session_json="$(curl -fsS -b "$cookie_jar" -c "$cookie_jar" \
    -H 'Content-Type: application/json' \
    -X POST "http://localhost:${DEEPSQL_FRONTEND_PORT}/agent-api/api/session/new" \
    -d "{\"profile\":\"${profile}\",\"enabled_toolsets\":[\"deepsql\",\"skills\"]}")"
  session_id="$(printf '%s' "$session_json" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("session",{}).get("session_id") or "")' 2>/dev/null || true)"
  if [[ -z "$session_id" ]]; then
    echo "Error: /agent-api/api/session/new did not return a session_id." >&2
    echo "$session_json" >&2
    exit 1
  fi

  # Backend→agent session (dashboard path) — same as AgentChatClient.ensureSession.
  # X-Remote-User is required once HERMES_WEBUI_TRUSTED_AUTH_HEADER is set; the
  # compose bridge is allowlisted via DEEPSQL_AGENT_TRUSTED_PROXY_CIDRS.
  remote_user="${profile#u-}"
  backend_switch="$(compose exec -T backend sh -c \
    "curl -fsS -c /tmp/hc.jar -H 'Content-Type: application/json' \
      -H 'X-Remote-User: ${remote_user}' \
      -X POST '${AGENT_WEBUI_URL}/api/profile/switch' \
      -d '{\"name\":\"${profile}\"}' >/dev/null && \
     curl -fsS -b /tmp/hc.jar -c /tmp/hc.jar -H 'Content-Type: application/json' \
      -H 'X-Remote-User: ${remote_user}' \
      -X POST '${AGENT_WEBUI_URL}/api/session/new' \
      -d '{\"profile\":\"${profile}\",\"enabled_toolsets\":[\"deepsql\",\"skills\"]}'")"
  if [[ "$backend_switch" != *"session_id"* ]]; then
    echo "Error: backend→DeepSQL Agent session/new failed (dashboard path)." >&2
    echo "$backend_switch" >&2
    exit 1
  fi

  echo "Agent smoke checks passed (DeepSQL Agent up, nginx profile/switch OK, backend session OK)."
  echo "Agent profile: $profile"
  echo "Agent session: $session_id"
fi

echo "Smoke test passed."
echo "Connection ID: ${connection_id}"
