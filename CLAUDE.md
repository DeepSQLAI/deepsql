# CLAUDE.md — DBA Agent

> **Full reference**: See [`docs/root/CLAUDE.md`](docs/root/CLAUDE.md) for comprehensive architecture, all API endpoints, entity details, and integration flows.
> **Architecture summary**: See [`AGENTS.md`](AGENTS.md) for high-level codebase map used by all AI agents.

## Project Overview

DBA Agent is an AI-powered Database Performance Assistant with autonomous troubleshooting capabilities. Monorepo with a Java backend and a React frontend.

**Tech Stack:**
- **Backend**: Spring Boot 4.0.3 (Java 25), Spring AI 2.0.0-M2, PostgreSQL vault DB
- **Frontend**: React 19.2.3, Vite 7.3.0, Tailwind CSS 4.1.18
- **AI**: bring-your-own LLM via `LlmProviderRegistry` — OpenAI, Azure OpenAI, or any
  OpenAI-compatible server. Vector store: pgvector or Azure AI Search (RAG)
- **Caching**: Redis/Valkey
- **Databases supported**: PostgreSQL, MySQL (via provider registry pattern)

## Development Commands

### Backend (Spring Boot)

```bash
cd backend
mvn clean install                    # Build
mvn spring-boot:run                  # Run (dev mode, auth disabled)
mvn spring-boot:run -Dspring-boot.run.profiles=prod  # Run (prod, auth enabled)
mvn test                             # Run all tests
mvn test -Dtest="*IntegrationTest"   # Integration tests only
```

**Backend URL**: http://localhost:8080/api

### Frontend (React + Vite)

```bash
npm install       # Install dependencies
npm run dev       # Dev server (http://localhost:3000)
npm run lint      # Lint
npm run test:local-regression  # Quick local regression suite
npm run local-deploy           # Start services + run local regression suite
npm run mcp:phase1  # Start the Phase 1 DeepSQL MCP server (stdio)
npm run build     # Build (dev)
npm run build:production  # Build (prod)
```

**Dev credentials**: There is no baked-in admin/admin login — `AuthController.login` requires a
real `User` row matched by **email**, not username, so a fresh database (new Postgres volume)
has no account to log in with at all. `SECURITY_AUTH_ENABLED=false` only bypasses JWT/MCP token
*validation* (`JwtAuthenticationFilter`, `McpTokenAuthenticationFilter`); it does not create a
user or skip the login form. Create the first admin via the bootstrap endpoint, gated by
`SECURITY_ADMIN_BOOTSTRAP_ENABLED=true` + `ADMIN_BOOTSTRAP_SECRET`, and only callable from
localhost:

```bash
curl -X POST http://localhost:8080/api/users/admin/bootstrap \
  -H "Content-Type: application/json" \
  -H "X-Admin-Bootstrap-Secret: $ADMIN_BOOTSTRAP_SECRET" \
  -d '{"email":"admin@localhost","password":"<your-password>"}'
```

Then log in with that **email** (not `admin`) and password. `POST /users/admin/reset` (same
header) replaces the existing admin if you need to rotate the password.

### Database

```bash
docker compose up -d postgres   # Start vault DB
docker compose down             # Stop
```

**Vault DB**: `jdbc:postgresql://localhost:5432/dba_agent` (postgres/postgres)

### Self-host Compose (5 services)

```bash
./scripts/self-host/install.sh   # builds + starts everything
docker compose ps                # postgres, valkey, backend, deepsql-agent, frontend
```

The **DeepSQL Agent** is the fifth container (`agent/Dockerfile`): Agent tab, AI
dashboards, Slack/CLI agent turns, and per-user profile provisioning on :8787/:8788.
No host-side agent install is required for Compose deployments.

## Architecture

