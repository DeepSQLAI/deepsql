#!/usr/bin/env bash
# seed-demo-data.sh — Seeds the DeepSQL installation with a demo database and sample data
#
# This script creates:
# - demo_shop: A realistic e-commerce database with customers, orders, products
# - Demo users: analyst, developer, viewer with scoped access
# - Sample saved queries in the SQL editor
# - Sample slow query analysis and index recommendations
# - Sample agent conversation history
#
# Run after install.sh completes. Requires the stack to be running.

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
: "${DB_PASSWORD:=postgres}"
: "${DEEPSQL_INITIAL_ADMIN_EMAIL:=}"
: "${DEEPSQL_INITIAL_ADMIN_PASSWORD:=}"
: "${DEEPSQL_SEED_SKIP_DEMO_DB:=0}"
: "${DEEPSQL_SEED_CONNECTION_NAME:=Demo Shop (E-commerce)}"

compose() {
    DEEPSQL_RUNTIME_ENV_FILE="$ENV_FILE" docker compose \
        --project-name "$PROJECT_NAME" \
        --env-file "$ENV_FILE" \
        -f "$COMPOSE_FILE" \
        "$@"
}

echo "=========================================="
echo "DeepSQL Demo Data Seeding"
echo "=========================================="

# ============================================================================
# Step 1: Create demo_shop database
# ============================================================================

if [[ "$DEEPSQL_SEED_SKIP_DEMO_DB" != "1" ]]; then
    echo ""
    echo "Step 1: Creating demo_shop database..."

    demo_sql="$ROOT_DIR/docker/postgres/init/10_create_demo_shop.sql"
    demo_exists="$(compose exec -T postgres psql -U postgres -At -c "SELECT 1 FROM pg_database WHERE datname = 'demo_shop'" 2>/dev/null || echo "")"
    # Presence alone is not enough: a failed init leaves an empty-ish catalog
    # (products/customers seeded, orders aborted on interval cast) and the old
    # skip path permanently left customers with a half-built demo.
    order_count="0"
    if [[ "$demo_exists" == "1" ]]; then
        order_count="$(compose exec -T postgres psql -U postgres -d demo_shop -At -c "SELECT COUNT(*) FROM orders" 2>/dev/null || echo "0")"
    fi

    recreate_demo=0
    if [[ "${DEEPSQL_SEED_FORCE_DEMO_DB:-0}" == "1" ]]; then
        recreate_demo=1
    elif [[ "$demo_exists" == "1" && "${order_count:-0}" -lt 1000 ]]; then
        # Full seed inserts 5000 orders. Anything well below that means the
        # init script aborted mid-file (historically: float||' hours' interval
        # casts) — treat it as incomplete and rebuild.
        recreate_demo=1
    fi

    if [[ "$demo_exists" == "1" && "$recreate_demo" -eq 0 ]]; then
        echo "  demo_shop database already exists with $order_count orders. Skipping creation."
        echo "  (Set DEEPSQL_SEED_FORCE_DEMO_DB=1 to drop and recreate, or DEEPSQL_SEED_SKIP_DEMO_DB=1 to skip)"
    elif [[ ! -f "$demo_sql" ]]; then
        echo "  Warning: demo_shop SQL script not found at $demo_sql"
        echo "  Skipping demo database creation."
    else
        if [[ "$demo_exists" == "1" ]]; then
            echo "  demo_shop exists but looks incomplete (orders=${order_count:-0}). Recreating…"
            # DROP DATABASE cannot run inside a multi-statement -c transaction.
            compose exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 -c \
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'demo_shop' AND pid <> pg_backend_pid();" >/dev/null || true
            compose exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 -c \
                "DROP DATABASE IF EXISTS demo_shop;"
        fi
        echo "  Running demo_shop creation script..."
        # Prefer the bind-mounted init script so recreate matches first-boot.
        # ON_ERROR_STOP so a mid-file failure cannot look like success.
        # The SQL file itself starts with DROP/CREATE DATABASE — run it against
        # the postgres maintenance DB, not demo_shop.
        if compose exec -T postgres test -f /docker-entrypoint-initdb.d/10_create_demo_shop.sql; then
            compose exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 \
                -f /docker-entrypoint-initdb.d/10_create_demo_shop.sql
        else
            compose exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 < "$demo_sql"
        fi
        echo "  demo_shop database created successfully."
    fi
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
    exit 1
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
    exit 1
fi

# ============================================================================
# Step 3: Create demo connection to demo_shop
# ============================================================================

