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
    security/       # JWT auth, RBAC, admin profile switch (`ImpersonationService`)
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
                    # Performance = Slow Queries + Workload, Editor)
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
- **Organization** (search/folders/favorites): `SavedDashboardController`'s search/folder/favorite endpoints existed for a while with no UI consumer. `DashboardsHome.jsx` now wires all of it — a search box (client-side filter over name/description), folder chips derived from `GET /connection/{id}/folders` with a per-card "move to folder" popover (`PUT /saved-dashboards/{id}` with `folder: ""` to clear — `updateDashboard` treats `null` as "field omitted" so blank is the explicit clear signal, same convention as `setSharePassword`), and a favorite star toggle (`POST /{id}/favorite`) with optimistic UI update.
- **Clone**: `POST /saved-dashboards/{id}/clone` (`SavedDashboardService.cloneDashboard`) duplicates a dashboard's config/chat/tags/folder into a fresh row — not shared, not favorited. Exposed as a copy icon on each `DashboardsHome.jsx` card.
- **Version history**: every real overwrite of `dashboardConfig` (agent build via `completeBuildTurn`, manual Source-tab edit via `updateDashboard`, or a restore) snapshots the *previous* config into `dashboard_versions` (`V113__create_dashboard_versions.sql`) before overwriting, tagged with a trigger (`AGENT_BUILD`/`MANUAL_EDIT`/`RESTORE`) — capped at 50 snapshots per dashboard, oldest pruned first. `GET /{id}/versions` lists them newest-first; `POST /{id}/versions/{versionId}/restore` swaps a snapshot back in as current (itself snapshotting whatever was live, so a restore is undoable too) and **dedupes**: after a restore, the restored row plus any other row with byte-identical `dashboard_config` are deleted, since that content is now "Current," not history — otherwise a restore-edit-restore cycle piles up an alternating chain of duplicate snapshots. `DashboardWorkspace.jsx`'s History panel shows a lightweight diff summary per entry (title/widget-count/size delta computed client-side, not a real line diff — the agent rewrites large chunks even for small logical changes) plus a Preview modal that renders that version's HTML live via `DashboardArtifact`.
- **Refresh**: `DashboardArtifact`'s `useImperativeHandle` exposes `reload()`, which bumps an internal `reloadEpoch` state used as the `<iframe>`'s `key` — forcing a genuine remount (and re-running every widget's `deepsql.query()` call) even when `html` is referentially unchanged, which changing `html`/`srcDoc` alone can't guarantee. `DashboardWorkspace.jsx`'s canvas toolbar has a manual Refresh button plus an auto-refresh interval dropdown (Off/30s/5m/1h) that calls it on a timer, paused while a build is in flight (a completing build already replaces the iframe). `DashboardViewer.jsx` (both share surfaces) takes the same `autoRefreshMs` optionally, plus `hideChrome` for kiosk mode.
- **TV/kiosk mode**: `PublicDashboardPage.jsx` reads `?kiosk=1&refresh=<seconds>` (chrome-less + auto-refresh, floor 10s) and `?tokens=tokA,tokB&advance=<seconds>` (cycles through multiple public share tokens, dwelling `advance` seconds each — the route's own `:token` is always the first slide). A password-protected dashboard mid-cycle is skipped (there's no one there to type a password) rather than parking the whole kiosk on a gate. `ShareMenu.jsx` surfaces a ready-made kiosk link (`?kiosk=1&refresh=60`) once a dashboard is public and unprotected.
- **Alerts**: `dashboard_alerts` (`V114__create_dashboard_alerts.sql`) holds a natural-language condition per dashboard (e.g. "alert if the error rate exceeds 5% in the last hour"), evaluated on a schedule by `DashboardAlertService.evaluate()` — a **bounded agent session** (fresh `ensureSession`, no tools beyond `execute_sql`/schema lookups, a short task prompt asking for exactly `YES`/`NO` + a one-sentence reason grounded in a real query result) reusing the same agent plumbing as dashboard generation, just for a one-line answer instead of a whole HTML document. `DashboardAlertTaskConfig` registers one db-scheduler recurring task (`dashboard-alert-tick`, every minute) that evaluates whichever alerts are actually due per `DashboardAlertRepository.findDue` (each alert has its own `checkIntervalMinutes`) rather than one scheduled task per alert. A fired alert dispatches through `EmailService.sendDashboardAlert`/`WebhookService.sendDashboardAlert` (new methods, same pattern as the existing growth/slow-query alert methods) gated by a per-alert `cooldownMinutes` so a condition that stays true doesn't re-fire every tick. The alert runs **as whoever created it** (`createdByUsername`, captured at creation time) — there's no ambient "system" identity for a background job, and running every alert as an arbitrary admin would let one user's alert read data through someone else's access grant. `DashboardAlertController` is the CRUD surface (`/saved-dashboards/{id}/alerts`); `DashboardWorkspace.jsx`'s toolbar has an Alerts panel (composer + per-alert enable/disable/delete, last-check verdict shown inline).

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
5. **Chat access policy**: Fail closed. Walk the whole SQL tree (CTEs, set ops, subqueries). Deny unparseable or unhandled statements. Require an actor except `INTERNAL`/`SCHEDULED`. MCP/Editor identity comes from `SecurityContext`, not `QueryActorContextHolder`. Persist `allowed_schemas`. Do not let "how many" override a protected-column mention. Public share is refused when the connection has an active policy.
6. **RAG Caching**: Three-tier cache (memory → Redis → Azure Search). Redis failure is graceful (app continues without caching).
7. **Virtual Threads**: Enabled for concurrency (JDK 25).

### Frontend Rules
1. **API Centralization**: ALL API calls through `src/lib/api/client.js`. Never create direct axios instances.
2. **Server State**: Use TanStack Query hooks from `src/lib/hooks/queries/` (not useState/useEffect for data fetching).
3. **UI State**: Use Zustand stores from `src/lib/stores/`. Prefer selector hooks for optimized re-renders.
4. **Tooltips**: Always use `HelpTooltip` component, never plain `title` attributes.
5. **Design**: Minimal black/white/grey palette, Inter font, subtle transitions. See UX guidelines in full CLAUDE.md.

### Admin profile switch
Admins can **View as** a sub-user from the top-right of the home layout (`ProfileSwitch`) to verify connection ACLs, chat/editor policies, and role-gated nav.

The admin JWT **subject** stays the administrator so logout, refresh, and `/admin/impersonate` still own the real session. Policy identity is the target: an httpOnly `impersonate_user` cookie plus an `impUid` claim on the access token. `JwtAuthenticationFilter` overlays that principal onto the SecurityContext for every request except the impersonation control plane, logout, and session refresh. Chat, Editor, schema listing, and Agent MCP calls then run `AccessControlService` / `ConnectionChatAccessPolicyService` as the target (`actorIsAdmin` is false, so policies apply).

The Agent tab must not inherit the admin MCP token. `/api/agent/session` mints an MCP token for the effective user and never falls back to the admin session JWT while View as is active. nginx `auth_request` on `/agent-api` forwards `/api/auth/me`'s `X-Remote-User` (the overlaid username) instead of hardcoding `admin`.

`POST|DELETE|GET /api/admin/impersonate` are excluded from the overlay so stop/list still run as the real admin. Cannot target another ADMIN, self, or a non-ACTIVE account. `/auth/me` returns the **effective** user plus `impersonating` / `impersonatorUsername`.

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
5. **The agent image build clones two third-party repos over the public internet,
   unauthenticated.** `agent/Dockerfile` fetches `NousResearch/hermes-agent` and
   `nesquena/hermes-webui` at build time. GitHub rate-limits unauthenticated
   requests *per source IP*, and Actions runners share pooled egress addresses, so
   `docker compose build` intermittently died on `fatal: unable to access ...: The
   requested URL returned error: 429` (exit 128) — 2 of 15 runs, always on branches
   whose diff had nothing to do with the agent. Both clones now retry 5x with
   backoff, and still print FATAL and exit 1 on exhaustion so a genuinely dead
   upstream cannot yield an image with no runtime in it. Two lessons worth keeping:
   a CI failure that is *intermittent and unrelated to the diff* is a network or
   rate-limit signature, not a code defect — read the log before bisecting the
   branch; and the webui clone's pre-existing `|| git clone` fallback looked like
   resilience but only ever handled a *moved ref*, re-issuing the identical refused
   request against a 429. A fallback that fails the same way as the thing it backs
   up is not a fallback.

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
- **SQL mutation guards must match statement verbs, not identifiers.**
  `McpSqlGuardService` / `mcp/deepsql-phase1-lib.js` used `\bCOMMENT\b` / `\bCALL\b`,
  so `SELECT * FROM comment` was rejected as "potentially mutating." Plenty of
  schemas have a `comment` table. Assert `SELECT * FROM comment` is allowed *and*
  that `WITH x AS (DELETE …) SELECT …` / `WITH x AS (…) DELETE …` still are not.

### SQL Editor Guard Rules

The Editor (`EditorSection` → `SqlRunnerTab` → `POST /connections/{id}/query` →
`QueryExecutionPolicyService` → `QueryExecutorService`) is the only surface where a
user submits arbitrary SQL. Everything below was a live bug, verified by executing
it against a real database — not a theoretical hardening pass.

- **Never classify SQL by its leading keyword alone.** `WITH x AS (DELETE FROM t
  RETURNING *) SELECT * FROM x` parses as a `Select` and *every* leading-keyword
  check calls it read-only — including `isReadOnlyQuery`, which reports anything
  starting with `WITH` as safe. PostgreSQL executes data-modifying CTEs for real,
  so a **non-admin** wiped whole tables through the Editor with no confirmation
  prompt, logged as an ordinary `EDITOR_QUERY_EXECUTED / SUCCESS`. `SELECT … INTO
  newtab` is the same class of bug (it is DDL). `classifyStatement` now inspects
  the parse tree (`detectSelectWrite`) **and** runs a text backstop
  (`detectHiddenWrite`) so an unparseable variant fails closed instead of falling
  through to the keyword path.
- **Read-only contexts open read-only JDBC sessions.** `QueryExecutorService` calls
  `connection.setReadOnly(true)` whenever `mutationMode() == READ_ONLY_ONLY`, so
  PostgreSQL refuses the write itself even if classification is wrong. Classification
  is a parser heuristic; this is what keeps the *next* parser gap from being data
  loss. A driver that rejects the hint raises rather than silently continuing
  writable. HikariCP resets the flag on return to the pool (verified), so it cannot
  leak into an admin's later write.
- **Row caps are enforced with `setMaxRows`, not by appending `LIMIT n`.** The old
  check skipped its own LIMIT whenever the regex `\blimit\s+\d+` matched anywhere —
  including inside a comment, a string literal, or a subquery. `WITH a AS (SELECT …
  LIMIT 100) SELECT * FROM a` is ordinary analyst SQL and returned **200k rows**
  against a 1,000 cap, straight into an unbounded `ArrayList` and then an
  unvirtualized table. The SQL `LIMIT` is still appended for simple SELECTs, but
  only as an optimization — correctness no longer depends on that text match.
- **Cancel must terminate the query, not just the HTTP request.** `abortController
  .abort()` only closes the socket; the statement runs on holding one of the pool's
  10 connections for up to its timeout. The client now sends an `executionId`,
  `RunningQueryRegistry` maps it to the backend session pid (via the dialect's
  `getSessionPidQuery()`), and `POST /connections/{id}/query/{executionId}/cancel`
  terminates exactly that session. The previous UI behavior was worse than nothing:
  it killed **every** active query on the connection, including other users' work.
  The cancel endpoint is scoped to the connection *and* the user who started the
  run, so an execution id is not a kill primitive for someone else's query.
- **Keep the client timeout under the proxy's.** `docker/nginx/default.conf` gives
  up at `proxy_read_timeout 300s`; the Editor used to ask for 600s, so a 6-minute
  query returned an opaque 504 while still running. `QUERY_TIMEOUT_SECONDS = 240`
  in `SqlRunnerTab.js` — change both together or not at all.
- **`/api/connections/*/query` is rate-limited in nginx** (`limit_req zone=sqlexec`,
  30r/m + burst 20, `429` on reject). It is the most expensive authenticated call
  in the product.
- **Test the policy against the real providers.** `QueryExecutionPolicyServiceTest`
  used to stub `isReadOnlyQuery` to always return `false` — the exact opposite of
  what the shipped providers do for `WITH`. It asserted behavior no deployment had,
  and `withInsert_isTreatedAsMutation` passed *because* of the stub. It now
  constructs a real `MySQLQueryExecutionProvider`. Do not reintroduce a stubbed
  dialect here; the mock is what let the blocker ship.

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