```
backend/
  src/main/java/com/dbaagent/
    controller/     # REST endpoints
    service/        # Business logic
    service/brain/  # ML-based DB intelligence (workload, config, query optimization)
    model/          # JPA entities
    repository/     # Spring Data repositories
    provider/       # Database dialect registry (PostgreSQL, MySQL)
    config/         # Spring configuration
    security/       # JWT auth, RBAC
    llm/            # LLM provider registry, config resolver, OpenAI-compatible provider
    util/           # Shared utilities
  src/test/         # JUnit 5 tests
  src/main/resources/
    db/migration/   # Hand-maintained SQL changelog (V5-V109) — NOT executed.
                    # There is no Flyway: pom.xml has no flyway-core and
                    # `mvn dependency:list` finds no org.flywaydb artifact.
                    # Schema is managed by spring.jpa.hibernate.ddl-auto=update.
                    # The directory even carries duplicate versions (V31, V63,
                    # V103) that a real Flyway runtime would refuse to start on.
                    # Apply anything here by hand with psql.

src/                # Frontend (React)
  components/       # UI components
    tabs/           # 40+ specialized tabs
    sections/       # Top-level sidebar destinations (Agent, Dashboards, Brain,
                    # Performance = Slow Queries + Workload, Editor, Docs)
  lib/
    api/client.js   # Centralized API layer (axios, 25+ modules)
    stores/         # Zustand stores (dashboard, connection, chat, UI)
    hooks/queries/  # TanStack Query v5 hooks
  pages/            # Page components

docs/               # Documentation
mcp/                # DeepSQL Phase 1 MCP server (Node stdio wrapper around backend APIs)
agent/              # DeepSQL Agent (persona, skills, skins, Dockerfile for the Compose service)
```

## MCP Server

- `mcp/deepsql-phase1-server.js` implements a Phase 1 stdio MCP server for internal rollout.
- It exposes read-only tools only: listing connections, fetching schema/objects, asking DeepSQL questions, executing read-only SQL, and running EXPLAIN without ANALYZE.
- It wraps existing backend APIs, so it reuses DeepSQL chat orchestration, RAG, connection management, and guardrails instead of exposing raw DB credentials.
- Read-only enforcement is applied in `mcp/deepsql-phase1-lib.js` before calling backend execution endpoints.
- Client config examples live in `.cursor/mcp.json` and `mcp/claude_desktop_config.example.json`.
- Usage and env vars are documented in `docs/root/MCP_PHASE1.md`.

## Dashboard Generation (artifact model)

Dashboards are **generated by the embedded DeepSQL Agent acting as a coding agent**
(customized Hermes runtime — see [`agent/README.md`](agent/README.md)) — it writes the whole dashboard as a single self-contained HTML document, not a JSON spec. The earlier spec+renderer model (metrics/charts/tables + a `{{placeholder}}` substitution engine + `DashboardBuilder.js`) was thrown away: the rigid `col BETWEEN {{name}}` convention couldn't express real SQL (e.g. a Unix-epoch date filter → `near '{range.start}'` syntax errors) and boxed the agent in.

