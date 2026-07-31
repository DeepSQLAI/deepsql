#!/usr/bin/env bash
# bootstrap-server.sh — One-time setup for a fresh Linux VM that will run the
# DeepSQL self-hosted Docker stack.
#
# Run as root or with sudo on the target server:
#   curl -fsSL https://.../bootstrap-server.sh | sudo bash
# Or copy and run locally:
#   sudo ./scripts/self-host/bootstrap-server.sh

set -euo pipefail

DEPLOY_DIR="${DEEPSQL_DEPLOY_DIR:-/opt/deepsql}"
DEPLOY_USER="${DEEPSQL_DEPLOY_USER:-ubuntu}"

echo "=== DeepSQL server bootstrap ==="

# ── Install Docker if missing ─────────────────────────────────────────────────
if ! command -v docker >/dev/null 2>&1; then
  echo "Installing Docker..."
  curl -fsSL https://get.docker.com | sh
  usermod -aG docker "$DEPLOY_USER"
  echo "Docker installed. Note: log out and back in as $DEPLOY_USER for group to take effect."
else
  echo "Docker already installed: $(docker --version)"
fi

# Ensure docker compose v2 plugin is available
if ! docker compose version >/dev/null 2>&1; then
  echo "Installing docker compose plugin..."
  apt-get install -y docker-compose-plugin
fi

# ── Install curl if missing ───────────────────────────────────────────────────
if ! command -v curl >/dev/null 2>&1; then
  apt-get update && apt-get install -y curl
fi

# ── Create deploy directory ───────────────────────────────────────────────────
mkdir -p "$DEPLOY_DIR"
chown "$DEPLOY_USER:$DEPLOY_USER" "$DEPLOY_DIR"
echo "Deploy directory: $DEPLOY_DIR"

# ── Seed .env if a checkout is already present ───────────────────────────────
# The stack is built from source, so $DEPLOY_DIR must hold a full checkout —
# copying only docker-compose.yml would leave nothing to build.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [[ -f "$REPO_ROOT/docker-compose.yml" && ! -f "$DEPLOY_DIR/.env" && -f "$REPO_ROOT/.env.example" ]]; then
  cp "$REPO_ROOT/.env.example" "$DEPLOY_DIR/.env"
  chown "$DEPLOY_USER:$DEPLOY_USER" "$DEPLOY_DIR/.env"
  echo "Created $DEPLOY_DIR/.env from .env.example."
elif [[ -f "$DEPLOY_DIR/.env" ]]; then
  echo "$DEPLOY_DIR/.env already exists — not overwritten."
fi

echo
echo "=== Bootstrap complete ==="
echo "Next steps:"
echo "  1. Put a checkout of the DeepSQL source in $DEPLOY_DIR (git clone), if it is not there already."
echo "  2. cd $DEPLOY_DIR && cp .env.example .env  (skip if .env was created above)"
echo "  3. Edit .env — at minimum DEEPSQL_CHAT_PROVIDER, DEEPSQL_CHAT_API_KEY, DEEPSQL_CHAT_ENDPOINT, DEEPSQL_CHAT_MODEL."
echo "  4. Run: ./scripts/self-host/install.sh"
echo "     (or: docker compose up -d --build — the first build takes several minutes)"
