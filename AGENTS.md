# AGENTS.md

> Persistent, high-level understanding of this codebase for all AI agents (Codex, Claude, Cursor, Antigravity).
> For comprehensive details, see [`docs/root/CLAUDE.md`](docs/root/CLAUDE.md).
> For development commands and key rules, see [`CLAUDE.md`](CLAUDE.md).

## Project Overview

DBA Agent — AI-powered Database Performance Assistant with autonomous troubleshooting.

- **Monorepo**: Java backend (`backend/`) + React frontend (`src/`)
- **Backend**: Spring Boot 4.0.3 (Java 25), Spring AI 2.0.0-M2, PostgreSQL vault DB
- **Frontend**: React 19.2.3, Vite 7.3.0, Tailwind CSS 4.1.18, Zustand, TanStack Query v5
- **AI**: Azure OpenAI (gpt-5.4-pro, text-embedding-3-large), Azure AI Search (RAG)
- **Databases supported**: PostgreSQL, MySQL (extensible via provider registry)

## Commands

```bash
# Backend
cd backend && mvn spring-boot:run          # Start backend (localhost:8080)
cd backend && mvn test                      # Run tests (143 tests, ~31 min)

# Frontend
npm install && npm run dev                  # Start frontend (localhost:3000)
npm run lint                                # Lint

# Database
docker compose up -d postgres               # Start vault DB (PostgreSQL)
```

## Backend Architecture (Java)

Entry point: `backend/src/main/java/com/dbaagent/DbaAgentApplication.java`

### Package Map

| Package | Purpose |
|---------|---------|
| `controller/` | REST endpoints (25+ controllers) |
| `service/` | Core business logic |
| `service/brain/` | ML-based DB intelligence (workload, config, query, classification) |
| `model/` | JPA entities and domain models |
| `repository/` | Spring Data JPA repositories |
| `provider/` | Database dialect registry — PostgreSQL and MySQL providers |
| `config/` | Spring configuration beans |
| `security/` | JWT auth, RBAC (VIEWER/EDITOR/ADMIN) |
| `util/` | Shared utilities (SQL parsing, normalization) |

### Key Domains

- **Chat/RAG**: `SpringAIChatService` → ChatClient with advisor chain (memory, feedback, schema, performance, RAG)
- **Connections**: `ConnectionService` manages HikariCP pools per database, SSH tunneling via `SshTunnelService`
- **Slow Query**: Ingestion (`SlowLogIngestionService`), parsing (`SlowQueryLogParserService`), fingerprinting (`QueryFingerprintService`), optimization (`QueryOptimizationService`)
- **Brain Intelligence**: Workload characterization, knob identification, config tuning, cardinality estimation, plan pattern memoization
- **Schema Analysis**: 8 classification services (access patterns, anti-patterns, health scores, business domains, data sensitivity, partition readiness, relationships, temporal)
- **Performance Actions**: ROI-ranked recommendations from multiple sources (index advisor, slow query analysis, brain config, anti-patterns, key columns)
- **Playbooks**: JSON-based automation with scheduled execution and multi-channel alerts
- **Monitoring**: Growth anomaly detection, performance insights, schema change tracking

### Database Provider Registry (Critical Pattern)

All database-specific operations go through `DatabaseProviderRegistry`:
```java
DatabaseDialect dialect = registry.getDialect(dbType);
dialect.connection().buildJdbcUrl(request, port);
dialect.introspection().getColumnDetails(conn, db, table);
dialect.slowQueries().collectSlowQueries(conn, threshold, limit);
```
**Anti-pattern**: Do NOT add if/else or switch for database types in services.

### Data Flow (Chat)

```
User Message → ChatController → SpringAIChatService
  → Schema scan + RAG retrieval + Feedback context + Performance insights
  → Azure OpenAI (gpt-5.4-pro) → Extract SQL → QueryExecutorService → Results
```

### Chat Guardrail

- Chat-path logic must remain schema-agnostic.
- Do not hardcode customer-specific table names, column names, SQL fragments, or prompt-to-table shortcuts in classifier, planner, resolver, composer, or execution paths.
- If behavior needs improvement, fix ranking, semantic context, or guardrails generically so it works across connections.

### Data Flow (Slow Query Logs)

1. Fetch from provider (S3/CloudWatch/Azure/GCP/Datadog/Elasticsearch)
2. Stream into temp file, cap at 500MB
3. Parse with `SlowQueryLogParserService`
4. Persist in `SlowQueryHistory` → auto-fingerprint → auto-alert

## Frontend Architecture (React)

### Structure

| Path | Purpose |
|------|---------|
| `src/lib/api/client.js` | Centralized axios client (25+ API modules) |
| `src/lib/stores/` | Zustand stores (dashboard, connection, chat, UI) |
| `src/lib/hooks/queries/` | TanStack Query v5 hooks (server state) |
| `src/components/tabs/` | 40+ specialized tab components |
| `src/pages/Home.jsx` | Main layout with resizable panels |
| `src/components/PromptPanel.js` | Chat sidebar with connection management |

