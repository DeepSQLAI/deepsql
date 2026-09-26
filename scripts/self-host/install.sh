#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# DeepSQL Self-Host Installer
# ═══════════════════════════════════════════════════════════════════════════════
#
# Usage:
#   ./scripts/self-host/install.sh [options]
#
# The installer checks prerequisites, generates secrets, prompts for or accepts
# LLM credentials, builds the stack from source, and verifies health. After a
# successful install, it optionally seeds a demo database.
#
# Keyless start: The stack starts without an LLM key. Chat and AI features are
# disabled until a key is configured during onboarding in the web UI.
#
# Environment variables override .env placeholders:
#   DEEPSQL_LLM_API_KEY        LLM key (optional - can set later in the web UI)
#   DEEPSQL_LLM_PROVIDER       Provider id (default: openai)
#   DEEPSQL_LLM_BASE_URL       API endpoint (default: https://api.openai.com/v1)
#   DEEPSQL_LLM_MODEL          Model name (default: gpt-4o)
#   DEEPSQL_INITIAL_ADMIN_EMAIL    Admin login email (prompted if unset)
#   DEEPSQL_INITIAL_ADMIN_PASSWORD Admin password (generated if unset)
#   DEEPSQL_FRONTEND_PORT      Frontend port (default: 3000)
#   DEEPSQL_PROJECT_NAME       Compose project name (default: deepsql-selfhost)
#
# Aliases (for backward compatibility):
#   DEEPSQL_CHAT_API_KEY, DEEPSQL_CHAT_PROVIDER, DEEPSQL_CHAT_ENDPOINT, DEEPSQL_CHAT_MODEL
#
# Options:
#   -h, --help           Show this help message and exit
#   -V, --version        Show version and exit
#   --non-interactive    Never prompt; use env vars or defaults
#   --seed-demo          Seed demo database after install (default)
#   --no-seed-demo       Skip demo database seeding
#   --fresh              Remove existing volumes before install
#   --project-name NAME  Set Compose project name
#
# License: Apache-2.0 — https://github.com/DeepSQLAI/deepsql/blob/main/LICENSE
# ═══════════════════════════════════════════════════════════════════════════════

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="${DEEPSQL_COMPOSE_FILE:-$ROOT_DIR/docker-compose.yml}"
ENV_FILE="${DEEPSQL_ENV_FILE:-$ROOT_DIR/.env}"

# Defaults
: "${DEEPSQL_PROJECT_NAME:=deepsql-selfhost}"
PROJECT_NAME="$DEEPSQL_PROJECT_NAME"

# Parse options first (before any install work)
NON_INTERACTIVE=0
SEED_DEMO=1  # Default ON per spec
FRESH_INSTALL=0
SHOW_HELP=0
SHOW_VERSION=0

# Version from git tag or commit
get_version() {
  cd "$ROOT_DIR"
  local tag
  tag="$(git describe --tags --exact-match 2>/dev/null || true)"
  if [[ -n "$tag" ]]; then
    echo "$tag"
  else
    local branch commit
    branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")"
    commit="$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")"
    echo "${branch}@${commit}"
  fi
}

usage() {
  cat <<'EOF'
DeepSQL Self-Host Installer

Usage: install.sh [options]

Options:
  -h, --help           Show this help message and exit
  -V, --version        Show version and exit
  --non-interactive    Never prompt; use env vars or defaults
  --seed-demo          Seed demo database after install (default)
  --no-seed-demo       Skip demo database seeding
  --fresh              Remove existing volumes before install
  --project-name NAME  Set Compose project name

Environment variables (override .env placeholders):
  DEEPSQL_LLM_API_KEY          LLM key (optional - can set later in the web UI)
  DEEPSQL_LLM_PROVIDER         Provider id (default: openai)
  DEEPSQL_LLM_BASE_URL         API endpoint (default: https://api.openai.com/v1)
  DEEPSQL_LLM_MODEL            Model name (default: gpt-4o)
  DEEPSQL_INITIAL_ADMIN_EMAIL  Admin login email
  DEEPSQL_INITIAL_ADMIN_PASSWORD  Admin password (generated if unset)
  DEEPSQL_FRONTEND_PORT        Frontend port (default: 3000)
  DEEPSQL_PROJECT_NAME         Compose project name

Aliases (backward compatible):
  DEEPSQL_CHAT_API_KEY, DEEPSQL_CHAT_PROVIDER, DEEPSQL_CHAT_ENDPOINT, DEEPSQL_CHAT_MODEL

Examples:
  # Interactive install (prompts for admin email)
  ./scripts/self-host/install.sh

  # Non-interactive with LLM key
  DEEPSQL_LLM_API_KEY=sk-... DEEPSQL_INITIAL_ADMIN_EMAIL=admin@example.com \
    ./scripts/self-host/install.sh --non-interactive

  # Keyless install (configure LLM later in the web UI)
  DEEPSQL_INITIAL_ADMIN_EMAIL=admin@example.com \
    ./scripts/self-host/install.sh --non-interactive

  # Fresh install (removes existing data)
  ./scripts/self-host/install.sh --fresh

For AI agents:
  DEEPSQL_LLM_API_KEY=<key> curl -fsSL https://deepsql.ai/install.sh | bash

  After install:
    Health: curl -fsS http://localhost:8080/api/actuator/health
    Login:  http://localhost:3000 (credentials in ~/deepsql/.env)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      SHOW_HELP=1
      shift
      ;;
    -V|--version)
      SHOW_VERSION=1
      shift
      ;;
    --non-interactive)
      NON_INTERACTIVE=1
      shift
      ;;
    --seed-demo)
      SEED_DEMO=1
      shift
      ;;
    --no-seed-demo)
      SEED_DEMO=0
      shift
      ;;
    --fresh)
      FRESH_INSTALL=1
      shift
      ;;
    --project-name)
      PROJECT_NAME="$2"
      shift 2
      ;;
    --project-name=*)
      PROJECT_NAME="${1#*=}"
      shift
      ;;
    *)
      echo "Error: Unknown option: $1" >&2
      echo "Run with --help for usage." >&2
      exit 1
      ;;
  esac