echo ""
echo "Step 3: Creating demo connection..."

# Check if connection already exists
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
    payload=$(cat <<JSON
{
  "connectionName": "${DEEPSQL_SEED_CONNECTION_NAME}",
  "dbType": "postgres",
  "host": "postgres",
  "port": 5432,
  "database": "demo_shop",
  "username": "postgres",
  "password": "${DB_PASSWORD}",
  "cloudProvider": "self-hosted",
  "ssl": false,
  "sslMode": "none",
  "sshEnabled": false
}
JSON
)

    # Match smoke-test.sh: sslMode must be "none" (not "disable"). Any other value
    # is treated as SSL-on by ConnectionRequest.getEffectiveSsl(), and the vault
    # Postgres image rejects SSL — so the connection test fails and the seed
    # used to report only an opaque "{}".
    http_code="$(curl -sS -o /tmp/deepsql-seed-conn.json -w '%{http_code}' -b "$cookie_jar" \
        -H 'Content-Type: application/json' \
        -X POST "$base/connections" -d "$payload" || true)"
    save_json="$(cat /tmp/deepsql-seed-conn.json 2>/dev/null || echo "{}")"
    rm -f /tmp/deepsql-seed-conn.json
    connection_id="$(printf '%s' "$save_json" | sed -n 's/.*"connectionId":"\([^"]*\)".*/\1/p')"
    
    if [[ -z "$connection_id" ]]; then
        echo "  Warning: Could not create demo connection (HTTP ${http_code:-?})."
        echo "  Response: $save_json"
        echo "  Continuing with other seed data..."
    else
        echo "  Created demo connection: $connection_id"
    fi
fi

# ============================================================================
# Step 4: Create demo users
# ============================================================================

echo ""
echo "Step 4: Creating demo users..."

create_user() {
    local username="$1"
    local email="$2"
    local password="$3"
    local role="$4"
    
    # Check if user exists
    users_json="$(curl -fsS -b "$cookie_jar" "$base/users" 2>/dev/null || echo "[]")"
    if [[ "$users_json" == *"\"$email\""* ]]; then
        echo "  User $username already exists, skipping."
        return 0
    fi
    
    # Create user via invite flow or direct insert
    # Note: Direct user creation may require admin privileges
    local user_payload
    user_payload=$(cat <<JSON
{
  "username": "$username",
  "email": "$email",
  "password": "$password",
  "role": "$role"
}
JSON
)
    
    result="$(curl -sS -b "$cookie_jar" -H 'Content-Type: application/json' \
        -X POST "$base/users" -d "$user_payload" 2>/dev/null || echo "{}")"
    
    if [[ "$result" == *"error"* || "$result" == *"Error"* ]]; then
        echo "  Note: Could not create user $username via API (may need manual creation)"
    else
        echo "  Created user: $username ($email) with role $role"
    fi
}

# Create demo users
create_user "analyst" "analyst@demo.local" "analyst123!" "DEVELOPER"
create_user "developer" "developer@demo.local" "developer123!" "DEVELOPER"
create_user "viewer" "viewer@demo.local" "viewer123!" "DEVELOPER"

# ============================================================================
# Step 5: Create saved queries
# ============================================================================

echo ""
echo "Step 5: Creating saved queries..."

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
            echo "  Note: Could not create query '$name'"
        fi
    }
    
    # Revenue & Sales Queries
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
    
    # Operations Queries
    create_saved_query \
        "Low Stock Products" \
        "SELECT p.sku, p.name, p.stock_quantity, p.low_stock_threshold, c.name as category FROM products p JOIN categories c ON p.category_id = c.id WHERE p.stock_quantity <= p.low_stock_threshold AND p.is_active = true ORDER BY p.stock_quantity ASC;" \
        "Products below their low stock threshold" \
        "Operations" \
        "inventory,stock,alerts" \
        false
    
    create_saved_query \
        "Orders Pending Shipment" \
        "SELECT o.order_number, o.created_at, o.total_amount, c.email, c.first_name, c.last_name FROM orders o JOIN customers c ON o.customer_id = c.id WHERE o.status IN ('confirmed', 'processing') AND o.payment_status = 'paid' ORDER BY o.created_at ASC;" \
        "Paid orders waiting to be shipped" \
        "Operations" \
        "orders,shipping,pending" \
        false
    
    create_saved_query \
        "Recent Inventory Movements" \
        "SELECT im.created_at, p.sku, p.name, im.movement_type, im.quantity, im.reference_id, im.notes FROM inventory_movements im JOIN products p ON im.product_id = p.id WHERE im.created_at >= CURRENT_DATE - INTERVAL '7 days' ORDER BY im.created_at DESC LIMIT 100;" \
        "Inventory changes in the last 7 days" \
        "Operations" \
        "inventory,movements,audit" \
        false
    
    # Analytics Queries
    create_saved_query \
        "Category Performance" \
        "SELECT c.name as category, COUNT(DISTINCT p.id) as products, COUNT(oi.id) as orders, SUM(oi.subtotal) as revenue FROM categories c LEFT JOIN products p ON c.id = p.category_id LEFT JOIN order_items oi ON p.id = oi.product_id LEFT JOIN orders o ON oi.order_id = o.id AND o.status NOT IN ('cancelled', 'refunded') WHERE c.parent_id IS NULL GROUP BY c.id, c.name ORDER BY revenue DESC NULLS LAST;" \
        "Revenue and order counts by top-level category" \
        "Analytics" \
        "categories,performance,analysis" \
        false
    
    create_saved_query \
        "Customer Tier Distribution" \
        "SELECT tier, COUNT(*) as customer_count, AVG(loyalty_points) as avg_points, SUM(loyalty_points) as total_points FROM customers WHERE is_active = true GROUP BY tier ORDER BY CASE tier WHEN 'platinum' THEN 1 WHEN 'gold' THEN 2 WHEN 'silver' THEN 3 ELSE 4 END;" \
        "Customer distribution across loyalty tiers" \
        "Analytics" \
        "customers,tiers,loyalty" \
        false
    
    create_saved_query \
        "Hourly Order Distribution" \
        "SELECT EXTRACT(HOUR FROM created_at) as hour_of_day, COUNT(*) as order_count, SUM(total_amount) as revenue FROM orders WHERE created_at >= CURRENT_DATE - INTERVAL '30 days' AND status NOT IN ('cancelled') GROUP BY EXTRACT(HOUR FROM created_at) ORDER BY hour_of_day;" \
        "Order volume by hour of day (last 30 days)" \
        "Analytics" \
        "orders,hourly,patterns" \
        false
    
    # Slow Query Examples (intentionally suboptimal for recommendations)
    create_saved_query \
        "[Example] Unoptimized Full Table Scan" \
        "SELECT * FROM orders WHERE LOWER(status) = 'delivered' AND total_amount > 100 ORDER BY created_at DESC;" \
        "Example of a query that could benefit from index optimization (uses LOWER() preventing index use)" \
        "Examples" \
        "slow,example,optimization" \
        false
    
    create_saved_query \
        "[Example] Missing Index Pattern" \
        "SELECT o.*, c.email, c.first_name FROM orders o JOIN customers c ON o.customer_id = c.id WHERE o.status = 'pending' AND o.payment_status = 'paid' AND o.created_at > CURRENT_DATE - INTERVAL '7 days';" \
        "Query that would benefit from a composite index on (status, payment_status, created_at)" \
        "Examples" \
        "slow,example,index" \
        false
    
    create_saved_query \
        "[Example] Aggregation Candidate for MV" \
        "SELECT DATE_TRUNC('month', o.created_at) as month, c.name as category, COUNT(DISTINCT o.id) as orders, SUM(oi.subtotal) as revenue, COUNT(DISTINCT o.customer_id) as customers FROM orders o JOIN order_items oi ON o.id = oi.order_id JOIN products p ON oi.product_id = p.id JOIN categories c ON p.category_id = c.id WHERE o.status NOT IN ('cancelled', 'refunded') GROUP BY DATE_TRUNC('month', o.created_at), c.id, c.name ORDER BY month DESC, revenue DESC;" \
        "Monthly category revenue - candidate for materialized view" \
        "Examples" \
        "slow,example,materialized-view" \
        false
else
    echo "  Skipping saved queries (no connection ID available)"
fi

# ============================================================================
# Step 6: Seed slow query history and recommendations via SQL
# ============================================================================

echo ""
echo "Step 6: Seeding performance data (slow queries, recommendations)..."

if [[ -n "${connection_id:-}" ]]; then
    # Generate sample slow query analysis data
    slow_query_json=$(cat <<'SQJSON'
{
  "slowQueries": [
    {
      "query": "SELECT * FROM orders WHERE LOWER(status) = 'delivered' AND total_amount > 100",
      "executionTime": 2340.5,
      "calls": 1250,
      "meanTime": 1.87,
      "maxTime": 45.2,
      "rows": 15000,
      "severity": "HIGH",
      "pattern": "FULL_TABLE_SCAN",
      "suggestion": "Add index on orders(status) and avoid LOWER() function on indexed column"
    },
    {
      "query": "SELECT o.*, c.* FROM orders o JOIN customers c ON o.customer_id = c.id WHERE o.status = 'pending' AND o.payment_status = 'paid'",
      "executionTime": 1856.3,
      "calls": 890,
      "meanTime": 2.08,
      "maxTime": 38.7,
      "rows": 5200,
      "severity": "HIGH",
      "pattern": "MISSING_INDEX",
      "suggestion": "Create composite index on orders(status, payment_status, customer_id)"
    },
    {
      "query": "SELECT p.*, AVG(r.rating) FROM products p LEFT JOIN product_reviews r ON p.id = r.product_id GROUP BY p.id",
      "executionTime": 1245.8,
      "calls": 2100,
      "meanTime": 0.59,
      "maxTime": 12.3,
      "rows": 100,
      "severity": "MEDIUM",
      "pattern": "AGGREGATION",
      "suggestion": "Consider materialized view for frequently accessed product ratings"
    },
    {
      "query": "SELECT * FROM audit_log WHERE table_name = 'orders' ORDER BY changed_at DESC LIMIT 1000",
      "executionTime": 3456.2,
      "calls": 450,
      "meanTime": 7.68,
      "maxTime": 89.4,
      "rows": 1000,
      "severity": "CRITICAL",
      "pattern": "LARGE_TABLE_SCAN",
      "suggestion": "Create composite index on audit_log(table_name, changed_at DESC)"
    }
  ],
  "summary": {
    "totalSlowQueries": 4,
    "criticalCount": 1,
    "highCount": 2,
    "mediumCount": 1,
    "lowCount": 0,
    "totalDatabaseTimeMs": 8898.8
  }
}
SQJSON
)

    # Escape for SQL
    slow_query_escaped="${slow_query_json//\'/\'\'}"

    compose exec -T postgres psql -U postgres -d dba_agent -v ON_ERROR_STOP=1 <<EOSQL
-- Insert slow query history
INSERT INTO slow_query_history (
    id, connection_id, time_range, slow_query_threshold_ms, analysis_data,
    total_slow_queries, overall_health, critical_count, high_count, total_database_time_ms,
    created_at, updated_at
) VALUES (
    gen_random_uuid()::text,
    '${connection_id}',
    'LAST_24_HOURS',
    100.0,
    '${slow_query_escaped}',
    4,
    'FAIR',
    1,
    2,
    8898.8,
    NOW() - INTERVAL '2 hours',
    NOW() - INTERVAL '2 hours'
) ON CONFLICT DO NOTHING;

-- Insert index recommendations
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
    'Composite index for common order filtering patterns. Covers 89% of slow queries involving order status lookups.',
    12,
    45000,
    2500,
    3,
    5,
    NOW() - INTERVAL '7 days',
    NOW() - INTERVAL '1 day',
    NOW() - INTERVAL '7 days',
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
    'Critical for audit log queries. Current queries scan 50K+ rows. Index would reduce to < 1000 rows.',
    8,
    89000,
    5000,
    2,
    3,
    NOW() - INTERVAL '5 days',
    NOW() - INTERVAL '12 hours',
    NOW() - INTERVAL '5 days',
    NOW()
),
(
    gen_random_uuid()::text,
    '${connection_id}',
    'product_reviews',
    'product_id,is_approved,created_at',
    'idx_reviews_product_approved',
    'CREATE INDEX idx_reviews_product_approved ON product_reviews(product_id, is_approved) WHERE is_approved = true;',
    'MEDIUM',
    'PENDING',
    'CREATE_INDEX',
    65,
    'Partial index for approved reviews. Improves product page load times by filtering pre-approved reviews only.',
    6,
    12000,
    800,
    2,
    4,
    NOW() - INTERVAL '10 days',
    NOW() - INTERVAL '2 days',
    NOW() - INTERVAL '10 days',
    NOW()
),
(
    gen_random_uuid()::text,
    '${connection_id}',
    'orders',
    'idx_orders_status',
    'idx_orders_status',
    'DROP INDEX IF EXISTS idx_orders_status;',
    'LOW',
    'PENDING',
    'DROP_INDEX',
    25,
    'Redundant index. Covered by the recommended composite index idx_orders_status_payment_created.',
    0,
    0,
    500,
    1,
    2,
    NOW() - INTERVAL '7 days',
    NOW() - INTERVAL '7 days',
    NOW() - INTERVAL '7 days',
    NOW()
) ON CONFLICT DO NOTHING;

