#!/usr/bin/env bash
# Runs one acceptance pass per database target and prints a pass/fail matrix.
#
# Each target gets the same treatment: seed an identical synthetic schema, register
# it as a DeepSQL connection, wait for brain initialisation, then ask the same
# questions and check the answers against values known from the seed. Because every
# target holds identical data, a disagreement between two engines is a real finding
# rather than a difference in fixtures.
#
# Local targets are Docker containers this script creates and destroys. External
# targets are databases you already have — a managed instance in a cloud account,
# for example — which the script uses but never creates, seeds or deletes. Keeping
# both behind one runner is the point: the expensive part of cloud testing is the
# time an instance is alive, and this reduces that to "seed, run, read the matrix".
#
#   ./run-matrix.sh
#   ./run-matrix.sh --engines pg18,my84
#   ./run-matrix.sh --external 'rds-pg:postgres:my.rds.amazonaws.com:5432:shopdb:app:secret'
#
# Requires DEEPSQL_EMAIL and DEEPSQL_PASSWORD for a DeepSQL account that can manage
# connections. Never put credentials in this file.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DEEPSQL_URL="${DEEPSQL_URL:-http://localhost:8080/api}"
DEEPSQL_EMAIL="${DEEPSQL_EMAIL:-}"
DEEPSQL_PASSWORD="${DEEPSQL_PASSWORD:-}"
DOCKER_NETWORK="${DEEPSQL_TEST_NETWORK:-deepsql_default}"
SEED_PASSWORD="${DEEPSQL_TEST_DB_PASSWORD:-matrixpw}"
INIT_TIMEOUT_SECONDS="${DEEPSQL_INIT_TIMEOUT:-1800}"

# Answers are kept on disk because the failure detail below is truncated, and a
# truncated answer cannot distinguish a wrong number from a differently formatted
# one. Whoever reads a red matrix needs the full text, not its first line.
ANSWER_DIR="${DEEPSQL_ANSWER_DIR:-${TMPDIR:-/tmp}/deepsql-matrix-answers}"

# Facts fixed by the seed files. Assertions compare against these, so changing a
# seed means changing these too.
readonly EXPECT_CUSTOMERS="300"
readonly EXPECT_TOP_PRODUCT="Product 23"
readonly EXPECT_TOP_REVENUE="8092"

# name:image-repo:image-tag:type:port:user
readonly LOCAL_ENGINES=(
  "pg17:postgres:17:postgres:5432:postgres"
  "pg18:postgres:18:postgres:5432:postgres"
  "my80:mysql:8.0:mysql:3306:root"
  "my84:mysql:8.4:mysql:3306:root"
)

ENGINES=""
EXTERNAL_TARGETS=()
KEEP=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --engines)  ENGINES="$2"; shift 2 ;;
    --external) EXTERNAL_TARGETS+=("$2"); shift 2 ;;
    --keep)     KEEP=true; shift ;;
    -h|--help)  sed -n '2,21p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)          echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$DEEPSQL_EMAIL" || -z "$DEEPSQL_PASSWORD" ]]; then
  echo "Error: set DEEPSQL_EMAIL and DEEPSQL_PASSWORD to a DeepSQL account that can manage connections." >&2
  exit 1
fi

COOKIE_JAR="$(mktemp)"
CREATED_CONTAINERS=()
CREATED_CONNECTIONS=()
RESULT_ROWS=()
FAILURES=0

cleanup() {
  # Runs on any exit path, including failure and Ctrl-C: a half-finished run must not
  # leave connections in the vault or containers on the host.
  if [[ "$KEEP" == true ]]; then
    echo ""
    echo "--keep set: leaving ${#CREATED_CONTAINERS[@]} container(s) and ${#CREATED_CONNECTIONS[@]} connection(s) in place."
    rm -f "$COOKIE_JAR"
    return
  fi
  echo ""
  echo "Cleaning up..."
  for id in "${CREATED_CONNECTIONS[@]:-}"; do
    [[ -n "$id" ]] && curl -s -b "$COOKIE_JAR" -X DELETE "$DEEPSQL_URL/connections/$id" -o /dev/null || true
  done
  for c in "${CREATED_CONTAINERS[@]:-}"; do
    [[ -n "$c" ]] && docker rm -f "$c" >/dev/null 2>&1 || true
  done
  rm -f "$COOKIE_JAR"
}
trap cleanup EXIT