done

if [[ "$SHOW_HELP" -eq 1 ]]; then
  usage
  exit 0
fi

if [[ "$SHOW_VERSION" -eq 1 ]]; then
  echo "DeepSQL $(get_version)"
  exit 0
fi

# ── Colors (disabled if not a terminal) ───────────────────────────────────────
if [[ -t 1 ]]; then
  RED='\033[0;31m'
  GREEN='\033[0;32m'
  YELLOW='\033[1;33m'
  BLUE='\033[0;34m'
  BOLD='\033[1m'
  NC='\033[0m'
else
  RED='' GREEN='' YELLOW='' BLUE='' BOLD='' NC=''
fi

info()    { printf "${BLUE}==>${NC} %s\n" "$*"; }
warn()    { printf "${YELLOW}Warning:${NC} %s\n" "$*" >&2; }
error()   { printf "${RED}Error:${NC} %s\n" "$*" >&2; }
success() { printf "${GREEN}✓${NC} %s\n" "$*"; }

# ── Prerequisite Checks ───────────────────────────────────────────────────────

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    error "Required command '$1' is not installed."
    return 1
  fi
}

check_docker_permission() {
  if ! docker info >/dev/null 2>&1; then
    # Distinguish between "not running" and "permission denied"
    local docker_err
    docker_err="$(docker info 2>&1 || true)"
    
    if echo "$docker_err" | grep -qi "permission denied\|connect: permission denied\|Got permission denied"; then
      error "Docker permission denied."
      echo
      echo "Your user is not in the docker group. Fix with:"
      echo
      echo "  ${BOLD}sudo usermod -aG docker \$USER${NC}"
      echo "  ${BOLD}newgrp docker${NC}  # or log out and back in"
      echo
      echo "Then re-run this installer."
      exit 1
    elif echo "$docker_err" | grep -qi "Is the docker daemon running\|Cannot connect"; then
      error "Docker daemon is not running."
      echo
      echo "Start Docker with:"
      echo "  sudo systemctl start docker"
      echo
      echo "Then re-run this installer."
      exit 1
    else
      error "Docker is not accessible."
      echo "$docker_err" >&2
      exit 1
    fi
  fi
}