-- Insert performance actions
INSERT INTO performance_action (
    id, connection_id, category, source, status, title, description, target_object,
    impact_score, effort_score, roi, sql_statement, queries_affected, time_savings_ms,
    created_at, updated_at
) VALUES
(
    gen_random_uuid()::text,
    '${connection_id}',
    'INDEX',
    'INDEX_ADVISOR',
    'PENDING',
    'Create composite index for order queries',
    'High-impact index covering common order filtering patterns. Expected to reduce query time by 85% for affected queries.',
    'orders',
    85,
    15,
    566.67,
    'CREATE INDEX idx_orders_status_payment_created ON orders(status, payment_status, created_at DESC);',
    12,
    38250,
    NOW() - INTERVAL '1 day',
    NOW()
),
(
    gen_random_uuid()::text,
    '${connection_id}',
    'INDEX',
    'INDEX_ADVISOR',
    'PENDING',
    'Add index for audit log queries',
    'Critical performance improvement for audit log access patterns. Currently causing full table scans on 50K+ rows.',
    'audit_log',
    90,
    10,
    900.0,
    'CREATE INDEX idx_audit_log_table_changed ON audit_log(table_name, changed_at DESC);',
    8,
    68376,
    NOW() - INTERVAL '2 days',
    NOW()
),
(
    gen_random_uuid()::text,
    '${connection_id}',
    'QUERY_REWRITE',
    'SLOW_QUERY_ANALYSIS',
    'PENDING',
    'Rewrite order status query to use index',
    'Replace LOWER(status) with direct comparison. The function call prevents index usage.',
    'orders',
    70,
    5,
    1400.0,
    NULL,
    1,
    21060,
    NOW() - INTERVAL '3 days',
    NOW()
),
(
    gen_random_uuid()::text,
    '${connection_id}',
    'SCHEMA',
    'ANTI_PATTERN_DETECTION',
    'PENDING',
    'Consider partitioning audit_log table',
    'Table has 50K+ rows and growing rapidly. Time-based partitioning would improve query performance and maintenance.',
    'audit_log',
    60,
    40,
    150.0,
    NULL,
    NULL,
    NULL,
    NOW() - INTERVAL '5 days',
    NOW()
),
(
    gen_random_uuid()::text,
    '${connection_id}',
    'CONFIG',
    'BRAIN_CONFIG_TUNING',
    'PENDING',
    'Increase work_mem for complex queries',
    'Several aggregation queries are spilling to disk. Increasing work_mem from 4MB to 64MB would improve performance.',
    'work_mem',
    55,
    5,
    1100.0,
    'ALTER SYSTEM SET work_mem = ''64MB'';',
    15,
    8500,
    NOW() - INTERVAL '4 days',
    NOW()
) ON CONFLICT DO NOTHING;

