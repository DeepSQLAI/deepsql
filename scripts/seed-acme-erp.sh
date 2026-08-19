#!/usr/bin/env bash
# seed-acme-erp.sh — Create the acme_erp multi-schema Postgres DB and register a DeepSQL connection.
#
# Native (non-Docker) cloud VM usage:
#   sudo -u postgres psql -f docker/postgres/init/11_create_acme_erp.sql
#   bash scripts/seed-acme-erp.sh
#
# Requires backend on :8080 with dev auth bypass (SECURITY_AUTH_ENABLED=false).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${DEEPSQL_ENV_FILE:-$ROOT_DIR/.env}"
SQL_FILE="$ROOT_DIR/docker/postgres/init/11_create_acme_erp.sql"
CONNECTION_NAME="${DEEPSQL_ACME_CONNECTION_NAME:-ACME ERP (Multi-Schema)}"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

: "${DEEPSQL_BACKEND_PORT:=8080}"
: "${DB_PASSWORD:=postgres}"

base="http://127.0.0.1:${DEEPSQL_BACKEND_PORT}/api"

echo "=========================================="
echo "ACME ERP multi-schema fixture"
echo "=========================================="

if [[ ! -f "$SQL_FILE" ]]; then
  echo "Missing SQL file: $SQL_FILE" >&2
  exit 1
fi

echo ""
echo "Step 1: Applying $SQL_FILE ..."
if command -v pg_ctlcluster >/dev/null 2>&1; then
  sudo pg_ctlcluster 16 main status >/dev/null 2>&1 || sudo pg_ctlcluster 16 main start
fi
sudo -u postgres psql -v ON_ERROR_STOP=1 -f "$SQL_FILE"

echo ""
echo "Step 2: Registering DeepSQL connection '$CONNECTION_NAME' ..."

existing="$(curl -fsS "$base/connections" 2>/dev/null || echo '[]')"
connection_id="$(printf '%s' "$existing" | python3 -c "
import json, sys, os
name = os.environ.get('CONNECTION_NAME', '')
try:
    data = json.load(sys.stdin)
except Exception:
    data = []
items = data if isinstance(data, list) else data.get('connections') or data.get('data') or []
for conn in items:
    if conn.get('connectionName') == name:
        print(conn.get('id') or conn.get('connectionId') or '')
        break
" CONNECTION_NAME="$CONNECTION_NAME")"

if [[ -n "$connection_id" ]]; then
  echo "  Connection already exists: $connection_id"
else
  payload="$(cat <<EOF
{
  "connectionName": "$CONNECTION_NAME",
  "dbType": "postgres",
  "host": "127.0.0.1",
  "port": 5432,
  "database": "acme_erp",
  "username": "postgres",
  "password": "$DB_PASSWORD",
  "sslEnabled": false
}
EOF
)"
  save_json="$(curl -sS -w '\n%{http_code}' -H 'Content-Type: application/json' -X POST "$base/connections" -d "$payload" || true)"
  http_code="$(printf '%s' "$save_json" | tail -n1)"
  body="$(printf '%s' "$save_json" | sed '$d')"
  connection_id="$(printf '%s' "$body" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('connectionId') or d.get('id') or '')" 2>/dev/null || true)"
  if [[ -z "$connection_id" ]]; then
    echo "  Warning: could not create connection (HTTP ${http_code:-?}). Body: $body" >&2
  else
    echo "  Created connection: $connection_id"
  fi
fi

echo ""
echo "Schemas in acme_erp:"
sudo -u postgres psql -d acme_erp -At -c "SELECT nspname FROM pg_namespace WHERE nspname NOT LIKE 'pg_%' AND nspname <> 'information_schema' ORDER BY 1;"

echo ""
echo "Done. Use connection '$CONNECTION_NAME'${connection_id:+ (id=$connection_id)} for multi-schema / policy tests."
