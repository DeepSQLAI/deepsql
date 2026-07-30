# DeepSQL Self-Host Guide

This package is the supported Docker deployment for DeepSQL self-hosting.
DeepSQL distributes it as prebuilt multi-arch Docker images on GitHub Container Registry (ghcr.io), plus a lightweight distribution repo with Docker Compose, scripts, and configuration.

Deployment boundary:
- Runs inside the customer environment: DeepSQL frontend, backend, PostgreSQL vault, Valkey cache, pgvector-backed RAG storage, and all customer database connections.
- Bring your own LLM: the operator supplies the model credentials (`DEEPSQL_CHAT_*` / `DEEPSQL_EMBEDDING_*`) and model traffic goes directly from the customer's backend to the provider they choose — OpenAI, Azure OpenAI, or a self-hosted OpenAI-compatible server. DeepSQL ships no model credentials and does not proxy or meter model calls.

## Supported Access Modes

Self-host V1 supports three product entry points:

- Direct chat in the DeepSQL web UI
- MCP through a local stdio shim used by Claude Desktop or Codex
- Slack through an in-backend Socket Mode bot

Important MCP note:

- The MCP process does not run inside Docker Compose in V1
- Each end user runs the stdio MCP client locally on their laptop or workstation
- That local process connects to the self-hosted DeepSQL backend over HTTPS with an MCP token
- A centrally hosted remote MCP endpoint is a future phase

## Docker Images

| Image | Source | Architectures |
|-------|--------|---------------|
| `ghcr.io/deepsqlai/deepsql-backend:<tag>` | `backend/Dockerfile` | linux/amd64, linux/arm64 |
| `ghcr.io/deepsqlai/deepsql-frontend:<tag>` | `Dockerfile` (root) | linux/amd64, linux/arm64 |

Images are built and published automatically by the `release-docker` GitHub Actions workflow when a version tag is pushed.

## Architecture

```
Customer Environment                          Your LLM provider
┌──────────────────────────────────────┐     ┌──────────────────┐
│  Browser → nginx (frontend:80)       │     │  OpenAI, Azure   │
│    ├─ Static SPA assets              │     │  OpenAI, or any  │
│    └─ /api/* → backend:8080 ─────────┼────→│  OpenAI-compat.  │
│         ├─ PostgreSQL:5432 (vault)   │     │  server you run  │
│         ├─ Valkey:6379 (cache)       │     └──────────────────┘
│         └─ Customer DBs (outbound)   │
└──────────────────────────────────────┘
```

All customer data stays local. Only LLM inference calls leave the environment, and they
go directly to the provider the operator configures — DeepSQL does not proxy them. Point
the endpoints at a self-hosted OpenAI-compatible server and nothing leaves at all.

## Distribution Repo

