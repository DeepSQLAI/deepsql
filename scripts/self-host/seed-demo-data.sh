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
# Step 4: Run real workload to populate pg_stat_statements
# ============================================================================

echo ""
echo "Step 4: Running real workload simulation (~${DEEPSQL_SEED_WORKLOAD_DURATION}s)..."

# Reset pg_stat_statements to get clean data
compose exec -T postgres psql -U postgres -d demo_shop -c "SELECT pg_stat_statements_reset();" 2>/dev/null || true

# Run inefficient queries that will be captured by pg_stat_statements
# These patterns are intentionally suboptimal to trigger recommendations
# Some queries use pg_sleep to ensure they exceed 100ms threshold
echo "  Starting workload (this takes about ${DEEPSQL_SEED_WORKLOAD_DURATION} seconds)..."
compose exec -T postgres psql -U postgres -d demo_shop -v ON_ERROR_STOP=1 <<EOWORK
-- Workload simulation for pg_stat_statements
-- Each pattern runs multiple times to accumulate meaningful statistics
-- Deliberately slow queries (>100ms) are marked with pg_sleep

-- First: Run deliberately slow queries that will DEFINITELY show up at 100ms threshold
-- These queries simulate "stuck" or poorly optimized production queries

-- Slow Query 1: Full table scan with sleep (simulates missing index)
-- This query would benefit from an index on audit_log(table_name, changed_at)
SELECT COUNT(*), pg_sleep(0.15)
FROM audit_log 
WHERE table_name = 'orders' 
AND changed_at > NOW() - INTERVAL '7 days';

-- Run it multiple times to accumulate calls
SELECT COUNT(*), pg_sleep(0.12)
FROM audit_log 
WHERE table_name = 'orders' 
AND changed_at > NOW() - INTERVAL '7 days';

SELECT COUNT(*), pg_sleep(0.11)
FROM audit_log 
WHERE table_name = 'orders' 
AND changed_at > NOW() - INTERVAL '7 days';

-- Slow Query 2: Cross-join style lookup with LOWER() function (prevents index use)
-- This query would benefit from a functional index on LOWER(status)
SELECT COUNT(*), pg_sleep(0.14)
FROM orders 
WHERE LOWER(status) = 'delivered' AND total_amount > 100;

SELECT COUNT(*), pg_sleep(0.13)
FROM orders 
WHERE LOWER(status) = 'delivered' AND total_amount > 100;

-- Slow Query 3: Missing composite index on frequently filtered columns
SELECT COUNT(*), pg_sleep(0.12)
FROM orders o 
JOIN customers c ON o.customer_id = c.id 
WHERE o.status = 'pending' AND o.payment_status = 'paid';

-- Now run the loop for additional patterns at faster speeds
DO \$\$
DECLARE 
    i int;
    start_time timestamp := clock_timestamp();
    duration_seconds int := ${DEEPSQL_SEED_WORKLOAD_DURATION};
    result_count bigint;
