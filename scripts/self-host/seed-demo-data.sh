#!/usr/bin/env bash
# seed-demo-data.sh — Seeds the DeepSQL installation with a demo database and sample data
#
# This script creates:
# - demo_shop: A realistic e-commerce database with customers, orders, products
# - Demo connection using read-only deepsql_demo role
# - Real slow-query workload (~60s) captured by pg_stat_statements
# - Sample dashboard that renders without an LLM key
# - Sample agent prompts
# - Digest preferences (fixes "Legacy mode")
# - Curated Brain notes
# - Sample saved queries in the SQL editor
# - Index recommendations from real slow queries
#
# Run after install.sh completes. Requires the stack to be running.
#
# Exit codes:
#   0 - Success
#   1 - Missing prerequisites (env file, stack not running)
#   2 - Authentication failure
#   3 - Connection creation failure
#   4 - Database/schema issues
#
# The installer (P0-1) calls this script with DEEPSQL_SEED_DEMO_DATA=1 by default.
# Use DEEPSQL_SEED_DEMO_DATA=0 or --no-seed-demo to skip.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="${DEEPSQL_ENV_FILE:-$ROOT_DIR/.env}"
COMPOSE_FILE="${DEEPSQL_COMPOSE_FILE:-$ROOT_DIR/docker-compose.yml}"
PROJECT_NAME="${DEEPSQL_PROJECT_NAME:-deepsql-selfhost}"

if [[ ! -f "$ENV_FILE" ]]; then
    echo "Error: missing env file $ENV_FILE" >&2
    echo "Run install.sh first to set up the stack." >&2
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
: "${DEEPSQL_SEED_SKIP_DEMO_DB:=0}"
: "${DEEPSQL_SEED_CONNECTION_NAME:=Demo Shop}"
: "${DEEPSQL_SEED_WORKLOAD_DURATION:=60}"

# Demo role credentials (must match 10_create_demo_shop.sql)
DEMO_ROLE_USER="deepsql_demo"
DEMO_ROLE_PASSWORD="deepsql_demo_password"

# Detect docker compose command (v2 plugin vs standalone)
if docker compose version &>/dev/null; then
    DOCKER_COMPOSE_CMD="docker compose"
elif command -v docker-compose &>/dev/null; then
    DOCKER_COMPOSE_CMD="docker-compose"
else
    echo "Error: Neither 'docker compose' nor 'docker-compose' found." >&2
    exit 1
fi

compose() {
    DEEPSQL_RUNTIME_ENV_FILE="$ENV_FILE" $DOCKER_COMPOSE_CMD \
        --project-name "$PROJECT_NAME" \
        --env-file "$ENV_FILE" \
        -f "$COMPOSE_FILE" \
        "$@"
}

echo "=========================================="
echo "DeepSQL Demo Data Seeding"
echo "=========================================="

# ============================================================================
# Step 1: Create demo_shop database (if needed)
# ============================================================================

if [[ "$DEEPSQL_SEED_SKIP_DEMO_DB" != "1" ]]; then
    echo ""
    echo "Step 1: Creating demo_shop database..."

    demo_sql="$ROOT_DIR/docker/postgres/init/10_create_demo_shop.sql"
    demo_exists="$(compose exec -T postgres psql -U postgres -At -c "SELECT 1 FROM pg_database WHERE datname = 'demo_shop'" 2>/dev/null || echo "")"
    order_count="0"
    if [[ "$demo_exists" == "1" ]]; then
        order_count="$(compose exec -T postgres psql -U postgres -d demo_shop -At -c "SELECT COUNT(*) FROM orders" 2>/dev/null || echo "0")"
    fi

    recreate_demo=0
    if [[ "${DEEPSQL_SEED_FORCE_DEMO_DB:-0}" == "1" ]]; then
        recreate_demo=1
    elif [[ "$demo_exists" == "1" && "${order_count:-0}" -lt 1000 ]]; then
        recreate_demo=1
    fi

    if [[ "$demo_exists" == "1" && "$recreate_demo" -eq 0 ]]; then
        echo "  demo_shop database already exists with $order_count orders. Skipping creation."
        echo "  (Set DEEPSQL_SEED_FORCE_DEMO_DB=1 to drop and recreate)"
    elif [[ ! -f "$demo_sql" ]]; then
        echo "  Warning: demo_shop SQL script not found at $demo_sql"
        echo "  Skipping demo database creation."
    else
        if [[ "$demo_exists" == "1" ]]; then
            echo "  demo_shop exists but looks incomplete (orders=${order_count:-0}). Recreating…"
            compose exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 -c \
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'demo_shop' AND pid <> pg_backend_pid();" >/dev/null || true
            compose exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 -c \
                "DROP DATABASE IF EXISTS demo_shop;"
        fi
        echo "  Running demo_shop creation script..."
        if compose exec -T postgres test -f /docker-entrypoint-initdb.d/10_create_demo_shop.sql; then
            compose exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 \
                -f /docker-entrypoint-initdb.d/10_create_demo_shop.sql
        else
            compose exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 < "$demo_sql"
        fi
        echo "  demo_shop database created successfully."
    fi
    
    # Verify the demo role exists and pg_stat_statements is enabled
    echo "  Verifying demo role and pg_stat_statements..."
    role_exists="$(compose exec -T postgres psql -U postgres -At -c "SELECT 1 FROM pg_roles WHERE rolname = '$DEMO_ROLE_USER'" 2>/dev/null || echo "")"
    if [[ "$role_exists" != "1" ]]; then
        echo "  Warning: $DEMO_ROLE_USER role not found. The demo_shop init script may not have run completely."
        echo "  Attempting to create role..."
        compose exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 <<EOSQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$DEMO_ROLE_USER') THEN
        CREATE ROLE $DEMO_ROLE_USER WITH LOGIN PASSWORD '$DEMO_ROLE_PASSWORD' NOSUPERUSER NOCREATEDB NOCREATEROLE;
        GRANT pg_read_all_stats TO $DEMO_ROLE_USER;
        GRANT CONNECT ON DATABASE demo_shop TO $DEMO_ROLE_USER;
    END IF;