login() {
  local code
  code=$(curl -s -c "$COOKIE_JAR" -o /dev/null -w '%{http_code}' \
    -X POST "$DEEPSQL_URL/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$DEEPSQL_EMAIL\",\"password\":\"$DEEPSQL_PASSWORD\"}")
  if [[ "$code" != "200" ]]; then
    echo "Error: login to $DEEPSQL_URL failed (HTTP $code)." >&2
    exit 1
  fi
}

start_local_engine() {
  # Echoes "host port user" for the started container; all logging goes to stderr so
  # the caller can capture the connection details cleanly.
  local name="$1" image="$2" type="$3" port="$4" user="$5"
  local container="mx-$name"

  docker rm -f "$container" >/dev/null 2>&1 || true
  echo "  starting $image ..." >&2

  if [[ "$type" == "postgres" ]]; then
    docker run -d --name "$container" --network "$DOCKER_NETWORK" \
      -e POSTGRES_PASSWORD="$SEED_PASSWORD" -e POSTGRES_DB=shopdb \
      -v "$SCRIPT_DIR/seed-postgres.sql":/docker-entrypoint-initdb.d/seed.sql:ro \
      "$image" \
      -c shared_preload_libraries=pg_stat_statements \
      -c pg_stat_statements.track=all >/dev/null
  else
    docker run -d --name "$container" --network "$DOCKER_NETWORK" \
      -e MYSQL_ROOT_PASSWORD="$SEED_PASSWORD" -e MYSQL_DATABASE=shopdb \
      -v "$SCRIPT_DIR/seed-mysql.sql":/docker-entrypoint-initdb.d/seed.sql:ro \
      "$image" >/dev/null
  fi

  local waited=0
  while (( waited < 180 )); do
    if [[ "$type" == "postgres" ]]; then
      docker exec "$container" pg_isready -U postgres -d shopdb >/dev/null 2>&1 && break
    else
      docker exec "$container" mysqladmin ping -uroot -p"$SEED_PASSWORD" --silent >/dev/null 2>&1 && break
    fi
    sleep 3; waited=$((waited + 3))
  done
  if (( waited >= 180 )); then
    echo "Error: $container never became ready." >&2
    return 1
  fi

  # Readiness and "seed finished" are different events: the entrypoint reports ready
  # before the init scripts complete, so poll for the last table the seed writes.
  waited=0
  while (( waited < 180 )); do
    if [[ "$type" == "postgres" ]]; then
      docker exec "$container" psql -U postgres -d shopdb -tAc \
        "SELECT COUNT(*) FROM payments" 2>/dev/null | grep -q '^250$' && break
    else
      docker exec "$container" mysql -uroot -p"$SEED_PASSWORD" shopdb -sN \
        -e "SELECT COUNT(*) FROM payments" 2>/dev/null | grep -q '^250$' && break
    fi
    sleep 3; waited=$((waited + 3))
  done

  echo "$container $port $user"
}

register_connection() {
  # Echoes "<connection-id> <granted>/<total>", or "FAIL <message>".
  local name="$1" type="$2" host="$3" port="$4" db="$5" user="$6" pass="$7"
  local response
  response=$(curl -s -b "$COOKIE_JAR" -X POST "$DEEPSQL_URL/connections" \
    -H 'Content-Type: application/json' \
    -d "$(printf '{"connectionName":"%s","dbType":"%s","host":"%s","port":%s,"database":"%s","username":"%s","password":"%s","ssl":false,"cloudProvider":"self-hosted"}' \
          "$name" "$type" "$host" "$port" "$db" "$user" "$pass")")
  python3 -c '
import json, sys
d = json.load(sys.stdin)
if not d.get("connectionId"):
    print("FAIL", (d.get("message") or "no connectionId returned")[:90]); sys.exit()
privs = d.get("privileges", [])
print(d["connectionId"], "%d/%d" % (sum(1 for p in privs if p.get("granted")), len(privs)))
' <<<"$response" 2>/dev/null || echo "FAIL unparseable response"
}

