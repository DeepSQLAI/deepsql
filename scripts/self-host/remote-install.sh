#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# DeepSQL Remote Installer
# ═══════════════════════════════════════════════════════════════════════════════
#
# One-liner install:
#   curl -fsSL https://deepsql.ai/install.sh | bash
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
DEEPSQL_BRANCH="${DEEPSQL_BRANCH:-}"  # empty = auto-detect latest release tag

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

info()  { printf "${BLUE}==>${NC} %s\n" "$*"; }
warn()  { printf "${YELLOW}Warning:${NC} %s\n" "$*" >&2; }
error() { printf "${RED}Error:${NC} %s\n" "$*" >&2; }
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
  
  if ! docker info >/dev/null 2>&1; then
    error "Docker daemon is not running or you lack permission."
    echo
    echo "If Docker is installed but not running:"
    echo "  sudo systemctl start docker"
    echo
    echo "If you need permission:"
    echo "  sudo usermod -aG docker \$USER"
    echo "  # Then log out and back in"
    exit 1
  fi
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
  
  # Check buildx (required for multi-stage builds)
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

# ── Release Tag Detection ─────────────────────────────────────────────────────

get_latest_release_tag() {
  # Query GitHub API for tags, filter to product tags (v[0-9]*), exclude desktop-v* tags
  local tags
  tags="$(curl -fsSL "https://api.github.com/repos/${DEEPSQL_REPO}/tags?per_page=50" 2>/dev/null || true)"
  
  if [[ -z "$tags" ]]; then
    return 1
  fi
  
  # Extract tag names, filter to product releases only:
  # - Must start with v followed by a digit (v1.0.0, v2.3.4, etc.)
  # - Excludes desktop-v*, agent-v*, or any other prefixed tags
  local latest
  latest="$(echo "$tags" \
    | grep -o '"name": *"[^"]*"' \
    | cut -d'"' -f4 \
    | grep -E '^v[0-9]' \
    | grep -v '^desktop-' \
    | head -1 || true)"
  
  if [[ -n "$latest" ]]; then
    echo "$latest"
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
        # If it's a tag, just checkout directly
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
  
  # For shallow clones, git describe may not work correctly, so prefer the
  # target ref we requested if it looks like a version tag
  if [[ "$target_ref" =~ ^v[0-9] ]]; then
    success "Version: $target_ref"
    return
  fi
  
  # Try to get the current tag or branch
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

# ── Print Next Steps ──────────────────────────────────────────────────────────

print_llm_setup() {
  echo
  echo "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  echo "${BOLD}  IMPORTANT: Configure your LLM before running install.sh${NC}"
  echo "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  echo
  echo "DeepSQL requires you to bring your own LLM. Edit ${BOLD}$DEEPSQL_HOME/.env${NC}"
  echo "and set these variables:"
  echo
  echo "  ${GREEN}DEEPSQL_CHAT_PROVIDER${NC}=openai"
  echo "  ${GREEN}DEEPSQL_CHAT_API_KEY${NC}=sk-your-key"
  echo "  ${GREEN}DEEPSQL_CHAT_ENDPOINT${NC}=https://api.openai.com/v1"
  echo "  ${GREEN}DEEPSQL_CHAT_MODEL${NC}=gpt-4o"
  echo
  echo "For Azure OpenAI, Anthropic, Ollama, or other providers, see the"
  echo "examples in .env.example or the README."
  echo
}

print_final_instructions() {
  local port="${DEEPSQL_FRONTEND_PORT:-3000}"
  
  echo
  echo "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  echo "${GREEN}${BOLD}  DeepSQL is ready to install!${NC}"
  echo "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  echo
  echo "Next steps:"
  echo
  echo "  1. ${BOLD}Edit .env${NC} with your LLM credentials (see above)"
  echo
  echo "  2. ${BOLD}Run the installer:${NC}"
  echo "     cd $DEEPSQL_HOME"
  echo "     ./scripts/self-host/install.sh"
  echo
  echo "  3. ${BOLD}Open DeepSQL:${NC}"
  echo "     http://localhost:$port"
  echo
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo
  echo "Resources:"
  echo "  Documentation: https://github.com/${DEEPSQL_REPO}#readme"
  echo "  Whitepaper:    https://deepsql.ai/whitepaper"
  echo "  Issues:        https://github.com/${DEEPSQL_REPO}/issues"
  echo
}

# ── Run Install Script (optional) ─────────────────────────────────────────────

run_install() {
  cd "$DEEPSQL_HOME"
  
  if [[ ! -x "./scripts/self-host/install.sh" ]]; then
    error "install.sh not found or not executable."
    exit 1
  fi
  
  info "Running install.sh..."
  echo
  
  # Pass through any arguments to install.sh
  exec ./scripts/self-host/install.sh "$@"
}

# ── Main ──────────────────────────────────────────────────────────────────────

usage() {
  cat <<EOF
DeepSQL Remote Installer

Usage: $0 [options]

Options:
  -h, --help           Show this help message
  -y, --yes            Run install.sh automatically after setup (noninteractive)
  --skip-docker-check  Skip Docker/Compose/buildx verification
  --branch <ref>       Use a specific branch or tag instead of latest release

Environment variables:
  DEEPSQL_HOME         Installation directory (default: \$HOME/deepsql)
  DEEPSQL_BRANCH       Branch or tag to checkout (default: latest release)

Examples:
  # Interactive setup (edit .env, then run install.sh yourself)
  curl -fsSL https://deepsql.ai/install.sh | bash

  # Noninteractive (requires .env to be pre-configured or prompts)
  curl -fsSL https://deepsql.ai/install.sh | bash -s -- --yes

  # Install a specific version
  curl -fsSL https://deepsql.ai/install.sh | bash -s -- --branch v1.3.0

  # Fallback: raw GitHub URL (if deepsql.ai is unreachable)
  curl -fsSL https://raw.githubusercontent.com/DeepSQLAI/deepsql/main/scripts/self-host/remote-install.sh | bash
EOF
}

main() {
  local auto_install=0
  local skip_docker=0
  
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -h|--help)
        usage
        exit 0
        ;;
      -y|--yes)
        auto_install=1
        shift
        ;;
      --skip-docker-check)
        skip_docker=1
        shift
        ;;
      --branch)
        DEEPSQL_BRANCH="$2"
        shift 2
        ;;
      *)
        error "Unknown option: $1"
        usage
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
  local target_ref="$DEEPSQL_BRANCH"
  if [[ -z "$target_ref" ]]; then
    info "Finding latest stable release..."
    target_ref="$(get_latest_release_tag || true)"
    if [[ -z "$target_ref" ]]; then
      target_ref="main"
      warn "Could not determine latest release. Using default branch: $target_ref"
    else
      success "Latest release: $target_ref"
    fi
  fi
  
  clone_or_update "$target_ref"
  setup_env
  
  if [[ "$auto_install" -eq 1 ]]; then
    print_llm_setup
    run_install
  else
    print_llm_setup
    print_final_instructions
  fi
}

main "$@"
