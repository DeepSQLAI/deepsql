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

# Upstream runtime clones, PINNED to release tags. Overridable at build time;
# defaults track what scripts/self-host/setup-agent.sh installs, so a container
# install and a host install get the identical runtime pair.
#
# These were `main` and `master` — moving branches. That meant an upstream commit
# could change what this image contains, and break it, with no change on our side:
# exactly the failure the `mcp>=1.0,<2` pin below exists to prevent, left open one
# layer up. The webui couples to the agent by direct import, so the two move
# together — bump them as a PAIR and re-validate the agent actually answers, not
# merely that the image builds.
ARG AGENT_RUNTIME_REPO=https://github.com/NousResearch/hermes-agent.git
ARG AGENT_RUNTIME_REF=v2026.8.18
ARG AGENT_API_REPO=https://github.com/nesquena/hermes-webui.git
ARG AGENT_API_REF=v0.52.76

# Runtime engine (Python agent).
#
# The clone retries with backoff. github.com rate-limits *unauthenticated*
# requests per source IP, and CI runners share pooled egress addresses, so a
# cold clone here intermittently returns HTTP 429 and took the whole image
# build down with it (`fatal: unable to access ...: error: 429`, exit 128 --
# 2 of 15 `docker compose build` runs). This is not CI-only defensiveness: a
# self-host user behind a shared NAT hits the identical refusal. Retry is the
# right layer precisely because the condition is transient; the loop still
# exits non-zero and loudly on the last attempt rather than continuing with no
# runtime cloned.
RUN n=0; rm -rf runtime; \
    until git clone --depth 1 --branch "${AGENT_RUNTIME_REF}" "${AGENT_RUNTIME_REPO}" runtime; do \
      n=$((n+1)); \
      if [ "$n" -ge 5 ]; then echo "FATAL: clone of ${AGENT_RUNTIME_REPO} failed after 5 attempts" >&2; exit 1; fi; \
      echo "clone attempt $n failed (HTTP 429 / transient network); retrying in $((n*15))s" >&2; \
      rm -rf runtime; sleep $((n*15)); \
    done \
    && cd runtime \
    && UV_NO_CONFIG=1 uv sync \
    && if [ -d .venv ] && [ ! -d venv ]; then ln -sfn .venv venv; fi \
    && if [ -d venv ] && [ ! -d .venv ]; then ln -sfn venv .venv; fi \
    # Pin MCP SDK below 2.0 — SDK 2.x renamed CallToolResult.isError → is_error
    # and breaks every DeepSQL tool call until the runtime catches up.
    && UV_NO_CONFIG=1 uv pip install --python venv/bin/python 'mcp>=1.0,<2' 'pyyaml>=6'

# HTTP API surface the frontend / backend talk to (:8787).
# Default branch is master (not main). Fall back to HEAD if the ref moves.
#
# Same 429 retry as the runtime clone above. Note the inner `||` fallback
# handles only a *moved ref*; on a rate-limit it just re-issues the same
# refused request, so it needed the outer retry to be any use at all.
RUN n=0; rm -rf api; \
    until (git clone --depth 1 --branch "${AGENT_API_REF}" "${AGENT_API_REPO}" api \
            || git clone --depth 1 "${AGENT_API_REPO}" api); do \
      n=$((n+1)); \
      if [ "$n" -ge 5 ]; then echo "FATAL: clone of ${AGENT_API_REPO} failed after 5 attempts" >&2; exit 1; fi; \
      echo "clone attempt $n failed (HTTP 429 / transient network); retrying in $((n*15))s" >&2; \
      rm -rf api; sleep $((n*15)); \
    done \
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
