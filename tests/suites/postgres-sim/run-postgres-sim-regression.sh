#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"

BASE_URL="${1:-${POSTGRES_SIM_BASE_URL:-http://localhost:8080/api}}"
CONNECTION_ID="${2:-${POSTGRES_SIM_CONNECTION_ID:-}}"
ARTIFACT_DIR="${POSTGRES_SIM_ARTIFACT_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/postgres-sim-regression.XXXXXX")}"

if [[ -z "$CONNECTION_ID" ]]; then
  echo "Error: provide a postgres-sim connection id as an argument or set POSTGRES_SIM_CONNECTION_ID." >&2
  exit 1
fi

INTERNAL_TEST_TOKEN="${INTERNAL_TEST_TOKEN:-}"
FAILED_STEPS=()
PASSED_STEPS=()

print_section() {
  echo ""
  echo "========================================"
  echo "  $1"
  echo "========================================"
}

run_step() {
  local step_name="$1"
  shift

  print_section "$step_name"
  if "$@"; then
    PASSED_STEPS+=("$step_name")
    echo "PASS: $step_name"
    return 0
  fi

  FAILED_STEPS+=("$step_name")
  echo "FAIL: $step_name"
  return 1
}

run_brain_retrieval_suite() {
  cd "$ROOT_DIR" || return 1
  BRAIN_RETRIEVAL_FIXTURE_FILE="$SCRIPT_DIR/postgres-sim-brain-retrieval-test-cases.json" \
  BRAIN_RETRIEVAL_REPORT_FILE="$ARTIFACT_DIR/postgres-sim-brain-retrieval-report.json" \
    bash "$ROOT_DIR/tests/suites/brain-retrieval/run-brain-retrieval-tests.sh" "$BASE_URL" "$CONNECTION_ID"
}

print_section "Postgres Sim Regression"
echo "Base URL:      $BASE_URL"
echo "Connection ID: $CONNECTION_ID"
echo "Artifacts:     $ARTIFACT_DIR"

if [[ -n "$INTERNAL_TEST_TOKEN" ]]; then
  _svc_token=$(curl -fsS -X POST "${BASE_URL}/auth/internal/token" \
    -H "Content-Type: application/json" \
    -H "X-Internal-Token: ${INTERNAL_TEST_TOKEN}" \
    2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))" 2>/dev/null || true)
  if [[ -n "$_svc_token" ]]; then
    export BRAIN_RETRIEVAL_AUTH_TOKEN="$_svc_token"
    echo "Service token acquired — brain suite will skip password login."
  else
    echo "WARN: INTERNAL_TEST_TOKEN set but token acquisition failed; suite will fall back to password login."
  fi
fi

run_step "Postgres sim brain retrieval suite" run_brain_retrieval_suite || true

print_section "Regression Summary"
echo "Artifacts: $ARTIFACT_DIR"
echo "Passed: ${#PASSED_STEPS[@]}"
for step in "${PASSED_STEPS[@]}"; do
  echo "  - $step"
done
echo "Failed: ${#FAILED_STEPS[@]}"
for step in "${FAILED_STEPS[@]}"; do
  echo "  - $step"
done

if [[ ${#FAILED_STEPS[@]} -gt 0 ]]; then
  exit 1
fi
