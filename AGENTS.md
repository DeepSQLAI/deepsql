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
- **Frontend** (port 3000): prefer `npx vite --host 0.0.0.0 --port 3000` (or `npm run dev`
  with `server.host` set). Plain `npm run dev` can bind **IPv6-only** (`::1:3000`) in this
  VM so `curl http://127.0.0.1:3000` fails even though Vite looks healthy. Vite proxies
  `/api` → 8080 and `/agent-api` → 8787.
- **DeepSQL Agent API** (port 8787, optional): needed for the sidebar **Agent** tab.
  Runtime is a customized Nous Hermes Agent; see caveats below for install +
  `HERMES_WEBUI_ALLOWED_ORIGINS` (upstream env name).
- A demo target DB `demo_shop` (same Postgres server, sample `customers`/`products`/`orders`)
  exists for exercising connection/schema features without an external database.
- A multi-schema fixture DB `acme_erp` (schemas: `crm`, `sales`, `finance`, `inventory`,
  `hr`, `marts`) exists for chat-access-policy and multi-schema tests. Seed with:
  `sudo -u postgres psql -f docker/postgres/init/11_create_acme_erp.sql` then
  `bash scripts/seed-acme-erp.sh` (registers `ACME ERP (Multi-Schema)` when backend auth
  is disabled or you have an admin session cookie).
- **Company Knowledge → Review queue** (code-scan suggestions): seed without an LLM via
  `python3 scripts/self-host/seed-review-suggestions.py --count 50`, then exercise approve/
  reject/bulk edge cases with `python3 scripts/self-host/e2e-review-approvals.py`.
  Approving `SCHEMA_DOC` rows needs `CODE_DERIVED` on `schema_documentation_source_check`
  (startup initializer repairs this; Hibernate `ddl-auto` does not).

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
- **`SECURITY_AUTH_ENABLED`** defaults to ON. This Cloud VM’s `.env` sets it
  `true` and uses a real admin user (`admin@localhost` — create via localhost
  bootstrap if missing; see `CLAUDE.md`). `SECURITY_AUTH_ENABLED=false` only
  bypasses JWT/MCP token *validation*; it does not skip the login form or mint
  an admin. Dev credentials are never `admin/admin`.
- **The `scheduled_tasks` table and the `vector`/`pg_stat_statements` extensions** come from
  `docker/postgres/init/*.sql`. In the native (non-Docker) setup those were applied by hand;
  they persist in the snapshot. If you ever recreate the vault DB, re-apply
  `docker/postgres/init/*.sql` or db-scheduler logs `relation "scheduled_tasks" does not exist`.
- **LLM (Azure OpenAI) is configured in the local gitignored `.env`** (not committed). Working
  values for this environment: `DEEPSQL_CHAT_PROVIDER=openai`,
  `DEEPSQL_CHAT_ENDPOINT=https://deepsql-selfhost-resource.cognitiveservices.azure.com/`,
  `DEEPSQL_CHAT_MODEL=gpt-5.4` (deployment name), plus matching `DEEPSQL_EMBEDDING_*` with
  `text-embedding-3-large`. Also set `AZURE_OPENAI_KEY` / `AZURE_OPENAI_ENDPOINT` aliases —
  `agent/install.sh` reads those. After changing LLM env, restart the backend
  (`scripts/start-backend.sh`); `/api/setup/status` should show `hasLlmConfig: true`.