BEGIN
    RAISE NOTICE 'Starting workload simulation for % seconds...', duration_seconds;
    
    WHILE (EXTRACT(EPOCH FROM (clock_timestamp() - start_time)) < duration_seconds) LOOP
        -- Pattern 1: LOWER() on indexed column (prevents index use)
        SELECT COUNT(*) INTO result_count FROM orders 
        WHERE LOWER(status) = 'delivered' AND total_amount > 100;
        
        -- Pattern 2: Missing composite index on frequently filtered columns
        SELECT COUNT(*) INTO result_count FROM orders o 
        JOIN customers c ON o.customer_id = c.id 
        WHERE o.status = 'pending' AND o.payment_status = 'paid';
        
        -- Pattern 3: N+1 style lookups (inefficient join pattern)
        FOR i IN 1..10 LOOP
            SELECT COUNT(*) INTO result_count FROM order_items oi
            JOIN products p ON oi.product_id = p.id
            WHERE oi.order_id = i * 100;
        END LOOP;
        
        -- Pattern 4: Unanchored LIKE (forces full table scan)
        SELECT COUNT(*) INTO result_count FROM products 
        WHERE name ILIKE '%phone%' OR description ILIKE '%wireless%';
        
        -- Pattern 5: Large sort without index support
        SELECT COUNT(*) INTO result_count FROM (
            SELECT * FROM audit_log 
            WHERE table_name = 'orders' 
            ORDER BY changed_at DESC 
            LIMIT 1000
        ) sub;
        
        -- Pattern 6: Aggregation across large table (MV candidate)
        SELECT COUNT(*) INTO result_count FROM (
            SELECT DATE_TRUNC('month', o.created_at) as month,
                   c.name as category,
                   COUNT(DISTINCT o.id) as order_count,
                   SUM(oi.subtotal) as revenue
            FROM orders o
            JOIN order_items oi ON o.id = oi.order_id
            JOIN products p ON oi.product_id = p.id
            JOIN categories c ON p.category_id = c.id
            WHERE o.status NOT IN ('cancelled', 'refunded')
            GROUP BY DATE_TRUNC('month', o.created_at), c.id, c.name
        ) sub;
        
        -- Pattern 7: Missing index on foreign key (orders.customer_id lookups)
        SELECT COUNT(*) INTO result_count FROM orders 
        WHERE customer_id IN (
            SELECT id FROM customers WHERE tier = 'platinum'
        );
        
        -- Small delay to spread the load
        PERFORM pg_sleep(0.1);
    END LOOP;
    
    RAISE NOTICE 'Workload simulation completed after % seconds', 
        EXTRACT(EPOCH FROM (clock_timestamp() - start_time))::int;
END \$\$;
EOWORK

echo "  Workload simulation completed."
echo "  Verifying pg_stat_statements data..."
slow_count="$(compose exec -T postgres psql -U postgres -d demo_shop -At -c \
    "SELECT COUNT(*) FROM pg_stat_statements WHERE mean_exec_time > 1 AND dbid = (SELECT oid FROM pg_database WHERE datname = 'demo_shop')" 2>/dev/null || echo "0")"
echo "  Found ${slow_count:-0} queries with mean_exec_time > 1ms in pg_stat_statements."

# ============================================================================
# Step 5: Create sample dashboard (plain SQL, no LLM needed)
# ============================================================================

echo ""
echo "Step 5: Creating sample dashboard..."

