#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
PACKAGE_NAME="${DEEPSQL_PACKAGE_NAME:-deepsql-self-host-${TIMESTAMP}}"
BUNDLE_DIR="$DIST_DIR/$PACKAGE_NAME"
ARCHIVE_PATH="$DIST_DIR/${PACKAGE_NAME}.tar.gz"
BACKEND_IMAGE_REF="${DEEPSQL_BACKEND_IMAGE:-}"
FRONTEND_IMAGE_REF="${DEEPSQL_FRONTEND_IMAGE:-}"

rm -rf "$BUNDLE_DIR" "$ARCHIVE_PATH"
mkdir -p "$BUNDLE_DIR/docker" "$BUNDLE_DIR/scripts/self-host"

copy_path() {
  local src="$1"
  local dest="$2"
  if command -v rsync >/dev/null 2>&1; then
    rsync -a "$src" "$dest"
  else
    cp -R "$src" "$dest"
  fi
}

copy_path "$ROOT_DIR/docker/" "$BUNDLE_DIR/docker/"
cp "$ROOT_DIR/scripts/self-host/install.sh" "$BUNDLE_DIR/scripts/self-host/install.sh"
cp "$ROOT_DIR/scripts/self-host/status.sh" "$BUNDLE_DIR/scripts/self-host/status.sh"
cp "$ROOT_DIR/scripts/self-host/smoke-test.sh" "$BUNDLE_DIR/scripts/self-host/smoke-test.sh"
cp "$ROOT_DIR/scripts/self-host/uninstall.sh" "$BUNDLE_DIR/scripts/self-host/uninstall.sh"

cp "$ROOT_DIR/docker-compose.yml" "$BUNDLE_DIR/docker-compose.yml"
cp "$ROOT_DIR/.env.example" "$BUNDLE_DIR/.env.example"
cp "$ROOT_DIR/docs/root/SELF_HOST_GUIDE.md" "$BUNDLE_DIR/SELF_HOST_GUIDE.md"
cp "$ROOT_DIR/docs/root/SELF_HOST_GUIDE.md" "$BUNDLE_DIR/README.md"

rewrite_env_value() {
  local file="$1"
  local key="$2"
  local value="$3"
  local tmp
  tmp="$(mktemp)"
  awk -v key="$key" -v value="$value" '
    BEGIN { replaced = 0 }
    index($0, key "=") == 1 {
      print key "=" value
      replaced = 1
      next
    }
    { print }
    END {
      if (!replaced) {
        print key "=" value
      }
    }
  ' "$file" > "$tmp"
  mv "$tmp" "$file"
}

if [[ -n "$BACKEND_IMAGE_REF" ]]; then
  rewrite_env_value "$BUNDLE_DIR/.env.example" "DEEPSQL_BACKEND_IMAGE" "$BACKEND_IMAGE_REF"
fi

if [[ -n "$FRONTEND_IMAGE_REF" ]]; then
  rewrite_env_value "$BUNDLE_DIR/.env.example" "DEEPSQL_FRONTEND_IMAGE" "$FRONTEND_IMAGE_REF"
fi

if [[ -n "$BACKEND_IMAGE_REF" || -n "$FRONTEND_IMAGE_REF" ]]; then
  {
    [[ -n "$BACKEND_IMAGE_REF" ]] && echo "DEEPSQL_BACKEND_IMAGE=$BACKEND_IMAGE_REF"
    [[ -n "$FRONTEND_IMAGE_REF" ]] && echo "DEEPSQL_FRONTEND_IMAGE=$FRONTEND_IMAGE_REF"
  } > "$BUNDLE_DIR/IMAGES.env"
fi

find "$BUNDLE_DIR/scripts/self-host" -type f -name '*.sh' -exec chmod +x {} +

tar -C "$DIST_DIR" -czf "$ARCHIVE_PATH" "$PACKAGE_NAME"

echo "Created self-host bundle: $ARCHIVE_PATH"