- **Agent tab is optional but required for the in-app Agent chat UI.** The Agent tab
  is DeepSQL’s own React (`AgentChatPanel` via `agentClient.js`); it talks to the
  DeepSQL Agent HTTP API on `:8787` (a heavily customized
  [Nous Hermes Agent](https://hermes-agent.nousresearch.com/) runtime — see
  [`agent/README.md`](agent/README.md)). Not a skin on the upstream webui.
  Flow: `POST /api/agent/session` (Spring provisions `u-<user>`) →
  `/agent-api/api/profile/switch` (sets upstream `hermes_profile` cookie) →
  `session/new` / `chat/start` / SSE `chat/stream`. Without the profile switch,
  the agent API 404s with "Session not found" (UI surfaces as a boot failure /
  early 500). Install upstream via
  `curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash -s -- --non-interactive --skip-setup`,
  symlink `~/.hermes/hermes-agent/.venv` → `venv` (DeepSQL’s `agent/install.sh` expects `.venv`),
  then `bash agent/install.sh`. Start the agent API/webui with
  `HERMES_WEBUI_ALLOWED_ORIGINS=http://localhost:3000,http://127.0.0.1:3000`
  (upstream env var; without it Vite’s Origin header yields **403** “Cross-origin mismatch”).
  Listens on `:8787`; Vite proxies `/agent-api` → there. Do not rename the
  `hermes_profile` cookie in DeepSQL clients — it is an upstream contract.
- **Local provisioner required for native (non-Compose) runs.**
  `AgentBridgeService` POSTs to `AGENT_PROVISIONER_URL` (default Compose:
  `http://deepsql-agent:8788/provision`) with `AGENT_PROVISION_SECRET`. In this VM run
  `python3 scripts/local-agent-provisioner.py` (needs those two env vars in `.env`).
  Without it, Spring logs `agent.provision-secret is unset — skipping…` and the
  `u-admin` agent profile is never created/token-refreshed. If provision returns HTTP
  500 with a PyYAML parse error on `config.yaml`, the profile is corrupt (often a
  mangled `agent.personalities` block that also drops `model:`) — the provisioner
  now restores from `~/.hermes/config.yaml`. Manual recovery: copy that default
  over `~/.hermes/profiles/u-<user>/config.yaml` and re-POST `/provision`. Symptom
  of a bad profile: Hermes logs `Missed model deployment` and CLI agent returns
  empty / “ended before producing an answer”.
- **After rotating MCP tokens, restart Hermes webui (and ensure the provisioner is
  current).** `scripts/local-agent-provisioner.py` writes `DEEPSQL_TOKEN_FILE` +
  `DEEPSQL_AUTH_TOKEN` into the profile; an old long-lived provisioner process will
  skip the token-file path. Even with a fresh profile config, Hermes webui can keep
  a stale MCP subprocess env (no auth token, `DEEPSQL_MCP_USER_ID=deepsql-agent`).
  Symptom: Agent tab tools return `Unauthorized - Please login` while
  `/api/agent/session` reports `mcpAuthOk: true`. Fix: restart
  `scripts/local-agent-provisioner.py`, re-open Agent (re-provision), restart the
  Hermes webui on `:8787`, and sync default `~/.hermes/config.yaml`
  `mcp_servers.deepsql.env` from the active `u-<user>` profile if the shared MCP
  is what webui spawns.
- **Multi-schema fixture.** This VM’s Postgres also has an ACME-style DB with
  non-`public` schemas (`crm`, `sales`, `finance`, `hr`, `inventory`) for Brain /
  MCP cross-schema checks. Prefer schema-qualified SQL (`sales.orders`); bare
  names follow the role’s `search_path` (usually `public`).
- **`AGENT_WEBUI_URL` / `AGENT_PROVISIONER_URL` for native runs.** Compose
  defaults (`http://deepsql-agent:8787` and `…:8788/provision`) do not resolve
  on the host. Native local must point both at loopback
  (`http://127.0.0.1:8787` and `http://127.0.0.1:8788/provision`) or the Agent
  tab returns 503 `Could not provision the DeepSQL Agent for this user`.
  `scripts/start-backend.sh` remaps those hostnames automatically when they
  don't resolve. If the agent container is used with a host-side Java backend,
  set `DEEPSQL_API_BASE_URL=http://host.docker.internal:8080/api/` so MCP
  tools can reach the native process (compose publishes `host.docker.internal`
  via `extra_hosts`).
- **DeepSQL CLI (`deepsql`) for agent testing.** Install from the repo package:
  `cd mcp && DEEPSQL_SKIP_AGENT_SETUP=1 npm install -g .` (prefix
  `~/.npm-global`, keep that on `PATH`). Auth against local backend with an MCP
  token (`POST /api/auth/mcp-tokens` when auth is disabled stores into
  `~/.config/deepsql/auth.json`). One-shot:
  `deepsql agent --connection <uuid> "…"`. Interactive: `deepsql` / `deepsql agent`.
  The CLI is a thin client over `POST /api/agent/chat` (not a local agent runtime);
  backend + agent API (:8787) + provisioner must already be up.
- **Spring CORS must allow both loopback hosts.** Set
  `CORS_ALLOWED_ORIGINS=http://localhost:3000,http://127.0.0.1:3000` in `.env`. Opening
  the UI as `http://127.0.0.1:3000` while only `localhost` is allowlisted yields **403**
  on `POST /api/agent/session` (and other cookie-auth APIs).
- **Before running backend tests that boot the Spring context** (e.g. `ApiSmokeTest`), stop
  the running backend first — both use `ddl-auto=update` on the same `dba_agent` DB and can
  deadlock on an `ALTER TABLE`. Test env vars are documented in `CLAUDE.md` (Testing).
- `npm run lint` currently reports many pre-existing warnings/errors in the repo; that is the
  baseline, not a setup failure.
- **`/opt/cursor/artifacts/` is ephemeral and agent-scoped.** It is wiped on new Cloud Agent
  VMs and is **not** shared with other agents. Durable OSS go-live handoff lives in
  [`docs/oss-ux/`](docs/oss-ux/) (usability critique, E2E fix proposal, security review).
- **Release manager.** This environment’s owning user (`venkateshsakamuri-lab`) is the
  release manager for OSS PRs. Prefer reviewing/merging against `docs/oss-ux/` criteria.
  Daily 9 AM triage setup: [`docs/oss-ux/DAILY_RELEASE_AUTOMATION.md`](docs/oss-ux/DAILY_RELEASE_AUTOMATION.md).