await_brain_init() {
  local id="$1" waited=0
  while (( waited < INIT_TIMEOUT_SECONDS )); do
    if curl -s -b "$COOKIE_JAR" "$DEEPSQL_URL/connections/$id/init-status" \
         | grep -q '"currentStage":"COMPLETED"'; then
      return 0
    fi
    sleep 15; waited=$((waited + 15))
  done
  return 1
}

ask() {
  # Echoes the answer text on one line, or an empty string when the run failed.
  local id="$1" question="$2"
  curl -s -b "$COOKIE_JAR" -X POST "$DEEPSQL_URL/chat" \
    -H 'Content-Type: application/json' \
    -d "$(printf '{"connectionId":"%s","message":"%s"}' "$id" "$question")" \
  | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print(""); sys.exit()
print(str(d.get("answer") or "").replace("\n", " ") if d.get("success") else "")
' 2>/dev/null
}

# "$8,092.00" -> "$8092.00". Leaves "80,92" alone: a comma qualifies only when it
# separates a digit from exactly three digits, which is what digit grouping is.
#
# Pure bash on purpose. The obvious sed spelling of this is GNU's ":a;...;ta",
# which BSD sed (macOS, where this script is mostly run) rejects — a label there
# runs to end of line. It fails as a silently empty result rather than an error,
# which would turn every target red for a reason that has nothing to do with the
# database.
# The match is only digits and a comma, so the unquoted replacement pattern below
# carries no glob metacharacters. Quoting it instead — ${s/"$a"/"$b"} — inserts
# literal quote marks under bash 3.2, which is what macOS still ships.
ungroup_digits() {
  local s="$1" whole lead rest
  while [[ "$s" =~ ([0-9]),([0-9][0-9][0-9]) ]]; do
    whole="${BASH_REMATCH[0]}"; lead="${BASH_REMATCH[1]}"; rest="${BASH_REMATCH[2]}"
    s="${s/$whole/$lead$rest}"
  done
  printf '%s' "$s"
}

run_target() {
  local label="$1" type="$2" host="$3" port="$4" db="$5" user="$6" pass="$7"
  local status="PASS" detail="" conn_id="" privs="-"

  echo "[$label] registering connection ..."
  local reg; reg=$(register_connection "$label" "$type" "$host" "$port" "$db" "$user" "$pass")
  if [[ "$reg" == FAIL* ]]; then
    RESULT_ROWS+=("$label|CONNECT|-|${reg#FAIL }")
    FAILURES=$((FAILURES + 1)); return
  fi
  conn_id=$(awk '{print $1}' <<<"$reg"); privs=$(awk '{print $2}' <<<"$reg")
  CREATED_CONNECTIONS+=("$conn_id")

  echo "[$label] waiting for brain init (up to ${INIT_TIMEOUT_SECONDS}s) ..."
  if ! await_brain_init "$conn_id"; then
    RESULT_ROWS+=("$label|BRAIN_INIT|$privs|did not reach COMPLETED in ${INIT_TIMEOUT_SECONDS}s")
    FAILURES=$((FAILURES + 1)); return
  fi

  echo "[$label] asking the acceptance questions ..."
  local a1 a2
  a1=$(ask "$conn_id" "How many customers are there?")
  printf '%s\n' "$a1" > "$ANSWER_DIR/${label}-count.txt"

  # Numbers are matched against a copy with digit grouping removed. Grouping is
  # presentation and it varies by model: Claude renders the revenue as "$8,092.00"
  # where GPT writes "8092.00". Both are the right number, and a matrix that fails
  # one of them is testing prose style, not the product.
  #
  # Only a comma between a digit and exactly three digits is removed, so "8,092"
  # normalises but a malformed "80,92" does not and still fails. The loop handles
  # repeated grouping ("1,234,567"), where a single pass would leave the second
  # comma behind.
  local a1_plain a2_plain
  a1_plain="$(ungroup_digits "$a1")"

  if [[ "$a1_plain" != *"$EXPECT_CUSTOMERS"* ]]; then
    status="FAIL"; detail="count: expected $EXPECT_CUSTOMERS, got \"${a1:0:60}\""
  fi

  a2=$(ask "$conn_id" "What are the top 3 products by total revenue from order items? Show product name and revenue.")
  printf '%s\n' "$a2" > "$ANSWER_DIR/${label}-join.txt"
  a2_plain="$(ungroup_digits "$a2")"

  # Report which half is missing. "expected X/Y" cannot say whether the agent picked
  # the wrong product or the wrong number, and those are different bugs.
  local missing=""
  if [[ "$a2" != *"$EXPECT_TOP_PRODUCT"* ]]; then
    missing="product $EXPECT_TOP_PRODUCT"
  fi
  if [[ "$a2_plain" != *"$EXPECT_TOP_REVENUE"* ]]; then
    missing="${missing:+$missing and }revenue $EXPECT_TOP_REVENUE"
  fi
  if [[ -n "$missing" ]]; then
    status="FAIL"
    detail="${detail:+$detail; }join: missing $missing; got \"${a2:0:60}\""
  fi

  [[ "$status" == "FAIL" ]] && echo "[$label] full answers: $ANSWER_DIR/${label}-*.txt"

  [[ "$status" == "FAIL" ]] && FAILURES=$((FAILURES + 1))
  RESULT_ROWS+=("$label|$status|$privs|$detail")
}