### Key Patterns

- **API calls**: Always through `src/lib/api/client.js` — never direct axios
- **Server state**: TanStack Query hooks (`useConnections`, `useBrain`, `useSlowQueries`, etc.)
- **UI state**: Zustand stores with selector hooks (`useActiveTab`, `useDashboardActions`)
- **Independent chat threads**: Per-tab, per-connection, stored in localStorage

## Desktop Client (Electron)

`desktop/` is a standalone Electron app (its own `package.json`, not part of the
root npm project). It is a **thin client**: it never bundles the React frontend,
it navigates a `WebContentsView` at the real DeepSQL origin, so the UI is always
the version the server runs. Two transports resolve to that origin — direct TLS,
or an in-process SSH local forward (`ssh2`, no `ssh` binary needed).

| Path | Purpose |
|------|---------|
| `desktop/src/main/transport.js` | Transport manager: connect/disconnect/health per profile |
| `desktop/src/main/tunnel.js` | SSH local forward, host-key TOFU-then-strict, auto-reconnect |
| `desktop/src/main/tls.js` | Cert policy (system / pinned / custom CA / TOFU) for Node **and** Chromium |
| `desktop/src/main/profiles.js` | Connection profiles; secrets only as `safeStorage` ciphertext |
| `desktop/src/main/windows/workspace.js` | Frameless shell: native chrome + embedded DeepSQL view |
| `desktop/src/renderer/shared/theme.css` | Mirrors `src/index.css` tokens — keep in step |

No backend change was needed: `docker/nginx/default.conf` already serves the SPA,
`/api` and `/agent-api` from one origin, which is what makes the thin-client
model work without CORS or cookie special-casing.

## Performance & Safety Guardrails

- Log size cap (500MB) via stream wrappers
- History reads are paged + lookback-bounded (90 days default)
- Redis graceful degradation (app works without Redis)
- Query timeout + fetch size configured for schema/monitoring scans
- SSH tunneling handles VPC/firewall access transparently
- Advisory locks for distributed benchmark coordination

## Config Defaults

| Property | Value | Purpose |
|----------|-------|---------|
| `slow-query.log.max-bytes` | 524288000 (500MB) | Log ingestion cap |
| `slow-query.history.lookback-days` | 90 | History query window |
| `db.fetch-size` | 1000 | JDBC fetch size |
| `db.query-timeout-seconds` | 30 | Query timeout |
| `brain.v2.learning.enabled` | true | ML learning scheduler |

## Testing

- **Backend**: `mvn test` from `backend/` (143 tests, ~31 min, needs local PostgreSQL)
- **Integration**: Requires `TEST_CONNECTION_ID` in `application-test.properties`
- **Frontend**: `npm run lint`

## Key Files

| Area | File |
|------|------|
| Backend entry | `backend/src/main/java/com/dbaagent/DbaAgentApplication.java` |
| Chat service | `backend/.../service/SpringAIChatService.java` |
| Connection pool | `backend/.../service/ConnectionService.java` |
| Provider registry | `backend/.../provider/DatabaseProviderRegistry.java` |
| Slow query ingestion | `backend/.../service/SlowLogIngestionService.java` |
| Query optimization | `backend/.../service/QueryOptimizationService.java` |
| Brain orchestrator | `backend/.../service/brain/BrainLearningScheduler.java` |
| API client | `src/lib/api/client.js` |
| Zustand stores | `src/lib/stores/` |
| TanStack hooks | `src/lib/hooks/queries/` |
| SQL changelog (**not** executed — no Flyway; schema is `ddl-auto=update`) | `backend/src/main/resources/db/migration/` (V5–V109) |
| LLM providers | `backend/src/main/java/com/dbaagent/llm/` (`LlmProviderRegistry`, `LlmConfigResolver`) |

## Common Risks / Hotspots

- Large logs (100–500MB) → must stream and cap, never concatenate in memory
- Unbounded history reads → always page + lookback
- Database provider anti-pattern → never use if/else for database types
- Connection pool pollution → always reset session state after benchmark operations
- Frontend state → use Zustand selector hooks to avoid infinite re-render loops

## Cursor Cloud specific instructions

The dev environment runs the stack **natively (no Docker)**: Java 25 + Maven wrapper backend,
Vite frontend, and locally-installed PostgreSQL 16 + Redis. System dependencies (JDK 25,
Postgres, pgvector, Redis) are baked into the VM snapshot; the startup update script only
refreshes `npm install`. Standard commands live in [`CLAUDE.md`](CLAUDE.md) — this section
only covers cloud-specific, non-obvious caveats.

