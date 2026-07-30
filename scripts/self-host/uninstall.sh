#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="${DEEPSQL_COMPOSE_FILE:-$ROOT_DIR/docker-compose.yml}"
ENV_FILE="${DEEPSQL_ENV_FILE:-$ROOT_DIR/.env}"
PROJECT_NAME="${DEEPSQL_PROJECT_NAME:-deepsql-selfhost}"
PURGE_DATA=false

for arg in "$@"; do
  case "$arg" in
    --purge-data)
      PURGE_DATA=true
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      exit 1
      ;;
  esac
done

compose() {
  DEEPSQL_RUNTIME_ENV_FILE="$ENV_FILE" docker compose \
    --project-name "$PROJECT_NAME" \
    --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" \
    "$@"
}

if [[ "$PURGE_DATA" == "true" ]]; then
  compose down --remove-orphans --volumes
  echo "DeepSQL self-hosted stack removed, including persisted Postgres / Valkey volumes."
else
  compose down --remove-orphans
  echo "DeepSQL self-hosted stack stopped and removed. Data volumes were preserved."
fi