mkdir -p "$ANSWER_DIR"
login
echo "DeepSQL: $DEEPSQL_URL"
echo "Answers: $ANSWER_DIR"

selected="${ENGINES:-pg17,pg18,my80,my84}"
if [[ -n "$ENGINES" || ${#EXTERNAL_TARGETS[@]} -eq 0 ]]; then
  for spec in "${LOCAL_ENGINES[@]}"; do
    IFS=':' read -r name img_repo img_tag type port user <<<"$spec"
    [[ ",$selected," == *",$name,"* ]] || continue
    echo ""
    echo "=== $name ($img_repo:$img_tag) ==="
    # Register for teardown BEFORE creating it, and here rather than inside
    # start_local_engine: that function is called in a command substitution, so any
    # array it appends to is lost with the subshell and cleanup would find nothing.
    # Recording the name up front also covers a container that starts but never
    # becomes ready.
    CREATED_CONTAINERS+=("mx-$name")
    if details=$(start_local_engine "$name" "$img_repo:$img_tag" "$type" "$port" "$user"); then
      read -r host cport cuser <<<"$details"
      run_target "$name" "$type" "$host" "$cport" "shopdb" "$cuser" "$SEED_PASSWORD"
    else
      RESULT_ROWS+=("$name|STARTUP|-|container never became ready")
      FAILURES=$((FAILURES + 1))
    fi
  done
fi

for spec in "${EXTERNAL_TARGETS[@]:-}"; do
  [[ -z "$spec" ]] && continue
  IFS=':' read -r name type host port db user pass <<<"$spec"
  echo ""
  echo "=== $name (external $type at $host) ==="
  echo "  note: seed this database with the matching seed-*.sql first; external"
  echo "        targets are never created, seeded or dropped by this script."
  run_target "$name" "$type" "$host" "$port" "$db" "$user" "$pass"
done

echo ""
printf '%-12s %-12s %-8s %s\n' "TARGET" "RESULT" "PRIVS" "DETAIL"
printf '%-12s %-12s %-8s %s\n' "------" "------" "-----" "------"
for row in "${RESULT_ROWS[@]:-}"; do
  IFS='|' read -r label status privs detail <<<"$row"
  printf '%-12s %-12s %-8s %s\n' "$label" "$status" "$privs" "$detail"
done
echo ""

if (( FAILURES > 0 )); then
  echo "$FAILURES target(s) failed."
  exit 1
fi
echo "All targets passed."