### Services (start these each session — systemd is NOT running in the VM)

- **PostgreSQL 16** (vault DB, port 5432): start with `sudo pg_ctlcluster 16 main start`.
  DB `dba_agent` (user/pass `postgres`/`postgres`), extensions `vector` + `pg_stat_statements`
  enabled. `shared_preload_libraries=pg_stat_statements` is already set in the cluster config.
- **Redis** (cache, port 6379): start with `sudo redis-server /etc/redis/redis.conf --daemonize yes`.
  Redis degrades gracefully but the local `.env` points at it.
- **Backend** (port 8080, base path `/api`): `bash scripts/start-backend.sh` (wraps
  `./mvnw spring-boot:run`; it strips `SPRING_PROFILES_ACTIVE=prod` for local runs → dev mode).
- **Frontend** (port 3000): `npm run dev` (Vite proxies `/api` → 8080 and `/agent-api` → 8787).
- **Hermes Agent webui** (port 8787, optional): needed only for the sidebar **Agent** tab.
  See caveats below for install + `HERMES_WEBUI_ALLOWED_ORIGINS`.
- A demo target DB `demo_shop` (same Postgres server, sample `customers`/`products`/`orders`)
  exists for exercising connection/schema features without an external database.

### Non-obvious setup caveats (each cost real debugging time)

- **Java 25 is mandatory** (`pom.xml` sets `java.version=25`); the VM's default `java` is set
  to Temurin 25 via `update-alternatives`, and `JAVA_HOME` is exported in `~/.bashrc`.
- **`.env` is loaded by `source` in `scripts/start-backend.sh`, which runs under `set -e`.**
  Dotted keys like `spring.data.redis.host=...` make bash abort the whole script with
  "command not found". Use Spring relaxed-binding UPPERCASE env names instead
  (e.g. `SPRING_DATA_REDIS_HOST`). This is why the local `.env` avoids dotted keys.
- **`ENCRYPTION_KEYS` must be set, not just `ENCRYPTION_KEY`.** `application.properties`
  hardcodes `ENCRYPTION_KEYS=${ENCRYPTION_KEYS:}`; with the OS env var unset this is a
  circular placeholder reference that fails `EncryptionService` bean creation at boot. The
  local `.env` sets `ENCRYPTION_KEYS=<id>:<base64key>` matching `ENCRYPTION_KEY_ID`.
- **`SECURITY_AUTH_ENABLED=false`** (set in `.env`) enables the dev auto-admin bypass, so the
  web UI needs no login. Auth defaults to ON in every profile otherwise (there is no
  `admin/admin`); a real login needs the localhost admin-bootstrap flow (see README).
- **The `scheduled_tasks` table and the `vector`/`pg_stat_statements` extensions** come from
  `docker/postgres/init/*.sql`. In the native (non-Docker) setup those were applied by hand;
  they persist in the snapshot. If you ever recreate the vault DB, re-apply
  `docker/postgres/init/*.sql` or db-scheduler logs `relation "scheduled_tasks" does not exist`.
- **LLM (Azure OpenAI) is configured in the local gitignored `.env`** (not committed). Working
  values for this environment: `DEEPSQL_CHAT_PROVIDER=openai`,
  `DEEPSQL_CHAT_ENDPOINT=https://deepsql-selfhost-resource.cognitiveservices.azure.com/`,
  `DEEPSQL_CHAT_MODEL=gpt-5.4` (deployment name), plus matching `DEEPSQL_EMBEDDING_*` with
  `text-embedding-3-large`. Also set `AZURE_OPENAI_KEY` / `AZURE_OPENAI_ENDPOINT` aliases —
  `hermes/install.sh` reads those. After changing LLM env, restart the backend
  (`scripts/start-backend.sh`); `/api/setup/status` should show `hasLlmConfig: true`.
- **Agent tab (Hermes) is optional but required for the in-app Agent chat UI.** Install via
  `curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash -s -- --non-interactive --skip-setup`,
  symlink `~/.hermes/hermes-agent/.venv` → `venv` (DeepSQL's `hermes/install.sh` expects `.venv`),
  then `bash hermes/install.sh`. Start the webui with
  `HERMES_WEBUI_ALLOWED_ORIGINS=http://localhost:3000,http://127.0.0.1:3000`
  (without this, Vite's Origin header makes Hermes return **403** "Cross-origin mismatch").
  Webui listens on `:8787`; Vite proxies `/agent-api` → there.
- **Before running backend tests that boot the Spring context** (e.g. `ApiSmokeTest`), stop
  the running backend first — both use `ddl-auto=update` on the same `dba_agent` DB and can
  deadlock on an `ALTER TABLE`. Test env vars are documented in `CLAUDE.md` (Testing).
- `npm run lint` currently reports many pre-existing warnings/errors in the repo; that is the
  baseline, not a setup failure.
