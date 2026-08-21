# Sourced by scripts/start-backend.sh (outer shell and the inner bash -lc).
# Compose service hostnames only resolve on the compose network. Native
# `mvn spring-boot:run` still sources a Compose-oriented .env, so rewrite
# those hosts to loopback when they don't resolve.
if ! getent hosts postgres >/dev/null 2>&1; then
    if [ -n "${DB_URL:-}" ]; then
        export DB_URL="${DB_URL//:\/\/postgres:/:\/\/127.0.0.1:}"
    fi
fi
if ! getent hosts valkey >/dev/null 2>&1; then
    case "${SPRING_DATA_REDIS_HOST:-}" in
        valkey|"") export SPRING_DATA_REDIS_HOST=127.0.0.1 ;;
    esac
fi
if ! getent hosts deepsql-agent >/dev/null 2>&1; then
    if [ -n "${AGENT_WEBUI_URL:-}" ]; then
        export AGENT_WEBUI_URL="${AGENT_WEBUI_URL//deepsql-agent/127.0.0.1}"
    fi
    if [ -n "${AGENT_PROVISIONER_URL:-}" ]; then
        export AGENT_PROVISIONER_URL="${AGENT_PROVISIONER_URL//deepsql-agent/127.0.0.1}"
    fi
fi
