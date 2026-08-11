# DeepSQL Agent — self-contained container for the Agent tab, AI dashboards,
# Slack/CLI agent turns, and per-user profile provisioning.
#
# Built from this checkout. The runtime engine is an upstream dependency
# installed at image-build time; the product surface (persona, skills, MCP,
# branding) is owned by DeepSQL and is what operators interact with.

FROM python:3.12-slim-bookworm

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    # Product home — operators never need to know the upstream layout.
    DEEPSQL_AGENT_HOME=/var/lib/deepsql-agent \
    DEEPSQL_AGENT_ROOT=/opt/deepsql-agent \
    PATH="/opt/deepsql-agent/runtime/venv/bin:/usr/local/bin:${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates \
      curl \
      git \
      nodejs \
      npm \
      build-essential \
    && rm -rf /var/lib/apt/lists/*

# Node 20+ is required by the DeepSQL MCP server. Debian bookworm ships
# Node 18; replace with NodeSource 20.
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/* \
    && node --version && npm --version

# Install uv for fast Python env management.
RUN curl -fsSL https://astral.sh/uv/install.sh | sh \
    && ln -sf /root/.local/bin/uv /usr/local/bin/uv

WORKDIR /opt/deepsql-agent

# Upstream runtime clones. Refs are overridable at build time; defaults track
# what scripts/self-host/setup-agent.sh installs for host-based installs.
ARG AGENT_RUNTIME_REPO=https://github.com/NousResearch/hermes-agent.git
ARG AGENT_RUNTIME_REF=main
ARG AGENT_API_REPO=https://github.com/nesquena/hermes-webui.git
ARG AGENT_API_REF=master

# Runtime engine (Python agent)
RUN git clone --depth 1 --branch "${AGENT_RUNTIME_REF}" "${AGENT_RUNTIME_REPO}" runtime \
    && cd runtime \
    && UV_NO_CONFIG=1 uv sync \
    && if [ -d .venv ] && [ ! -d venv ]; then ln -sfn .venv venv; fi \
    && if [ -d venv ] && [ ! -d .venv ]; then ln -sfn venv .venv; fi \
    # Pin MCP SDK below 2.0 — SDK 2.x renamed CallToolResult.isError → is_error
    # and breaks every DeepSQL tool call until the runtime catches up.
    && UV_NO_CONFIG=1 uv pip install --python venv/bin/python 'mcp>=1.0,<2' 'pyyaml>=6'

# HTTP API surface the frontend / backend talk to (:8787).
# Default branch is master (not main). Fall back to HEAD if the ref moves.
RUN (git clone --depth 1 --branch "${AGENT_API_REF}" "${AGENT_API_REPO}" api \
      || git clone --depth 1 "${AGENT_API_REPO}" api) \
    && cd api \
    && if [ -f requirements.txt ]; then \
         UV_NO_CONFIG=1 uv pip install --python /opt/deepsql-agent/runtime/venv/bin/python -r requirements.txt; \
       fi

# DeepSQL product surface — persona, skills, branding, MCP, provisioner.
COPY agent/SOUL.md              /opt/deepsql-agent/SOUL.md
COPY agent/skills               /opt/deepsql-agent/skills
COPY agent/webui                /opt/deepsql-agent/webui-overlay
COPY agent/skins                /opt/deepsql-agent/skins
COPY agent/distribution.yaml    /opt/deepsql-agent/distribution.yaml
COPY mcp                        /opt/deepsql-agent/mcp
COPY scripts/local-agent-provisioner.py /opt/deepsql-agent/provisioner.py
COPY agent/docker-entrypoint.sh /opt/deepsql-agent/docker-entrypoint.sh

RUN chmod +x /opt/deepsql-agent/docker-entrypoint.sh \
    && cd /opt/deepsql-agent/mcp && npm install --omit=dev --ignore-scripts \
    && mkdir -p /var/lib/deepsql-agent/logs /var/lib/deepsql-agent/profiles \
    # Apply DeepSQL Agent branding to the API UI (idempotent overlay).
    && bash /opt/deepsql-agent/webui-overlay/apply-overlay.sh /opt/deepsql-agent/api || true

# Expose the agent API (:8787) and the secret-gated provisioner (:8788).
EXPOSE 8787 8788

VOLUME ["/var/lib/deepsql-agent"]

# Provisioner /health is unauthenticated and always 200 when the process is up.
# The API on :8787 may require a session, so we don't use it for the healthcheck.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8788/health >/dev/null || exit 1

ENTRYPOINT ["/opt/deepsql-agent/docker-entrypoint.sh"]