SELECT 'Performance seed data inserted successfully' AS status;
EOSQL

    echo "  Performance data seeded."
else
    echo "  Skipping performance data (no connection ID available)"
fi

# ============================================================================
# Step 7: Seed agent conversation (sample prompts)
# ============================================================================

echo ""
echo "Step 7: Seeding agent conversation history..."

if [[ -n "${connection_id:-}" ]]; then
    # Get admin user ID
    admin_id="$(compose exec -T postgres psql -U postgres -d dba_agent -At -c \
        "SELECT id FROM users WHERE username = 'admin' LIMIT 1" 2>/dev/null || echo "")"
    
    if [[ -n "$admin_id" ]]; then
        sample_transcript=$(cat <<'TRANSCRIPT'
[
  {
    "role": "user",
    "content": "What are the top 5 most ordered products in the last 30 days?"
  },
  {
    "role": "assistant",
    "content": "I'll query the orders and order_items tables to find the most popular products.\n\nBased on the data, the top 5 most ordered products in the last 30 days are:\n\n1. **ProPhone X15** (SKU: ELEC-SP-002) - 156 orders, $155,843.44 revenue\n2. **AirPods Pro 3** (SKU: ELEC-AU-001) - 142 orders, $39,758.58 revenue\n3. **Budget Phone A5** (SKU: ELEC-SP-007) - 128 orders, $25,598.72 revenue\n4. **Atomic Habits** (SKU: BOOK-002) - 119 orders, $2,260.81 revenue\n5. **Air Fryer XL** (SKU: HOME-KT-005) - 98 orders, $12,739.02 revenue\n\nNotably, electronics dominate the top sellers, with smartphones and audio accessories being particularly popular."
  },
  {
    "role": "user",
    "content": "Can you suggest indexes to improve query performance?"
  },
  {
    "role": "assistant", 
    "content": "Based on my analysis of your slow queries and access patterns, here are the recommended indexes:\n\n**High Priority:**\n1. `CREATE INDEX idx_orders_status_payment_created ON orders(status, payment_status, created_at DESC);`\n   - Impact: 85% improvement for order filtering queries\n   - Affects: 12 slow queries\n\n2. `CREATE INDEX idx_audit_log_table_changed ON audit_log(table_name, changed_at DESC);`\n   - Impact: 90% improvement for audit log access\n   - Current queries scan 50K+ rows\n\n**Medium Priority:**\n3. `CREATE INDEX idx_reviews_product_approved ON product_reviews(product_id, is_approved) WHERE is_approved = true;`\n   - Partial index for approved reviews only\n   - Improves product page load times\n\nWould you like me to generate the full DDL statements or analyze any specific queries?"
  }
]
TRANSCRIPT
)
        # Escape for SQL
        transcript_escaped="${sample_transcript//\'/\'\'}"
        
        compose exec -T postgres psql -U postgres -d dba_agent -v ON_ERROR_STOP=1 <<EOSQL
