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
