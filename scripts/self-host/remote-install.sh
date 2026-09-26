#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# DeepSQL Remote Installer
# ═══════════════════════════════════════════════════════════════════════════════
#
# One-liner install:
#   curl -fsSL https://deepsql.ai/install.sh | bash
#
# This script clones the repository and runs the full install. Pass environment
# variables for LLM credentials and admin account; everything else is generated
# or prompted.
#
# For AI agents (non-interactive):
#   DEEPSQL_CHAT_API_KEY=sk-... curl -fsSL https://deepsql.ai/install.sh | bash
#
# Fallback (raw GitHub URL):
#   curl -fsSL https://raw.githubusercontent.com/DeepSQLAI/deepsql/main/scripts/self-host/remote-install.sh | bash
#
# This script is the source of truth. deepsql.ai/install.sh serves a copy for the
# homepage CTA. The public GitHub repository: https://github.com/DeepSQLAI/deepsql
#
# You can always clone and build manually instead:
#   git clone https://github.com/DeepSQLAI/deepsql.git
#   cd deepsql && ./scripts/self-host/install.sh
#
# Security: This script downloads only from GitHub (raw.githubusercontent.com,
# github.com, api.github.com) for the DeepSQLAI organization. No third-party
# binaries are fetched. DeepSQL has no container registry — everything is built
# from source in your checkout.
#
# License: Apache-2.0 — https://github.com/DeepSQLAI/deepsql/blob/main/LICENSE
# ═══════════════════════════════════════════════════════════════════════════════

set -euo pipefail

DEEPSQL_REPO="DeepSQLAI/deepsql"
DEEPSQL_HOME="${DEEPSQL_HOME:-$HOME/deepsql}"
DEEPSQL_REF="${DEEPSQL_REF:-}"  # empty = auto-detect latest release tag

# Script version (updated with releases)
REMOTE_INSTALLER_VERSION="1.4.0"

# Colors (disabled if not a terminal)
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

# ── OS / Architecture Detection ───────────────────────────────────────────────

detect_platform() {
  OS="$(uname -s)"
  ARCH="$(uname -m)"
  
  case "$OS" in
    Linux)  OS_TYPE="linux" ;;
    Darwin) OS_TYPE="darwin" ;;
    MINGW*|MSYS*|CYGWIN*) OS_TYPE="windows" ;;
    *)
      error "Unsupported operating system: $OS"
      error "DeepSQL self-hosting requires Linux or macOS with Docker."
      exit 1
      ;;
  esac
  
  case "$ARCH" in
    x86_64|amd64)  ARCH_TYPE="amd64" ;;
    aarch64|arm64) ARCH_TYPE="arm64" ;;
    *)
      error "Unsupported architecture: $ARCH"
      exit 1
      ;;
  esac
  
  info "Detected: $OS_TYPE/$ARCH_TYPE"
}

# ── Prerequisite Checks ───────────────────────────────────────────────────────

require_command() {
  local cmd="$1"
  local install_hint="${2:-}"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    error "Required command not found: $cmd"
    [[ -n "$install_hint" ]] && echo "  $install_hint" >&2
    return 1
  fi
}

check_prerequisites() {
  info "Checking prerequisites..."
  local missing=0
  
  require_command bash || missing=1
  require_command curl "Install with your package manager (apt/dnf/brew)" || missing=1
  require_command git "Install with your package manager (apt/dnf/brew)" || missing=1
  
  if [[ "$missing" -eq 1 ]]; then
    error "Missing required tools. Install them and retry."
    exit 1
  fi
  success "bash, curl, git available"
}

