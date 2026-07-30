#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"

RELEASE_TAG="${DEEPSQL_RELEASE_TAG:-}"
IMAGE_REGISTRY="${DEEPSQL_IMAGE_REGISTRY:-}"
BACKEND_IMAGE="${DEEPSQL_BACKEND_IMAGE:-}"
FRONTEND_IMAGE="${DEEPSQL_FRONTEND_IMAGE:-}"
FRONTEND_API_URL="${DEEPSQL_RELEASE_VITE_API_URL:-}"
PUSH_IMAGES=false
EXPORT_IMAGES=false

usage() {
  cat <<'EOF'
Usage:
  ./scripts/self-host/release.sh --tag <version> [options]

Options:
  --tag <version>             Release tag used in image and bundle names. Required.
  --registry <registry>       Image registry / namespace prefix.
                              Example: ghcr.io/acme
  --backend-image <image>     Full backend image reference override.
  --frontend-image <image>    Full frontend image reference override.
  --frontend-api-url <url>    Optional Vite API URL at frontend build time.
  --push                      Push backend and frontend images after building.
  --export-images             Export a gzip-compressed docker image archive for offline installs.
  --help                      Show this help text.

Examples:
  ./scripts/self-host/release.sh --tag v1.4.0 --registry ghcr.io/acme --push
  ./scripts/self-host/release.sh --tag v1.4.0 --export-images
EOF
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Error: required command '$1' is not installed." >&2
    exit 1
  fi
}

derive_image_ref() {
  local image_name="$1"
  if [[ -n "$IMAGE_REGISTRY" ]]; then
    printf '%s/%s:%s' "${IMAGE_REGISTRY%/}" "$image_name" "$RELEASE_TAG"
  else
    printf '%s:%s' "$image_name" "$RELEASE_TAG"
  fi
}

ensure_image_available() {
  local image_ref="$1"
  if docker image inspect "$image_ref" >/dev/null 2>&1; then
    return 0
  fi

  echo "Pulling required base image: $image_ref"
  docker pull "$image_ref" >/dev/null
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      RELEASE_TAG="${2:-}"
      shift 2
      ;;
    --registry)
      IMAGE_REGISTRY="${2:-}"
      shift 2
      ;;
    --backend-image)
      BACKEND_IMAGE="${2:-}"
      shift 2
      ;;
    --frontend-image)
      FRONTEND_IMAGE="${2:-}"
      shift 2
      ;;
    --frontend-api-url)
      FRONTEND_API_URL="${2:-}"
      shift 2
      ;;
    --push)
      PUSH_IMAGES=true
      shift
      ;;
    --export-images)
      EXPORT_IMAGES=true
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ -z "$RELEASE_TAG" ]]; then
  echo "Error: --tag is required." >&2
  usage >&2
  exit 1
fi

require_command docker

if [[ -z "$BACKEND_IMAGE" ]]; then
  BACKEND_IMAGE="$(derive_image_ref deepsql-backend)"
fi

if [[ -z "$FRONTEND_IMAGE" ]]; then
  FRONTEND_IMAGE="$(derive_image_ref deepsql-frontend)"
fi

PACKAGE_NAME="deepsql-self-host-${RELEASE_TAG}"
PACKAGE_PATH="$DIST_DIR/${PACKAGE_NAME}.tar.gz"

mkdir -p "$DIST_DIR"

echo "Building backend image:  $BACKEND_IMAGE"
docker build \
  -f "$ROOT_DIR/backend/Dockerfile" \
  -t "$BACKEND_IMAGE" \
  "$ROOT_DIR/backend"

echo "Building frontend image: $FRONTEND_IMAGE"
docker build \
  -f "$ROOT_DIR/Dockerfile" \
  --build-arg "VITE_API_URL=$FRONTEND_API_URL" \
  -t "$FRONTEND_IMAGE" \
  "$ROOT_DIR"

if [[ "$PUSH_IMAGES" == "true" ]]; then
  echo "Pushing backend image..."
  docker push "$BACKEND_IMAGE"
  echo "Pushing frontend image..."
  docker push "$FRONTEND_IMAGE"
fi

DEEPSQL_PACKAGE_NAME="$PACKAGE_NAME" \
DEEPSQL_BACKEND_IMAGE="$BACKEND_IMAGE" \
DEEPSQL_FRONTEND_IMAGE="$FRONTEND_IMAGE" \
  "$SCRIPT_DIR/package.sh"

if [[ "$EXPORT_IMAGES" == "true" ]]; then
  IMAGE_ARCHIVE="$DIST_DIR/${PACKAGE_NAME}-images.tar.gz"
  ensure_image_available "pgvector/pgvector:pg17"
  ensure_image_available "valkey/valkey:9.0.1"

  echo "Exporting offline image archive: $IMAGE_ARCHIVE"
  docker image save \
    "$BACKEND_IMAGE" \
    "$FRONTEND_IMAGE" \
    "pgvector/pgvector:pg17" \
    "valkey/valkey:9.0.1" | gzip > "$IMAGE_ARCHIVE"
fi

echo
echo "Release bundle: $PACKAGE_PATH"
echo "Backend image:  $BACKEND_IMAGE"
echo "Frontend image: $FRONTEND_IMAGE"
if [[ "$EXPORT_IMAGES" == "true" ]]; then
  echo "Offline images: $DIST_DIR/${PACKAGE_NAME}-images.tar.gz"
  echo "Customers using the offline archive should set DEEPSQL_SKIP_IMAGE_PULL=true before install."
fi
