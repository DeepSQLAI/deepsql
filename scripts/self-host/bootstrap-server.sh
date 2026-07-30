#!/usr/bin/env bash
# bootstrap-server.sh — One-time setup for a fresh Linux VM that will run the
# DeepSQL self-hosted Docker stack (used for the dba-agent.stayflexi.com deployment).
#
# Run as root or with sudo on the target server:
#   curl -fsSL https://.../bootstrap-server.sh | sudo bash
# Or copy and run locally:
#   sudo ./scripts/self-host/bootstrap-server.sh

set -euo pipefail

DEPLOY_DIR="${DEEPSQL_DEPLOY_DIR:-/opt/dba-agent}"
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

# ── Copy stack files if running from the repo ────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [[ -f "$REPO_ROOT/docker-compose.yml" ]]; then
  cp "$REPO_ROOT/docker-compose.yml" "$DEPLOY_DIR/docker-compose.yml"
  cp -r "$REPO_ROOT/docker" "$DEPLOY_DIR/docker"
  echo "Copied docker-compose.yml and docker/ config to $DEPLOY_DIR."
fi

# ── Create .env from example if not present ──────────────────────────────────
if [[ ! -f "$DEPLOY_DIR/.env" ]]; then
  if [[ -f "$REPO_ROOT/.env.example" ]]; then
    cp "$REPO_ROOT/.env.example" "$DEPLOY_DIR/.env"
    chown "$DEPLOY_USER:$DEPLOY_USER" "$DEPLOY_DIR/.env"
    echo "Created $DEPLOY_DIR/.env from .env.example."
    echo ">>> Edit $DEPLOY_DIR/.env and fill in required values before starting."
  else
    echo "Warning: .env.example not found. Create $DEPLOY_DIR/.env manually."
  fi
else
  echo "$DEPLOY_DIR/.env already exists — not overwritten."
fi

echo
echo "=== Bootstrap complete ==="
echo "Next steps:"
echo "  1. Edit $DEPLOY_DIR/.env (set DEEPSQL_CHAT_API_KEY, DEEPSQL_EMBEDDING_API_KEY, etc.)"
echo "  2. Run: cd $DEPLOY_DIR && docker compose pull && docker compose up -d"
echo "  3. Or trigger the backend-prod-deploy GitHub Actions workflow."