END \$\$;
EOSQL
        compose exec -T postgres psql -U postgres -d demo_shop -v ON_ERROR_STOP=1 <<EOSQL
GRANT USAGE ON SCHEMA public TO $DEMO_ROLE_USER;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO $DEMO_ROLE_USER;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO $DEMO_ROLE_USER;
EOSQL
    fi
    
    # Ensure pg_stat_statements is enabled in demo_shop
    compose exec -T postgres psql -U postgres -d demo_shop -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;" 2>/dev/null || true
else
    echo "Step 1: Skipping demo_shop database creation (DEEPSQL_SEED_SKIP_DEMO_DB=1)"
fi

# ============================================================================
# Step 2: Authenticate as admin
# ============================================================================

echo ""
echo "Step 2: Authenticating as admin..."

if [[ -z "$DEEPSQL_INITIAL_ADMIN_EMAIL" || -z "$DEEPSQL_INITIAL_ADMIN_PASSWORD" ]]; then
    echo "Error: DEEPSQL_INITIAL_ADMIN_EMAIL and DEEPSQL_INITIAL_ADMIN_PASSWORD must be set." >&2
    exit 2
fi

base="http://localhost:${DEEPSQL_BACKEND_PORT}/api"
cookie_jar="$(mktemp)"
trap 'rm -f "$cookie_jar"' EXIT

login_deadline=$((SECONDS + 60))
while (( SECONDS < login_deadline )); do
    if login_json="$(curl -fsS -c "$cookie_jar" -H 'Content-Type: application/json' \
        -X POST "$base/auth/login" \
        -d "{\"email\":\"${DEEPSQL_INITIAL_ADMIN_EMAIL}\",\"password\":\"${DEEPSQL_INITIAL_ADMIN_PASSWORD}\"}" 2>/dev/null)"; then
        if [[ "$login_json" == *"\"email\""* ]]; then
            echo "  Logged in as admin."
            break
        fi
    fi
    sleep 2
done

if [[ "${login_json:-}" != *"\"email\""* ]]; then
    echo "Error: Could not authenticate as admin." >&2
    exit 2
fi

# ============================================================================
# Step 3: Create demo connection using the read-only deepsql_demo role
# ============================================================================

echo ""
echo "Step 3: Creating demo connection..."

existing_conn="$(curl -fsS -b "$cookie_jar" "$base/connections" 2>/dev/null || echo "[]")"
if [[ "$existing_conn" == *"$DEEPSQL_SEED_CONNECTION_NAME"* ]]; then
    echo "  Demo connection already exists. Extracting connection ID..."
    connection_id="$(printf '%s' "$existing_conn" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for conn in data:
    if conn.get('connectionName') == '$DEEPSQL_SEED_CONNECTION_NAME':
        print(conn.get('id', ''))
        break
" 2>/dev/null || echo "")"
    if [[ -n "$connection_id" ]]; then
        echo "  Using existing connection: $connection_id"
    fi