-- Insert sample agent conversation
INSERT INTO agent_conversation (
    id, user_id, connection_id, agent_session_id, title, transcript, archived,
    created_at, updated_at, last_message_at
) VALUES (
    gen_random_uuid()::text,
    ${admin_id},
    '${connection_id}',
    'demo-session-' || gen_random_uuid()::text,
    'Product Analysis & Index Recommendations',
    '${transcript_escaped}'::jsonb,
    false,
    NOW() - INTERVAL '1 hour',
    NOW() - INTERVAL '30 minutes',
    NOW() - INTERVAL '30 minutes'
) ON CONFLICT DO NOTHING;

SELECT 'Agent conversation seeded successfully' AS status;
EOSQL
        echo "  Agent conversation history seeded."
    else
        echo "  Skipping agent conversations (admin user not found)"
    fi
else
    echo "  Skipping agent conversations (no connection ID available)"
fi

# ============================================================================
# Step 8: Wait for brain init (optional)
# ============================================================================

echo ""
echo "Step 8: Triggering brain initialization..."

if [[ -n "${connection_id:-}" ]]; then
    # Check init status
    init_status="$(curl -fsS -b "$cookie_jar" "$base/connections/${connection_id}/init-status" 2>/dev/null || echo "{}")"
    current_stage="$(printf '%s' "$init_status" | sed -n 's/.*"currentStage":"\([^"]*\)".*/\1/p')"
    
    if [[ "$current_stage" == "COMPLETED" ]]; then
        echo "  Brain initialization already completed."
    elif [[ "$current_stage" == "FAILED" ]]; then
        echo "  Note: Brain initialization previously failed. Check LLM configuration."
    else
        echo "  Brain initialization is in progress or pending."
        echo "  Stage: ${current_stage:-UNKNOWN}"
        echo "  The initialization will continue in the background."
        echo "  Run 'curl http://localhost:${DEEPSQL_BACKEND_PORT}/api/connections/${connection_id}/init-status' to check progress."
    fi
else
    echo "  Skipping brain init check (no connection ID available)"
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
echo "  - Sample products, customers, orders (5000+)"
echo "  - Demo connection: ${DEEPSQL_SEED_CONNECTION_NAME}"
if [[ -n "${connection_id:-}" ]]; then
echo "  - Connection ID: ${connection_id}"
fi
echo "  - Saved queries in SQL Editor"
echo "  - Sample slow query analysis"
echo "  - Index recommendations"
echo "  - Performance actions"
echo "  - Sample agent conversation"
echo ""
echo "Demo users (create manually if API creation failed):"
echo "  - analyst@demo.local / analyst123!"
echo "  - developer@demo.local / developer123!"  
echo "  - viewer@demo.local / viewer123!"
echo ""
echo "Next steps:"
echo "  1. Open http://localhost:${DEEPSQL_FRONTEND_PORT:-3000}"
echo "  2. Select '${DEEPSQL_SEED_CONNECTION_NAME}' connection"
echo "  3. Explore the Schema, Slow Queries, and Actions tabs"
echo "  4. Try the SQL Editor with pre-saved queries"
echo "  5. Ask the Agent about the database"
echo ""