version_ge() {
  [[ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -1)" == "$2" ]]
}

check_docker_permission() {
  if ! docker info >/dev/null 2>&1; then
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
      echo "Then re-run this installer:"
      echo "  curl -fsSL https://deepsql.ai/install.sh | bash"
      exit 1
    elif echo "$docker_err" | grep -qi "Is the docker daemon running\|Cannot connect"; then
      error "Docker daemon is not running."
      echo
      echo "Start Docker with:"
      echo "  sudo systemctl start docker"
      echo
      echo "Then re-run this installer."
      exit 1
    fi
  fi
}

check_docker() {
  info "Checking Docker..."
  
  if ! command -v docker >/dev/null 2>&1; then
    error "Docker is not installed."
    echo
    if [[ "$OS_TYPE" == "linux" ]]; then
      echo "On a fresh Linux server, run the bootstrap script first:"
      echo "  ${BOLD}curl -fsSL https://raw.githubusercontent.com/${DEEPSQL_REPO}/main/scripts/self-host/bootstrap-server.sh | sudo bash${NC}"
      echo
      echo "Or install Docker manually:"
      echo "  curl -fsSL https://get.docker.com | sh"
      echo
      echo "After installing Docker, re-run this installer:"
      echo "  curl -fsSL https://deepsql.ai/install.sh | bash"
    elif [[ "$OS_TYPE" == "darwin" ]]; then
      echo "Install Docker Desktop for Mac from:"
      echo "  https://www.docker.com/products/docker-desktop"
    fi
    exit 1
  fi
  
  check_docker_permission
  success "Docker is running"
  
  # Check Compose v2
  local compose_version
  compose_version="$(docker compose version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)"
  if [[ -z "$compose_version" ]]; then
    error "Docker Compose v2 is not installed."
    echo
    echo "Compose v2 comes bundled with Docker Desktop. On Linux, install the plugin:"
    echo "  apt install docker-compose-plugin   # Debian/Ubuntu"
    echo "  dnf install docker-compose-plugin   # RHEL/Fedora"
    exit 1
  fi
  if ! version_ge "$compose_version" "2.0.0"; then
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
    echo "buildx comes bundled with Docker Desktop. On Linux:"
    echo "  apt install docker-buildx-plugin   # Debian/Ubuntu"
    echo
    echo "Or run the bootstrap script:"
    echo "  curl -fsSL https://raw.githubusercontent.com/${DEEPSQL_REPO}/main/scripts/self-host/bootstrap-server.sh | sudo bash"
    echo
    echo "Then re-run this installer."
    exit 1
  fi
  if ! version_ge "$buildx_version" "0.17.0"; then
    error "Docker buildx $buildx_version is too old (need >= 0.17.0)."
    echo
    echo "Upgrade buildx or run the bootstrap script to install a newer version."
    exit 1
  fi
  success "Docker buildx $buildx_version"
}

# ── Release Tag Detection with Retry ──────────────────────────────────────────

get_latest_release_tag() {
  local max_retries=3
  local retry_delay=2
  local attempt
  
  for ((attempt=1; attempt<=max_retries; attempt++)); do
    local tags
    tags="$(curl -fsSL --retry 2 "https://api.github.com/repos/${DEEPSQL_REPO}/tags?per_page=50" 2>/dev/null || true)"
    
    if [[ -n "$tags" ]]; then
      # Extract tag names, filter to product releases only:
      # - Must start with v followed by a digit (v1.0.0, v2.3.4, etc.)
      # - Excludes desktop-v*, agent-v*, or any other prefixed tags
      local latest
      latest="$(echo "$tags" \
        | grep -o '"name": *"[^"]*"' \
        | cut -d'"' -f4 \
        | grep -E '^v[0-9]' \
        | grep -v '^desktop-' \
        | sort -V -r \
        | head -1 || true)"
      
      if [[ -n "$latest" ]]; then
        echo "$latest"
        return 0
      fi
    fi
    
    if [[ "$attempt" -lt "$max_retries" ]]; then
      sleep "$retry_delay"
      retry_delay=$((retry_delay * 2))
    fi
  done
  
  return 1
}

# Fallback: use git ls-remote (not rate-limited like API)
get_latest_release_tag_git() {
  local tags
  tags="$(git ls-remote --tags "https://github.com/${DEEPSQL_REPO}.git" 2>/dev/null | \
    grep -oE 'refs/tags/v[0-9][^{]*$' | \
    sed 's|refs/tags/||' | \
    grep -v '^desktop-' | \
    sort -V -r | \
    head -1 || true)"
  
  if [[ -n "$tags" ]]; then
    echo "$tags"
    return 0
  fi
  return 1
}

# ── Clone / Update Repository ─────────────────────────────────────────────────

clone_or_update() {
  local target_ref="$1"
  
  if [[ -d "$DEEPSQL_HOME/.git" ]]; then
    info "Updating existing checkout at $DEEPSQL_HOME..."
    cd "$DEEPSQL_HOME"
    
    # Fetch latest (include tags)
    if ! git fetch --tags origin 2>/dev/null; then
      warn "Failed to fetch updates. Continuing with existing checkout."
    fi
    
    if [[ -n "$target_ref" ]]; then
      info "Checking out $target_ref..."
      git checkout "$target_ref" 2>/dev/null || git checkout -b "$target_ref" "origin/$target_ref" 2>/dev/null || {
        git checkout "$target_ref" 2>/dev/null || {
          warn "Could not checkout $target_ref. Staying on current branch."
        }
      }
    fi
    
    success "Repository updated"
    report_version "$target_ref"
  else
    info "Cloning DeepSQL to $DEEPSQL_HOME..."
    
    mkdir -p "$(dirname "$DEEPSQL_HOME")"
    
    local clone_args=(--depth 1)
    if [[ -n "$target_ref" ]]; then
      clone_args+=(--branch "$target_ref")
    fi
    
    if ! git clone "${clone_args[@]}" "https://github.com/${DEEPSQL_REPO}.git" "$DEEPSQL_HOME"; then
      error "Failed to clone repository."
      exit 1
    fi
    
    cd "$DEEPSQL_HOME"
    success "Repository cloned"
    report_version "$target_ref"
  fi
}

report_version() {
  local target_ref="$1"
  
  if [[ "$target_ref" =~ ^v[0-9] ]]; then
    success "Version: $target_ref"
    return
  fi
  
  local current_tag current_branch
  current_tag="$(git describe --tags --exact-match 2>/dev/null || true)"
  current_branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  
  if [[ -n "$current_tag" ]]; then
    success "Version: $current_tag"
  elif [[ -n "$target_ref" ]]; then
    success "Branch: $target_ref"
  elif [[ -n "$current_branch" && "$current_branch" != "HEAD" ]]; then
    success "Branch: $current_branch"
  else
    success "Checked out at $(git rev-parse --short HEAD 2>/dev/null || echo 'unknown')"
  fi
}

# ── Environment Setup ─────────────────────────────────────────────────────────

setup_env() {
  cd "$DEEPSQL_HOME"
  
  if [[ ! -f ".env" ]]; then
    if [[ -f ".env.example" ]]; then
      info "Creating .env from template..."
      cp .env.example .env
      success "Created .env"
    else
      error ".env.example not found in checkout."
      exit 1
    fi
  else
    info ".env already exists — not overwritten"
  fi
}

# ── Run Install Script ────────────────────────────────────────────────────────

run_install() {
  cd "$DEEPSQL_HOME"
  
  if [[ ! -x "./scripts/self-host/install.sh" ]]; then
    chmod +x "./scripts/self-host/install.sh"
  fi
  
  info "Running install.sh..."
  echo
  
  # Pass through any remaining arguments to install.sh
  exec ./scripts/self-host/install.sh "$@"
}

# ── Main ──────────────────────────────────────────────────────────────────────

usage() {
  cat <<EOF
DeepSQL Remote Installer

Usage: curl -fsSL https://deepsql.ai/install.sh | bash -s -- [options]

One command installs DeepSQL. The only input is an LLM key (optional—can be
set later in Settings → AI Provider).

Options:
  -h, --help           Show this help message and exit
  -V, --version        Show version and exit
  --skip-docker-check  Skip Docker/Compose/buildx verification
  --ref <ref>          Use a specific branch or tag instead of latest release
  --non-interactive    Never prompt; use env vars or defaults
  --seed-demo          Seed demo database after install (default)
  --no-seed-demo       Skip demo database seeding
  --fresh              Remove existing volumes before install
  --project-name NAME  Set Compose project name

Environment variables (override .env placeholders):
  DEEPSQL_HOME                 Installation directory (default: \$HOME/deepsql)
  DEEPSQL_REF                  Branch or tag to checkout (default: latest release)
  DEEPSQL_CHAT_API_KEY         LLM key for chat (optional)
  DEEPSQL_CHAT_PROVIDER        Provider id (default: openai)
  DEEPSQL_CHAT_ENDPOINT        API endpoint
  DEEPSQL_CHAT_MODEL           Model name
  DEEPSQL_INITIAL_ADMIN_EMAIL  Admin login email
  DEEPSQL_INITIAL_ADMIN_PASSWORD  Admin password (generated if unset)

Examples:
  # Interactive install
  curl -fsSL https://deepsql.ai/install.sh | bash

  # With LLM key (non-interactive for AI agents)
  DEEPSQL_CHAT_API_KEY=sk-... curl -fsSL https://deepsql.ai/install.sh | bash

  # Keyless install (configure LLM later in UI)
  DEEPSQL_INITIAL_ADMIN_EMAIL=admin@example.com \\
    curl -fsSL https://deepsql.ai/install.sh | bash

  # Install a specific version
  curl -fsSL https://deepsql.ai/install.sh | bash -s -- --ref v1.3.0

  # Fresh install (removes existing data)
  curl -fsSL https://deepsql.ai/install.sh | bash -s -- --fresh

For AI agents:
  Ask the user for their LLM API key (OpenAI, Azure OpenAI, etc.), then run:

    DEEPSQL_CHAT_API_KEY=<key> curl -fsSL https://deepsql.ai/install.sh | bash

  After install:
    Health: curl -fsS http://localhost:8080/api/actuator/health
    Login:  http://localhost:3000 (credentials in ~/deepsql/.env)
EOF
}

main() {
  local skip_docker=0
  local install_args=()
  
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -h|--help)
        usage
        exit 0
        ;;
      -V|--version)
        echo "DeepSQL Remote Installer $REMOTE_INSTALLER_VERSION"
        exit 0
        ;;
      --skip-docker-check)
        skip_docker=1
        shift
        ;;
      --ref|--branch)
        DEEPSQL_REF="$2"
        shift 2
        ;;
      --ref=*|--branch=*)
        DEEPSQL_REF="${1#*=}"
        shift
        ;;
      --non-interactive|--seed-demo|--no-seed-demo|--fresh)
        install_args+=("$1")
        shift
        ;;
      --project-name)
        install_args+=("$1" "$2")
        shift 2
        ;;
      --project-name=*)
        install_args+=("--project-name" "${1#*=}")
        shift
        ;;
      *)
        error "Unknown option: $1"
        echo "Run with --help for usage." >&2
        exit 1
        ;;
    esac
  done
  
  echo
  echo "${BOLD}═══════════════════════════════════════════════════════════════════════════${NC}"
  echo "${BOLD}  DeepSQL Remote Installer${NC}"
  echo "${BOLD}═══════════════════════════════════════════════════════════════════════════${NC}"
  echo
  
  detect_platform
  check_prerequisites
  
  if [[ "$skip_docker" -eq 0 ]]; then
    check_docker
  else
    warn "Skipping Docker check (--skip-docker-check)"
  fi
  
  # Determine target ref
  local target_ref="$DEEPSQL_REF"
  if [[ -z "$target_ref" ]]; then
    info "Finding latest stable release..."
    target_ref="$(get_latest_release_tag || true)"
    
    # Fallback to git ls-remote if API fails
    if [[ -z "$target_ref" ]]; then
      target_ref="$(get_latest_release_tag_git || true)"
    fi
    
    if [[ -z "$target_ref" ]]; then
      # Fail loudly instead of silently falling back to main
      error "Could not determine latest release."
      echo
      echo "This may be due to GitHub API rate limits. Try one of:"
      echo "  1. Wait a few minutes and try again"
      echo "  2. Specify a version explicitly:"
      echo "     curl -fsSL https://deepsql.ai/install.sh | bash -s -- --ref v1.3.0"
      echo "  3. Check available releases: https://github.com/${DEEPSQL_REPO}/releases"
      exit 1
    else
      success "Latest release: $target_ref"
    fi
  else
    info "Using specified ref: $target_ref"
  fi
  
  clone_or_update "$target_ref"
  setup_env
  
  # Run install with passed-through arguments
  run_install "${install_args[@]}"
}

main "$@"