- `DashboardAgentService` is a thin broker: `ensureProfileForUser` → `ensureSession` (fresh session) → `sendAndAwait` with an **artifact contract**. The agent grounds on the brain/schema, verifies every query with `execute_sql`, then emits ONE HTML doc (in a ` ```html ` block). The broker extracts the HTML and returns `{version:3, renderMode:"artifact", title, html, trace}`, stored verbatim in `saved_dashboards.dashboardConfig`.
- The agent loads the **`dashboard-design` skill** (`agent/skills/dashboard-design/SKILL.md`, v2 — artifact contract, the `deepsql.query` runtime, composition/UX rules, an **intent checklist**, and Unix-epoch date handling).
- **Rendering + data access**: `DashboardArtifact.jsx` renders the HTML in a **sandboxed iframe** (`sandbox="allow-scripts"`, opaque origin + a strict CSP — no external network). The artifact fetches data only through an injected `deepsql.query(sql)` bridge that `postMessage`s to the parent; the parent calls **`POST /api/dashboards/query`** (`DashboardQueryController`), which is **read-only twice over** (`McpSqlGuardService.validateReadOnlySql` + `QueryExecutionContext.api` = `READ_ONLY_ONLY`) and access-scoped via `assertCanReadConnectionContent`. So the agent's code has full creative freedom while every query stays guarded and sandboxed. The bridge also auto-sizes the iframe and forwards runtime errors.
- Generation endpoints unchanged (`POST /api/dashboards/generate` + `/generate/stream`). `DashboardBuilder.js`/`DashboardInputs.js` remain only because `tabs/Core/PreviewTab.js` still uses them — the dashboard *creation* path no longer touches them.
- **Sharing**: both share types render a standalone read-only `DashboardViewer` (title + `DashboardArtifact` with an injected `queryFn`). Internal link `/dashboard-view/:id` (auth) uses the authed broker; public link `/share/dashboard/:token` (permitAll) uses `PublicDashboardController` (`GET /api/public/dashboards/{token}` + `/query`), which resolves only while `saved_dashboards.is_public` is true (revoke = flip it) and runs read-only + connection-scoped. `share_token`/`is_public` are set only via `POST|DELETE /api/saved-dashboards/{id}/share` (access-checked), never a general update. `ShareMenu.jsx` drives the UI. The public query path has its own nginx `dashq` limiter.

## LLM Providers

`com.dbaagent.llm` holds the provider abstraction. `LlmProviderRegistry` auto-discovers
`LlmChatProvider` / `LlmEmbeddingProvider` beans, exactly like `DatabaseProviderRegistry`,
indexing chat and embedding in **separate** maps (Anthropic publishes no embeddings API;
one shared index would force the if/else-on-provider-type this file forbids). Duplicate
ids or aliases fail fast at startup. Startup logs
`Registered 1 LLM chat providers [openai] and 1 embedding providers [openai]`.

`OpenAiCompatibleChatProvider` / `OpenAiCompatibleEmbeddingProvider` are the only shipped
implementations, both under the id `openai`. One provider covers OpenAI, Azure OpenAI, and
self-hosted vLLM/Ollama/LM Studio/TGI — it dispatches on the **endpoint shape**, not on a
provider id: an `.azure.com` / `.azure-api.net` base URL switches to Azure's `api-key`
header, everything else uses `Authorization: Bearer`.

**Configuration resolution** — `LlmConfigResolver`, two tiers, no property-default tier
(a credential default in a properties file is how the production Azure key reached git
history):

1. **Database** — `llm.<role>.provider`, then `llm.<role>.<providerId>.<field>` in
   `system_config`.
2. **Environment** — `DEEPSQL_{CHAT,EMBEDDING}_{PROVIDER,API_KEY,ENDPOINT,MODEL,…}`.
   `PROVIDER` gates the whole bundle: unset, nothing else is read.

`SetupController`'s `/setup/llm-config` writes the namespaced keys above for **both**
roles from the single credential the wizard collects, and `/setup/status` derives
`hasLlmConfig` from `resolveChat() != null` rather than from a config key. It previously
wrote a flat pre-BYO namespace (`llm.provider`, `llm.openai.api-key`, `llm.chat-model`,
`llm.embedding-model`) that intersected nothing the resolver reads, so the wizard stored
keys that did nothing and every env-configured install reported itself unconfigured.
`V109__drop_legacy_llm_config.sql` removes those orphaned rows (apply by hand — this repo
has no Flyway runtime).

**Embedding hazard:** embeddings must resolve through a single provider. Spring AI will
auto-configure its own `EmbeddingModel` from `spring.ai.openai.*` if one is not supplied,
giving `VectorStore` and `QuestionAnswerAdvisor` a second, independent embedding source.
`LlmConfig.embeddingModel` registers `ProviderBackedEmbeddingModel` as `@Primary` so it
wins by type — but `@Primary` alone is not enough, because `OpenAiEmbeddingAutoConfiguration`
builds eagerly and asserts `spring.ai.openai.api-key` is non-empty, so it is *also* excluded
outright in `DbaAgentApplication`. Do not reintroduce either half. A store written by one
embedding model and read through another raises no error — retrieval just degrades silently
(pgvector's `text`-column fallback has no dimension constraint and cosine similarity still
returns a number).

## Key Rules & Patterns

### Backend Rules
1. **Database Provider Registry**: Use `DatabaseProviderRegistry` for all DB-specific operations. Do NOT add if/else or switch for database types.
2. **LLM Provider Registry**: Use `LlmProviderRegistry` for all provider-specific LLM behavior. Do NOT add if/else or switch on provider type. Chat and embedding providers are registered and resolved independently — some providers offer only one. Providers are *factories* over credentials, not `ChatModel`s, so credentials stay resolvable per call and key rotation needs no restart.
3. **SSH-Aware Access**: Always use `ConnectionService.getJdbcTemplate(connectionId, request)` — handles SSH tunneling transparently.
4. **SQL Rule**: All generated SQL MUST use table-qualified column names (`table.column_name`).
5. **RAG Caching**: Three-tier cache (memory → Redis → Azure Search). Redis failure is graceful (app continues without caching).
6. **Virtual Threads**: Enabled for concurrency (JDK 25).

### Frontend Rules
1. **API Centralization**: ALL API calls through `src/lib/api/client.js`. Never create direct axios instances.
2. **Server State**: Use TanStack Query hooks from `src/lib/hooks/queries/` (not useState/useEffect for data fetching).
3. **UI State**: Use Zustand stores from `src/lib/stores/`. Prefer selector hooks for optimized re-renders.
4. **Tooltips**: Always use `HelpTooltip` component, never plain `title` attributes.
5. **Design**: Minimal black/white/grey palette, Inter font, subtle transitions. See UX guidelines in full CLAUDE.md.

### Git Rules
- Do NOT commit automatically — wait for explicit user instruction.
- Conventional commits: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`, `perf:`, `ci:`

### Agent Runtime Rules (learned the hard way, 2026-08-07)

1. **Pin the Python MCP SDK below 2.0.** `scripts/self-host/setup-agent.sh` installs
   it as `mcp>=1.0,<2`. SDK **2.0.0 renamed `CallToolResult.isError` to `is_error`**
   and split the models into a separate `mcp-types` package, while `hermes-agent`
   0.20.0 still reads `result.isError` (`tools/mcp_tool.py:5222`). With the old
   unbounded `mcp>=1.0`, every DeepSQL tool call raised
   `AttributeError: 'CallToolResult' object has no attribute 'isError'` — a time bomb
   that detonated the day 2.0.0 shipped, with no code change on our side. Raise the
   ceiling only once hermes reads `is_error`.
2. **Three tool failures trip hermes's circuit breaker**, after which the rest are
   refused as `MCP server 'deepsql' is unreachable` — blaming a healthy server for a
   client-side parse error. Do not trust that message; find the *first* failure in
   `~/.hermes/logs/errors.log`.
3. **A running webui holds its SDK in memory.** After changing the SDK it must be
   restarted; `setup-agent.sh` now does that itself. It previously printed
   `✓ Hermes webui already running` and left the broken SDK loaded, so re-running the
   repair script gave a full column of ticks and no change.
4. **The agent version was never the problem.** `agent/distribution.yaml` once pinned
   `hermes_requires <0.20.0` on a "verified" 401 that came from a hand-rolled
   `hermes serve` run rather than `hermes webui`. 0.20.0 works. Verify against the
   real start path before writing a version constraint.

### Verification Anti-Patterns (do not repeat)

These all reported success over broken systems — which is how the agent shipped
broken. Assert the *outcome*, never the attempt:

- **`e2e-agent-check.py`** passed on `any("execute_sql" in t for t in tools)` — a tool
  being *attempted*. It printed `✓ All agent UI paths OK` and exited 0 while the
  agent's own reply said "I'm blocked". It now requires the answer itself.
- **A dashboard that is "HTML and long"** proves nothing: with every tool failing, the
  agent emitted a plausible artifact full of invented numbers. A real one calls
  `deepsql.query()`; absence of that call means the data never came from the database.
- **Presence ≠ compatibility.** The SDK check tested only that `mcp` imports, so it
  printed `✓ Python MCP SDK available` on an SDK whose every call failed. It now
  asserts `CallToolResult` still carries `isError`.
- **Mocks hide SDK breaks.** `tests/tools/test_mcp_structured_content.py` uses a
  `_FakeCallToolResult` with a hardcoded `.isError`, so it kept passing precisely when
  the real SDK stopped matching. Pin the dependency; a fake cannot catch this.
- **Never claim a check you did not run.** `install.sh` reported "up to date" when it
  could not reach npm; it now says it could not check.
- **`set -e` + `read` at EOF aborts silently.** Prompts in `install.sh` use
  `read … || true` so the explicit emptiness checks report the problem. Without it the
  installer exited 1 with no message, after writing generated secrets to `.env`.
- **Silent-failure rule, concretely:** the CLI rendered an unreachable server as
  `No databases connected yet` because one `catch` covered both the connection fetch
  and decorative extras. An unreachable host must never look like an empty account.

### Data Model Rules

- **`mcp_tokens.user_id` is a non-null FK with no cascade.** Deleting a user who holds
  a token throws `ConstraintViolationException`. `UserController` clears the user's
  tokens first via `McpTokenRepository.deleteByUserId`, which carries its own
  `@Transactional` — a derived delete needs one, and annotating a self-invoked caller
  does nothing (Spring proxies are bypassed by `this::`). This broke
  `POST /users/admin/reset` on every install that had run `setup-agent.sh`, since that
  mints an admin MCP token on each run.

### MCP & CLI Release Rules

**Whenever you add, rename, or remove an MCP tool or a CLI subcommand, you MUST update all of these in the same commit — they are agent-facing surfaces and drift silently breaks discoverability:**

1. **MCP tool definition** — `mcp/deepsql-phase1-lib.js` (`TOOL_DEFINITIONS` + `handleToolCall` case + `buildToolResult` case + a `summarize*` function for the human-readable summary).
2. **CLI dispatcher** — `mcp/src/commands/<command>.js` (`SUBCOMMANDS` map + handler function).
3. **CLI help text** — `mcp/src/cli.js` (`COMMAND_HELP[<command>].subcommands` and `.options`). The drift guard in `mcp/src/cli.test.js` will fail the build if `SUBCOMMANDS` ≠ documented subcommands. If you add a new command file, extend `HELP_DRIFT_TARGETS` in that test.
4. **Agent skill body** — `mcp/skills/SKILL_BODY.md` (the MCP tools table and the CLI catalog table). This is what every agent with the DeepSQL skill loaded actually reads. Bump the tool count at the top if it changed.
5. **Package docs** — `mcp/CLAUDE.md` (full tool table) and `mcp/README.md` (the npm landing page tool table).
6. **npm version bump** — `mcp/package.json`: minor (`0.X.0`) for new tools/commands, patch (`0.X.Y`) for fixes. Then run `npm publish` (requires OTP from authenticator).

After every publish, sanity-check by reinstalling globally and running `deepsql <command> -h` on the changed command — the help output is the user's source of truth and must match what's dispatchable.

## Environment Variables (Required)

None of these carry a baked-in default in `application*.properties` anymore
(`SelfHostPropertiesSafetyTest` enforces this). The database, JWT, and encryption
values must be exported before `mvn spring-boot:run`, even for local dev against the
docker-compose Postgres — without them the backend will not start. The LLM and vector
store values are needed to *use* those features, not to boot; see the comments inline.

```bash
DB_URL=jdbc:postgresql://localhost:5432/dba_agent
DB_USERNAME=postgres
# Must match docker-compose.yml's postgres service (POSTGRES_PASSWORD/DB_PASSWORD),
# which itself defaults to "postgres" only inside the compose network — the bare
# `mvn spring-boot:run` path (no compose) always needs this exported explicitly.
DB_PASSWORD=postgres
SECURITY_JWT_SECRET=<secret>

# LLM — read by LlmConfigResolver. PROVIDER gates the rest: with it unset, no other
# DEEPSQL_CHAT_*/DEEPSQL_EMBEDDING_* value is read. `openai` is the only provider id
# shipped and covers OpenAI, Azure OpenAI, and any OpenAI-compatible server.
# Chat ENDPOINT has no working fallback — set it explicitly. Embedding MODEL and
# ENDPOINT do default (text-embedding-3-large, https://api.openai.com/v1).
# Nothing here is needed to *boot*; the backend starts unconfigured and throws
# LlmNotConfiguredException at call time.
DEEPSQL_CHAT_PROVIDER=openai
DEEPSQL_CHAT_API_KEY=<key>
DEEPSQL_CHAT_ENDPOINT=https://api.openai.com/v1
DEEPSQL_CHAT_MODEL=gpt-4o
DEEPSQL_EMBEDDING_PROVIDER=openai
DEEPSQL_EMBEDDING_API_KEY=<key>
DEEPSQL_EMBEDDING_MODEL=text-embedding-3-large
# Optional chat tuning: DEEPSQL_CHAT_TEMPERATURE, DEEPSQL_CHAT_API_VERSION,
# DEEPSQL_CHAT_USE_RESPONSES_API (true|false|auto).

# Only if using Azure AI Search instead of pgvector for the vector store.
azure.search.api-key=<key>
azure.search.endpoint=https://<resource>.search.windows.net
# /api/llm/v1 — the OpenAI-shaped gateway the DeepSQL CLI agent points at — needs no
# variables of its own. LlmProxyController resolves through LlmConfigResolver.resolveChat()
# and picks its auth header with OpenAiEndpoints.isAzure(endpoint), so the DEEPSQL_CHAT_*
# bundle above configures it too; unconfigured, it returns 503 naming those variables.
# AZURE_OPENAI_* is now read by no code at all.
# Encryption key(s) for the credential vault. EncryptionService requires ENCRYPTION_KEY
# or ENCRYPTION_KEYS to be set — with neither, the backend fails to start
# (IllegalStateException: "Missing encryption key; set ENCRYPTION_KEY or ENCRYPTION_KEYS").
# Single-key form (simplest for local dev/self-host):
ENCRYPTION_KEY=<32-byte-base64-key>          # generate with: openssl rand -base64 32
# Multi-key form (supports rotation; "id:key" pairs, comma-separated; the active one
# is selected by ENCRYPTION_KEY_ID). Used by docker-compose.yml's backend service:
ENCRYPTION_KEYS=<id-1>:<32-byte-base64-key-1>,<id-2>:<32-byte-base64-key-2>
ENCRYPTION_KEY_ID=<id-1>
```

## Testing

- **Backend**: 143 tests, ~31 min. `mvn test` from `backend/`.
- **Integration tests**: Require `TEST_CONNECTION_ID` in `application-test.properties`.
  Four more requirements are not optional, and each fails in a way that points somewhere
  else entirely — all four were diagnosed the hard way:
  1. **`ENCRYPTION_KEYS` must contain the key id the app used when it saved the
     connections**, not just the one `application-test.properties` pins
     (`ENCRYPTION_KEY_ID=local-2025-01`). The id is embedded in each ciphertext envelope,
     so a test JVM that knows only a different id cannot decrypt any stored credential.
     Symptom: dozens of `No encryption key configured for id: <id>`, surfacing to the
     caller as "DeepSQL can't access this database connection right now". Pass both:
     `ENCRYPTION_KEYS=local-2025-01:$KEY,<app-key-id>:$KEY`.
  2. **A reachable Redis/Valkey.** Redis failure is graceful for *caching*, but not on
     this path — with nothing at `localhost:6379` the connection-access lookup fails and
     reports itself as a database-connectivity problem. Set `SPRING_DATA_REDIS_HOST`.
  3. **LLM credentials** (`DEEPSQL_CHAT_*`). Chat integration tests make real model
     calls; without them the agent runtime fails and every chat assertion reports "the
     agent runtime hit an internal execution failure". Note this costs real tokens.
  4. **Stop the running backend first.** It and the test JVM open the same `dba_agent`
     database with `ddl-auto=update`. The test JVM's `ALTER TABLE` needs ACCESS EXCLUSIVE
     on a table the live app is inserting into, every later insert queues behind the
     pending ALTER, and the suite **hangs indefinitely with no error** — observed as a
     42-minute stall on `rag_documents`. Run `docker compose stop backend` first.
- **`application-test.properties` no longer bakes in credentials either** (same
  `SelfHostPropertiesSafetyTest` guard scans it). Any test that boots the full Spring
  context under `@ActiveProfiles("test")` (e.g. `ApiSmokeTest`) requires
  `TEST_DB_PASSWORD`, `ENCRYPTION_KEYS`, and `AZURE_SEARCH_API_KEY` to be exported.
  `ENCRYPTION_KEYS` must use the id `local-2025-01` — `application-test.properties`
  pins `ENCRYPTION_KEY_ID` to it. **No LLM credential is needed to boot the context**:
  the eager Azure OpenAI client bean is gone (`AzureOpenAIConfig`, `OpenAISdkConfig`
  and `ResponsesApiConfig` were all deleted), and providers now resolve credentials
  per call. Verified green with `AZURE_OPENAI_KEY` unset:

  ```bash
  cd backend && TEST_DB_PASSWORD=postgres \
    ENCRYPTION_KEYS=local-2025-01:$(openssl rand -base64 32) \
    AZURE_SEARCH_API_KEY=dummy-test-key \
    mvn test -Dtest=ApiSmokeTest
  ```
- **Frontend**: `npm run lint` for static analysis.
- **Quick local deploy regression**: `npm run test:local-regression` runs the frontend build, service health probes, and the backend `ApiSmokeTest`. Enable frontend lint explicitly with `LOCAL_REGRESSION_RUN_FRONTEND_LINT=1`.

## Documentation Updates

After completing any significant task, update this file and/or `docs/root/CLAUDE.md` to reflect changes (new services, APIs, behaviors, config, bug fixes, anti-patterns).
