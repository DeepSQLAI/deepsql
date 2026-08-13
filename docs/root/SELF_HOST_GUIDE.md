# DeepSQL Self-Host Operations Guide

Everything you need to run DeepSQL in production that does not fit in the README.

The [root `README.md`](../../README.md) covers what DeepSQL is, the quick start, the
`DEEPSQL_CHAT_*` / `DEEPSQL_EMBEDDING_*` LLM configuration, and the development loop.
This guide picks up from there: sizing a host, the full environment reference with the
code that reads each variable, how the first admin account is actually created, TLS,
upgrades, backups, and the failure modes worth knowing before they happen.

There is **no container registry and no prebuilt image**. You clone the repository and
Docker Compose builds the backend and frontend from the checkout. Upgrading is
`git pull` plus a rebuild.

---

## Architecture

Four containers, defined in [`docker-compose.yml`](../../docker-compose.yml):

```
                     your network                          outbound
┌───────────────────────────────────────────────┐     ┌──────────────────┐
│                                               │     │  the LLM         │
│  browser ──▶ frontend  (nginx :80 → host 3000)│     │  endpoint you    │
│                 │  static SPA bundle          │     │  configure       │
│                 └─ /api/ ─▶ backend :8080 ────┼────▶│  (OpenAI, Azure  │
│                               │               │     │  OpenAI, or an   │
│                               ├─▶ postgres :5432     │  OpenAI-compat.  │
│                               │    vault + pgvector  │  server you run) │
│                               ├─▶ valkey :6379       └──────────────────┘
│                               │    cache
│                               └─▶ your databases (outbound, incl. SSH tunnels)
└───────────────────────────────────────────────┘
```

| Service    | Image / build                                    | Role |
|------------|--------------------------------------------------|------|
| `postgres` | `pgvector/pgvector:pg18`                          | Vault DB: encrypted connection credentials, users, chat history, brain artefacts, and — in the default `pgvector` mode — the RAG embeddings. Started with `pg_stat_statements` preloaded. |
| `valkey`   | `valkey/valkey:9.1.1`                             | Redis-compatible cache. Loss of this container degrades performance, not correctness. |
| `backend`  | built from [`backend/Dockerfile`](../../backend/Dockerfile) | Spring Boot 4.1.0 / Spring AI 2.0.0 on Java 25. All API, orchestration, retrieval and guardrails. |
| `frontend` | built from the root [`Dockerfile`](../../Dockerfile) | Vite-built React SPA served by nginx 1.27, which also reverse-proxies `/api/` to `backend:8080` so the browser bundle can use relative URLs. |

