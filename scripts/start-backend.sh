#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"

echo "Starting DBA Agent Backend..."
echo "================================"

if [ -f "$ENV_FILE" ]; then
    echo "Loading environment from .env..."
    set -a
    source "$ENV_FILE"
    set +a
    if [ "${SPRING_PROFILES_ACTIVE:-}" = "prod" ]; then
        echo "Local source-run startup ignores SPRING_PROFILES_ACTIVE=prod from .env"
        unset SPRING_PROFILES_ACTIVE
    fi
fi

build_backend_launch_command() {
    local mvn_command="$1"
    local env_snippet=""
    if [ -f "$ENV_FILE" ]; then
        env_snippet="set -a && source \"$ENV_FILE\" && set +a && if [ \"\${SPRING_PROFILES_ACTIVE:-}\" = \"prod\" ]; then unset SPRING_PROFILES_ACTIVE; fi && "
    fi
    printf '%s' "${env_snippet}cd \"$PROJECT_ROOT/backend\" && exec ${mvn_command} spring-boot:run"
}

cd "$PROJECT_ROOT/backend"

if [ -f "./mvnw" ]; then
    echo "Using Maven wrapper..."
    chmod +x ./mvnw
    bash -lc "$(build_backend_launch_command "./mvnw")"
elif command -v mvn &> /dev/null; then
    echo "Using system Maven..."
    bash -lc "$(build_backend_launch_command "mvn")"
else
    echo "ERROR: Maven not found!"
    echo "Please install Maven or use the Maven wrapper."
    echo ""
    echo "To install Maven on macOS:"
    echo "  brew install maven"
    exit 1
fi
