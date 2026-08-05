#!/usr/bin/env bash
# bootstrap-server.sh — One-time setup for a fresh Linux VM that will run the
# DeepSQL self-hosted Docker stack.
#
# Run as root or with sudo on the target server:
#   curl -fsSL https://.../bootstrap-server.sh | sudo bash
# Or copy and run locally:
#   sudo ./scripts/self-host/bootstrap-server.sh
#
# Handles Debian/Ubuntu (apt) and Amazon Linux 2023 / RHEL family (dnf). The two
# differ in ways that are easy to miss and fatal to a first install:
#
#   * get.docker.com refuses to run on Amazon Linux ("unsupported distribution
#     amzn"), so Docker there comes from the distro package instead.
#   * The AL2023 `docker` package ships NEITHER the Compose v2 plugin NOR buildx.
#     Compose delegates every build to buildx and refuses to start without
#     >= 0.17.0, so `docker compose up --build` — the entire distribution model
#     for this project — fails on a box that looks correctly set up.
#   * The unprivileged account is `ubuntu` on Ubuntu images and `ec2-user` on
#     Amazon Linux ones.
#
# Overrides: DEEPSQL_DEPLOY_DIR, DEEPSQL_DEPLOY_USER.

set -euo pipefail

DEPLOY_DIR="${DEEPSQL_DEPLOY_DIR:-/opt/deepsql}"
CLI_PLUGIN_DIR="/usr/local/lib/docker/cli-plugins"

echo "=== DeepSQL server bootstrap ==="

if [[ "${EUID}" -ne 0 ]]; then
  echo "Error: run this as root or with sudo." >&2
  exit 1
fi

# ── Detect the platform ───────────────────────────────────────────────────────
if command -v apt-get >/dev/null 2>&1; then
  PKG=apt
elif command -v dnf >/dev/null 2>&1; then
  PKG=dnf
elif command -v yum >/dev/null 2>&1; then
  PKG=yum
else
  echo "Error: no supported package manager found (apt-get, dnf, yum)." >&2
  exit 1
fi

# Prefer an explicit override, then the account that invoked sudo, then the
# conventional image default. Guessing wrong here silently creates a deploy
# directory nobody can write to.
if [[ -n "${DEEPSQL_DEPLOY_USER:-}" ]]; then
  DEPLOY_USER="$DEEPSQL_DEPLOY_USER"
elif [[ -n "${SUDO_USER:-}" && "$SUDO_USER" != "root" ]]; then
  DEPLOY_USER="$SUDO_USER"
else
  DEPLOY_USER=""
  for candidate in ubuntu ec2-user debian admin rocky fedora cloud-user; do
    if id -u "$candidate" >/dev/null 2>&1; then DEPLOY_USER="$candidate"; break; fi
  done
fi
if [[ -z "$DEPLOY_USER" ]]; then
  echo "Error: could not determine the deploy user. Set DEEPSQL_DEPLOY_USER." >&2
  exit 1
fi

. /etc/os-release 2>/dev/null || true
echo "Platform: ${PRETTY_NAME:-unknown}  (package manager: $PKG, deploy user: $DEPLOY_USER)"

pkg_install() {
  case "$PKG" in
    apt) DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "$@" >/dev/null ;;
    dnf) dnf install -y -q "$@" >/dev/null ;;
    yum) yum install -y -q "$@" >/dev/null ;;
  esac
}

# ── Base tools ────────────────────────────────────────────────────────────────
# install.sh needs curl for the health probes and the bootstrap call, and openssl to
# generate the JWT secret and the vault encryption key. git is here because the very
# first documented step is `git clone` — and a vanilla Amazon Linux 2023 image does not
# ship it, so the README's step 2 fails before DeepSQL is involved at all.
[[ "$PKG" == apt ]] && apt-get update -qq >/dev/null
for tool in curl openssl tar git; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Installing $tool..."
    pkg_install "$tool"
  fi
done

# ── Docker engine ─────────────────────────────────────────────────────────────
if command -v docker >/dev/null 2>&1; then
  echo "Docker already installed: $(docker --version)"
else
  echo "Installing Docker..."
  case "$PKG" in
    apt)
      # The convenience script pulls docker-ce, which bundles the compose and
      # buildx plugins, so those checks below become no-ops on Debian/Ubuntu.
      curl -fsSL https://get.docker.com | sh
      ;;
    dnf|yum)
      pkg_install docker
      ;;
  esac
fi

systemctl enable --now docker >/dev/null 2>&1 || true
if ! docker info >/dev/null 2>&1; then
  echo "Error: the Docker daemon is not running after install." >&2
  exit 1
fi

usermod -aG docker "$DEPLOY_USER"