Named volumes: `dba-agent-postgres` (vault data), `dba-agent-valkey` (cache), and
`dba-agent-logs` (backend log files at `/app/logs`). Docker prefixes them with the
Compose project name — see [Project name](#project-name-two-stacks-by-accident).

Only two things leave the environment: calls to the LLM endpoint you configure, and
optional anonymous telemetry that is a no-op unless you supply your own PostHog key.
Point the LLM endpoints at a server on your own network and nothing leaves at all.

### First-run database initialisation

The scripts in [`docker/postgres/init/`](../../docker/postgres/init/) run **only when the
`dba-agent-postgres` volume is empty** — that is standard postgres-image behaviour. They
create the db-scheduler table, enable `pgvector`, and enable `pg_stat_statements`.
`install.sh` re-applies the first and second of those on every run
(`ensure_scheduler_table`, `ensure_pg_stat_statements`), so an upgrade over an existing
volume is not silently missing them.

---

## Host requirements

**Software**

- Docker Engine with Compose v2 — check with `docker compose version`
- `curl` and `openssl`, used by `scripts/self-host/install.sh`
- Git, to clone and later to `git pull` for upgrades

Nothing else. The JDK, Maven and Node toolchains live inside the build stages.

**Memory — read this before provisioning.** The backend entrypoint sets `-Xmx3g` together
with `-XX:+ExitOnOutOfMemoryError` ([`backend/Dockerfile`](../../backend/Dockerfile),
lines 41–66), and the comment there records why:

> Heap was 1 GB … but brain init's `SCHEMA_CLASSIFICATION` runs ~10 parallel
> virtual-thread sub-classifications that each load hundreds of tables' columns +
> indexes into memory. On a large MySQL schema (~600 tables) the parallel load OOMs
> the 1 GB heap; with `-XX:+ExitOnOutOfMemoryError` the JVM exits cleanly (code 0, no
> shutdown logs — looks like an external SIGTERM) and the brain init progress is lost.
> The container auto-restarts and the cycle repeats every ~8 min.

Two things follow.

1. **The backend alone wants ~4 GB available to Docker** — 3 GB of heap plus JVM
   overhead. The 3 GB figure was chosen assuming a host with roughly 15 GB of RAM, which
   leaves plenty for postgres, valkey, nginx and the OS. Size the host so postgres and
   valkey are not competing with that 3 GB.
2. **A restart loop with no error is the OOM signature.** If the backend container
   restarts every few minutes during brain initialisation, with exit code 0 and no
   shutdown logs, it ran out of heap. It does not look like an OOM — it looks like
   something killed the process from outside. See
   [Troubleshooting](#backend-restarts-every-few-minutes-during-brain-init).

**Disk.** Building compiles the backend with Maven inside the container and bundles the
frontend with Vite, so the build cache and images together are multiple GB. On top of
that the vault volume grows with your schema metadata and — in `pgvector` mode — one
embedding row per RAG document. Both grow with the number and size of the databases you
connect. Give the host generous headroom and monitor the volume rather than trusting a
fixed number.

**CPU.** No verified minimum. Brain initialisation is the CPU-heavy phase and is
parallel across virtual threads, so more cores shorten it; steady-state serving is light.

---

## Installing

```bash
git clone <this-repo> deepsql
cd deepsql
cp .env.example .env
$EDITOR .env                     # at minimum the DEEPSQL_CHAT_* group

./scripts/self-host/install.sh
```

[`scripts/self-host/install.sh`](../../scripts/self-host/install.sh) is the recommended
path, not a convenience wrapper — it is the only thing in the repository that performs
the [first-run admin bootstrap](#first-run-access) correctly. In order it:

1. Copies `.env.example` to `.env` and exits if `.env` did not exist, so you get a chance
   to fill it in.
2. Generates `SECURITY_JWT_SECRET`, `ENCRYPTION_KEY`, `DB_PASSWORD` and
   `ADMIN_BOOTSTRAP_SECRET` with `openssl` if they are still placeholders, and writes
   them back into `.env`.
3. Prompts for `DEEPSQL_CHAT_API_KEY`, for `DEEPSQL_EMBEDDING_API_KEY` (only if
   `DEEPSQL_EMBEDDING_PROVIDER` is set), for the initial admin email and password, and
   optionally for `DEEPSQL_COMPANY_NAME`.
4. Requires `DEEPSQL_CHAT_PROVIDER`, `DEEPSQL_CHAT_API_KEY` and `DEEPSQL_CHAT_ENDPOINT`
   to be real values, and requires `AZURE_SEARCH_*` only if you chose the Azure vector
   store.
5. Sets `SPRING_AUTOCONFIGURE_EXCLUDE` to exclude Spring AI's Azure vector-store
   auto-configuration when `VECTOR_STORE_TYPE=pgvector`, so the stack needs no Azure
   settings at all.
6. Builds both images, starts the stack under the Compose project name
   `deepsql-selfhost`, and waits for the backend and frontend to answer.
7. Ensures the db-scheduler table and `pg_stat_statements` exist, then verifies the
   pgvector RAG store: the `vector` extension, the `rag_documents` table, that
   `rag_documents.embedding` is `vector(3072)`, and that the `idx_rag_docs_embedding` ANN
   index exists. Any of those missing is a hard failure.
8. Flips `SECURITY_ADMIN_BOOTSTRAP_ENABLED=true`, creates the admin account, then flips it
   back to `false` in `.env` and restarts the backend.
9. Runs [`scripts/self-host/setup-agent.sh`](../../scripts/self-host/setup-agent.sh) unless
   `DEEPSQL_SKIP_AGENT_SETUP=1` — installs Hermes under `~/.hermes/`, provisions the admin
   MCP profile, and starts the webui on `0.0.0.0:8787`.

Re-running it is the supported way to apply configuration changes and to rebuild after a
`git pull`. It is idempotent: already-set secrets are left alone, and the admin bootstrap
step no-ops once `.env` no longer carries a blank admin password.

The script honours `DEEPSQL_ENV_FILE`, `DEEPSQL_COMPOSE_FILE` and `DEEPSQL_PROJECT_NAME`
if you need to run more than one stack on a host.

### Preparing a bare Linux VM

[`scripts/self-host/bootstrap-server.sh`](../../scripts/self-host/bootstrap-server.sh),
run as root on a fresh Debian/Ubuntu host, installs Docker and the Compose plugin, ensures
`curl`, creates the deploy directory (`/opt/deepsql`, override with `DEEPSQL_DEPLOY_DIR`;
owner `ubuntu`, override with `DEEPSQL_DEPLOY_USER`), and seeds `.env` from `.env.example`
if a checkout is already in place. It does not clone the repository for you — put the
checkout in the deploy directory yourself, since the stack is built from source.

---

## First-run access

**`docker compose up -d --build` on its own leaves you with a stack you cannot log into.**
There is no seeded account, and all three of the obvious ways to make one are closed:

| Path | Status | Code |
|---|---|---|
| `POST /api/auth/signup` | Always returns 403 — the handler is a stub with no logic behind it. | [`AuthController.java:77-82`](../../backend/src/main/java/com/dbaagent/controller/AuthController.java) |
| `POST /api/setup/initialize` (the onboarding wizard) | Always returns 410. The handler ignores its request body entirely. | [`SetupController.java:68-73`](../../backend/src/main/java/com/dbaagent/controller/SetupController.java) |
| `POST /api/users/admin/reset` | The only working path, behind three independent gates. | [`UserController.java:81-91`](../../backend/src/main/java/com/dbaagent/controller/UserController.java) |

`/api/users/admin/reset` checks, in order:

1. `security.admin.bootstrap.enabled` must be true — set `SECURITY_ADMIN_BOOTSTRAP_ENABLED=true`
   ([`UserController.java:36-37`](../../backend/src/main/java/com/dbaagent/controller/UserController.java),
   bound from [`application.properties:21`](../../backend/src/main/resources/application.properties)).
   Note that [`application-prod.properties:11`](../../backend/src/main/resources/application-prod.properties)
   hardcodes it to `false`; the environment variable overrides it, which is why it works
   under `SPRING_PROFILES_ACTIVE=prod`.
2. The request must come from a **loopback address**. `isLocalRequest` reads
   `request.getRemoteAddr()` and accepts only `127.0.0.1`, `::1` or another loopback
   address ([`UserController.java:133-149`](../../backend/src/main/java/com/dbaagent/controller/UserController.java)).
   There is no `X-Forwarded-For` handling, so a call routed through the frontend nginx, a
   reverse proxy, or from another host is rejected. It has to originate **inside the
   backend container**.
3. The `X-Admin-Bootstrap-Secret` header must equal `security.admin.bootstrap.secret`. A
   blank configured secret fails every comparison, so `ADMIN_BOOTSTRAP_SECRET` must
   genuinely be set ([`UserController.java:151-158`](../../backend/src/main/java/com/dbaagent/controller/UserController.java)).

`install.sh` satisfies all three and then closes the door again. If you must do it by
hand — recovering a lost admin password, say — this is the procedure it uses:

```bash
# 1. Set both in .env, then restart the backend so it picks them up.
#      SECURITY_ADMIN_BOOTSTRAP_ENABLED=true
#      ADMIN_BOOTSTRAP_SECRET=<a one-time secret>
docker compose -p deepsql-selfhost up -d backend

# 2. Call the endpoint from inside the backend container (loopback requirement).
docker compose -p deepsql-selfhost exec -T backend sh -lc '
  curl -fsS -X POST http://localhost:8080/api/users/admin/reset \
    -H "Content-Type: application/json" \
    -H "X-Admin-Bootstrap-Secret: <the same secret>" \
    -d "{\"email\":\"admin@example.com\",\"password\":\"<a strong password>\"}"'

# 3. Set SECURITY_ADMIN_BOOTSTRAP_ENABLED=false in .env and restart again.
docker compose -p deepsql-selfhost up -d backend
```

Two things worth knowing about the endpoint. It **deletes any existing user named
`admin`** before recreating it — that is what makes it a password reset, and it is
destructive. And the created account's *username* is always `admin`, regardless of the
email you pass; the email is what you log in with.

Every subsequent user is created by an administrator from the UI. Self-service signup
never re-opens.

---

## Configuration

All configuration is environment variables in `.env`, which Compose passes to the backend
via `env_file` ([`docker-compose.yml:66-67`](../../docker-compose.yml)).
[`.env.example`](../../.env.example) is documented inline and is the fastest reference;
the tables below add the code that actually reads each value, so you can check a claim
rather than trust it.

> The onboarding wizard in the UI is **not** a way to configure the LLM. It writes a
> different, older namespace of config keys that
> [`LlmConfigResolver`](../../backend/src/main/java/com/dbaagent/llm/LlmConfigResolver.java)
> does not read. Environment variables are the working path.

### Security and secrets

| Variable | Read by | Notes |
|---|---|---|
| `SECURITY_JWT_SECRET` | `application.properties:20`, `application-prod.properties:10` | Signs session tokens. `openssl rand -base64 64`. Rotating it invalidates every session. |
| `ENCRYPTION_KEY` | [`EncryptionService.java:36`](../../backend/src/main/java/com/dbaagent/security/EncryptionService.java) | AES-GCM key for the credential vault. `openssl rand -base64 32`. |
| `ENCRYPTION_KEYS` | [`EncryptionService.java:35`](../../backend/src/main/java/com/dbaagent/security/EncryptionService.java) | Rotation form: comma-separated `id:key` pairs. Use instead of `ENCRYPTION_KEY`, not alongside. |
| `ENCRYPTION_KEY_ID` | [`EncryptionService.java:37`](../../backend/src/main/java/com/dbaagent/security/EncryptionService.java) | Names the active key. Required when more than one key is configured (`EncryptionService.java:217`). `install.sh` requires it to be non-blank; `.env.example` ships `self-hosted-key-1`. |
| `SECURITY_AUTH_ENABLED` | `application.properties:18` → [`SecurityConfig.java`](../../backend/src/main/java/com/dbaagent/config/SecurityConfig.java) | Leave at `true`. `false` accepts unauthenticated API calls — local development only. |
| `SECURITY_ADMIN_BOOTSTRAP_ENABLED` | `application.properties:21` → [`UserController.java:36`](../../backend/src/main/java/com/dbaagent/controller/UserController.java) | Gate for the first-admin endpoint. Must be `false` in steady state. |
| `ADMIN_BOOTSTRAP_SECRET` | `application.properties:22` → [`UserController.java:39`](../../backend/src/main/java/com/dbaagent/controller/UserController.java) | The `X-Admin-Bootstrap-Secret` value. Blank disables the endpoint outright. |
| `SECURITY_COOKIE_SECURE` | `application.properties:28` → [`AuthSessionService.java:41`](../../backend/src/main/java/com/dbaagent/service/AuthSessionService.java) | **Set `true` once you are behind TLS.** Defaults to `false`. |
| `SECURITY_COOKIE_SAME_SITE` | `application.properties:27` → [`AuthSessionService.java:44`](../../backend/src/main/java/com/dbaagent/service/AuthSessionService.java) | Defaults to `Lax`. Only change it if the UI and API are on different sites. |
| `SECURITY_SESSION_ACCESS_MINUTES` | `application.properties:23` → [`JwtUtil.java`](../../backend/src/main/java/com/dbaagent/security/JwtUtil.java), `AuthSessionService.java` | Access-token lifetime. Default 15. |
| `SECURITY_SESSION_REFRESH_DAYS` | `application.properties:24` → [`AuthSessionService.java`](../../backend/src/main/java/com/dbaagent/service/AuthSessionService.java) | Refresh-token lifetime. Default 7. |
| `SPRING_PROFILES_ACTIVE` | [`docker-compose.yml:90`](../../docker-compose.yml) | `prod` for self-hosting. Compose and `install.sh` both default to it. |

### DeepSQL Agent (Hermes) — required for Agent tab + AI dashboards

The four Compose services give you auth, brain, classic chat, and schema tools. Two UI
surfaces additionally need a **host Hermes webui** on `:8787`:

| UI surface | How it reaches Hermes |
|---|---|
| **Agent** tab | Browser → nginx `/agent-api/` → `host.docker.internal:8787` |
| **Dashboards** → AI generate | Backend `AgentChatClient` → `AGENT_WEBUI_URL` (default `http://host.docker.internal:8787`) |

```bash
./scripts/self-host/setup-agent.sh
```

That script (also invoked by `install.sh`) will:

1. Clone [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent) and
   [nesquena/hermes-webui](https://github.com/nesquena/hermes-webui) into `~/.hermes/` if needed.
2. Ensure the Python `mcp` SDK is installed (without it, DeepSQL tools never register).
3. Run [`agent/install.sh`](../../agent/install.sh) to write the DBA persona, skills, and
   model config from your `DEEPSQL_CHAT_*` values.
4. Mint an MCP token for the admin user and write `~/.hermes/profiles/u-<user>/`.
5. Start the webui bound to `0.0.0.0:8787` with **no** `HERMES_WEBUI_PASSWORD`.

Compose already sets `AGENT_WEBUI_URL` and `extra_hosts` on the backend, and nginx proxies
`/agent-api/` with `$http_host` (port-preserving) so Hermes CSRF accepts
`Origin: http://localhost:3000`.

Verify:

```bash
curl -fsS http://127.0.0.1:8787/api/mcp/servers
./scripts/self-host/smoke-test.sh   # includes agent path checks when Hermes is up
```

Set `DEEPSQL_SKIP_AGENT_SETUP=1` before `install.sh` if you only want the core stack;
set `DEEPSQL_SMOKE_AGENT=0` to skip agent checks in the smoke test.

### The LLM

[`LlmConfigResolver`](../../backend/src/main/java/com/dbaagent/llm/LlmConfigResolver.java)
is the authority. It resolves two independent bundles, `chat` and `embedding`, from the
database first and the environment second, and builds each environment variable name as
`DEEPSQL_<ROLE>_<FIELD>` with `-` replaced by `_`
([`LlmConfigResolver.java:112-129`](../../backend/src/main/java/com/dbaagent/llm/LlmConfigResolver.java)).

**`DEEPSQL_<ROLE>_PROVIDER` gates the whole bundle.** With it unset, no other variable in
that group is read at all (`LlmConfigResolver.java:113-115`). The fields it collects are
listed at `LlmConfigResolver.java:31-33`: `api-key`, `endpoint`, `model`, `region`,
`access-key-id`, `secret-access-key`, `api-version`, `use-responses-api`, `temperature`.

| Variable | Notes |
|---|---|
| `DEEPSQL_CHAT_PROVIDER` | `openai` is the only id shipped. It covers OpenAI, Azure OpenAI, and any OpenAI-compatible server. |
| `DEEPSQL_CHAT_API_KEY` | Required by `install.sh`. |
| `DEEPSQL_CHAT_ENDPOINT` | Required by `install.sh` — chat has no working endpoint fallback, so set it explicitly even for OpenAI. |
| `DEEPSQL_CHAT_MODEL` | Model id; for Azure, the *deployment* name. |
| `DEEPSQL_CHAT_TEMPERATURE`, `DEEPSQL_CHAT_API_VERSION`, `DEEPSQL_CHAT_USE_RESPONSES_API` | Optional tuning. `_API_VERSION` is the Azure REST version; `_USE_RESPONSES_API` accepts `true` / `false` / `auto`. |
| `DEEPSQL_EMBEDDING_PROVIDER`, `_API_KEY`, `_ENDPOINT`, `_MODEL` | Same shape. Optional: the app starts without them and retrieval stays keyword-only. If you set `_PROVIDER`, `install.sh` also requires `_API_KEY`. |
| `EMBEDDING_FAIL_OPEN` | `application.properties:131` / `application-prod.properties:95` → [`EmbeddingService.java`](../../backend/src/main/java/com/dbaagent/service/EmbeddingService.java). `true` returns an empty vector when an embedding call fails and degrades retrieval silently; `false` propagates the error. The `prod` profile defaults to `false` — keep it there. |
| `APP_CHAT_AGENTIC_ENABLED`, `APP_CHAT_AGENTIC_MAX_STEPS` | `application.properties:132-133` → [`AgentOrchestrator.java`](../../backend/src/main/java/com/dbaagent/service/agent/AgentOrchestrator.java). Step budget for agentic chat; default 6. |

`AZURE_OPENAI_KEY`, `AZURE_OPENAI_ENDPOINT` and `AZURE_OPENAI_CHAT_DEPLOYMENT` still
appear in `application.properties` but configure nothing in the chat path — the resolver
does not consult them. Use an Azure endpoint through `DEEPSQL_CHAT_*` instead.

#### Behind an LLM gateway (LiteLLM)

A gateway is just another OpenAI-compatible endpoint. Point at the proxy, authenticate
with a virtual key, and name the alias from its `config.yaml`:

```env
DEEPSQL_CHAT_PROVIDER=openai
DEEPSQL_CHAT_ENDPOINT=http://litellm:4000/v1
DEEPSQL_CHAT_API_KEY=sk-your-litellm-virtual-key
DEEPSQL_CHAT_MODEL=your-alias

DEEPSQL_EMBEDDING_PROVIDER=openai
DEEPSQL_EMBEDDING_ENDPOINT=http://litellm:4000/v1
DEEPSQL_EMBEDDING_API_KEY=sk-your-litellm-virtual-key
DEEPSQL_EMBEDDING_MODEL=your-embedding-alias
```

Verified against a self-hosted LiteLLM: provider registration, chat with SQL generation,
the `/api/llm/v1` gateway, and a full brain initialisation (every document embedded, no
drops). Three things are worth knowing before you deploy it.

**Keep the `/v1`.** The chat path decides its URL shape from the endpoint string: a base
containing `/v1` or `/v2` is treated as OpenAI-style and gets `/chat/completions`, while
anything else is treated as Azure-style and gets
`/openai/deployments/<model>/chat/completions?api-version=…`
([`ResponsesApiChatModel.resolveApiUrl`](../../backend/src/main/java/com/dbaagent/llm/openai/ResponsesApiChatModel.java)).
LiteLLM happens to serve the Azure shape too, so omitting `/v1` still worked in testing —
but that relies on the gateway's Azure emulation rather than on the path you configured,
and a plain vLLM or Ollama in the same position returns 404.

**Alias names steer `_USE_RESPONSES_API=auto`.** The heuristic reads the model name, not
the endpoint's capabilities: an alias starting `gpt-5`, `o1`, `o3`, `o4` or `codex`, or an
empty model, selects `/v1/responses`. Mirroring upstream model names in your gateway config
can therefore route DeepSQL to an API the gateway does not serve. Aliases that do not look
like OpenAI models resolve to chat completions on their own; otherwise set
`DEEPSQL_CHAT_USE_RESPONSES_API=false`.

**Embedding width is fixed at initialisation.** The pgvector column is created for the
width of whichever embedding model ran first. Repointing the gateway's embedding alias at a
model of a different width means re-running brain initialisation for every connection.
Authentication needs no special handling: only `.azure.com` / `.azure-api.net` endpoints
switch to the `api-key` header, so a gateway receives `Authorization: Bearer <virtual key>`.

### Vector store

| Variable | Read by | Notes |
|---|---|---|
| `VECTOR_STORE_TYPE` | `application.properties:144` → [`PgVectorRagStoreInitializer.java`](../../backend/src/main/java/com/dbaagent/config/PgVectorRagStoreInitializer.java), [`PgVectorSearchService.java`](../../backend/src/main/java/com/dbaagent/service/PgVectorSearchService.java) | `pgvector` for self-hosting — embeddings stay in the vault DB, no external dependency. `azure` selects Azure AI Search. |
| `VECTOR_STORE_EMBEDDING_DIMENSIONS` | same | Default 3072, matching `text-embedding-3-large`. **Changing this after ingestion invalidates the `rag_documents.embedding` column** — `install.sh` fails the install if the column type does not match. |
| `SPRING_AUTOCONFIGURE_EXCLUDE` | Spring Boot; set at [`docker-compose.yml:92`](../../docker-compose.yml) | For pgvector mode, set to `org.springframework.ai.vectorstore.azure.autoconfigure.AzureVectorStoreAutoConfiguration`. `install.sh` sets it for you. |
| `AZURE_SEARCH_ENABLED` | `application.properties:140` → [`VectorSearchService.java`](../../backend/src/main/java/com/dbaagent/service/VectorSearchService.java) | `false` in pgvector mode. |
| `AZURE_SEARCH_ENDPOINT`, `AZURE_SEARCH_API_KEY`, `AZURE_SEARCH_INDEX_NAME` | `application.properties:137-139` → [`AzureSearchService.java`](../../backend/src/main/java/com/dbaagent/service/AzureSearchService.java) | Required only for `VECTOR_STORE_TYPE=azure`; `install.sh` enforces all three in that mode. |

Switching modes requires a restart. Embeddings do not migrate between the two stores —
connections have to be re-initialised.

### Vault database and cache

| Variable | Read by | Notes |
|---|---|---|
| `DB_PASSWORD` | [`docker-compose.yml:25,72`](../../docker-compose.yml) | Used for both the postgres container's `POSTGRES_PASSWORD` and the backend's datasource, so the two cannot drift. Changing it after first start does **not** change the password already stored in the volume. |
| `DB_URL`, `DB_USERNAME` | `application.properties:52,54` | Compose pins these to its own `postgres` service ([`docker-compose.yml:70-71`](../../docker-compose.yml)) and its values win over `.env`. Only relevant when running the backend outside Compose. |
| `DEEPSQL_VALKEY_PASSWORD` | [`docker-compose.yml`](../../docker-compose.yml) → Valkey `--requirepass` and `spring.data.redis.password` | **Required.** Compose fails to start without it. `install.sh` generates a strong value when the placeholder is blank. Backend and Valkey must share the same password. |

### Public URLs — the one that breaks `deepsql login`

`APP_BASE_URL` and `APP_PUBLIC_URL` are **not in `.env.example`**. You have to add them,
and getting them wrong fails in a way that is hard to trace back.

| Variable | Read by | What it does |
|---|---|---|
| `APP_BASE_URL` | `application-prod.properties:47` → [`CliAuthorizationService.java:65`](../../backend/src/main/java/com/dbaagent/service/CliAuthorizationService.java) | The backend returns this **verbatim** as the CLI's `authorize_url` and `verification_uri`. |
| `APP_PUBLIC_URL` | `application.properties:42`, `application-prod.properties:37` → [`AuthController.java:54`](../../backend/src/main/java/com/dbaagent/controller/AuthController.java), [`UserInviteService.java:31`](../../backend/src/main/java/com/dbaagent/service/UserInviteService.java) | Base for invitation emails and signup links. |

Both default to `http://localhost:3000`. That default works for someone sitting on the
VM, and fails for everyone else: `deepsql login` on a teammate's laptop opens
`http://localhost:3000/...` on *their* machine, where nothing is listening. The same goes
for an invitation email — the recipient gets a link to their own localhost.

Set them to the URL your users actually type into a browser:

```env
APP_BASE_URL=https://deepsql.your-company.example
APP_PUBLIC_URL=https://deepsql.your-company.example
```

The backend logs the resolved value at startup
([`CliAuthorizationService.java:82`](../../backend/src/main/java/com/dbaagent/service/CliAuthorizationService.java)):

```
CLI auth flows will hand out authorize_url and verification_uri rooted at <url>
```

Check that line after every restart that touches this configuration. An empty value logs
an explicit error instead.

### Ports and CORS

Every host port is overridable, and CORS has to be told about the origin you actually
serve from.

| Variable | Default | Read by |
|---|---|---|
| `DEEPSQL_FRONTEND_PORT` | 3000 | [`docker-compose.yml:116`](../../docker-compose.yml) |
| `DEEPSQL_BACKEND_PORT` | 8080 | [`docker-compose.yml:94`](../../docker-compose.yml) |
| `DEEPSQL_POSTGRES_PORT` | 5432 (published as `127.0.0.1:…` only) | [`docker-compose.yml`](../../docker-compose.yml) |
| `DEEPSQL_VALKEY_PORT` | 6379 (published as `127.0.0.1:…` only) | [`docker-compose.yml`](../../docker-compose.yml) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | `application.properties:87` → [`SecurityConfig.java`](../../backend/src/main/java/com/dbaagent/config/SecurityConfig.java) |

```env
DEEPSQL_FRONTEND_PORT=13000
CORS_ALLOWED_ORIGINS=http://localhost:13000
```

Behind a reverse proxy, `CORS_ALLOWED_ORIGINS` must list the **public** origin
(`https://deepsql.your-company.example`), not the container port. Consider binding
postgres and valkey to `127.0.0.1` on a public host, or dropping their `ports:` entries
entirely — nothing outside the Compose network needs them.

### Optional integrations

**Slack.** The bot runs inside the backend over Socket Mode, so no inbound webhook has to
be exposed. All six variables bind through
[`SlackProperties.java`](../../backend/src/main/java/com/dbaagent/config/SlackProperties.java)
(`@ConfigurationProperties(prefix = "slack")`) from `application.properties:290-295`:
`SLACK_ENABLED`, `SLACK_SOCKET_MODE_ENABLED`, `SLACK_APP_TOKEN`, `SLACK_BOT_TOKEN`,
`SLACK_SIGNING_SECRET`, `SLACK_DEEPSQL_BOT_USERNAME`.

Create a dedicated non-admin DeepSQL user for the bot and give it only the connections
Slack is allowed to reach; `SLACK_DEEPSQL_BOT_USERNAME` names that user. The slash
commands registered at
[`SlackBotService.java:137-168`](../../backend/src/main/java/com/dbaagent/service/SlackBotService.java)
are `/deepsql-use <connection>`, `/deepsql-reset` and `/deepsql-help`. Admin status is at
`GET /api/admin/slack/status`
([`SlackAdminController.java:20,30`](../../backend/src/main/java/com/dbaagent/controller/SlackAdminController.java)).

**SMTP.** `EMAIL_HOST`, `EMAIL_PORT`, `EMAIL_USERNAME`, `EMAIL_PASSWORD`, `EMAIL_FROM`
(`application.properties:277-284`). These are fallback defaults; runtime settings stored
in the database take priority. Without SMTP, invitation and OTP emails cannot be sent.

**Telemetry.** Anonymous install and usage counters, off by default in practice: no
PostHog project key ships with the repository, so the sink is a no-op unless you set
`deepsql.telemetry.posthog-project-key` yourself
([`TelemetryConfig.java:39`](../../backend/src/main/java/com/dbaagent/config/TelemetryConfig.java)).
A truthy `DO_NOT_TRACK` or `DEEPSQL_TELEMETRY_DISABLED` disables it outright
([`TelemetryConfig.java:32-33`](../../backend/src/main/java/com/dbaagent/config/TelemetryConfig.java)),
as does the admin toggle. `DEEPSQL_COMPANY_NAME` labels the install and falls back to the
admin email's domain ([`InstallTelemetryBootstrap.java:95,117`](../../backend/src/main/java/com/dbaagent/service/telemetry/InstallTelemetryBootstrap.java)).

---

## TLS and reverse proxy

Nothing in the stack terminates TLS. For any deployment that is not `localhost`, put a
TLS terminator in front of the frontend container and let it proxy to
`DEEPSQL_FRONTEND_PORT`. The frontend's own nginx already forwards `/api/` to the backend
([`docker/nginx/default.conf`](../../docker/nginx/default.conf)), so a single upstream is
enough — you do not need to expose `DEEPSQL_BACKEND_PORT` publicly.

Once TLS is in place, three settings must follow or authentication behaves oddly:

```env
SECURITY_COOKIE_SECURE=true
CORS_ALLOWED_ORIGINS=https://deepsql.your-company.example
APP_BASE_URL=https://deepsql.your-company.example
APP_PUBLIC_URL=https://deepsql.your-company.example
```

[`nginx-production.conf`](../../nginx-production.conf) in the repository root is an
**example vhost pair for a different topology** — one where the SPA is served from
`/var/www/deepsql` on the host and the backend runs on `localhost:8080` outside Docker.
Read it for the proxy settings worth copying: `proxy_http_version 1.1` with the `Upgrade`
/ `Connection` headers, the `X-Forwarded-*` set, and 300-second connect/send/read timeouts
for long-running queries. It listens on port 80 with the certbot TLS lines commented out,
and its hostnames are placeholders (`api.example.com`, `app.example.com`) — replace them.
Its `location /_next/static/` block is a leftover from a Next.js era; DeepSQL builds with
Vite and never emits that path, so the block is inert.

If you do terminate TLS at a separate proxy in front of the frontend container, make sure
it also disables response buffering for `/api/`, or the streaming chat responses will
arrive all at once. The bundled config already does this
(`proxy_buffering off` in `docker/nginx/default.conf`).

---

## Operating the stack

### Project name (two stacks by accident)

`install.sh` and its sibling scripts run Compose with `--project-name deepsql-selfhost`.
A bare `docker compose` in the repository directory uses the directory name instead, so
`docker compose ps` will report an *empty, different* stack and `docker compose up -d`
would build a second one. Either use the scripts, or pass the project name explicitly:

```bash
docker compose -p deepsql-selfhost ps
```

or export `COMPOSE_PROJECT_NAME=deepsql-selfhost` in your shell. The same prefix appears
on the volumes: `deepsql-selfhost_dba-agent-postgres`.

### Health and status

```bash
./scripts/self-host/status.sh
```

[`status.sh`](../../scripts/self-host/status.sh) prints `docker compose ps` and probes
both HTTP endpoints. The underlying checks:

```bash
curl -fsS http://localhost:8080/api/actuator/health
curl -fsS http://localhost:3000
```

The backend actuator exposes `health`, `info`, `metrics`, `caches` and `prometheus`
(`application-prod.properties:140-142`), with full health detail
(`management.endpoint.health.show-details=always`). That is more than you may want on a
publicly reachable port — another reason not to expose `DEEPSQL_BACKEND_PORT` directly.

Compose runs its own health checks: `pg_isready` for postgres, `valkey-cli ping` for
valkey, and the actuator endpoint for the backend with a 60-second start period. The
frontend waits for the backend to be healthy before starting.

### Logs

```bash
docker compose -p deepsql-selfhost logs -f backend
docker compose -p deepsql-selfhost logs -f frontend
docker compose -p deepsql-selfhost logs -f postgres
```

The backend also writes files to `/app/logs` inside its container, persisted in the
`dba-agent-logs` volume. See [`docs/LOGGING-GUIDE.md`](../LOGGING-GUIDE.md) for the log
format and what to look for.

### Smoke test

```bash
./scripts/self-host/smoke-test.sh
```

[`smoke-test.sh`](../../scripts/self-host/smoke-test.sh) is a real end-to-end exercise,
not a ping. Against the vault database itself it: verifies the `vector` and
`pg_stat_statements` extensions, the `rag_documents` table and its ANN index; logs in as
the admin; creates a PostgreSQL connection pointing at the internal `postgres` service;
confirms it appears in the connection list; fetches live schema metadata; waits for brain
initialisation to reach `COMPLETED` (up to 20 minutes by default); asserts that pgvector
holds embedded documents for that connection; then (unless `DEEPSQL_SMOKE_AGENT=0`)
verifies Hermes is up, nginx `/agent-api/api/profile/switch` returns 200, and the backend
can create a Hermes session via `AGENT_WEBUI_URL`.

For a live LLM turn (Agent tab SQL + dashboard HTML), run
[`e2e-agent-check.py`](../../scripts/self-host/e2e-agent-check.py).

It reads credentials from `DEEPSQL_INITIAL_ADMIN_EMAIL` / `DEEPSQL_INITIAL_ADMIN_PASSWORD`
in `.env`, or from `DEEPSQL_SMOKE_EMAIL` / `DEEPSQL_SMOKE_PASSWORD`. Set
`DEEPSQL_SMOKE_WAIT_FOR_INIT=false` to skip the brain-init wait, or
`DEEPSQL_SMOKE_INIT_TIMEOUT_SECONDS` to change the budget.

It leaves the connection it created behind. Delete it from the UI afterwards.

### Upgrading

```bash
git pull
./scripts/self-host/install.sh
```

or, driving Compose yourself:

```bash
git pull
docker compose -p deepsql-selfhost up -d --build
```

Prefer `install.sh`: it re-applies the scheduler table and `pg_stat_statements` (the
`docker/postgres/init/` scripts do **not** re-run on an existing volume) and re-verifies
the pgvector store afterwards. It will not re-prompt for values already set in `.env`.

Take a backup first. Schema migrations run automatically on backend startup and are not
reversible.

### Backup and restore

The vault database holds encrypted connection credentials, users, chat history, brain
artefacts and — in pgvector mode — every embedding. A logical dump is the portable
option:

```bash
# Backup
docker compose -p deepsql-selfhost exec -T postgres \
  pg_dump -U postgres -d dba_agent --format=custom > deepsql-vault-$(date +%F).dump

# Restore into a freshly started, empty stack
docker compose -p deepsql-selfhost exec -T postgres \
  pg_restore -U postgres -d dba_agent --clean --if-exists < deepsql-vault-2026-01-01.dump
```

**Back up `.env` alongside it, and store it somewhere else.** A dump without
`ENCRYPTION_KEY` / `ENCRYPTION_KEYS` is unusable — every stored database password in it is
ciphertext that only that key can open. Losing the key means re-entering every connection
credential by hand. Keeping the key in the same place as the dump defeats the encryption.

For a volume-level snapshot instead, stop the stack first so postgres is not mid-write:

```bash
docker compose -p deepsql-selfhost stop
docker run --rm -v deepsql-selfhost_dba-agent-postgres:/data -v "$PWD":/backup \
  alpine tar czf /backup/deepsql-postgres-volume.tar.gz -C /data .
docker compose -p deepsql-selfhost start
```

The valkey volume is a cache and does not need backing up.

### Stopping and removing

```bash
./scripts/self-host/uninstall.sh                 # stop and remove containers, keep volumes
./scripts/self-host/uninstall.sh --purge-data    # also delete the postgres and valkey volumes
```

`--purge-data` is `docker compose down --volumes`. It is irreversible and takes the vault
with it. Back up first.

---

## Troubleshooting

### Dashboard generate: 502 “DeepSQL agent is unavailable”

Dashboards call Hermes **from the backend container** (`AgentChatClient`), not through
the browser `/agent-api` proxy. Compose sets `AGENT_WEBUI_URL=http://host.docker.internal:8787`
and `extra_hosts: host.docker.internal:host-gateway` on the backend. If you still see
502, confirm Hermes is up (`curl -sS http://127.0.0.1:8787/api/mcp/servers`) and that
the backend can reach it:

```bash
docker exec deepsql-selfhost-backend-1 curl -sS -o /dev/null -w '%{http_code}\n' \
  http://host.docker.internal:8787/api/mcp/servers
```

### `/agent-api/*` returns 502 Bad Gateway

[`docker/nginx/default.conf`](../../docker/nginx/default.conf) proxies `/agent-api/` to
`http://host.docker.internal:8787/`. The agent is **not** one of the four Compose services —
it is an optional Hermes webui process, typically started on the host at `:8787`.

The frontend service maps `host.docker.internal` → `host-gateway` via `extra_hosts` in
[`docker-compose.yml`](../../docker-compose.yml). If you still see 502:

1. Confirm the agent is listening:
   `curl -sS -X POST http://127.0.0.1:8787/api/session/new -H 'Content-Type: application/json' -d '{}'`
2. Rebuild the frontend so the nginx config is picked up:
   `docker compose -p deepsql-selfhost up -d --build frontend`

### Agent tab spins / never talks to brain

Two host-side requirements after Hermes is up on `:8787`:

1. **Profile cookie.** Hermes scopes sessions by `hermes_profile`. The Agent tab must
   `POST /agent-api/api/profile/switch` with `{ "name": "u-<user>" }` before
   `session/new` / `chat/start`. Without it, `chat/start` returns 404
   `"Session not found"` and the UI loader never resolves. Fixed in
   [`src/lib/api/agentClient.js`](../../src/lib/api/agentClient.js).
2. **Python MCP SDK in the webui process.** Discovery runs in-process. Start the webui
   with the agent `venv` that has the `mcp` package (not a bare `.venv` missing it):

   ```bash
   # Prefer the canonical venv (has mcp). Bind 0.0.0.0 so Docker nginx can reach it.
   export HERMES_HOME=~/.hermes HERMES_WEBUI_HOST=0.0.0.0 HERMES_WEBUI_PORT=8787
   unset HERMES_WEBUI_PASSWORD
   cd ~/.hermes/hermes-webui && ~/.hermes/hermes-agent/venv/bin/python server.py
   ```

   Sanity check: `curl -sS http://127.0.0.1:8787/api/mcp/servers` should list
   `deepsql` and, after the first agent turn, `active: true` with a non-zero
   `tool_count`. If discovery logs `mcp package not installed`, install it into
   that interpreter (`uv pip install --python …/venv/bin/python mcp`).

### Frontend container will not start: `host not found in upstream "…"`

```
nginx: [emerg] host not found in upstream "…" in /etc/nginx/conf.d/default.conf
```

Ensure `extra_hosts: ["host.docker.internal:host-gateway"]` is present on the frontend
service (Compose adds this for Linux; Docker Desktop usually injects the name already),
then rebuild:

```bash
docker compose -p deepsql-selfhost up -d --build frontend
```

### Backend restarts every few minutes during brain init

Exit code 0, no shutdown logs, and the restart cadence roughly matching how long brain
initialisation gets before dying. That is the `-XX:+ExitOnOutOfMemoryError` heap
exhaustion described under [Host requirements](#host-requirements), not an external kill.
`docker stats` during initialisation will show the backend pinned near its limit.

Give the host more memory, or raise the ceiling by editing the `-Xmx3g` flag in
[`backend/Dockerfile`](../../backend/Dockerfile) and rebuilding. Large schemas — hundreds
of tables — are what tips it over.

### `deepsql login` opens the wrong page, or opens nothing

`APP_BASE_URL` is unset or wrong. See
[Public URLs](#public-urls--the-one-that-breaks-deepsql-login). Check the startup log
line and fix the value; there is no client-side workaround, because the backend hands the
URL to the CLI verbatim.

### Cannot log in after a fresh `docker compose up`

Expected. There is no seeded account and signup is disabled — see
[First-run access](#first-run-access). Run `install.sh`, or perform the bootstrap by hand.

### `Admin reset is only allowed from localhost`

The bootstrap call did not originate inside the backend container. Curling
`http://<host>:8080/api/users/admin/reset` from your laptop, or through the frontend
nginx, both fail this check — there is no `X-Forwarded-For` handling. Use
`docker compose exec backend` as shown in [First-run access](#first-run-access).

### `Invalid bootstrap secret` even though the secret looks right

`ADMIN_BOOTSTRAP_SECRET` is blank in the backend's environment, which fails every
comparison regardless of the header you send
([`UserController.java:151-158`](../../backend/src/main/java/com/dbaagent/controller/UserController.java)).
Confirm what the container actually has:

```bash
docker compose -p deepsql-selfhost exec backend printenv ADMIN_BOOTSTRAP_SECRET
```

Editing `.env` is not enough — the backend must be restarted to pick it up.

### `Missing encryption key; set ENCRYPTION_KEY or ENCRYPTION_KEYS`

The backend refuses to start without one
([`EncryptionService.java:208`](../../backend/src/main/java/com/dbaagent/security/EncryptionService.java)).
A sibling error, `ENCRYPTION_KEY_ID is required when multiple keys are configured`
(`EncryptionService.java:217`), means `ENCRYPTION_KEYS` holds more than one `id:key` pair
without naming the active one.

### `install.sh` fails on the pgvector verification

The messages are specific about which check failed: missing `rag_documents` table, missing
`vector` extension, an `embedding` column whose type is not `vector(3072)`, or a missing
`idx_rag_docs_embedding` index. The type mismatch usually means
`VECTOR_STORE_EMBEDDING_DIMENSIONS` was changed after the table was created; the others
usually mean the postgres volume was initialised from something other than the
`pgvector/pgvector` image.

### RAG answers are vague, or retrieval only matches literal keywords

No embedding provider is configured. `DEEPSQL_EMBEDDING_PROVIDER` gates the entire
embedding bundle, and without it retrieval falls back to keyword matching — the app runs
normally, just less well. `install.sh` prints a note when it detects this. Configure the
embedding group and re-initialise the affected connections.

### `docker compose ps` shows nothing after a successful install

Project-name mismatch. See [Project name](#project-name-two-stacks-by-accident).

---

## Production checklist

Agent-specific (in addition to the core stack):

- [ ] `./scripts/self-host/setup-agent.sh` succeeded (or was run by `install.sh`)
- [ ] `curl -fsS http://127.0.0.1:8787/api/mcp/servers` returns the `deepsql` server
- [ ] Backend reaches Hermes: `AGENT_WEBUI_URL` + `extra_hosts` on the backend service
- [ ] `./scripts/self-host/smoke-test.sh` passes with agent checks (default `DEEPSQL_SMOKE_AGENT=1`)
- [ ] Optional full turn: `python3 scripts/self-host/e2e-agent-check.py <connectionId>`

- [ ] `SPRING_PROFILES_ACTIVE=prod`
- [ ] `SECURITY_AUTH_ENABLED` left at `true`
- [ ] `SECURITY_ADMIN_BOOTSTRAP_ENABLED=false` after the first admin exists
- [ ] `SECURITY_JWT_SECRET`, `ENCRYPTION_KEY` and `DB_PASSWORD` are generated values, not the `.env.example` placeholders
- [ ] `ENCRYPTION_KEY` backed up somewhere other than where the database dumps live
- [ ] TLS terminated in front of the frontend, with `SECURITY_COOKIE_SECURE=true`
- [ ] `APP_BASE_URL` and `APP_PUBLIC_URL` set to the public URL, and the startup log line checked
- [ ] `CORS_ALLOWED_ORIGINS` set to the public origin only
- [ ] `EMBEDDING_FAIL_OPEN=false` (the `prod` default) so retrieval failures surface
- [ ] Postgres and valkey not published to a public interface
- [ ] `.env` never committed — it is gitignored, keep it that way
- [ ] A backup taken before every upgrade
- [ ] Model traffic reviewed: prompts go to whatever `DEEPSQL_CHAT_ENDPOINT` points at. Point it at a server on your own network if nothing may leave.

---

## Related documentation

- [`README.md`](../../README.md) — overview, quick start, LLM configuration, development
- [`.env.example`](../../.env.example) — inline documentation for every shipped variable
- [`mcp/README.md`](../../mcp/README.md) — the `deepsql` CLI and MCP server, including client configuration for Claude Desktop and Codex
- [`docs/root/MCP_PHASE1.md`](./MCP_PHASE1.md) — MCP tools and environment variables
- [`docs/LOGGING-GUIDE.md`](../LOGGING-GUIDE.md) — log format
- [`docs/RBAC_USAGE_GUIDE.md`](../RBAC_USAGE_GUIDE.md) — roles and permissions
- [`docs/AZURE-AI-SEARCH.md`](../AZURE-AI-SEARCH.md) — the optional Azure vector backend
