#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="${DEEPSQL_COMPOSE_FILE:-$ROOT_DIR/docker-compose.yml}"
ENV_FILE="${DEEPSQL_ENV_FILE:-$ROOT_DIR/.env}"
PROJECT_NAME="${DEEPSQL_PROJECT_NAME:-deepsql-selfhost}"

compose() {
  DEEPSQL_RUNTIME_ENV_FILE="$ENV_FILE" docker compose \
    --project-name "$PROJECT_NAME" \
    --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" \
    "$@"
}

if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  set -a
  source "$ENV_FILE"
  set +a
fi

: "${DEEPSQL_FRONTEND_PORT:=3000}"
: "${DEEPSQL_BACKEND_PORT:=8080}"

echo "Compose services:"
compose ps

echo
printf 'Backend health: '
if curl -fsS "http://localhost:${DEEPSQL_BACKEND_PORT}/api/actuator/health"; then
  printf '\n'
else
  echo "unreachable"
fi

printf 'Frontend health: '
if curl -fsS "http://localhost:${DEEPSQL_FRONTEND_PORT}" >/dev/null 2>&1; then
  echo "ok"
else
  echo "unreachable"
fi

: "${DEEPSQL_AGENT_PORT:=8787}"
: "${DEEPSQL_AGENT_PROVISIONER_PORT:=8788}"
: "${AGENT_WEBUI_URL:=http://deepsql-agent:8787}"
printf 'DeepSQL Agent provisioner (:%s): ' "$DEEPSQL_AGENT_PROVISIONER_PORT"
if curl -fsS "http://127.0.0.1:${DEEPSQL_AGENT_PROVISIONER_PORT}/health" >/dev/null 2>&1; then
  echo "ok"
else
  echo "unreachable — check: docker compose logs deepsql-agent"
fi
printf 'Backend → DeepSQL Agent (%s): ' "$AGENT_WEBUI_URL"
agent_code="$(compose exec -T backend sh -c "curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 2 '${AGENT_WEBUI_URL}/api/mcp/servers'" 2>/dev/null || echo "000")"
if [[ "$agent_code" != "000" && -n "$agent_code" ]]; then
  echo "ok (HTTP ${agent_code})"
else
  echo "unreachable"
fi