# ── Compose v2 and buildx ─────────────────────────────────────────────────────
install_cli_plugin() {
  local name="$1" url="$2"
  # /usr/local/lib takes precedence over the distro's /usr/libexec, so this also
  # shadows a too-old plugin shipped by the package manager.
  mkdir -p "$CLI_PLUGIN_DIR"
  curl -fsSL "$url" -o "$CLI_PLUGIN_DIR/docker-$name"
  chmod +x "$CLI_PLUGIN_DIR/docker-$name"
}

# Presence is not the question — the version is. The Amazon Linux 2023 `docker`
# package bundles buildx 0.12.1, and Compose refuses to build with anything below
# 0.17.0. A plain `command -v` style check passes there and then fails at
# `docker compose up --build`, which is the least useful place to find out.
version_ge() {
  [[ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -1)" == "$2" ]]
}
# `|| true` is load-bearing. This script runs under `set -euo pipefail`, and on a host
# with no compose plugin `docker compose version` exits non-zero — as does the grep when
# there is nothing to match. Without the guard that failing pipeline propagates out of
# the command substitution and kills the script at the very check whose job is to notice
# the plugin is missing, which is the one case it has to survive.
plugin_version() {
  docker "$1" version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true
}

COMPOSE_MIN="2.0.0"
BUILDX_MIN="0.17.0"

compose_have="$(plugin_version compose)"
if [[ -n "$compose_have" ]] && version_ge "$compose_have" "$COMPOSE_MIN"; then
  echo "Compose already available: $compose_have"
else
  echo "Installing Docker Compose v2 plugin (have: ${compose_have:-none}, need >= $COMPOSE_MIN)..."
  if [[ "$PKG" == apt ]]; then
    pkg_install docker-compose-plugin
  else
    install_cli_plugin compose \
      "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-$(uname -m)"
  fi
fi

buildx_have="$(plugin_version buildx)"
if [[ -n "$buildx_have" ]] && version_ge "$buildx_have" "$BUILDX_MIN"; then
  echo "buildx already available: $buildx_have"
else
  echo "Installing Docker buildx plugin (have: ${buildx_have:-none}, need >= $BUILDX_MIN)..."
  if [[ "$PKG" == apt ]]; then
    pkg_install docker-buildx-plugin
  else
    # The buildx release assets embed the version in the filename, so there is no
    # /latest/download shortcut as there is for compose — resolve the real URL.
    arch="$(uname -m)"
    [[ "$arch" == "x86_64"  ]] && arch="amd64"
    [[ "$arch" == "aarch64" ]] && arch="arm64"
    bx_url="$(curl -fsSL https://api.github.com/repos/docker/buildx/releases/latest \
      | grep -o "\"browser_download_url\": *\"[^\"]*linux-${arch}\"" | head -1 | cut -d'"' -f4)"
    if [[ -z "$bx_url" ]]; then
      echo "Error: could not resolve a buildx release for linux-${arch}." >&2
      exit 1
    fi
    install_cli_plugin buildx "$bx_url"
  fi
fi

# ── Verify before declaring success ───────────────────────────────────────────
# Checked explicitly because each of these failing produces an error at
# `docker compose up --build` time that names neither the missing plugin nor
# this script.
fail=0
compose_have="$(plugin_version compose)"
buildx_have="$(plugin_version buildx)"
if [[ -z "$compose_have" ]] || ! version_ge "$compose_have" "$COMPOSE_MIN"; then
  echo "FAIL: docker compose is ${compose_have:-unavailable}, need >= $COMPOSE_MIN" >&2; fail=1
fi
if [[ -z "$buildx_have" ]] || ! version_ge "$buildx_have" "$BUILDX_MIN"; then
  echo "FAIL: docker buildx is ${buildx_have:-unavailable}, need >= $BUILDX_MIN" >&2; fail=1
fi
for t in openssl curl git; do
  command -v "$t" >/dev/null 2>&1 || { echo "FAIL: $t missing" >&2; fail=1; }
done
[[ "$fail" -eq 0 ]] || exit 1

# ── Deploy directory ──────────────────────────────────────────────────────────
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
echo "  docker  : $(docker --version)"
echo "  compose : $(docker compose version --short 2>/dev/null)"
echo "  buildx  : $(docker buildx version 2>/dev/null | awk '{print $2}')"
echo
echo "Log out and back in as $DEPLOY_USER before continuing, so the docker group applies."
echo
echo "Next steps:"
echo "  1. Put a checkout of the DeepSQL source in $DEPLOY_DIR (git clone), if not already there."
echo "  2. cd $DEPLOY_DIR && cp .env.example .env   (skip if .env was created above)"
echo "  3. Edit .env — at minimum DEEPSQL_CHAT_PROVIDER, DEEPSQL_CHAT_API_KEY,"
echo "     DEEPSQL_CHAT_ENDPOINT, DEEPSQL_CHAT_MODEL."
echo "  4. Run: ./scripts/self-host/install.sh"
