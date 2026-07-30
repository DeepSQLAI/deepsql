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