Customers receive access to [DeepSQLAI/deepsql](https://github.com/DeepSQLAI/deepsql). The
older private `deepsql-self-host` repo is deprecated — it is kept only so existing clone
URLs do not go dead. The runtime files a customer needs are:

```
deepsql/
  docker-compose.yml          # Full stack: postgres, valkey, backend, frontend
  .env.example                # Pre-filled with ghcr.io image refs
  scripts/
    self-host/
      install.sh              # Auto-generates secrets, prompts for DEEPSQL_CHAT_API_KEY
    status.sh                 # Health check monitoring
    smoke-test.sh             # Post-deployment validation
    uninstall.sh              # Cleanup (with --purge-data option)
  docker/
    nginx/default.conf        # SPA routing + API proxy
    postgres/init/
      01_create_scheduled_tasks.sql
  README.md
```

## What is included (main repo)

Release engineering scripts in the main repo:

- `scripts/self-host/install.sh` — enhanced with auto-secret generation
- `scripts/self-host/status.sh`
- `scripts/self-host/smoke-test.sh`
- `scripts/self-host/uninstall.sh`
- `scripts/self-host/package.sh` — tarball bundler
- `scripts/self-host/release.sh` — local build + package + optional push
- `.github/workflows/release-docker.yml` — CI/CD for ghcr.io publishing

## Prerequisites

Required on the host machine:
- Docker Engine 24+
- Docker Compose v2 (`docker compose`)
- `curl`

Recommended minimum host sizing:
- 4 vCPU
- 8 GB RAM (4 GB minimum)
- 40 GB free disk

---

## Creating a New Release

### Step 1: Tag the release

```bash
git tag v1.1.0
git push origin v1.1.0
```

This triggers the `release-docker` GitHub Actions workflow which:
1. Builds backend and frontend images for amd64 + arm64 via Buildx + QEMU
2. Pushes them to ghcr.io with tags `1.1.0` and `latest`
3. Creates a GitHub Release with auto-generated release notes

Monitor the build:

```bash
gh run list --workflow=release-docker.yml --limit 1
gh run watch <run-id>
```

### Step 2: Update the distribution repo

```bash
cd /path/to/deepsql-self-host
```

Update `.env.example` to pin the new version:

```env
DEEPSQL_BACKEND_IMAGE=ghcr.io/deepsqlai/deepsql-backend:1.1.0
DEEPSQL_FRONTEND_IMAGE=ghcr.io/deepsqlai/deepsql-frontend:1.1.0
```

If docker-compose, scripts, or config files changed in the main repo, copy them over:

```bash
# Only copy files that actually changed:
cp /path/to/dba-agent/docker-compose.yml .
cp /path/to/dba-agent/scripts/self-host/install.sh scripts/install.sh
cp /path/to/dba-agent/scripts/self-host/status.sh scripts/status.sh
cp /path/to/dba-agent/scripts/self-host/smoke-test.sh scripts/smoke-test.sh
cp /path/to/dba-agent/scripts/self-host/uninstall.sh scripts/uninstall.sh
cp /path/to/dba-agent/docker/nginx/default.conf docker/nginx/default.conf
cp /path/to/dba-agent/docker/postgres/init/01_create_scheduled_tasks.sql docker/postgres/init/
```

**Important:** The self-host repo scripts use `ROOT_DIR` one level up (not two). After copying `install.sh`, verify these differences:
- `ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"` (not `../../`)
- "Useful commands" output uses `./scripts/*.sh` (not `./scripts/self-host/*.sh`)

Commit and tag:

```bash
git add -A
git commit -m "chore: sync with dba-agent v1.1.0"
git tag v1.1.0
git push origin main --tags
```

### Step 3: Notify the customer

Tell them to update:

```bash
docker compose pull
docker compose up -d
```

Or pin the new version in their `.env`:

```env
DEEPSQL_BACKEND_IMAGE=ghcr.io/deepsqlai/deepsql-backend:1.1.0
DEEPSQL_FRONTEND_IMAGE=ghcr.io/deepsqlai/deepsql-frontend:1.1.0
```

---

## Distributing to a New Customer

### Step 1: Grant access

**Option A — GitHub collaborator (recommended):**
Invite their GitHub username as an outside collaborator with read access on `DeepSQLAI/deepsql`. ghcr.io packages inherit repo access, so they can pull images too.

**Option B — Read-only PAT:**
Generate a Personal Access Token (classic) with `read:packages` scope and share it.

### Step 2: Share credentials

Via a secure channel (1Password, encrypted message, etc.), send:
- GitHub PAT (if using Option B instead of collaborator access)

Do **not** send LLM credentials. The customer brings their own — see "Configuring the
LLM" in the README. DeepSQL ships no model credentials.

### Step 3: Customer setup

They follow the README in `deepsql`:

```bash
# 1. Authenticate with registry
echo "<TOKEN>" | docker login ghcr.io -u <USERNAME> --password-stdin

# 2. Clone the distribution repo
git clone https://github.com/DeepSQLAI/deepsql.git
cd deepsql

# 3. Configure
cp .env.example .env
# Edit .env — set DEEPSQL_CHAT_* (and DEEPSQL_EMBEDDING_* for RAG)

# 4. Install (auto-generates JWT secret, encryption key, DB password)
./scripts/self-host/install.sh

# 5. Open http://localhost:3000
```

---

## Quick Start (Main Repo — Development)

### 1. Prepare the environment file

```bash
cp .env.example .env
```

The install script auto-generates security secrets (`SECURITY_JWT_SECRET`, `ENCRYPTION_KEY`, `DB_PASSWORD`) if they are still set to placeholders. It also prompts interactively for your LLM chat API key.

Required values that must be provided manually:
- `DEEPSQL_CHAT_PROVIDER` — `openai` is the only id shipped; it also covers Azure OpenAI and any OpenAI-compatible server
- `DEEPSQL_CHAT_API_KEY`
- `DEEPSQL_CHAT_ENDPOINT` — e.g. `https://api.openai.com/v1`
- `DEEPSQL_CHAT_MODEL` — e.g. `gpt-4o`

Strongly recommended (without them RAG retrieval stays keyword-only):
- `DEEPSQL_EMBEDDING_PROVIDER`, `DEEPSQL_EMBEDDING_API_KEY`

Recommended defaults for self-host:

```env
SPRING_PROFILES_ACTIVE=prod
VECTOR_STORE_TYPE=pgvector
AZURE_SEARCH_ENABLED=false
CORS_ALLOWED_ORIGINS=http://localhost:3000

# CRITICAL for `deepsql login` (CLI / MCP install) — must point at the
# DeepSQL frontend your users open in their browser. The backend
# returns this verbatim as the authorize_url; if you forget to set
# these, the CLI's browser flow falls back to http://localhost:3000.
# That works for someone running on the VM itself, but fails for
# anyone connecting from their laptop (port-forwarding or otherwise) —
# the redirect can't reach their machine.
APP_BASE_URL=https://deepsql.your-company.com
APP_PUBLIC_URL=https://deepsql.your-company.com
```

The backend logs the resolved `app.base-url` at startup:

```
CLI auth flows will hand out authorize_url and verification_uri rooted at <url>
```

Check that log line after restart — if it points anywhere other than
your DeepSQL frontend, `deepsql login` on your team's laptops will
open the wrong page. (We've seen this bite a customer once already;
before 0.13.5 of the backend, the prod profile silently fell back to
our hosted demo URL when `APP_BASE_URL` wasn't set.)

`install.sh` automatically disables the Spring AI Azure vector-store auto-configuration when `VECTOR_STORE_TYPE=pgvector`, so the stack does not require Azure AI Search settings in self-host mode.

### 2. Optional first-run admin bootstrap

If you want `install.sh` to create the first admin account automatically, set:

```env
SECURITY_ADMIN_BOOTSTRAP_ENABLED=true
ADMIN_BOOTSTRAP_SECRET=choose-a-one-time-bootstrap-secret
DEEPSQL_INITIAL_ADMIN_EMAIL=admin@example.com
DEEPSQL_INITIAL_ADMIN_PASSWORD=choose-a-strong-password
```

After the first successful login, set `SECURITY_ADMIN_BOOTSTRAP_ENABLED=false` and restart the stack.

### 3. Install the stack

```bash
./scripts/self-host/install.sh
```

This script:
- auto-generates security secrets if still placeholders
- prompts for the LLM chat API key (and the embedding key, when an embedding provider is
  selected) if still placeholders
- requires `DEEPSQL_CHAT_PROVIDER`, `DEEPSQL_CHAT_API_KEY` and `DEEPSQL_CHAT_ENDPOINT`
- validates the registry is accessible
- pulls the frontend and backend images, unless `DEEPSQL_SKIP_IMAGE_PULL=true`
- starts PostgreSQL, Valkey, backend, and frontend
- waits for health checks
- resets or creates the first admin user if bootstrap vars are present

### 4. Verify the deployment

```bash
./scripts/self-host/status.sh
./scripts/self-host/smoke-test.sh
```

Open the UI:
- Frontend: `http://localhost:3000`
- Backend API: `http://localhost:8080/api`

## MCP From Another Machine

Self-host V1 supports Claude Desktop and Codex running on separate client machines.

Requirements on the client machine:

- Node 20+
- Network access to the customer-hosted DeepSQL backend over HTTPS
- A DeepSQL MCP personal access token created in the self-hosted deployment

Customer-facing examples are included here:

- `mcp/README.md`
- `mcp/claude_desktop_config.customer.example.json`
- `mcp/codex_config.customer.example.toml`

Typical client configuration:

```env
DEEPSQL_API_BASE_URL=https://customer-deepsql.example.com/api/
DEEPSQL_AUTH_TOKEN=dsql_mcp_...
```

Then run the local stdio shim with:

```bash
npx -y @deepsql/mcp
```

## Slack Data Agent

Slack V1 runs inside the backend with Slack Socket Mode, so customers do not need to expose a public inbound webhook endpoint.

Required environment variables:

```env
SLACK_ENABLED=true
SLACK_SOCKET_MODE_ENABLED=true
SLACK_APP_TOKEN=xapp-...
SLACK_BOT_TOKEN=xoxb-...
SLACK_SIGNING_SECRET=...
SLACK_DEEPSQL_BOT_USERNAME=slack-bot
```

Recommended DeepSQL setup:

- Create a dedicated non-admin DeepSQL user for Slack
- Store only Slack-approved database connections under that DeepSQL user
- Use slash commands to bind a channel or DM to one of those allowed connections

Runtime behavior:

- `/deepsql-use <connection>` binds the default connection for a channel or DM
- `/deepsql-reset` clears the binding
- `/deepsql-help` shows usage and available Slack-owned connections
- Channel mentions and DMs create or reuse one DeepSQL `chatId` per Slack thread and always reply in-thread

Admin status endpoint:

- `GET /api/admin/slack/status`

If you override the host ports in `.env`, use those values instead.

If the deployment is using an offline image archive, load it first and skip the registry pull:

```bash
gunzip -c deepsql-self-host-vX.Y.Z-images.tar.gz | docker load
```

Then set:

```env
DEEPSQL_SKIP_IMAGE_PULL=true
```

## Smoke test behavior

`scripts/self-host/smoke-test.sh` validates the packaged deployment against the vault PostgreSQL database itself.

It performs these checks:
- login as the bootstrap admin user
- create a PostgreSQL connection pointing to the internal `postgres` service
- verify the connection is listed
- fetch live schema metadata for that connection

This is the recommended post-install sanity check before handing the deployment to a customer team.

## Port configuration

The stack exposes these host ports by default:
- `3000` frontend
- `8080` backend
- `5432` PostgreSQL
- `6379` Valkey

Override them in `.env` if needed:

```env
DEEPSQL_FRONTEND_PORT=13000
DEEPSQL_BACKEND_PORT=18080
DEEPSQL_POSTGRES_PORT=15432
DEEPSQL_VALKEY_PORT=16379
CORS_ALLOWED_ORIGINS=http://localhost:13000
```

## Vector Store Modes

**Mode A — pgvector (default, fully local):**

```env
VECTOR_STORE_TYPE=pgvector
AZURE_SEARCH_ENABLED=false
```

Embeddings stored in the local PostgreSQL vault DB. No external dependencies.

**Mode B — Azure AI Search (opt-in):**

```env
VECTOR_STORE_TYPE=azure
AZURE_SEARCH_ENABLED=true
AZURE_SEARCH_ENDPOINT=https://your-search-resource.search.windows.net
AZURE_SEARCH_API_KEY=your-key
AZURE_SEARCH_INDEX_NAME=dba-agent-training-data
```

Restart the stack after changing. The app auto-configures based on `VECTOR_STORE_TYPE`.

## Operations

Start or update:

```bash
./scripts/self-host/install.sh
```

Check status:

```bash
./scripts/self-host/status.sh
```

Stop and remove containers, preserving data:

```bash
./scripts/self-host/uninstall.sh
```

Stop and remove everything, including persisted PostgreSQL and Valkey volumes:

```bash
./scripts/self-host/uninstall.sh --purge-data
```

View logs:

```bash
docker compose logs -f backend
docker compose logs -f frontend
docker compose logs -f postgres
```

## Packaging for offline delivery

Create a customer-deliverable bundle with offline image archive:

```bash
./scripts/self-host/release.sh --tag vX.Y.Z --registry ghcr.io/deepsqlai --push
./scripts/self-host/release.sh --tag vX.Y.Z --export-images
```

That produces:
- `dist/deepsql-self-host-vX.Y.Z.tar.gz` — runtime bundle
- `dist/deepsql-self-host-vX.Y.Z-images.tar.gz` — Docker image archive

For offline installs, the customer loads the archive and sets `DEEPSQL_SKIP_IMAGE_PULL=true`.

## Security notes

- ghcr.io packages are private (inherit from private repo visibility)
- Customer `.env` files are gitignored — secrets never committed
- Security secrets (JWT, encryption key, DB password) are auto-generated on first install
- No shared LLM secrets: the customer supplies their own provider credentials, which never
  pass through DeepSQL
- All customer data stays local (vault DB, cache, embeddings with pgvector default)
- Only outbound HTTPS to the LLM provider the customer configures — none at all if that is
  a server on their own network
- No inbound network exposure required
- Self-host must run with `SPRING_PROFILES_ACTIVE=prod`
- Do not leave admin bootstrap enabled after first-run setup
- Pin image references to an explicit release tag for production. Do not deploy `latest`
- Use a reverse proxy / TLS terminator in front of the frontend for any non-local deployment
- Backup the PostgreSQL volume before upgrades and before running `--purge-data`

## Current limitation

This package is self-hosted for infrastructure and data plane, but it is not fully air-gapped
by default: it calls out to whichever LLM provider you configure. Pointing
`DEEPSQL_CHAT_ENDPOINT` / `DEEPSQL_EMBEDDING_ENDPOINT` at a self-hosted OpenAI-compatible
server (vLLM, Ollama, LM Studio, TGI) keeps model traffic inside your network too.