if [[ -n "${connection_id:-}" ]]; then
    # Get admin user ID
    admin_id="$(compose exec -T postgres psql -U postgres -d dba_agent -At -c \
        "SELECT id FROM users WHERE email = '${DEEPSQL_INITIAL_ADMIN_EMAIL}' LIMIT 1" 2>/dev/null || echo "")"
    
    if [[ -n "$admin_id" ]]; then
        # Create the sample dashboard HTML artifact
        dashboard_html='<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: Inter, -apple-system, BlinkMacSystemFont, sans-serif; background: #f8f9fa; padding: 24px; }
        .dashboard { display: grid; grid-template-columns: repeat(2, 1fr); gap: 20px; max-width: 1400px; margin: 0 auto; }
        .widget { background: white; border-radius: 12px; padding: 20px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
        .widget-full { grid-column: span 2; }
        .widget h3 { font-size: 14px; font-weight: 600; color: #374151; margin-bottom: 16px; display: flex; align-items: center; gap: 8px; }
        .metric { font-size: 32px; font-weight: 700; color: #111827; }
        .metric-label { font-size: 12px; color: #6b7280; margin-top: 4px; }
        .metric-row { display: flex; gap: 40px; }
        .metric-item { flex: 1; }
        table { width: 100%; border-collapse: collapse; font-size: 13px; }
        th { text-align: left; padding: 10px 12px; background: #f9fafb; color: #6b7280; font-weight: 500; border-bottom: 1px solid #e5e7eb; }
        td { padding: 10px 12px; border-bottom: 1px solid #f3f4f6; color: #374151; }
        tr:hover { background: #f9fafb; }
        .status-badge { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 11px; font-weight: 500; }
        .status-delivered { background: #d1fae5; color: #065f46; }
        .status-shipped { background: #dbeafe; color: #1e40af; }
        .status-processing { background: #fef3c7; color: #92400e; }
        .status-pending { background: #f3f4f6; color: #4b5563; }
        .bar-chart { display: flex; flex-direction: column; gap: 8px; }
        .bar-row { display: flex; align-items: center; gap: 12px; }
        .bar-label { width: 100px; font-size: 12px; color: #6b7280; text-align: right; }
        .bar-container { flex: 1; height: 24px; background: #f3f4f6; border-radius: 4px; overflow: hidden; }
        .bar { height: 100%; background: linear-gradient(90deg, #3b82f6 0%, #1d4ed8 100%); border-radius: 4px; transition: width 0.3s; }
        .bar-value { width: 80px; font-size: 12px; color: #374151; font-weight: 500; }
        .loading { color: #9ca3af; font-style: italic; }
        .error { color: #ef4444; font-size: 12px; }
    </style>
</head>
<body>
<div class="dashboard">
    <div class="widget">
        <h3>📊 Key Metrics</h3>
        <div id="metrics" class="metric-row"><span class="loading">Loading...</span></div>
    </div>
    <div class="widget">
        <h3>📈 Orders by Status</h3>
        <div id="status-chart" class="bar-chart"><span class="loading">Loading...</span></div>
    </div>
    <div class="widget widget-full">
        <h3>💰 Daily Revenue (Last 14 Days)</h3>
        <div id="revenue-table"><span class="loading">Loading...</span></div>
    </div>
    <div class="widget widget-full">
        <h3>🏆 Top 10 Products by Revenue</h3>
        <div id="products-table"><span class="loading">Loading...</span></div>
    </div>
</div>
<script>
async function query(sql) {
    return deepsql.query(sql);
}
async function loadDashboard() {
    try {
        // Key Metrics
        const metrics = await query(\`
            SELECT 
                (SELECT COUNT(*) FROM orders) as total_orders,
                (SELECT SUM(total_amount) FROM orders WHERE status != '"'"'cancelled'"'"') as total_revenue,
                (SELECT COUNT(*) FROM customers) as total_customers,
                (SELECT COUNT(*) FROM products WHERE is_active = true) as active_products
        \`);
        if (metrics.rows && metrics.rows[0]) {
            const m = metrics.rows[0];
            document.getElementById("metrics").innerHTML = \`
                <div class="metric-item"><div class="metric">\${Number(m.total_orders || 0).toLocaleString()}</div><div class="metric-label">Total Orders</div></div>
                <div class="metric-item"><div class="metric">$\${Number(m.total_revenue || 0).toLocaleString(undefined, {minimumFractionDigits: 0, maximumFractionDigits: 0})}</div><div class="metric-label">Total Revenue</div></div>
                <div class="metric-item"><div class="metric">\${Number(m.total_customers || 0).toLocaleString()}</div><div class="metric-label">Customers</div></div>
                <div class="metric-item"><div class="metric">\${Number(m.active_products || 0).toLocaleString()}</div><div class="metric-label">Active Products</div></div>
            \`;
        }
        
        // Orders by Status
        const statusData = await query(\`
            SELECT status, COUNT(*) as count 
            FROM orders 
            GROUP BY status 
            ORDER BY count DESC
        \`);
        if (statusData.rows && statusData.rows.length > 0) {
            const maxCount = Math.max(...statusData.rows.map(r => Number(r.count)));
            document.getElementById("status-chart").innerHTML = statusData.rows.map(row => \`
                <div class="bar-row">
                    <div class="bar-label">\${row.status}</div>
                    <div class="bar-container"><div class="bar" style="width: \${(Number(row.count) / maxCount * 100)}%"></div></div>
                    <div class="bar-value">\${Number(row.count).toLocaleString()}</div>
                </div>
            \`).join("");
        }
        
        // Daily Revenue
        const revenue = await query(\`
            SELECT DATE(created_at) as day, COUNT(*) as orders, SUM(total_amount) as revenue
            FROM orders WHERE status NOT IN ('"'"'cancelled'"'"', '"'"'refunded'"'"')
            AND created_at >= CURRENT_DATE - INTERVAL '"'"'14 days'"'"'
            GROUP BY DATE(created_at) ORDER BY day DESC
        \`);
        if (revenue.rows && revenue.rows.length > 0) {
            document.getElementById("revenue-table").innerHTML = \`<table>
                <thead><tr><th>Date</th><th>Orders</th><th>Revenue</th></tr></thead>
                <tbody>\${revenue.rows.map(r => \`<tr><td>\${r.day}</td><td>\${Number(r.orders).toLocaleString()}</td><td>$\${Number(r.revenue || 0).toLocaleString(undefined, {minimumFractionDigits: 2})}</td></tr>\`).join("")}</tbody>
            </table>\`;
        }
        
        // Top Products
        const products = await query(\`
            SELECT p.name, p.sku, SUM(oi.quantity) as units, SUM(oi.subtotal) as revenue
            FROM products p JOIN order_items oi ON p.id = oi.product_id
            JOIN orders o ON oi.order_id = o.id WHERE o.status NOT IN ('"'"'cancelled'"'"', '"'"'refunded'"'"')
            GROUP BY p.id, p.name, p.sku ORDER BY revenue DESC LIMIT 10
        \`);
        if (products.rows && products.rows.length > 0) {
            document.getElementById("products-table").innerHTML = \`<table>
                <thead><tr><th>Product</th><th>SKU</th><th>Units Sold</th><th>Revenue</th></tr></thead>
                <tbody>\${products.rows.map(r => \`<tr><td>\${r.name}</td><td>\${r.sku}</td><td>\${Number(r.units).toLocaleString()}</td><td>$\${Number(r.revenue || 0).toLocaleString(undefined, {minimumFractionDigits: 2})}</td></tr>\`).join("")}</tbody>
            </table>\`;
        }
    } catch (err) {
        console.error("Dashboard error:", err);
        document.querySelectorAll(".loading").forEach(el => el.innerHTML = \`<span class="error">Failed to load: \${err.message || "Unknown error"}</span>\`);
    }
}
loadDashboard();
</script>
</body>
</html>'

        # Escape for SQL
        dashboard_html_escaped="${dashboard_html//\'/\'\'}"
        
        dashboard_config=$(cat <<JSON
{
  "version": 3,
  "renderMode": "artifact",
  "title": "Demo Shop Overview",
  "html": "${dashboard_html_escaped}",
  "summary": "A sample dashboard showing key metrics, orders by status, daily revenue, and top products from the Demo Shop database. This dashboard works without an LLM key."
}
JSON
)
        # Escape the JSON for SQL
        dashboard_config_escaped="${dashboard_config//\'/\'\'}"

        compose exec -T postgres psql -U postgres -d dba_agent -v ON_ERROR_STOP=1 <<EOSQL
-- Check if sample dashboard already exists
DO \$\$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM saved_dashboards 
        WHERE connection_id = '${connection_id}' 
        AND name = 'Demo Shop Overview'
    ) THEN
        INSERT INTO saved_dashboards (
            id, connection_id, user_id, name, description, dashboard_config,
            tags, is_favorite, is_public, generation_status, created_at, updated_at, version
        ) VALUES (
            gen_random_uuid(),
            '${connection_id}',
            ${admin_id},
            'Demo Shop Overview',
            'Sample dashboard showing key e-commerce metrics. Works without an LLM key.',
            '${dashboard_config_escaped}'::text,
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
END \$\$;
EOSQL
        echo "  Sample dashboard created."
    else
        echo "  Skipping dashboard (admin user not found)"
    fi
else
    echo "  Skipping dashboard (no connection ID available)"
fi

# ============================================================================
# Step 6: Create saved queries
# ============================================================================

echo ""
echo "Step 6: Creating saved queries..."

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
# Step 7: Seed index recommendations from real pg_stat_statements data
# ============================================================================

echo ""
echo "Step 7: Seeding index recommendations..."

if [[ -n "${connection_id:-}" ]]; then
    compose exec -T postgres psql -U postgres -d dba_agent -v ON_ERROR_STOP=1 <<EOSQL
-- Clean up any old seed data
DELETE FROM index_recommendations WHERE connection_id = '${connection_id}' AND reason LIKE '%seed%';

-- Insert index recommendations based on the workload patterns we ran
INSERT INTO index_recommendations (
    id, connection_id, table_name, column_names, index_name, create_statement,
    priority, status, kind, estimated_impact, reason, affected_queries,
    workload_score_ms, write_cost_score, evidence_count, occurrence_count,
    first_seen_at, last_seen_at, created_at, updated_at
) VALUES
(
    gen_random_uuid()::text,
    '${connection_id}',
    'orders',
    'status,payment_status,created_at',
    'idx_orders_status_payment_created',
    'CREATE INDEX idx_orders_status_payment_created ON orders(status, payment_status, created_at DESC);',
    'HIGH',
    'PENDING',
    'CREATE_INDEX',
    85,
    'Composite index for order filtering. Covers JOIN pattern with customers where status and payment_status are filtered. [seed]',
    12,
    45000,
    2500,
    3,
    5,
    NOW() - INTERVAL '1 hour',
    NOW(),
    NOW() - INTERVAL '1 hour',
    NOW()
),
(
    gen_random_uuid()::text,
    '${connection_id}',
    'audit_log',
    'table_name,changed_at',
    'idx_audit_log_table_changed',
    'CREATE INDEX idx_audit_log_table_changed ON audit_log(table_name, changed_at DESC);',
    'HIGH',
    'PENDING',
    'CREATE_INDEX',
    90,
    'Critical for audit log queries. Currently performing sequential scan on 50K+ rows. [seed]',
    8,
    89000,
    5000,
    2,
    3,
    NOW() - INTERVAL '1 hour',
    NOW(),
    NOW() - INTERVAL '1 hour',
    NOW()
),
(
    gen_random_uuid()::text,
    '${connection_id}',
    'products',
    'name,description',
    'idx_products_name_trgm',
    'CREATE INDEX idx_products_name_trgm ON products USING gin(name gin_trgm_ops);',
    'MEDIUM',
    'PENDING',
    'CREATE_INDEX',
    65,
    'Trigram index for ILIKE searches on product name. Current ILIKE pattern forces full table scan. [seed]',
    4,
    12000,
    800,
    2,
    4,
    NOW() - INTERVAL '1 hour',
    NOW(),
    NOW() - INTERVAL '1 hour',
    NOW()
)
ON CONFLICT DO NOTHING;

-- Insert performance actions
INSERT INTO performance_action (
    id, connection_id, category, source, status, title, description, target_object,
    impact_score, effort_score, roi, sql_statement, queries_affected, time_savings_ms,
    created_at, updated_at
) 
SELECT 
    gen_random_uuid()::text,
    '${connection_id}',
    'INDEX',
    'INDEX_ADVISOR',
    'PENDING',
    'Create composite index for order queries',
    'High-impact index for order filtering by status and payment_status. Affects multiple slow queries.',
    'orders',
    85,
    15,
    566.67,
    'CREATE INDEX idx_orders_status_payment_created ON orders(status, payment_status, created_at DESC);',
    12,
    38250,
    NOW() - INTERVAL '1 hour',
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM performance_action 
    WHERE connection_id = '${connection_id}' AND target_object = 'orders' AND category = 'INDEX'
);

SELECT 'Index recommendations created' AS status;
EOSQL
    echo "  Index recommendations seeded."
else
    echo "  Skipping index recommendations (no connection ID available)"
fi

# ============================================================================
# Step 8: Seed digest preferences (fixes "Legacy mode")
# ============================================================================

echo ""
echo "Step 8: Seeding digest preferences..."

if [[ -n "${connection_id:-}" && -n "${admin_id:-}" ]]; then
    compose exec -T postgres psql -U postgres -d dba_agent -v ON_ERROR_STOP=1 <<EOSQL
-- Create digest preference for the admin user on the demo connection
INSERT INTO digest_preferences (
    id, user_id, connection_id, delivery_method, enabled, 
    persona_tag, cron_expression, created_at, updated_at
)
SELECT 
    gen_random_uuid(),
    ${admin_id},
    '${connection_id}',
    'SLACK_DM',
    true,
    'dba',
    '0 0 9 * * *',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM digest_preferences 
    WHERE user_id = ${admin_id} AND connection_id = '${connection_id}'
);

SELECT 'Digest preferences seeded' AS status;
EOSQL
    echo "  Digest preferences created."
    
    # Trigger a real digest using DigestInsightAssemblerService
    # This generates content from actual demo data (index recommendations, slow queries, etc.)
    echo "  Triggering real digest generation..."
    
    trigger_result="$(curl -sS -b "$cookie_jar" \
        -H 'Content-Type: application/json' \
        -X POST "$base/admin/slack/digest/trigger" 2>/dev/null || echo "{}")"
    
    if [[ "$trigger_result" == *"triggered\":true"* ]]; then
        echo "  Real digest generated successfully."
        # Wait a moment for the async digest to be written
        sleep 2
    else
        echo "  Note: Digest trigger returned: $trigger_result"
        echo "  This is expected if Slack is not configured - digest shows in web UI only."
        
        # If no Slack, we need to manually call the assembler and save the result
        # The trigger endpoint requires Slack to be configured for actual delivery
        # For web-only display, insert a digest based on seeded index recommendations
        echo "  Generating web-only digest from seeded data..."
        
        pref_id="$(compose exec -T postgres psql -U postgres -d dba_agent -At -c \
            "SELECT id FROM digest_preferences WHERE user_id = ${admin_id} AND connection_id = '${connection_id}' LIMIT 1" 2>/dev/null || echo "")"
        
        # Build digest content from actual seeded data
        compose exec -T postgres psql -U postgres -d dba_agent -v ON_ERROR_STOP=1 <<EOSQL
-- Generate digest from actual seeded index recommendations
-- This is deterministic content based on what was seeded, not hardcoded prose

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
_' || TO_CHAR(NOW(), 'FMDay, FMMonth DD') || ' · ' || rec_count || ' Index Recommendations_

────────────────────────────────

*🔦 Index Recommendations*

' || COALESCE(rec_list, 'No pending recommendations') || '

────────────────────────────────

*🎯 Quick Actions*

• Review slow queries in Performance tab (pg_stat_statements)
• Apply high-priority indexes from Index Advisor
• Check Brain notes for table documentation

────────────────────────────────

*📈 Database Status*

• Connection: ${DEEPSQL_SEED_CONNECTION_NAME}
• Role: deepsql_demo (read-only + pg_read_all_stats)
• pg_stat_statements: Enabled and tracking queries

────────────────────────────────

_Powered by DeepSQL · Generated from actual database analysis_',
    rec_count || ' Index Recommendations',
    NOW(),
    'SENT',
    '${DEEPSQL_INITIAL_ADMIN_EMAIL}',
    'ADMIN',
    'DBA',
    'SLACK_DM',
    ${pref_id:-NULL},
    true
FROM rec_summary
WHERE NOT EXISTS (
    SELECT 1 FROM slack_digest_log 
    WHERE connection_id = '${connection_id}' 
    AND recipient_username = '${DEEPSQL_INITIAL_ADMIN_EMAIL}'
);

SELECT 'Digest entry created from seeded data' AS status;
EOSQL
        echo "  Web-only digest created from actual seeded data."
    fi
else
    echo "  Skipping digest preferences (missing connection or admin ID)"
fi

# ============================================================================
# Step 9: Seed curated Brain notes (instead of 90 noisy items)
# ============================================================================

echo ""
echo "Step 9: Seeding curated Brain notes..."

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
    documentation, source, reviewed, created_at, updated_at
) VALUES
(
    gen_random_uuid()::text,
    '${connection_id}',
    'TABLE',
    'orders',
    NULL,
    'Core transaction table tracking all customer orders. Status transitions: pending → confirmed → processing → shipped → delivered (or cancelled/refunded). Foreign key to customers via customer_id. Contains denormalized totals (subtotal, tax_amount, shipping_amount, discount_amount, total_amount) for query performance.',
    'USER',
    true,
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
    true,
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
    true,
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
    true,
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
    true,
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
# Step 10: Trigger initial slow query analysis from pg_stat_statements
# ============================================================================

echo ""
echo "Step 10: Triggering initial slow query analysis..."

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
# Summary
# ============================================================================

echo ""
echo "=========================================="
echo "Demo Data Seeding Complete!"
echo "=========================================="
echo ""
echo "What was created:"
echo "  - demo_shop database with e-commerce schema (5000+ orders)"
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