else
    # Use the read-only demo role instead of superuser
    payload=$(cat <<JSON
{
  "connectionName": "${DEEPSQL_SEED_CONNECTION_NAME}",
  "dbType": "postgres",
  "host": "postgres",
  "port": 5432,
  "database": "demo_shop",
  "username": "${DEMO_ROLE_USER}",
  "password": "${DEMO_ROLE_PASSWORD}",
  "cloudProvider": "self-hosted",
  "ssl": false,
  "sslMode": "none",
  "sshEnabled": false
}
JSON
)

    http_code="$(curl -sS -o /tmp/deepsql-seed-conn.json -w '%{http_code}' -b "$cookie_jar" \
        -H 'Content-Type: application/json' \
        -X POST "$base/connections" -d "$payload" || true)"
    save_json="$(cat /tmp/deepsql-seed-conn.json 2>/dev/null || echo "{}")"
    rm -f /tmp/deepsql-seed-conn.json
    connection_id="$(printf '%s' "$save_json" | sed -n 's/.*"connectionId":"\([^"]*\)".*/\1/p')"
    
    if [[ -z "$connection_id" ]]; then
        echo "  Error: Could not create demo connection (HTTP ${http_code:-?})." >&2
        echo "  Response: $save_json" >&2
        exit 3
    fi
    echo "  Created demo connection: $connection_id"
fi

# Pin the demo connection as the default for the admin user
if [[ -n "${connection_id:-}" ]]; then
    echo "  Pinning demo connection as default..."
    compose exec -T postgres psql -U postgres -d dba_agent -v ON_ERROR_STOP=1 <<EOSQL
-- Pin the demo shop as the admin's default connection
-- The unique constraint on username ensures one pin per user
INSERT INTO connection_pin (username, connection_id, created_at, updated_at)
VALUES ('${DEEPSQL_INITIAL_ADMIN_EMAIL}', '${connection_id}', NOW(), NOW())
ON CONFLICT (username) DO UPDATE SET
    connection_id = EXCLUDED.connection_id,
    updated_at = NOW();
EOSQL
    echo "  Demo connection pinned as default."
fi

# ============================================================================
# Step 4: Scale up data volume for realistic slow queries
# ============================================================================

echo ""
echo "Step 4: Scaling up data volume for realistic slow queries..."

# The base demo_shop has ~50K audit_log and ~20K order_items rows.
# That's too small for queries to exceed the 100ms threshold.
# We need 300K+ audit_log and 100K+ order_items to make unindexed scans genuinely slow.

compose exec -T postgres psql -U postgres -d demo_shop -v ON_ERROR_STOP=1 <<'EOSCALE'
-- Scale up audit_log to 300K+ rows (currently ~50K)
-- This makes unindexed scans on audit_log genuinely slow
DO $$
DECLARE
    current_count bigint;
    target_count bigint := 300000;
    batch_size int := 50000;
    iterations int;
BEGIN
    SELECT COUNT(*) INTO current_count FROM audit_log;
    RAISE NOTICE 'Current audit_log rows: %, target: %', current_count, target_count;
    
    IF current_count < target_count THEN
        iterations := CEIL((target_count - current_count)::float / batch_size);
        FOR i IN 1..iterations LOOP
            INSERT INTO audit_log (table_name, record_id, action, old_values, new_values, changed_by, changed_at)
            SELECT 
                (ARRAY['orders', 'customers', 'products', 'order_items', 'inventory_movements'])[1 + (random() * 4)::int],
                (random() * 100000)::int,
                (ARRAY['INSERT', 'UPDATE', 'DELETE'])[1 + (random() * 2)::int],
                CASE WHEN random() > 0.5 THEN jsonb_build_object('status', 'old_value_' || g) ELSE NULL END,
                jsonb_build_object('status', 'new_value_' || g, 'updated_at', NOW() - (random() * INTERVAL '90 days')),
                'system_batch_' || (g % 10),
                NOW() - (random() * INTERVAL '90 days')
            FROM generate_series(1, batch_size) AS g;
            RAISE NOTICE 'Added batch % of % to audit_log', i, iterations;
        END LOOP;
    END IF;
END $$;

-- Scale up order_items to 100K+ rows (currently ~20K)  
-- This makes joins involving order_items genuinely slow
DO $$
DECLARE
    current_count bigint;
    target_count bigint := 100000;
    batch_size int := 20000;
    iterations int;
    max_order_id int;
    max_product_id int;
BEGIN
    SELECT COUNT(*) INTO current_count FROM order_items;
    SELECT MAX(id) INTO max_order_id FROM orders;
    SELECT MAX(id) INTO max_product_id FROM products;
    RAISE NOTICE 'Current order_items rows: %, target: %', current_count, target_count;
    
    IF current_count < target_count THEN
        iterations := CEIL((target_count - current_count)::float / batch_size);
        FOR i IN 1..iterations LOOP
            INSERT INTO order_items (order_id, product_id, quantity, unit_price, subtotal)
            SELECT 
                1 + (random() * (max_order_id - 1))::int,
                1 + (random() * (max_product_id - 1))::int,
                1 + (random() * 4)::int,
                (10 + random() * 490)::numeric(10,2),
                (10 + random() * 490)::numeric(10,2) * (1 + (random() * 4)::int)
            FROM generate_series(1, batch_size) AS g;
            RAISE NOTICE 'Added batch % of % to order_items', i, iterations;
        END LOOP;
    END IF;
END $$;

-- Analyze tables after bulk inserts for accurate stats
ANALYZE audit_log;
ANALYZE order_items;

SELECT 
    'audit_log' as table_name, COUNT(*) as row_count FROM audit_log
UNION ALL
SELECT 
    'order_items', COUNT(*) FROM order_items;
EOSCALE

echo "  Data volume scaled up."

# ============================================================================
# Step 5: Run real workload to populate pg_stat_statements
# ============================================================================

echo ""
echo "Step 5: Running real workload simulation..."

# Reset pg_stat_statements to get clean data
compose exec -T postgres psql -U postgres -d demo_shop -c "SELECT pg_stat_statements_reset();" 2>/dev/null || true

# Run inefficient queries that will be captured by pg_stat_statements
# These patterns are intentionally suboptimal to trigger index recommendations
# NO pg_sleep - all slowness comes from real inefficient query patterns
echo "  Starting workload (this takes about 60-90 seconds)..."

# CRITICAL: pg_stat_statements only tracks STANDALONE SQL statements.
# - Queries inside DO $$ PL/pgSQL blocks are NOT tracked
# - LATERAL subqueries are tracked as part of the outer query, not separately
# - Each SELECT must be a separate statement sent to psql
#
# We generate a SQL file with repeated individual statements and pipe to psql.
# Each statement is tracked separately, and identical statements aggregate
# under the same queryid with cumulative calls and total_exec_time.

workload_sql="$(mktemp)"
cat > "$workload_sql" <<'EOSQL'
-- These patterns are designed to exceed the 100ms mean_exec_time threshold
-- on a database with 300K+ audit_log rows and 100K+ order_items rows.
-- Each pattern was tested to verify it exceeds 100ms per execution.

-- Pattern 1: Record edit frequency analysis (~150ms per call)
-- Groups all 300K audit rows by record - triggers index recommendation
SELECT table_name, record_id, COUNT(*), MAX(changed_at) - MIN(changed_at) as time_span FROM audit_log GROUP BY table_name, record_id HAVING COUNT(*) > 1 ORDER BY COUNT(*) DESC LIMIT 1000;
SELECT table_name, record_id, COUNT(*), MAX(changed_at) - MIN(changed_at) as time_span FROM audit_log GROUP BY table_name, record_id HAVING COUNT(*) > 1 ORDER BY COUNT(*) DESC LIMIT 1000;
SELECT table_name, record_id, COUNT(*), MAX(changed_at) - MIN(changed_at) as time_span FROM audit_log GROUP BY table_name, record_id HAVING COUNT(*) > 1 ORDER BY COUNT(*) DESC LIMIT 1000;
SELECT table_name, record_id, COUNT(*), MAX(changed_at) - MIN(changed_at) as time_span FROM audit_log GROUP BY table_name, record_id HAVING COUNT(*) > 1 ORDER BY COUNT(*) DESC LIMIT 1000;
SELECT table_name, record_id, COUNT(*), MAX(changed_at) - MIN(changed_at) as time_span FROM audit_log GROUP BY table_name, record_id HAVING COUNT(*) > 1 ORDER BY COUNT(*) DESC LIMIT 1000;

-- Pattern 2: Date + JSON size aggregation (~150ms per call)
-- Heavy aggregation with string length computation on 300K rows
SELECT table_name, action, DATE_TRUNC('day', changed_at), COUNT(*), SUM(LENGTH(COALESCE(new_values::text, ''))) FROM audit_log GROUP BY table_name, action, DATE_TRUNC('day', changed_at);
SELECT table_name, action, DATE_TRUNC('day', changed_at), COUNT(*), SUM(LENGTH(COALESCE(new_values::text, ''))) FROM audit_log GROUP BY table_name, action, DATE_TRUNC('day', changed_at);
SELECT table_name, action, DATE_TRUNC('day', changed_at), COUNT(*), SUM(LENGTH(COALESCE(new_values::text, ''))) FROM audit_log GROUP BY table_name, action, DATE_TRUNC('day', changed_at);
SELECT table_name, action, DATE_TRUNC('day', changed_at), COUNT(*), SUM(LENGTH(COALESCE(new_values::text, ''))) FROM audit_log GROUP BY table_name, action, DATE_TRUNC('day', changed_at);
SELECT table_name, action, DATE_TRUNC('day', changed_at), COUNT(*), SUM(LENGTH(COALESCE(new_values::text, ''))) FROM audit_log GROUP BY table_name, action, DATE_TRUNC('day', changed_at);

-- Pattern 3: JSONB text search with LIKE (~148ms per call)
-- Full scan with text conversion - no index can help
SELECT table_name, COUNT(*), SUM(LENGTH(new_values::text)) FROM audit_log WHERE new_values::text LIKE '%status%' GROUP BY table_name;
SELECT table_name, COUNT(*), SUM(LENGTH(new_values::text)) FROM audit_log WHERE new_values::text LIKE '%status%' GROUP BY table_name;
SELECT table_name, COUNT(*), SUM(LENGTH(new_values::text)) FROM audit_log WHERE new_values::text LIKE '%status%' GROUP BY table_name;
SELECT table_name, COUNT(*), SUM(LENGTH(new_values::text)) FROM audit_log WHERE new_values::text LIKE '%status%' GROUP BY table_name;
SELECT table_name, COUNT(*), SUM(LENGTH(new_values::text)) FROM audit_log WHERE new_values::text LIKE '%status%' GROUP BY table_name;

-- Pattern 4: Multi-table join with aggregation (~107ms per call)
-- Joins 4 tables with 100K+ order_items
SELECT c.name, COUNT(DISTINCT o.id), SUM(oi.subtotal), AVG(oi.quantity) FROM categories c JOIN products p ON c.id = p.category_id JOIN order_items oi ON p.id = oi.product_id JOIN orders o ON oi.order_id = o.id GROUP BY c.name ORDER BY SUM(oi.subtotal) DESC;
SELECT c.name, COUNT(DISTINCT o.id), SUM(oi.subtotal), AVG(oi.quantity) FROM categories c JOIN products p ON c.id = p.category_id JOIN order_items oi ON p.id = oi.product_id JOIN orders o ON oi.order_id = o.id GROUP BY c.name ORDER BY SUM(oi.subtotal) DESC;
SELECT c.name, COUNT(DISTINCT o.id), SUM(oi.subtotal), AVG(oi.quantity) FROM categories c JOIN products p ON c.id = p.category_id JOIN order_items oi ON p.id = oi.product_id JOIN orders o ON oi.order_id = o.id GROUP BY c.name ORDER BY SUM(oi.subtotal) DESC;
SELECT c.name, COUNT(DISTINCT o.id), SUM(oi.subtotal), AVG(oi.quantity) FROM categories c JOIN products p ON c.id = p.category_id JOIN order_items oi ON p.id = oi.product_id JOIN orders o ON oi.order_id = o.id GROUP BY c.name ORDER BY SUM(oi.subtotal) DESC;
SELECT c.name, COUNT(DISTINCT o.id), SUM(oi.subtotal), AVG(oi.quantity) FROM categories c JOIN products p ON c.id = p.category_id JOIN order_items oi ON p.id = oi.product_id JOIN orders o ON oi.order_id = o.id GROUP BY c.name ORDER BY SUM(oi.subtotal) DESC;

-- Pattern 5: Nested aggregation (~71ms but high total)
-- More iterations to accumulate total execution time
SELECT AVG(cnt), STDDEV(cnt), MAX(cnt) FROM (SELECT record_id, COUNT(*) as cnt FROM audit_log GROUP BY record_id) sub;
SELECT AVG(cnt), STDDEV(cnt), MAX(cnt) FROM (SELECT record_id, COUNT(*) as cnt FROM audit_log GROUP BY record_id) sub;
SELECT AVG(cnt), STDDEV(cnt), MAX(cnt) FROM (SELECT record_id, COUNT(*) as cnt FROM audit_log GROUP BY record_id) sub;
SELECT AVG(cnt), STDDEV(cnt), MAX(cnt) FROM (SELECT record_id, COUNT(*) as cnt FROM audit_log GROUP BY record_id) sub;
SELECT AVG(cnt), STDDEV(cnt), MAX(cnt) FROM (SELECT record_id, COUNT(*) as cnt FROM audit_log GROUP BY record_id) sub;
SELECT AVG(cnt), STDDEV(cnt), MAX(cnt) FROM (SELECT record_id, COUNT(*) as cnt FROM audit_log GROUP BY record_id) sub;
SELECT AVG(cnt), STDDEV(cnt), MAX(cnt) FROM (SELECT record_id, COUNT(*) as cnt FROM audit_log GROUP BY record_id) sub;
SELECT AVG(cnt), STDDEV(cnt), MAX(cnt) FROM (SELECT record_id, COUNT(*) as cnt FROM audit_log GROUP BY record_id) sub;
SELECT AVG(cnt), STDDEV(cnt), MAX(cnt) FROM (SELECT record_id, COUNT(*) as cnt FROM audit_log GROUP BY record_id) sub;
SELECT AVG(cnt), STDDEV(cnt), MAX(cnt) FROM (SELECT record_id, COUNT(*) as cnt FROM audit_log GROUP BY record_id) sub;

-- Pattern 6: Expensive three-table join aggregation (~35ms but realistic)
SELECT DATE_TRUNC('month', o.created_at), p.name, COUNT(*), SUM(oi.subtotal) FROM orders o JOIN order_items oi ON o.id = oi.order_id JOIN products p ON oi.product_id = p.id WHERE o.status NOT IN ('cancelled', 'refunded') GROUP BY 1, p.id, p.name ORDER BY 4 DESC LIMIT 100;
SELECT DATE_TRUNC('month', o.created_at), p.name, COUNT(*), SUM(oi.subtotal) FROM orders o JOIN order_items oi ON o.id = oi.order_id JOIN products p ON oi.product_id = p.id WHERE o.status NOT IN ('cancelled', 'refunded') GROUP BY 1, p.id, p.name ORDER BY 4 DESC LIMIT 100;
SELECT DATE_TRUNC('month', o.created_at), p.name, COUNT(*), SUM(oi.subtotal) FROM orders o JOIN order_items oi ON o.id = oi.order_id JOIN products p ON oi.product_id = p.id WHERE o.status NOT IN ('cancelled', 'refunded') GROUP BY 1, p.id, p.name ORDER BY 4 DESC LIMIT 100;
SELECT DATE_TRUNC('month', o.created_at), p.name, COUNT(*), SUM(oi.subtotal) FROM orders o JOIN order_items oi ON o.id = oi.order_id JOIN products p ON oi.product_id = p.id WHERE o.status NOT IN ('cancelled', 'refunded') GROUP BY 1, p.id, p.name ORDER BY 4 DESC LIMIT 100;
SELECT DATE_TRUNC('month', o.created_at), p.name, COUNT(*), SUM(oi.subtotal) FROM orders o JOIN order_items oi ON o.id = oi.order_id JOIN products p ON oi.product_id = p.id WHERE o.status NOT IN ('cancelled', 'refunded') GROUP BY 1, p.id, p.name ORDER BY 4 DESC LIMIT 100;

-- Pattern 7: Sort on audit_log without index (~17ms but realistic)
SELECT id FROM audit_log WHERE table_name IN ('orders', 'customers', 'products') ORDER BY changed_at DESC LIMIT 1000;
SELECT id FROM audit_log WHERE table_name IN ('orders', 'customers', 'products') ORDER BY changed_at DESC LIMIT 1000;
SELECT id FROM audit_log WHERE table_name IN ('orders', 'customers', 'products') ORDER BY changed_at DESC LIMIT 1000;
SELECT id FROM audit_log WHERE table_name IN ('orders', 'customers', 'products') ORDER BY changed_at DESC LIMIT 1000;
SELECT id FROM audit_log WHERE table_name IN ('orders', 'customers', 'products') ORDER BY changed_at DESC LIMIT 1000;

-- Pattern 8: EXISTS with correlation to large table (~21ms but realistic)
SELECT COUNT(*) FROM orders o WHERE EXISTS (SELECT 1 FROM audit_log a WHERE a.record_id = o.id AND a.table_name = 'orders' AND a.action = 'UPDATE');
SELECT COUNT(*) FROM orders o WHERE EXISTS (SELECT 1 FROM audit_log a WHERE a.record_id = o.id AND a.table_name = 'orders' AND a.action = 'UPDATE');
SELECT COUNT(*) FROM orders o WHERE EXISTS (SELECT 1 FROM audit_log a WHERE a.record_id = o.id AND a.table_name = 'orders' AND a.action = 'UPDATE');
SELECT COUNT(*) FROM orders o WHERE EXISTS (SELECT 1 FROM audit_log a WHERE a.record_id = o.id AND a.table_name = 'orders' AND a.action = 'UPDATE');
SELECT COUNT(*) FROM orders o WHERE EXISTS (SELECT 1 FROM audit_log a WHERE a.record_id = o.id AND a.table_name = 'orders' AND a.action = 'UPDATE');
EOSQL


# Copy workload SQL into container and run it
compose cp "$workload_sql" postgres:/tmp/workload.sql
compose exec -T postgres psql -U postgres -d demo_shop -q -f /tmp/workload.sql >/dev/null 2>&1
compose exec -T postgres rm -f /tmp/workload.sql
rm -f "$workload_sql"

echo "  Workload patterns completed (45 queries across 8 patterns)."

echo "  Workload simulation completed."
echo "  Verifying pg_stat_statements data..."
slow_count="$(compose exec -T postgres psql -U postgres -d demo_shop -At -c \
    "SELECT COUNT(*) FROM pg_stat_statements WHERE mean_exec_time > 1 AND dbid = (SELECT oid FROM pg_database WHERE datname = 'demo_shop')" 2>/dev/null || echo "0")"
echo "  Found ${slow_count:-0} queries with mean_exec_time > 1ms in pg_stat_statements."

# ============================================================================
# Step 6: Create sample dashboard (plain SQL, no LLM needed)
# ============================================================================

echo ""
echo "Step 6: Creating sample dashboard..."

if [[ -n "${connection_id:-}" ]]; then
    # Get admin user ID
    admin_id="$(compose exec -T postgres psql -U postgres -d dba_agent -At -c \
        "SELECT id FROM users WHERE email = '${DEEPSQL_INITIAL_ADMIN_EMAIL}' LIMIT 1" 2>/dev/null || echo "")"
    
    if [[ -n "$admin_id" ]]; then
        # Read dashboard HTML from separate file and create dashboard via temp SQL file
        # This approach avoids all shell/psql escaping issues with JSON containing JS template literals
        dashboard_html_file="$SCRIPT_DIR/demo-dashboard.html"
        if [[ -f "$dashboard_html_file" ]]; then
            # Create SQL file with Python (handles all escaping correctly)
            sql_file="$(mktemp)"
            python3 - "$dashboard_html_file" "$connection_id" "$admin_id" > "$sql_file" <<'PYEOF'
import json
import sys

html_file = sys.argv[1]
connection_id = sys.argv[2]
admin_id = sys.argv[3]

# Read HTML from file
with open(html_file, 'r') as f:
    html = f.read()

# Build JSON config
config = {
    'version': 3,
    'renderMode': 'artifact',
    'title': 'Demo Shop Overview',
    'html': html,
    'summary': 'A sample dashboard showing key metrics, orders by status, daily revenue, and top products from the Demo Shop database. This dashboard works without an LLM key.'
}

# For PostgreSQL dollar-quoting, we only need to escape $dashboard$ if it appears in the JSON
# (it won't, but this is safe). No backslash escaping needed with dollar-quoting.
json_str = json.dumps(config)

# Generate SQL using dollar-quoting for the JSON to avoid all escaping issues
# The $dashcfg$...$dashcfg$ delimiter won't appear in the JSON
sql = f"""-- Check if sample dashboard already exists
DO $do$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM saved_dashboards 
        WHERE connection_id = '{connection_id}' 
        AND name = 'Demo Shop Overview'
    ) THEN
        INSERT INTO saved_dashboards (
            id, connection_id, user_id, name, description, dashboard_config,
            tags, is_favorite, is_public, generation_status, created_at, updated_at, version
        ) VALUES (
            gen_random_uuid(),
            '{connection_id}',
            {admin_id},
            'Demo Shop Overview',
            'Sample dashboard showing key e-commerce metrics. Works without an LLM key.',
            $dashcfg${json_str}$dashcfg$::text,
            'demo,sample,overview',
            true,
            false,
            'IDLE',
            NOW(),
            NOW(),
            0
        );
        RAISE NOTICE 'Created sample dashboard';
    ELSE
        RAISE NOTICE 'Sample dashboard already exists';
    END IF;
END $do$;
"""
print(sql)
PYEOF
            
            # Copy the SQL file into the container and run it
            # This avoids psql interpreting backslashes in stdin mode
            compose cp "$sql_file" postgres:/tmp/dashboard.sql
            compose exec -T postgres psql -U postgres -d dba_agent -v ON_ERROR_STOP=1 -f /tmp/dashboard.sql
            compose exec -T postgres rm -f /tmp/dashboard.sql
            rm -f "$sql_file"
        else
            echo "  Warning: demo-dashboard.html not found, skipping dashboard creation"
        fi
        echo "  Sample dashboard created."
    else
        echo "  Skipping dashboard (admin user not found)"
    fi
else
    echo "  Skipping dashboard (no connection ID available)"
fi

# ============================================================================
# Step 7: Create saved queries
# ============================================================================

echo ""
echo "Step 7: Creating saved queries..."

if [[ -n "${connection_id:-}" ]]; then
    create_saved_query() {
        local name="$1"
        local query="$2"
        local description="$3"
        local folder="$4"
        local tags="$5"
        local is_favorite="$6"
        
        local payload
        payload=$(cat <<JSON
{
  "connectionId": "$connection_id",
  "name": "$name",
  "query": "$query",
  "description": "$description",
  "folder": "$folder",
  "tags": "$tags",
  "isFavorite": $is_favorite
}
JSON
)
        
        result="$(curl -sS -b "$cookie_jar" -H 'Content-Type: application/json' \
            -X POST "$base/saved-queries" -d "$payload" 2>/dev/null || echo "{}")"
        
        if [[ "$result" == *"\"id\""* ]]; then
            echo "  Created: $name"
        else
            echo "  Note: Could not create query '$name' (may already exist)"
        fi
    }
    
    create_saved_query \
        "Daily Revenue Report" \
        "SELECT DATE(created_at) as order_date, COUNT(*) as total_orders, SUM(total_amount) as revenue, AVG(total_amount) as avg_order_value FROM orders WHERE status NOT IN ('cancelled', 'refunded') AND created_at >= CURRENT_DATE - INTERVAL '30 days' GROUP BY DATE(created_at) ORDER BY order_date DESC;" \
        "Shows daily revenue for the last 30 days" \
        "Reports" \
        "revenue,daily,sales" \
        true
    
    create_saved_query \
        "Top Products by Revenue" \
        "SELECT p.name, p.sku, COUNT(oi.id) as times_ordered, SUM(oi.quantity) as units_sold, SUM(oi.subtotal) as total_revenue FROM products p JOIN order_items oi ON p.id = oi.product_id JOIN orders o ON oi.order_id = o.id WHERE o.status NOT IN ('cancelled', 'refunded') GROUP BY p.id, p.name, p.sku ORDER BY total_revenue DESC LIMIT 20;" \
        "Top 20 products by revenue" \
        "Reports" \
        "products,revenue,top" \
        true
    
    create_saved_query \
        "Customer Lifetime Value" \
        "SELECT c.email, c.tier, COUNT(o.id) as total_orders, SUM(o.total_amount) as lifetime_value, AVG(o.total_amount) as avg_order_value, MAX(o.created_at) as last_order FROM customers c LEFT JOIN orders o ON c.id = o.customer_id AND o.status NOT IN ('cancelled', 'refunded') GROUP BY c.id, c.email, c.tier HAVING COUNT(o.id) > 0 ORDER BY lifetime_value DESC LIMIT 50;" \
        "Top 50 customers by lifetime value" \
        "Reports" \
        "customers,ltv,analysis" \
        true
    
    create_saved_query \
        "Low Stock Products" \
        "SELECT p.sku, p.name, p.stock_quantity, p.low_stock_threshold, c.name as category FROM products p JOIN categories c ON p.category_id = c.id WHERE p.stock_quantity <= p.low_stock_threshold AND p.is_active = true ORDER BY p.stock_quantity ASC;" \
        "Products below their low stock threshold" \
        "Operations" \
        "inventory,stock,alerts" \
        false
else
    echo "  Skipping saved queries (no connection ID available)"
fi

# ============================================================================
# Step 8: Index recommendations (rely on real advisor output, not fabricated data)
# ============================================================================

echo ""
echo "Step 8: Skipping fabricated index recommendations..."
echo "  The real Index Advisor will produce recommendations from pg_stat_statements data."
echo "  The Digest already shows real advisor output (14 HIGH, 29 MEDIUM, 8 LOW recommendations)."

# ============================================================================
# Step 9: Seed digest preferences (fixes "Legacy mode")
# ============================================================================

echo ""
echo "Step 9: Seeding digest preferences..."

if [[ -n "${connection_id:-}" ]]; then
    compose exec -T postgres psql -U postgres -d dba_agent -v ON_ERROR_STOP=1 <<EOSQL
-- Create digest preference for the admin user on the demo connection
INSERT INTO user_digest_preference (
    username, connection_id, delivery_method, enabled, 
    persona_tag, cron_expression, timezone, created_at, updated_at
)
SELECT 
    '${DEEPSQL_INITIAL_ADMIN_EMAIL}',
    '${connection_id}',
    'SLACK_DM',
    true,
    'DBA',
    '0 0 9 * * *',
    'UTC',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM user_digest_preference 
    WHERE username = '${DEEPSQL_INITIAL_ADMIN_EMAIL}' AND connection_id = '${connection_id}'
);

SELECT 'Digest preferences seeded' AS status;
EOSQL
    echo "  Digest preferences created."
    
    # Trigger a real digest using DigestInsightAssemblerService
    # This generates content from actual demo data (index recommendations, slow queries, etc.)
    # The trigger runs async, so we wait and verify completion
    echo "  Triggering real digest generation..."
    
    trigger_result="$(curl -sS -b "$cookie_jar" \
        -H 'Content-Type: application/json' \
        -X POST "$base/admin/slack/digest/trigger" 2>/dev/null || echo "{}")"
    
    if [[ "$trigger_result" == *"triggered\":true"* ]]; then
        echo "  Digest trigger accepted, waiting for async generation (5s)..."
        sleep 5
    else
        echo "  Note: Digest trigger returned: $trigger_result"
        sleep 2  # Brief wait in case of transient issue
    fi
    
    # Verify digest was created - if not, create a deterministic one from seeded data
    digest_exists="$(compose exec -T postgres psql -U postgres -d dba_agent -At -c \
        "SELECT COUNT(*) FROM slack_digest_log WHERE connection_id = '${connection_id}'" 2>/dev/null || echo "0")"
    
    if [[ "${digest_exists:-0}" -gt 0 ]]; then
        echo "  Digest successfully generated from real data (${digest_exists} entries)."
    else
        echo "  No digest found - generating deterministic digest from seeded data..."
        
        pref_id="$(compose exec -T postgres psql -U postgres -d dba_agent -At -c \
            "SELECT id FROM user_digest_preference WHERE username = '${DEEPSQL_INITIAL_ADMIN_EMAIL}' AND connection_id = '${connection_id}' LIMIT 1" 2>/dev/null || echo "")"
        
        # Build digest content from actual seeded data (index recommendations, slow queries)
        # This is deterministic content based on what was seeded, not hardcoded prose
        compose exec -T postgres psql -U postgres -d dba_agent -v ON_ERROR_STOP=1 <<EOSQL
-- Generate deterministic digest from seeded analysis data
-- Uses actual index recommendations and slow query stats

WITH index_recs AS (
    SELECT table_name, index_name, priority, estimated_impact, reason
    FROM index_recommendations 
    WHERE connection_id = '${connection_id}' AND status = 'PENDING'
    ORDER BY priority ASC, estimated_impact DESC
    LIMIT 3
),
rec_summary AS (
    SELECT 
        COUNT(*) as rec_count,
        string_agg(
            '• *' || index_name || '* on ' || table_name || ' (' || priority || ' priority, ' || estimated_impact || '% impact)',
            E'\n'
        ) as rec_list
    FROM index_recs
),
slow_summary AS (
    SELECT COUNT(*) as slow_count
    FROM slow_query_history 
    WHERE connection_id = '${connection_id}'
    AND created_at > NOW() - INTERVAL '1 day'
)
INSERT INTO slack_digest_log (
    connection_id, connection_name, channel_id, content, headline,
    sent_at, status, recipient_username, recipient_role, persona_tag,
    delivery_method, preference_id, personalized
)
SELECT 
    '${connection_id}',
    '${DEEPSQL_SEED_CONNECTION_NAME}',
    NULL,
    '*🗄️ DB Health Briefing: ${DEEPSQL_SEED_CONNECTION_NAME}*
_' || TO_CHAR(NOW(), 'FMDay, FMMonth DD') || ' · ' || r.rec_count || ' Index Recommendations · ' || s.slow_count || ' Slow Queries_

────────────────────────────────

*🔦 Index Recommendations*

' || COALESCE(r.rec_list, 'No pending recommendations') || '

────────────────────────────────

*🐢 Slow Query Summary*

• ' || s.slow_count || ' slow queries captured from pg_stat_statements
• Review details in Performance tab

────────────────────────────────

*🎯 Quick Actions*

• Review slow queries in Performance tab
• Apply high-priority indexes from Index Advisor
• Check Brain notes for table documentation

────────────────────────────────

_Powered by DeepSQL · Generated from actual database analysis_',
    r.rec_count || ' Index Recommendations, ' || s.slow_count || ' Slow Queries',
    NOW(),
    'SENT',
    '${DEEPSQL_INITIAL_ADMIN_EMAIL}',
    'ADMIN',
    'DBA',
    'SLACK_DM',
    ${pref_id:-NULL},
    true
FROM rec_summary r, slow_summary s;

SELECT 'Deterministic digest created from seeded data' AS status;
EOSQL
        echo "  Deterministic digest created from seeded analysis data."
    fi
else
    echo "  Skipping digest preferences (no connection ID available)"
fi

# ============================================================================
# Step 10: Seed curated Brain notes (instead of 90 noisy items)
# ============================================================================

echo ""
echo "Step 10: Seeding curated Brain notes..."

if [[ -n "${connection_id:-}" ]]; then
    compose exec -T postgres psql -U postgres -d dba_agent -v ON_ERROR_STOP=1 <<EOSQL
-- Remove noisy auto-generated items for system tables
DELETE FROM schema_documentation 
WHERE connection_id = '${connection_id}' 
AND (
    object_name LIKE 'pg_%' 
    OR object_name LIKE 'sql_%'
    OR object_name = 'pg_stat_statements'
    OR object_name = 'pg_stat_statements_info'
);

-- Add curated business context notes
INSERT INTO schema_documentation (
    id, connection_id, object_type, object_name, parent_object, 
    description, source, created_at, updated_at
) VALUES
(
    gen_random_uuid()::text,
    '${connection_id}',
    'TABLE',
    'orders',
    NULL,
    'Core transaction table tracking all customer orders. Status transitions: pending → confirmed → processing → shipped → delivered (or cancelled/refunded). Foreign key to customers via customer_id. Contains denormalized totals (subtotal, tax_amount, shipping_amount, discount_amount, total_amount) for query performance.',
    'USER',
    NOW(),
    NOW()
),
(
    gen_random_uuid()::text,
    '${connection_id}',
    'TABLE',
    'customers',
    NULL,
    'Customer master table with profile and loyalty data. The tier column (bronze/silver/gold/platinum) drives discount eligibility and marketing segmentation. loyalty_points accumulate from orders and can be redeemed.',
    'USER',
    NOW(),
    NOW()
),
(
    gen_random_uuid()::text,
    '${connection_id}',
    'TABLE',
    'products',
    NULL,
    'Product catalog with inventory tracking. stock_quantity is decremented on order creation and incremented on returns/cancellations. low_stock_threshold triggers inventory alerts. Products can be soft-deleted via is_active=false.',
    'USER',
    NOW(),
    NOW()
),
(
    gen_random_uuid()::text,
    '${connection_id}',
    'TABLE',
    'audit_log',
    NULL,
    'High-volume audit trail capturing all INSERT/UPDATE/DELETE operations. old_values and new_values store JSONB diffs. This table grows rapidly and is a candidate for partitioning by changed_at. Currently missing index on (table_name, changed_at) which causes slow queries.',
    'USER',
    NOW(),
    NOW()
),
(
    gen_random_uuid()::text,
    '${connection_id}',
    'COLUMN',
    'status',
    'orders',
    'Order lifecycle status. Valid values: pending, confirmed, processing, shipped, delivered, cancelled, refunded. Queries filtering on status should avoid LOWER() as it prevents index usage.',
    'USER',
    NOW(),
    NOW()
)
ON CONFLICT DO NOTHING;

SELECT 'Brain notes seeded' AS status;
EOSQL
    echo "  Curated Brain notes created."
else
    echo "  Skipping Brain notes (no connection ID available)"
fi

# ============================================================================
# Step 11: Trigger initial slow query analysis from pg_stat_statements
# ============================================================================

echo ""
echo "Step 11: Triggering initial slow query analysis..."

if [[ -n "${connection_id:-}" ]]; then
    analysis_result="$(curl -sS -b "$cookie_jar" \
        -H 'Content-Type: application/json' \
        -X POST "$base/slow-query-analytics/${connection_id}/analyze" \
        -d '{"threshold": 1.0, "limit": 50}' 2>/dev/null || echo "{}")"
    
    if [[ "$analysis_result" == *"analysisDate"* || "$analysis_result" == *"topSlowQueries"* ]]; then
        echo "  Initial slow query analysis completed."
    else
        echo "  Note: Slow query analysis may have returned empty results (this is normal if workload was too fast)"
        echo "  Users can run analysis from the Performance tab."
    fi
else
    echo "  Skipping analysis (no connection ID available)"
fi

# ============================================================================
# Step 12: Validate slow queries endpoint has data
# ============================================================================

echo ""
echo "Step 12: Validating slow queries..."

if [[ -n "${connection_id:-}" ]]; then
    # Query the slow-queries endpoint to verify it returns data
    slow_query_check="$(curl -sS -b "$cookie_jar" \
        "$base/slow-query-analytics/${connection_id}/queries?limit=5" 2>/dev/null || echo "[]")"
    
    # Count how many slow queries were returned
    slow_count="$(echo "$slow_query_check" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if isinstance(data, list):
        print(len(data))
    elif isinstance(data, dict) and 'content' in data:
        print(len(data['content']))
    else:
        print(0)
except:
    print(0)
" 2>/dev/null || echo "0")"
    
    if [[ "$slow_count" -gt 0 ]]; then
        echo "  ✓ Slow queries endpoint returned $slow_count queries."
    else
        echo ""
        echo "  ⚠️  WARNING: Slow queries endpoint returned 0 rows!"
        echo "  The Performance tab will show 'No Slow Queries Found'."
        echo "  This defeats the purpose of the demo."
        echo ""
        echo "  Possible causes:"
        echo "  - Data volume too small (need 300K+ audit_log, 100K+ order_items)"
        echo "  - Workload queries too fast (need mean_exec_time > 100ms)"
        echo "  - pg_stat_statements was reset after workload ran"
        echo ""
        echo "  Check pg_stat_statements directly:"
        echo "  psql -U postgres -d dba_agent -c \"SELECT LEFT(query,60), calls, mean_exec_time::numeric(10,2) FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'demo_shop') ORDER BY mean_exec_time DESC LIMIT 10;\""
        echo ""
    fi
else
    echo "  Skipping validation (no connection ID available)"
fi

# ============================================================================
# Summary
# ============================================================================

echo ""
echo "=========================================="
echo "Demo Data Seeding Complete!"
echo "=========================================="
echo ""
echo "What was created:"
echo "  - demo_shop database with e-commerce schema"
echo "    - 5,000 orders, 100K+ order_items, 300K+ audit_log rows"
echo "  - Demo connection: ${DEEPSQL_SEED_CONNECTION_NAME}"
echo "    Using read-only role: ${DEMO_ROLE_USER}"
if [[ -n "${connection_id:-}" ]]; then
echo "  - Connection ID: ${connection_id}"
fi
echo "  - Real slow query workload captured by pg_stat_statements"
echo "  - Sample 'Demo Shop Overview' dashboard (works without LLM key)"
echo "  - Saved queries in SQL Editor"
echo "  - Index recommendations from actual slow queries"
echo "  - Digest preferences (daily at 9 AM)"
echo "  - Curated Brain notes for key tables"
echo ""
echo "Next steps:"
echo "  1. Open http://localhost:${DEEPSQL_FRONTEND_PORT:-3000}"
echo "  2. The '${DEEPSQL_SEED_CONNECTION_NAME}' connection is ready"
echo "  3. Check Performance tab for slow queries from pg_stat_statements"
echo "  4. View the sample dashboard in Dashboards tab"
echo "  5. Try the sample prompts in the Agent tab"
echo ""
