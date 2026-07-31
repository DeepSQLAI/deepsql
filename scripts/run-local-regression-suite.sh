#!/usr/bin/env bash
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="${LOCAL_REGRESSION_ENV_FILE:-$ROOT_DIR/.env}"
BACKEND_DIR="$ROOT_DIR/backend"

if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  set -a
  source "$ENV_FILE"
  set +a
fi

set -o pipefail

DEFAULT_CONNECTION_ID=""
BASE_URL="${1:-${LOCAL_REGRESSION_BASE_URL:-http://localhost:8080/api}}"
FRONTEND_URL="${LOCAL_REGRESSION_FRONTEND_URL:-http://localhost:3000}"
CONNECTION_ID="${2:-${LOCAL_REGRESSION_CONNECTION_ID:-${BRAIN_RETRIEVAL_CONNECTION_ID:-$DEFAULT_CONNECTION_ID}}}"
STRICT_BRAIN="${LOCAL_REGRESSION_STRICT_BRAIN:-0}"
RUN_FRONTEND_LINT="${LOCAL_REGRESSION_RUN_FRONTEND_LINT:-0}"
SKIP_FRONTEND_BUILD="${LOCAL_REGRESSION_SKIP_FRONTEND_BUILD:-0}"
SKIP_BRAIN_SUITE="${LOCAL_REGRESSION_SKIP_BRAIN_SUITE:-0}"
INTERNAL_TEST_TOKEN="${INTERNAL_TEST_TOKEN:-}"

ARTIFACT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/dbaagent-local-regression.XXXXXX")"
STATUS_FILE="$ARTIFACT_DIR/status.txt"
PID_FILE="$ARTIFACT_DIR/pid.txt"
FAILED_STEPS=()
SKIPPED_STEPS=()
PASSED_STEPS=()

cleanup_old_artifacts() {
  local base_dir="${TMPDIR:-/tmp}"
  find "$base_dir" -maxdepth 1 -type d -name 'dbaagent-local-regression.*' -mtime +1 -exec rm -rf {} +
}

write_status() {
  local status="$1"
  {
    echo "status=$status"
    echo "pid=$$"
    echo "updated_at=$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    echo "artifact_dir=$ARTIFACT_DIR"
  } >"$STATUS_FILE"
}

finish_run() {
  local exit_code=$?
  rm -f "$PID_FILE"
  if [[ $exit_code -eq 0 ]]; then
    write_status "COMPLETED"
  else
    write_status "FAILED"
  fi
  return $exit_code
}

print_section() {
  echo ""
  echo "========================================"
  echo "  $1"
  echo "========================================"
}

record_pass() {
  PASSED_STEPS+=("$1")
  echo "PASS: $1"
}

record_fail() {
  FAILED_STEPS+=("$1")
  echo "FAIL: $1"
}

record_skip() {
  SKIPPED_STEPS+=("$1")
  echo "SKIP: $1"
}

run_step() {
  local step_name="$1"
  shift

  print_section "$step_name"
  if "$@"; then
    record_pass "$step_name"
    return 0
  fi

  record_fail "$step_name"
  return 1
}

require_backend_health() {
  curl -fsS "${BASE_URL}/actuator/health" >/dev/null
}

require_frontend_health() {
  curl -fsS "$FRONTEND_URL" >/dev/null
}

run_frontend_lint() {
  cd "$ROOT_DIR" || return 1
  npm run lint
}

run_frontend_build() {
  cd "$ROOT_DIR" || return 1
  npm run build
}

run_backend_smoke() {
  cd "$BACKEND_DIR" || return 1
  if [[ -x "./mvnw" ]]; then
    ./mvnw -q -Dtest=ApiSmokeTest test
  else
    mvn -q -Dtest=ApiSmokeTest test
  fi
}

cleanup_old_artifacts
echo "$$" >"$PID_FILE"
write_status "RUNNING"
trap finish_run EXIT

print_section "Local Regression Suite"
echo "Base URL:       $BASE_URL"
echo "Frontend URL:   $FRONTEND_URL"
echo "Connection ID:  ${CONNECTION_ID:-<not set>}"
echo "Artifacts:      $ARTIFACT_DIR"

run_step "Backend health probe" require_backend_health || true
run_step "Frontend health probe" require_frontend_health || true
if [[ "$RUN_FRONTEND_LINT" == "1" ]]; then
  run_step "Frontend lint" run_frontend_lint || true
else
  record_skip "Frontend lint"
fi

if [[ "$SKIP_FRONTEND_BUILD" == "1" ]]; then
  record_skip "Frontend production build"
else
  run_step "Frontend production build" run_frontend_build || true
fi

run_step "Backend API smoke test" run_backend_smoke || true

# Acquire a service token via the internal endpoint so the brain suite skips password login.
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

# The brain-retrieval fixture suite is not part of this repository; the regression
# suite here covers the frontend build, service health probes, and the backend
# ApiSmokeTest.
record_skip "Brain retrieval smoke suite"

print_section "Regression Summary"
echo "Artifacts: $ARTIFACT_DIR"
echo "Passed: ${#PASSED_STEPS[@]}"
for step in "${PASSED_STEPS[@]}"; do
  echo "  - $step"
done
echo "Skipped: ${#SKIPPED_STEPS[@]}"
for step in "${SKIPPED_STEPS[@]}"; do
  echo "  - $step"
done
echo "Failed: ${#FAILED_STEPS[@]}"
for step in "${FAILED_STEPS[@]}"; do
  echo "  - $step"
done

if [[ ${#FAILED_STEPS[@]} -gt 0 ]]; then
  exit 1
fi