version_ge() {
  [[ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -1)" == "$2" ]]
}

check_prerequisites() {
  info "Checking prerequisites..."
  
  require_command docker || exit 1
  require_command curl || exit 1
  
  check_docker_permission
  success "Docker is running"
  
  # Check Compose v2
  if ! docker compose version >/dev/null 2>&1; then
    error "Docker Compose v2 is not installed."
    echo
    echo "Install the Compose plugin:"
    echo "  apt install docker-compose-plugin   # Debian/Ubuntu"
    echo "  dnf install docker-compose-plugin   # RHEL/Fedora"
    exit 1
  fi
  
  local compose_version
  compose_version="$(docker compose version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)"
  if [[ -z "$compose_version" ]] || ! version_ge "$compose_version" "2.0.0"; then
    error "Docker Compose $compose_version is too old (need >= 2.0.0)."
    exit 1
  fi
  success "Docker Compose $compose_version"
  
  # Check buildx
  local buildx_version
  buildx_version="$(docker buildx version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)"
  if [[ -z "$buildx_version" ]]; then
    error "Docker buildx is not installed."
    echo
    echo "Install buildx:"
    echo "  apt install docker-buildx-plugin   # Debian/Ubuntu"
    exit 1
  fi
  if ! version_ge "$buildx_version" "0.17.0"; then
    error "Docker buildx $buildx_version is too old (need >= 0.17.0)."
    echo
    echo "Upgrade buildx or run the bootstrap script:"
    echo "  curl -fsSL https://raw.githubusercontent.com/DeepSQLAI/deepsql/main/scripts/self-host/bootstrap-server.sh | sudo bash"
    exit 1
  fi
  success "Docker buildx $buildx_version"
}

# ── Volume / Fresh Install Handling ───────────────────────────────────────────

check_existing_volumes() {
  local volumes
  volumes="$(docker volume ls --filter "name=${PROJECT_NAME}" --format '{{.Name}}' 2>/dev/null || true)"
  
  if [[ -n "$volumes" ]]; then
    if [[ "$FRESH_INSTALL" -eq 1 ]]; then
      warn "Removing existing volumes for project '$PROJECT_NAME'..."
      # Stop containers first
      docker compose --project-name "$PROJECT_NAME" -f "$COMPOSE_FILE" down --volumes 2>/dev/null || true
      success "Existing volumes removed"
    else
      warn "Existing volumes found for project '$PROJECT_NAME'."
      echo "  This install will reuse existing data (admin account, connections, etc.)."
      echo "  For a fresh install, run with ${BOLD}--fresh${NC} flag."
      echo
    fi
  fi
}

# ── Environment File Handling ─────────────────────────────────────────────────

is_placeholder() {
  local value="${1:-}"
  [[ -z "$value" || "$value" == change-me-* || "$value" == replace-with-* || "$value" == your-* || "$value" == "postgres" ]]
}

# Write NAME='value' into $ENV_FILE with proper quoting
write_env_value() {
  local name="$1" value="$2" quoted
  local sq="'" esc="'\\''"
  quoted="${sq}${value//${sq}/${esc}}${sq}"
  
  if grep -q "^${name}=" "$ENV_FILE" 2>/dev/null; then
    NAME="$name" QUOTED="$quoted" python3 - "$ENV_FILE" <<'PY'
import os, re, sys
path = sys.argv[1]
name, quoted = os.environ["NAME"], os.environ["QUOTED"]
text = open(path).read()
text = re.sub(rf"(?m)^{re.escape(name)}=.*$", lambda _: f"{name}={quoted}", text)
open(path, "w").write(text)
PY
  else
    printf '%s=%s\n' "$name" "$quoted" >> "$ENV_FILE"
  fi
  export "${name}=${value}"
}

generate_secret() {
  local name="$1"
  local cmd="$2"
  local value="${!name:-}"
  if is_placeholder "$value"; then
    local generated
    generated="$(eval "$cmd")"
    write_env_value "$name" "$generated"
    echo "Auto-generated $name."
  fi
}

# ── TTY-aware Prompts ─────────────────────────────────────────────────────────

# Check if we can prompt interactively
can_prompt() {
  # Non-interactive mode disables prompts
  [[ "$NON_INTERACTIVE" -eq 1 ]] && return 1
  # Check if /dev/tty is actually accessible (not just exists)
  # When piped via setsid, /dev/tty exists but cannot be opened
  [[ -r /dev/tty ]] && [[ -w /dev/tty ]] && : </dev/tty 2>/dev/null
}

# Prompt for a value, reading from /dev/tty if available
prompt_value() {
  local name="$1"
  local label="$2"
  local required="$3"  # 1 for required, 0 for optional
  local secret="$4"    # 1 for password (hidden), 0 for normal
  local value="${!name:-}"
  
  # If already set and not a placeholder, use it
  if ! is_placeholder "$value"; then
    return 0
  fi
  
  # Can we prompt?
  if can_prompt; then
    if [[ "$secret" -eq 1 ]]; then
      printf '%s: ' "$label" >/dev/tty
      read -rs value </dev/tty
      printf '\n' >/dev/tty
    else
      printf '%s: ' "$label" >/dev/tty
      read -r value </dev/tty || true
    fi
    
    if [[ -n "$value" ]]; then
      write_env_value "$name" "$value"
      return 0
    fi
  fi
  
  # No value and can't prompt (or user skipped)
  if [[ "$required" -eq 1 ]]; then
    if [[ "$NON_INTERACTIVE" -eq 1 ]]; then
      echo "NEEDS_USER_INPUT: $name (required)"
      return 1
    else
      error "'$name' is required."
      return 1
    fi
  fi
  
  return 0
}

# ── Compose Helper ────────────────────────────────────────────────────────────

compose() {
  DEEPSQL_RUNTIME_ENV_FILE="$ENV_FILE" docker compose \
    --project-name "$PROJECT_NAME" \
    --env-file "$ENV_FILE" \
    -f "$COMPOSE_FILE" \
    "$@"
}

# ── Health Checks ─────────────────────────────────────────────────────────────

wait_for_http() {
  local url="$1"
  local label="$2"
  local retries="${3:-90}"
  local delay="${4:-2}"
  for ((i=1; i<=retries; i++)); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      echo "$label is healthy: $url"
      return 0
    fi
    sleep "$delay"
  done
  error "Timed out waiting for $label at $url"
  return 1
}

# ── Database Setup ────────────────────────────────────────────────────────────

ensure_scheduler_table() {
  local sql_file="$ROOT_DIR/docker/postgres/init/01_create_scheduled_tasks.sql"
  if [[ ! -f "$sql_file" ]]; then
    error "Missing scheduler bootstrap SQL at $sql_file"
    exit 1
  fi
  compose exec -T postgres psql -U postgres -d dba_agent -v ON_ERROR_STOP=1 < "$sql_file" >/dev/null
  echo "Ensured db-scheduler table exists in the vault database."
}

ensure_pg_stat_statements() {
  compose exec -T postgres psql -U postgres -d dba_agent -v ON_ERROR_STOP=1 \
    -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" >/dev/null
  echo "Ensured pg_stat_statements extension exists in the vault database."
}

ensure_pgvector_store() {
  if [[ "${VECTOR_STORE_TYPE:-pgvector}" != "pgvector" ]]; then
    return 0
  fi

  local expected_dims="${VECTOR_STORE_EMBEDDING_DIMENSIONS:-3072}"
  local result
  result="$(compose exec -T postgres psql -U postgres -d dba_agent -At -c "
    SELECT EXISTS(SELECT 1 FROM pg_extension WHERE extname = 'vector');
    SELECT EXISTS(
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = 'public' AND table_name = 'rag_documents'
    );
    SELECT COALESCE(
      (SELECT pg_catalog.format_type(a.atttypid, a.atttypmod)
       FROM pg_attribute a
       JOIN pg_class c ON c.oid = a.attrelid
       JOIN pg_namespace n ON n.oid = c.relnamespace
       WHERE n.nspname = 'public' AND c.relname = 'rag_documents'
         AND a.attname = 'embedding' AND a.attnum > 0 AND NOT a.attisdropped),
      ''
    );
    SELECT EXISTS(
      SELECT 1 FROM pg_indexes
      WHERE schemaname = 'public' AND tablename = 'rag_documents'
        AND indexname = 'idx_rag_docs_embedding'
    );
  ")"

  local has_vector has_table embedding_type has_ann_index
  has_vector="$(printf '%s\n' "$result" | sed -n '1p')"
  has_table="$(printf '%s\n' "$result" | sed -n '2p')"
  embedding_type="$(printf '%s\n' "$result" | sed -n '3p')"
  has_ann_index="$(printf '%s\n' "$result" | sed -n '4p')"

  if [[ "$has_table" != "t" ]]; then
    error "Local pgvector RAG store was not initialized (rag_documents table missing)."
    exit 1
  fi

  if [[ "$has_vector" != "t" ]]; then
    error "VECTOR_STORE_TYPE=pgvector but the PostgreSQL 'vector' extension is not installed."
    exit 1
  fi

  if [[ "$embedding_type" != "vector(${expected_dims})" ]]; then
    error "rag_documents.embedding is '$embedding_type' instead of 'vector(${expected_dims})'."
    exit 1
  fi

  if [[ "$has_ann_index" != "t" ]]; then
    error "Local pgvector ANN index idx_rag_docs_embedding is missing."
    exit 1
  fi

  echo "Verified local pgvector RAG store in the vault database."
}

# ── Admin Bootstrap ───────────────────────────────────────────────────────────

bootstrap_admin() {
  if [[ "${SECURITY_ADMIN_BOOTSTRAP_ENABLED:-false}" != "true" ]]; then
    return 0
  fi

  if [[ -z "${ADMIN_BOOTSTRAP_SECRET:-}" || -z "${DEEPSQL_INITIAL_ADMIN_PASSWORD:-}" || -z "${DEEPSQL_INITIAL_ADMIN_EMAIL:-}" ]]; then
    warn "Admin bootstrap enabled, but credentials not all set. Skipping bootstrap."
    return 0
  fi

  local payload response
  payload="$(printf '{\"email\":\"%s\",\"password\":\"%s\"}' \
    "${DEEPSQL_INITIAL_ADMIN_EMAIL}" \
    "${DEEPSQL_INITIAL_ADMIN_PASSWORD}")"
  
  response="$(printf '%s' "$payload" | compose exec -T \
    -e ADMIN_BOOTSTRAP_SECRET="${ADMIN_BOOTSTRAP_SECRET}" \
    backend sh -lc \
    'curl -fsS -H "Content-Type: application/json" -H "X-Admin-Bootstrap-Secret: ${ADMIN_BOOTSTRAP_SECRET}" -X POST http://localhost:8080/api/users/admin/reset --data @-' || true)"

  if [[ "$response" == *"Admin reset successfully"* || "$response" == *"Admin created successfully"* ]]; then
    # Fixed: login is by email, not "username: admin"
    echo "Admin bootstrap complete. Login email: ${DEEPSQL_INITIAL_ADMIN_EMAIL}"
  else
    error "Admin bootstrap did not return a success message."
    echo "$response" >&2
    return 1
  fi
}

wait_for_login() {
  local url="http://localhost:${DEEPSQL_BACKEND_PORT:-8080}/api/auth/login"
  local payload deadline=$((SECONDS + 120))
  payload="$(printf '{"email":"%s","password":"%s"}' \
    "${DEEPSQL_INITIAL_ADMIN_EMAIL}" "${DEEPSQL_INITIAL_ADMIN_PASSWORD}")"
  
  while (( SECONDS < deadline )); do
    if curl -fsS -o /dev/null -H 'Content-Type: application/json' \
         -X POST "$url" --data "$payload" 2>/dev/null; then
      echo "Login verified for ${DEEPSQL_INITIAL_ADMIN_EMAIL}."
      return 0
    fi
    sleep 5
  done
  error "The admin account was created but could not log in within 120s."
  echo "Check 'docker compose logs backend' before running smoke-test.sh." >&2
  return 1
}

sed_inplace() {
  if [[ "$(uname)" == "Darwin" ]]; then
    sed -i '' "$@"
  else
    sed -i "$@"
  fi
}

# ── Build ─────────────────────────────────────────────────────────────────────

build_application_images() {
  echo "Building the DeepSQL backend, frontend, and DeepSQL Agent from source..."
  echo "The first build compiles the Java backend, bundles the frontend, and builds"
  echo "the DeepSQL Agent image; expect several minutes. Subsequent runs reuse the"
  echo "Docker layer cache and are quick."
  compose build backend frontend deepsql-agent
}

# ── CLI Setup ─────────────────────────────────────────────────────────────────

install_deepsql_cli() {
  if npm i -g @deepsql/mcp >/dev/null 2>&1; then
    return 0
  fi
  if command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
    if sudo npm i -g @deepsql/mcp >/dev/null 2>&1; then
      return 0
    fi
  fi
  return 1
}

setup_deepsql_cli() {
  if ! command -v npm >/dev/null 2>&1; then
    echo "DeepSQL CLI: npm not found — skipping install."
    echo "  The CLI is optional; install Node 20+ then: npm i -g @deepsql/mcp"
    echo
    return 0
  fi

  local installed latest
  installed="$(deepsql --version 2>/dev/null | tr -d '[:space:]' || true)"

  if [[ -z "$installed" ]]; then
    echo "Installing DeepSQL CLI (@deepsql/mcp)…"
    if install_deepsql_cli; then
      installed="$(deepsql --version 2>/dev/null | tr -d '[:space:]' || true)"
      echo "DeepSQL CLI: ${installed:-installed}."
    else
      echo "DeepSQL CLI: install failed (npm i -g @deepsql/mcp may need elevated"
      echo "  permissions on this system). Install it yourself, then:"
      echo "  deepsql login --url http://localhost:${DEEPSQL_BACKEND_PORT:-8080}"
      echo
      return 0
    fi
  else
    latest="$(npm view @deepsql/mcp version 2>/dev/null | tr -d '[:space:]' || true)"
    if [[ -z "$latest" ]]; then
      echo "DeepSQL CLI: ${installed} installed (could not reach npm to check for updates)."
    elif [[ "$installed" != "$latest" ]]; then
      echo "DeepSQL CLI: ${installed} installed, ${latest} available."
      echo "  Update with:  npm i -g @deepsql/mcp@latest"
    else
      echo "DeepSQL CLI: ${installed} (up to date)."
    fi
  fi

  if ! command -v deepsql >/dev/null 2>&1; then
    echo
    return 0
  fi

  if [[ -z "${DEEPSQL_INITIAL_ADMIN_EMAIL:-}" || -z "${DEEPSQL_INITIAL_ADMIN_PASSWORD:-}" ]]; then
    echo "  Point it at this stack:  deepsql login --url http://localhost:${DEEPSQL_BACKEND_PORT:-8080}"
    echo
    return 0
  fi

  if deepsql whoami --url "http://localhost:${DEEPSQL_BACKEND_PORT:-8080}" >/dev/null 2>&1; then
    echo "DeepSQL CLI: already logged in as ${DEEPSQL_INITIAL_ADMIN_EMAIL}."
  else
    echo "Logging in the DeepSQL CLI as ${DEEPSQL_INITIAL_ADMIN_EMAIL}…"
    if printf '%s' "${DEEPSQL_INITIAL_ADMIN_PASSWORD}" | deepsql login \
         --url "http://localhost:${DEEPSQL_BACKEND_PORT:-8080}" --password \
         --email "${DEEPSQL_INITIAL_ADMIN_EMAIL}" --password-stdin --label install 2>/dev/null; then
      :
    else
      echo "DeepSQL CLI: login failed. Run manually:"
      echo "  deepsql login --url http://localhost:${DEEPSQL_BACKEND_PORT:-8080}"
    fi
  fi
  echo
}

# ── Demo Seeding ──────────────────────────────────────────────────────────────

run_demo_seed() {
  if [[ "$SEED_DEMO" -eq 0 ]]; then
    echo "Demo data seeding skipped (--no-seed-demo)."
    echo "  Run ./scripts/self-host/seed-demo-data.sh for a ready-to-explore demo database."
    echo
    return 0
  fi

  if [[ -x "$SCRIPT_DIR/seed-demo-data.sh" ]]; then
    echo "Seeding demo data..."
    # Seed failure is a warning, not an install failure
    if "$SCRIPT_DIR/seed-demo-data.sh"; then
      echo "Demo data seeding complete."
    else
      warn "Demo data seeding had issues. The stack still works."
      echo "  Run manually: ./scripts/self-host/seed-demo-data.sh"
    fi
    echo
  else
    warn "seed-demo-data.sh not found. Skipping demo seeding."
  fi
}

# ── Print Final Summary ───────────────────────────────────────────────────────

print_summary() {
  local frontend_port="${DEEPSQL_FRONTEND_PORT:-3000}"
  local backend_port="${DEEPSQL_BACKEND_PORT:-8080}"
  local has_llm_key=0
  [[ -n "${DEEPSQL_CHAT_API_KEY:-}" ]] && ! is_placeholder "${DEEPSQL_CHAT_API_KEY:-}" && has_llm_key=1
  
  echo
  echo "${BOLD}═══════════════════════════════════════════════════════════════════════════${NC}"
  echo "${GREEN}${BOLD}  DeepSQL is running!${NC}"
  echo "${BOLD}═══════════════════════════════════════════════════════════════════════════${NC}"
  echo
  echo "  ${BOLD}Login URL:${NC}       http://localhost:${frontend_port}"
  echo "  ${BOLD}Login email:${NC}     ${DEEPSQL_INITIAL_ADMIN_EMAIL}"
  echo "  ${BOLD}Password:${NC}        stored in ${ENV_FILE}"
  echo "  ${BOLD}Health URL:${NC}      http://localhost:${backend_port}/api/actuator/health"
  echo
  
  if [[ "$has_llm_key" -eq 0 ]]; then
    echo "${YELLOW}  Note: No LLM key configured. Chat and AI features are disabled.${NC}"
    echo "  Add the key during onboarding in the web UI, or re-run the installer with:"
    echo "    DEEPSQL_LLM_API_KEY=<key> ./scripts/self-host/install.sh"
    echo
  fi
  
  echo "Project:  $PROJECT_NAME"
  echo "Images:   built from source in this checkout"
  echo
  echo "Useful commands:"
  echo "  ./scripts/self-host/status.sh"
  echo "  ./scripts/self-host/smoke-test.sh"
  echo "  ./scripts/self-host/seed-demo-data.sh       # Seed demo e-commerce database"
  echo "  docker compose logs -f backend              # Backend logs"
  echo "  ./scripts/self-host/uninstall.sh"
}

# ═══════════════════════════════════════════════════════════════════════════════
# MAIN
# ═══════════════════════════════════════════════════════════════════════════════

main() {
  echo
  echo "${BOLD}═══════════════════════════════════════════════════════════════════════════${NC}"
  echo "${BOLD}  DeepSQL Self-Host Installer${NC}"
  echo "${BOLD}═══════════════════════════════════════════════════════════════════════════${NC}"
  echo
  
  check_prerequisites
  
  # ── Create .env if needed ───────────────────────────────────────────────────
  if [[ ! -f "$ENV_FILE" ]]; then
    if [[ -f "$ROOT_DIR/.env.example" ]]; then
      cp "$ROOT_DIR/.env.example" "$ENV_FILE"
      info "Created $ENV_FILE from .env.example"
    else
      error ".env.example not found in checkout."
      exit 1
    fi
  fi
  
  # ── Env var aliasing ─────────────────────────────────────────────────────────
  # DEEPSQL_LLM_* is the primary documented name; DEEPSQL_CHAT_* is the alias.
  # The backend uses DEEPSQL_CHAT_*, so we map LLM->CHAT here.
  if [[ -n "${DEEPSQL_LLM_API_KEY:-}" ]]; then
    export DEEPSQL_CHAT_API_KEY="${DEEPSQL_CHAT_API_KEY:-$DEEPSQL_LLM_API_KEY}"
  fi
  if [[ -n "${DEEPSQL_LLM_PROVIDER:-}" ]]; then
    export DEEPSQL_CHAT_PROVIDER="${DEEPSQL_CHAT_PROVIDER:-$DEEPSQL_LLM_PROVIDER}"
  fi
  if [[ -n "${DEEPSQL_LLM_BASE_URL:-}" ]]; then
    export DEEPSQL_CHAT_ENDPOINT="${DEEPSQL_CHAT_ENDPOINT:-$DEEPSQL_LLM_BASE_URL}"
  fi
  if [[ -n "${DEEPSQL_LLM_MODEL:-}" ]]; then
    export DEEPSQL_CHAT_MODEL="${DEEPSQL_CHAT_MODEL:-$DEEPSQL_LLM_MODEL}"
  fi
  
  # ── Load .env but let env vars take precedence ──────────────────────────────
  # Store current env vars that should override .env
  declare -A override_vars
  for var in DEEPSQL_CHAT_API_KEY DEEPSQL_CHAT_PROVIDER DEEPSQL_CHAT_ENDPOINT DEEPSQL_CHAT_MODEL \
             DEEPSQL_LLM_API_KEY DEEPSQL_LLM_PROVIDER DEEPSQL_LLM_BASE_URL DEEPSQL_LLM_MODEL \
             DEEPSQL_EMBEDDING_PROVIDER DEEPSQL_EMBEDDING_API_KEY DEEPSQL_EMBEDDING_ENDPOINT DEEPSQL_EMBEDDING_MODEL \
             DEEPSQL_INITIAL_ADMIN_EMAIL DEEPSQL_INITIAL_ADMIN_PASSWORD \
             DEEPSQL_FRONTEND_PORT DEEPSQL_BACKEND_PORT DEEPSQL_POSTGRES_PORT DEEPSQL_VALKEY_PORT \
             SECURITY_JWT_SECRET ENCRYPTION_KEY DB_PASSWORD DEEPSQL_VALKEY_PASSWORD \
             ADMIN_BOOTSTRAP_SECRET AGENT_PROVISION_SECRET DEEPSQL_COMPANY_NAME; do
    if [[ -n "${!var:-}" ]]; then
      override_vars[$var]="${!var}"
    fi
  done
  
  # Source .env (path determined at runtime)
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
  
  # Restore overrides (env vars take precedence over .env placeholders)
  for var in "${!override_vars[@]}"; do
    export "$var=${override_vars[$var]}"
  done
  
  # Re-apply LLM->CHAT aliasing after sourcing .env (in case .env had DEEPSQL_LLM_*)
  if [[ -n "${DEEPSQL_LLM_API_KEY:-}" ]] && [[ -z "${DEEPSQL_CHAT_API_KEY:-}" || "${DEEPSQL_CHAT_API_KEY}" == replace-with-* ]]; then
    export DEEPSQL_CHAT_API_KEY="$DEEPSQL_LLM_API_KEY"
  fi
  if [[ -n "${DEEPSQL_LLM_PROVIDER:-}" ]] && [[ -z "${DEEPSQL_CHAT_PROVIDER:-}" || "${DEEPSQL_CHAT_PROVIDER}" == "openai" ]]; then
    export DEEPSQL_CHAT_PROVIDER="$DEEPSQL_LLM_PROVIDER"
  fi
  if [[ -n "${DEEPSQL_LLM_BASE_URL:-}" ]] && [[ -z "${DEEPSQL_CHAT_ENDPOINT:-}" || "${DEEPSQL_CHAT_ENDPOINT}" == https://api.openai.com* ]]; then
    export DEEPSQL_CHAT_ENDPOINT="$DEEPSQL_LLM_BASE_URL"
  fi
  if [[ -n "${DEEPSQL_LLM_MODEL:-}" ]] && [[ -z "${DEEPSQL_CHAT_MODEL:-}" || "${DEEPSQL_CHAT_MODEL}" == "gpt-4o" ]]; then
    export DEEPSQL_CHAT_MODEL="$DEEPSQL_LLM_MODEL"
  fi
  
  # ── Check for existing volumes ──────────────────────────────────────────────
  check_existing_volumes
  
  # ── Auto-generate security secrets if still placeholders ────────────────────
  generate_secret SECURITY_JWT_SECRET "openssl rand -base64 64 | tr -d '\n'"
  generate_secret ENCRYPTION_KEY "openssl rand -base64 32 | tr -d '\n'"
  generate_secret DB_PASSWORD "openssl rand -base64 16 | tr -d '\n'"
  generate_secret DEEPSQL_VALKEY_PASSWORD "openssl rand -base64 24 | tr -d '\n'"
  generate_secret ADMIN_BOOTSTRAP_SECRET "openssl rand -base64 32 | tr -d '\n'"
  generate_secret AGENT_PROVISION_SECRET "openssl rand -base64 32 | tr -d '\n'"
  
  # ── LLM Configuration (optional - keyless start allowed) ────────────────────
  # Set defaults for provider/endpoint/model if key is provided
  if [[ -n "${DEEPSQL_CHAT_API_KEY:-}" ]] && ! is_placeholder "${DEEPSQL_CHAT_API_KEY:-}"; then
    # Key is set, ensure provider and endpoint have defaults
    if is_placeholder "${DEEPSQL_CHAT_PROVIDER:-}"; then
      write_env_value DEEPSQL_CHAT_PROVIDER "openai"
    fi
    if is_placeholder "${DEEPSQL_CHAT_ENDPOINT:-}"; then
      write_env_value DEEPSQL_CHAT_ENDPOINT "https://api.openai.com/v1"
    fi
    if is_placeholder "${DEEPSQL_CHAT_MODEL:-}"; then
      write_env_value DEEPSQL_CHAT_MODEL "gpt-4o"
    fi
  else
    # No key - prompt if interactive, otherwise allow keyless start
    if can_prompt; then
      echo
      echo "LLM API key (e.g., OpenAI sk-... key)."
      echo "Press Enter to skip and configure later during onboarding in the web UI."
      prompt_value DEEPSQL_CHAT_API_KEY "LLM API key" 0 1
    fi
    
    if [[ -n "${DEEPSQL_CHAT_API_KEY:-}" ]] && ! is_placeholder "${DEEPSQL_CHAT_API_KEY:-}"; then
      # User provided key - set defaults
      if is_placeholder "${DEEPSQL_CHAT_PROVIDER:-}"; then
        write_env_value DEEPSQL_CHAT_PROVIDER "openai"
      fi
      if is_placeholder "${DEEPSQL_CHAT_ENDPOINT:-}"; then
        write_env_value DEEPSQL_CHAT_ENDPOINT "https://api.openai.com/v1"
      fi
      if is_placeholder "${DEEPSQL_CHAT_MODEL:-}"; then
        write_env_value DEEPSQL_CHAT_MODEL "gpt-4o"
      fi
    else
      # Keyless start - clear placeholders so backend doesn't reject them
      if is_placeholder "${DEEPSQL_CHAT_API_KEY:-}"; then
        write_env_value DEEPSQL_CHAT_API_KEY ""
      fi
      if is_placeholder "${DEEPSQL_CHAT_PROVIDER:-}"; then
        write_env_value DEEPSQL_CHAT_PROVIDER ""
      fi
      echo
      echo "No LLM key configured. Chat and AI features will be disabled."
      echo "Configure your LLM key during onboarding in the web UI after logging in."
      # Print machine-readable output when non-interactive OR when no TTY available
      if [[ "$NON_INTERACTIVE" -eq 1 ]] || ! can_prompt; then
        echo "NEEDS_USER_INPUT: DEEPSQL_LLM_API_KEY (optional, can be set during onboarding at http://localhost:${DEEPSQL_FRONTEND_PORT:-3000})"
      fi
    fi
  fi
  
  # ── Admin account ───────────────────────────────────────────────────────────
  prompt_value DEEPSQL_INITIAL_ADMIN_EMAIL "Initial admin email" 1 0 || {
    if [[ "$NON_INTERACTIVE" -eq 1 ]]; then
      error "DEEPSQL_INITIAL_ADMIN_EMAIL is required for non-interactive install."
      echo "Set it via environment variable: DEEPSQL_INITIAL_ADMIN_EMAIL=admin@example.com"
    fi
    exit 1
  }
  
  # Generate password if not provided
  if is_placeholder "${DEEPSQL_INITIAL_ADMIN_PASSWORD:-}"; then
    local gen_password
    gen_password="$(openssl rand -base64 16 | tr -d '\n')"
    write_env_value DEEPSQL_INITIAL_ADMIN_PASSWORD "$gen_password"
    echo "Auto-generated admin password (saved to .env)."
  fi
  
  # Optional company name
  if can_prompt && is_placeholder "${DEEPSQL_COMPANY_NAME:-}"; then
    prompt_value DEEPSQL_COMPANY_NAME "Company / organization name (optional, press Enter to skip)" 0 0
  fi
  
  # ── Export required variables ───────────────────────────────────────────────
  : "${SPRING_PROFILES_ACTIVE:=prod}"
  : "${DEEPSQL_FRONTEND_PORT:=3000}"
  : "${DEEPSQL_BACKEND_PORT:=8080}"
  : "${DEEPSQL_POSTGRES_PORT:=5432}"
  : "${DEEPSQL_VALKEY_PORT:=6379}"
  : "${CORS_ALLOWED_ORIGINS:=http://localhost:${DEEPSQL_FRONTEND_PORT}}"

  if [[ "${VECTOR_STORE_TYPE:-pgvector}" == "pgvector" && -z "${SPRING_AUTOCONFIGURE_EXCLUDE:-}" ]]; then
    SPRING_AUTOCONFIGURE_EXCLUDE="org.springframework.ai.vectorstore.azure.autoconfigure.AzureVectorStoreAutoConfiguration"
  fi

  export SPRING_PROFILES_ACTIVE DEEPSQL_FRONTEND_PORT DEEPSQL_BACKEND_PORT
  export DEEPSQL_POSTGRES_PORT DEEPSQL_VALKEY_PORT CORS_ALLOWED_ORIGINS
  export SPRING_AUTOCONFIGURE_EXCLUDE
  export SECURITY_ADMIN_BOOTSTRAP_ENABLED=true
  
  sed_inplace "s|^SECURITY_ADMIN_BOOTSTRAP_ENABLED=.*|SECURITY_ADMIN_BOOTSTRAP_ENABLED=true|" "$ENV_FILE"
  
  # ── Validate required secrets ───────────────────────────────────────────────
  local missing_secrets=0
  for var in SECURITY_JWT_SECRET ENCRYPTION_KEY ENCRYPTION_KEY_ID DB_PASSWORD DEEPSQL_VALKEY_PASSWORD; do
    if is_placeholder "${!var:-}"; then
      error "'$var' must be set."
      missing_secrets=1
    fi
  done
  [[ "$missing_secrets" -eq 1 ]] && exit 1

  # ── Build and start ─────────────────────────────────────────────────────────
  echo
  info "Starting DeepSQL self-hosted stack with project '$PROJECT_NAME'..."
  build_application_images
  compose up -d
  
  ensure_scheduler_table
  ensure_pg_stat_statements
  wait_for_http "http://localhost:${DEEPSQL_BACKEND_PORT}/api/actuator/health" "Backend"
  wait_for_http "http://localhost:${DEEPSQL_FRONTEND_PORT}" "Frontend"
  ensure_pgvector_store
  
  bootstrap_admin
  
  # Disable bootstrap and restart backend
  sed_inplace "s|^SECURITY_ADMIN_BOOTSTRAP_ENABLED=.*|SECURITY_ADMIN_BOOTSTRAP_ENABLED=false|" "$ENV_FILE"
  export SECURITY_ADMIN_BOOTSTRAP_ENABLED=false
  compose up -d backend >/dev/null
  wait_for_http "http://localhost:${DEEPSQL_BACKEND_PORT}/api/actuator/health" "Backend"
  wait_for_login
  
  echo
  
  # Wait for agent
  if wait_for_http "http://localhost:${DEEPSQL_AGENT_PROVISIONER_PORT:-8788}/health" "DeepSQL Agent" 60 2; then
    echo "DeepSQL Agent is healthy."
  else
    warn "DeepSQL Agent did not become healthy in time."
    echo "  The core UI still works. Check: docker compose logs deepsql-agent"
  fi
  echo
  
  # Host-side agent (only for native development)
  if [[ "${DEEPSQL_HOST_AGENT_SETUP:-0}" == "1" ]]; then
    if [[ -x "$SCRIPT_DIR/setup-agent.sh" ]]; then
      echo "Starting host-side DeepSQL Agent (DEEPSQL_HOST_AGENT_SETUP=1)…"
      if "$SCRIPT_DIR/setup-agent.sh"; then
        echo "Host agent setup complete."
      else
        warn "Host agent setup failed."
      fi
      echo
    fi
  fi
  
  # CLI setup
  setup_deepsql_cli
  
  # Demo seeding
  run_demo_seed
  
  # Final summary
  print_summary
}

main
