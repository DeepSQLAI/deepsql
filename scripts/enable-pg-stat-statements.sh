#!/usr/bin/env bash
# Enable pg_stat_statements extension and grant permissions in PostgreSQL.
# Requires: Docker (for docker compose) or psql in PATH.
# Usage: ./scripts/enable-pg-stat-statements.sh [DB_USER]
#   DB_USER: Database username to grant permissions to (default: same as connection user)
set -e

DB_NAME="${DB_NAME:-dba_agent}"
DB_USER="${DB_USER:-postgres}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
TARGET_USER="${1:-${DB_USER}}"  # User to grant permissions to (first arg or DB_USER)

SQL_COMMANDS=$(cat <<EOF
-- Create extension (requires superuser)
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Grant permissions (PostgreSQL 13+)
DO \$\$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pg_read_all_stats') THEN
    GRANT pg_read_all_stats TO "${TARGET_USER}";
  ELSE
    -- Fallback for older PostgreSQL versions
    GRANT SELECT ON pg_stat_statements TO "${TARGET_USER}";
    GRANT EXECUTE ON FUNCTION pg_stat_statements_reset() TO "${TARGET_USER}";
  END IF;
END
\$\$;
EOF
)

if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  echo "Using Docker Compose postgres service..."
  echo "Creating extension and granting permissions to user: ${TARGET_USER}"
  docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" <<< "$SQL_COMMANDS"
elif command -v psql >/dev/null 2>&1; then
  echo "Using local psql..."
  echo "Creating extension and granting permissions to user: ${TARGET_USER}"
  PGPASSWORD="${DB_PASSWORD:-postgres}" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" <<< "$SQL_COMMANDS"
else
  echo "Error: Need either 'docker compose' (with postgres service) or 'psql' in PATH."
  echo "  Docker: docker compose up -d postgres && ./scripts/enable-pg-stat-statements.sh [username]"
  echo "  Local:  brew install postgresql@18 && ./scripts/enable-pg-stat-statements.sh [username]"
  exit 1
fi

echo "pg_stat_statements extension enabled and permissions granted to ${TARGET_USER}."
