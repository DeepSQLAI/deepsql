# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Documentation Rules

**IMPORTANT**: After completing any significant task, update this CLAUDE.md file to reflect the changes. This includes:
- New services, components, or APIs added
- Changed behaviors, logic, or algorithms
- New configuration options or environment variables
- Updated integration flows or data pipelines
- Bug fixes that change expected behavior
- New anti-patterns, rules, or validations added

Do NOT ask "should I update CLAUDE.md?" - just update it as part of task completion.

## Git Workflow Rules

**IMPORTANT**: Do NOT commit changes automatically. Wait for the user to explicitly tell you to commit. This allows the user to:
- Review all changes before committing
- Batch multiple related changes into a single commit
- Provide a custom commit message if needed

## Recent Changes

- 2026-08-17: `McpSqlGuardService` (and the matching MCP JS shim) no longer treats
  `COMMENT` / `CALL` / `REPLACE` as mutating when they appear as table, column, or
  function names. The guard matches statement verbs: mutating CTEs, `WITH … DELETE`,
  `FOR UPDATE`, and `EXPLAIN DELETE`. `SELECT * FROM comment` is allowed. Dashboards
  and `/api/mcp/query-readonly` share this guard.
- 2026-02-04: Moved all Markdown docs into `docs/` (root docs under `docs/root/`), added a root `README.md` stub, and updated doc links.
- 2026-03-12: Added a Phase 1 DeepSQL MCP stdio server in `mcp/` with read-only tools for connections, schema, chat, SQL execution, and EXPLAIN. See `docs/root/MCP_PHASE1.md`.
- 2026-03-30: Main chat execution was tightened to stay schema-agnostic. Do not add customer-specific table names, column names, SQL templates, or prompt-to-table shortcuts in chat classifier, planner, resolver, composer, or execution paths. Fix chat behavior through generic semantic ranking, context retrieval, and guardrails instead.
- 2026-04-14: Added a quick local regression flow via `scripts/run-local-regression-suite.sh` and `scripts/local-deploy.sh`. The runner pairs frontend build + health probes with the backend `ApiSmokeTest`. Frontend lint is opt-in via `LOCAL_REGRESSION_RUN_FRONTEND_LINT=1` because the repo currently has broader pre-existing lint debt.

## Project Overview

DBA Agent is an AI-powered Database Performance Assistant with autonomous troubleshooting capabilities. It combines RAG-powered SQL generation, real-time monitoring, and automated playbook execution to provide comprehensive database management.

**Tech Stack:**
- Backend: Spring Boot 4.0.3 (Java 25), Spring AI 2.0.0-M2, PostgreSQL vault DB, MySQL/PostgreSQL target DB support
- Frontend: React 19.2.3 with Vite 7.3.0, Tailwind CSS 4.1.18
- AI: bring-your-own LLM through `LlmProviderRegistry` — OpenAI, Azure OpenAI, or any OpenAI-compatible server (vLLM, Ollama, LM Studio, TGI). Vector store: pgvector or Azure AI Search for RAG
- Caching: Redis/Valkey (for RAG/application caching)
- Chat Memory: JDBC/PostgreSQL (Spring AI JdbcChatMemoryRepository)

## Cross-Session Memory & Feedback System

**Status**: Implemented (Spring AI 2.0.0-M2)

The system supports cross-session learning through two mechanisms:
1. **JDBC Chat Memory** - Conversation history persistence in PostgreSQL (via Spring AI)
2. **User Feedback Storage** - Corrections, teachings, and ratings stored in PostgreSQL

### JDBC Chat Memory (Spring AI)

**Components:**
- `spring-ai-starter-model-chat-memory-repository-jdbc` - Spring AI JDBC chat memory starter
- `SpringAIChatMemoryConfig` - Creates `MessageWindowChatMemory` bean (default 50 messages)
- `ChatMemoryService` - Service wrapper for conversation management
- `ChatMemoryController` - REST API for memory inspection/clearing

**Endpoints:**
- `GET /api/memory/status` - Check if JDBC memory is available
- `GET /api/memory/connection/{connId}/conversations` - List all chats for a connection
- `GET /api/memory/connection/{connId}/chat/{chatId}` - Get conversation history
- `DELETE /api/memory/connection/{connId}/chat/{chatId}` - Clear a conversation
- `DELETE /api/memory/connection/{connId}` - Clear all conversations for connection

**Storage:**
Chat memory is stored in Spring AI's JDBC tables (`spring_ai_chat_memory`) in the same PostgreSQL vault database. No additional infrastructure required.

**Graceful Degradation:** If JDBC repository is unavailable, `ChatMemoryService.isAvailable()` returns false and endpoints return empty responses with `available: false`.

### User Feedback System

**Components:**
- `FeedbackController` (`/api/feedback/*`) - REST API for feedback submission
- `FeedbackService` - Stores feedback in PostgreSQL, builds context for prompts
- `ChatFeedback` entity - Stores all feedback types with metadata

**Feedback Types:**
| Type | Purpose | Used in Prompts |
|------|---------|-----------------|
| `THUMBS_UP` | Positive rating | No (analytics only) |
| `THUMBS_DOWN` | Negative rating with optional comment | No (analytics only) |
| `CORRECTION` | "Use 'active' not 'enabled'" | Yes |
| `TEACHING` | "status values are: confirmed, pending" | Yes |
| `COLUMN_VALUES` | Auto-collected low-cardinality values | Yes |

**Endpoints:**
- `POST /api/feedback/thumbs-up` - Record positive feedback
- `POST /api/feedback/thumbs-down` - Record negative feedback with reason
- `POST /api/feedback/correction` - Record a correction for table/column
- `POST /api/feedback/teaching` - Record a teaching about domain knowledge
- `POST /api/feedback/column-values` - Record column value specifications
- `GET /api/feedback/connection/{connId}/stats` - Get feedback statistics
- `GET /api/feedback/connection/{connId}` - Get recent feedback

**Integration with ChatService:**
The `FeedbackService.buildFeedbackContext(connectionId)` method generates a prompt section with all learnable feedback (corrections, teachings, column values) which is injected into the system prompt before each chat request.

### Column Value Collection

**Components:**
- `ColumnValueCollectionService` - Collects distinct values for low-cardinality columns
- Triggered automatically after Key Column Analysis completes (async via virtual threads)
- Background scheduler refreshes values every 7 days (cron: `0 0 3 * * *`)
- Rate-limited to prevent CPU spikes on large databases (batch processing with delays)

**Azure AI Search Embedding:** Values are embedded into Azure AI Search with document type `COLUMN_VALUES` for semantic RAG retrieval. This helps the chat agent answer questions like "What are valid statuses for orders?" and use exact values in SQL WHERE clauses.

**Frontend UI:** View and manage cached column values in Brain → Overview → Column Values panel.

See section 16 "Column Value Embedding for Better SQL Filtering" for full implementation details.

## Development Commands

### Backend (Spring Boot)

```bash
cd backend

# Build
mvn clean install

# Run in development mode (auth disabled)
mvn spring-boot:run

# Run in production mode (auth enabled)
mvn spring-boot:run -Dspring-boot.run.profiles=prod

# Run all tests
mvn test

# Run integration tests only
mvn test -Dtest="*IntegrationTest"

# Run specific test class
mvn test -Dtest=BrainControllerIntegrationTest

# Run specific test method
mvn test -Dtest=ConnectionControllerIntegrationTest#testListConnections
```

**Backend runs on:** http://localhost:8080/api

### Frontend (React + Vite)

```bash
# Install dependencies
npm install

# Run development server
npm run dev

# Lint code
npm run lint

# Run the quick local deploy regression suite
npm run test:local-regression

# Start local services, then run the quick regression suite
npm run local-deploy

# Build for development
npm run build

# Build for production
npm run build:production

# Preview production build
npm run preview

# Start the Phase 1 MCP server
npm run mcp:phase1
```

**Frontend runs on:** http://localhost:3000

**Development credentials:** admin/admin (auth bypass enabled in dev mode)

### Database Setup

```bash
# Start vault database (PostgreSQL)
docker compose up -d postgres

# Stop database
docker compose down
```

**Vault DB defaults:**
- URL: jdbc:postgresql://localhost:5432/dba_agent?sslmode=disable
- User: postgres
- Password: postgres

## Architecture

### Backend Architecture

**Layered Design:**
```
Controller Layer → Service Layer → Repository Layer → Database
```

**Key Service Patterns:**

1. **Dynamic Connection Management**
   - `ConnectionService` manages HikariCP connection pools
   - Each database connection gets its own pool (ConcurrentHashMap)
   - Supports PostgreSQL and MySQL with database-specific configurations
   - Thread-safe connection retrieval with automatic pool creation
   - **SSH Tunnel Support**: Connections can optionally use SSH tunneling for accessing databases behind firewalls/VPCs (see SSH Tunneling section below)
   - **Provider Registry Integration**: `ConnectionService` delegates to `DatabaseProviderRegistry` for all database-specific operations (JDBC URL building, driver class, connection properties)

2. **RAG (Retrieval-Augmented Generation) System**
   - Three-tier implementation: Azure AI Search → Redis → In-memory
   - `TrainingService` orchestrates DDL generation → embedding → storage
   - `EmbeddingService` delegates to the embedding provider resolved from
     `LlmProviderRegistry` + `LlmConfigResolver`
     - `OpenAiCompatibleEmbeddingProvider` calls the OpenAI Java SDK
       (`com.openai:openai-java`) and caches one client per credential bundle
     - `OpenAISdkConfig` is **deleted**. There is no eagerly built client and no
       LLM credential is required to boot
     - Spring AI's `OpenAiEmbeddingAutoConfiguration` is excluded in `DbaAgentApplication`;
       `LlmConfig.embeddingModel` supplies a `@Primary` `ProviderBackedEmbeddingModel` so
       `VectorStore` and `QuestionAnswerAdvisor` embed through the same provider
   - `AzureSearchService` manages persistent vector storage (index: dba-agent-training-data)
   - `BrainInsightEmbeddingService` embeds Brain insights (workload, patterns, cardinality) into Azure Search
   - `ChatService.processMessage()` retrieves top-K relevant examples for context

3. **Playbook Execution Engine**
   - JSON-based step definitions with tools and parameters
   - `PlaybookExecutionService` orchestrates multi-step execution
   - `PlaybookToolsService` executes diagnostic tools (table_growth_rate, index_fragmentation, etc.)
   - `PlaybookSchedulerService` handles cron-based and manual triggers
   - Alert routing: browser/Slack/email with severity filtering

4. **Chat Service Architecture (Spring AI 2.0)**
   - `SpringAIChatService` uses `ChatClient` with advisor chain pattern
   - Advisors automatically inject context in order (memory → feedback → column values → schema → performance → RAG)
   - `FeedbackLearningAdvisor` retrieves user corrections/teachings from vector store for cross-session memory
   - `ColumnValueAdvisor` provides low-cardinality column values for filter hints
   - Scans database schema dynamically for context
   - **Critical Rule**: All SQL queries MUST use table-qualified columns (table.column_name)
   - **Critical Rule**: Chat-path logic must remain schema-agnostic. Never hardcode customer-specific table names, column names, SQL fragments, or prompt-to-table shortcuts in main chat flow. Resolve behavior through generic semantic ranking and schema-aware reasoning.
   - Supports image analysis for screenshots
   - Automatically executes SQL from markdown code blocks

5. **MCP Server + CLI** (`@deepsql/mcp`)
   - `mcp/deepsql-phase1-server.js` exposes DeepSQL to MCP clients over stdio using JSON-RPC 2.0 (auto-detects newline-delimited and LSP `Content-Length` framing). The `deepsql` CLI shim spawns this with the saved auth token from `~/.config/deepsql/auth.json`, so editor configs never need to embed a token.
   - **Agentless surface**: brain tools + policy-gated SQL execution + AI-enriched plan analysis. Coding agents (Claude Code, Cursor, Codex) use these to ground their own SQL/answer generation. There is no chat-pipeline `answer_question` tool — DeepSQL's value is the brain, not an embedded agent.
   - Tools:
     - `list_connections`, `get_schema`, `get_database_objects` — connection metadata
     - `get_brain_context` — wraps `/training/context/{cid}` (or `/training/retrieve/{cid}` when `topK` is set) for embedding-ranked retrieval
     - `list_business_rules` — wraps `/business-rules/connection/{cid}`
     - `get_relationships` — wraps `/brain/inferred-relationships/{cid}`
     - `get_anti_patterns` — wraps `/brain/table-anti-patterns/{cid}` and `/brain/query-anti-patterns/{cid}`
     - `analyze_slow_queries` — wraps `/slow-queries/analyze/{cid}`
     - `get_index_recommendations` — wraps `/index-recommendations/{cid}/top?limit=N`; serves the workload-weighted, recurrence-ranked top-N index recommendations with evidence + optional HypoPG cost-delta
     - `apply_index_recommendation` — wraps `/index-recommendations/{rec_id}/apply?mode=&confirm=`; the only write tool in the MCP surface that takes a server-resolved recommendation id (DDL is server-generated, never client-supplied). DRY_RUN uses HypoPG (Postgres) to estimate cost-delta without writes. APPLY runs `CREATE INDEX CONCURRENTLY` / `DROP INDEX CONCURRENTLY` (configurable via `concurrent=false`). APPLY_AND_MEASURE also runs `EXPLAIN ANALYZE` for wall-clock timings. APPLY modes require `confirm=true`.
     - `execute_sql` — runs any SQL through the canonical Editor endpoint `POST /connections/{cid}/query`. Backend enforces role-based policy via `QueryExecutionPolicyService`: developers get SELECT/WITH/SHOW/EXPLAIN; admins additionally get DML/DDL with a two-step `confirmMutation` flow plus a WHERE-clause guard for `UPDATE`/`DELETE`. EXPLAIN and EXPLAIN ANALYZE are just SQL — pass them as the query string.
     - `analyze_query_plan` — wraps `POST /explain/analyze` for AI-enriched plan analysis. Returns the parsed plan tree, performance issues, index recommendations, and an LLM-written summary that takes the connection's schema + business rules into account. `useAnalyze=true` actually runs the query (`EXPLAIN ANALYZE` semantics); for mutating statements the same admin/WHERE/confirm gates apply.
   - **Origin propagation**: every call carries `X-DeepSQL-Client-Type` (cli/mcp/editor), `X-DeepSQL-Client-Agent` (claude-code/cursor/codex/terminal/web — sourced from `DEEPSQL_MCP_USER_ID` for the MCP server, `--caller-agent` / `DEEPSQL_CALLER_AGENT` for the CLI), and `X-DeepSQL-Client-Version` headers. `SqlExecutionAuditService` writes them into every `security_events` row so admins can trace which surface ran which statement.
   - **Server-side query truncation**: pg_stat_statements (`track_activity_query_size`, default 1024B) and performance_schema (`performance_schema_max_sql_text_length`, default 1024B) silently truncate long queries. Provider code flags such rows with `SlowQuery.sourceTruncated=true`; `SlowQueryService.recoverTruncatedQueriesFromLineage(...)` then looks in `query_lineage` (vault DB) for a previously-ingested log-file copy with the full text and sets `queryTextRecoveredFromLogs=true` on success. Chat / MCP summarizers report three states (clean / recovered / still-truncated).
   - **Deprecated** (one-cycle forwarding aliases, removed in 0.14.0): `execute_readonly_sql` tool → forwards to `execute_sql`; `explain_readonly_sql` → forwards to `analyze_query_plan`; same for the `/api/mcp/query-readonly` and `/api/mcp/explain-readonly` endpoints behind them. `McpController` logs a deprecation warning on each call.
   - **Editor install** (one command per editor — writes the MCP entry AND a DBA-consult skill file in one shot):
     - `deepsql mcp config --install --for claude-code` → `claude mcp add --scope user` (falls back to `~/.claude.json`) + `~/.claude/skills/deepsql/SKILL.md`
     - `deepsql mcp config --install --for claude-desktop` → `~/Library/.../Claude/claude_desktop_config.json` (Desktop has no skills surface)
     - `deepsql mcp config --install --for cursor` → `~/.cursor/mcp.json` + `~/.cursor/rules/deepsql.mdc` (with globs on `**/*.sql`, `**/migrations/**`, `**/schema/**`, `**/models/**`, `**/*.prisma`, `**/entities/**`)
     - `deepsql mcp config --install --for codex` → `~/.codex/config.toml` + guarded section in `~/.codex/AGENTS.md`
   - Skill body lives in `mcp/skills/SKILL_BODY.md`; rendered with per-editor frontmatter at install time. `--no-skill` opts out, `--force` overwrites a stale entry, `--print` shows snippets without touching disk.
   - Cursor's project-level dev config is in `.cursor/mcp.json`. Customer-facing example configs live in `mcp/claude_desktop_config.customer.example.json` and `mcp/codex_config.customer.example.toml` (both updated to use the `deepsql mcp` shim — token-embedded shape still works but is no longer the recommended path).
   - See `docs/root/MCP_PHASE1.md` for full tool semantics; `mcp/CLAUDE.md` ships in the npm tarball with the runtime usage skill; `mcp/AGENT-SETUP.md` is the paste-into-agent install playbook.

5. **Security/Authentication**
   - JWT-based stateless authentication (10-hour expiry)
   - `JwtUtil` for token generation/validation
   - `SecurityConfig` with CORS support for multiple domains
   - Auth can be disabled for development: `security.auth.enabled=false`
   - `EncryptionService` for credential encryption
   - **Invite Code System** for private beta signup (see section 17)

6. **Database Provider Registry Pattern**
   - Extensible architecture for adding new database types without modifying core services
   - `DatabaseProviderRegistry` auto-discovers and registers all `DatabaseDialect` Spring beans
   - **Supported Databases**:
     - PostgreSQL: aliases `postgres`, `postgresql`, `pg`, `aurora-postgresql`, `amazon-aurora-postgresql`
     - MySQL: aliases `mysql`, `mariadb`, `aurora-mysql`, `amazon-aurora-mysql`
   - **Provider Interface Hierarchy**:
     ```
     DatabaseDialect (composite)
       ├── ConnectionProvider      - JDBC URLs, driver class, connection properties, default ports
       ├── IntrospectionProvider   - Schema/table/column metadata, indexes, constraints, foreign keys
       ├── SlowQueryProvider       - Slow query collection (pg_stat_statements, performance_schema)
       ├── PerformanceMetricsProvider - Active queries, index stats, cache stats
       ├── LockProvider            - Lock detection and analysis
       ├── ExplainPlanProvider     - EXPLAIN plan parsing (JSON format)
       ├── ConfigurationProvider   - Database configuration analysis
       └── QueryExecutionProvider  - Query fingerprinting, normalization
     ```
   - **Spring DI Integration**: All 16 provider classes are `@Component` beans with `@RequiredArgsConstructor` for dependency injection
   - **Alias Collision Detection**: Registry logs warnings if multiple dialects register the same alias
   - **Canonical Name Resolution**: Use `registry.getCanonicalName(dbType)` to normalize aliases before any branching logic:
     ```java
     String dbType = providerRegistry.getCanonicalName(connection.getDbType());
     // "postgresql" → "postgres", "mariadb" → "mysql", "aurora-mysql" → "mysql"
     ```
   - **Usage in Services**: Services call `registry.getDialect(dbType)` then access specific providers:
     ```java
     // Connection operations
     DatabaseDialect dialect = registry.getDialect("postgres");
     String jdbcUrl = dialect.connection().buildJdbcUrl(request, tunnelPort);
     int port = dialect.connection().getDefaultPort();

     // Schema introspection
     IntrospectionProvider introspection = dialect.introspection();
     List<ColumnDetail> columns = introspection.getColumnDetails(conn, database, tableName);
     List<IndexDetail> indexes = introspection.getIndexDetails(conn, database, tableName);
     Map<String, List<String>> relationships = introspection.getTableRelationships(conn, database);

     // Slow query collection
     List<SlowQuery> queries = dialect.slowQueries().collectSlowQueries(conn, threshold, limit);
     ```
   - **Refactored Services** (now use providers instead of hardcoded if/else):
     - `ConnectionService` - Delegates JDBC URL building, driver class, connection properties to ConnectionProvider
     - `SshTunnelService` - Uses ConnectionProvider for default port lookup
     - `SlowQueryService` - Uses `getCanonicalName()` for database type normalization
     - `SlowQueryCollectorService` - Uses `ConnectionService.getJdbcTemplate()` for SSH/alias support
     - `SlowQueryLogParserService` - Uses `getCanonicalName()` for parser selection (MySQL vs PostgreSQL)
     - `SchemaIntrospectionService` - Fully delegates to IntrospectionProvider (reduced from 537 → 95 lines)
     - `PerformanceMonitoringService` - Uses `getCanonicalName()` in all 8 monitoring methods
     - `PlaybookToolsService` - Uses `getCanonicalName()` for all 10 diagnostic tool methods
     - `IndexAdvisorService` - Uses `getCanonicalName()` for index health analysis
     - `ExplainPlanService` - Uses `getCanonicalName()` for EXPLAIN plan parsing
     - `DatabaseAdvisorService` - Uses `getCanonicalName()` for performance analysis
     - `DatabaseConfigurationService` - Uses `getCanonicalName()` for configuration tuning recommendations
     - `IndexRecommendationService` - Uses `getCanonicalName()` for recommendation generation
     - `QueryAnalysisService` - Uses `getCanonicalName()` for query validation
     - `QueryOptimizationService` - Uses `getCanonicalName()` for AI-powered optimization
     - `PerformanceInsightsService` - Uses `getCanonicalName()` for metrics collection
     - `QueryExecutorService` - Uses `getCanonicalName()` for database type normalization
     - `QueryPlanCacheService` - Uses canonical name comparisons for plan caching
     - `StatsCollectorService` - Uses `getCanonicalName()` for DBA stats collection
     - `TableHealthScoreService` - Uses `getCanonicalName()` for health score calculation
     - `DataLifecycleClassificationService` - Uses `getCanonicalName()` for lifecycle classification
     - `DependencyCriticalityService` - Uses `getCanonicalName()` for dependency analysis
   - **Adding New Database Support** (e.g., Oracle):
     1. Create package `com.dbaagent.provider.oracle`
     2. Implement all 8 provider interfaces as `@Component` beans
     3. Create `OracleDialect` implementing `DatabaseDialect` with `@Component`
     4. Add JDBC driver dependency to `pom.xml`
     5. Registry auto-discovers the new dialect on startup
   - **Anti-Pattern**: Do NOT add new `if/else` or `switch` statements for database types in services. Always delegate to providers.

**Key Service Categories:**
- **Core**: SpringAIChatService (primary), ChatService (legacy), ConnectionService, SshTunnelService, QueryExecutorService, DatabaseProviderRegistry
- **Database Providers**: PostgresDialect, MySQLDialect (each with 8 provider implementations: Connection, Introspection, SlowQuery, PerformanceMetrics, Lock, ExplainPlan, Configuration, QueryExecution)
- **Spring AI Advisors**: FeedbackLearningAdvisor, ColumnValueAdvisor, SchemaContextAdvisor, PerformanceInsightsAdvisor
- **Feedback/Learning**: FeedbackService
- **RAG/Training**: TrainingService, EmbeddingService, AzureSearchService, BrainInsightEmbeddingService
- **Playbooks**: PlaybookService, PlaybookExecutionService, PlaybookSchedulerService, PlaybookToolsService
- **Monitoring**: GrowthAnomalyDetectionService, PerformanceInsightsService, StatsCollectorService
- **Database Analysis**: SchemaIntrospectionService
- **Brain Package** (`com.dbaagent.service.brain.*`):
  - **brain/core**: BrainService, BrainScoreService, BrainTaskService, BrainNoteService
  - **brain/workload**: WorkloadMetricsCollectorService, WorkloadCharacterizationService
  - **brain/config**: KnobIdentificationService, ConfigTuningService
  - **brain/query**: CardinalityEstimationService, AdaptivePlanScoringService, PlanPatternLibraryService
  - **brain/keycolumn**: KeyColumnAnalysisService, ColumnValueCollectionService, JoinRelationshipInferenceService
  - **brain/classification**: SchemaClassificationService, AccessPatternClassificationService, AntiPatternDetectionService, TemporalClassificationService, TableHealthScoreService, BusinessDomainClassificationService, DataSensitivityClassificationService, PartitionReadinessService, RelationshipClassificationService, DataLifecycleClassificationService, CacheAffinityClassificationService, QueryComplexityClassificationService, SchemaEvolutionRiskService, DenormalizationCandidateService, CostAttributionService, ShardingReadinessService, DataQualityScoreService, DependencyCriticalityService, GrowthPredictionService
  - **brain/analysis**: ColumnProfilingService, ColumnDisambiguationService, QueryQualityAnalysisService, ScalabilitySimulationService
  - **brain** (root): BrainLearningScheduler (orchestrates background learning)
- **Query Analysis**: SlowQueryService, QueryPerformanceService, ExplainPlanService, QueryPlanCacheService
- **Performance Monitoring**: PerformanceMonitoringService (slow queries, locks, cache stats, index usage)
- **Schema Change Tracking**: SchemaChangeTrackingService (snapshots, drift detection, change history)
- **Index Advisor**: IndexAdvisorService, IndexRecommendationService (health reports, cost-benefit analysis)
- **Slow Query Ingestion**: SlowLogIngestionService, IngestionJobService, SlowLogIngestionScheduler, CloudWatchLogFetchService, S3LogFetchService, AzureBlobLogFetchService, GcpCloudLoggingFetchService, DatadogLogFetchService, ElasticsearchLogFetchService
- **Query Optimization**: QueryOptimizationService, QueryFingerprintService, SlowQueryAlertService, SlowQueryDashboardService, QueryOptimizationCacheRepository
- **Performance Actions**: PerformanceActionAggregatorService, PerformanceActionRepository (ROI-based unified recommendations)

**LLM Usage by Service (bring-your-own provider):**

There is no per-service deployment property any more. Every service below resolves through
the same two roles — `chat` and `embedding` — configured once via
`DEEPSQL_CHAT_*` / `DEEPSQL_EMBEDDING_*` (see **LLM Providers** below). The model is
whatever you set in `DEEPSQL_CHAT_MODEL` / `DEEPSQL_EMBEDDING_MODEL`.

| Service | Role | Purpose |
|---------|------|---------|
| `ChatService` | chat | Conversational SQL generation, schema analysis |
| `QueryOptimizationService` | chat | AI-powered query optimization suggestions |
| `SlowQueryService` | chat | Slow query analysis summaries |
| `ExplainPlanService` | chat | EXPLAIN plan interpretation |
| `DatabaseAdvisorService` | chat | Database performance recommendations |
| `EmbeddingService` | embedding | RAG vector embeddings |
| `BrainInsightEmbeddingService` | embedding (via `EmbeddingService`) | Brain insight embedding (workload, patterns, cardinality) |

**Spring AI Integration:**
- `ChatService` uses Spring AI `ChatClient`, backed by `RefreshableChatModel`, which asks
  `LlmConfigResolver` + `LlmProviderRegistry` for a `ChatModel` per call. Credentials are
  therefore re-resolved on every call and key rotation needs no restart.
- The old `spring.ai.openai.*` block that pinned the base URL to an Azure deployment path
  is gone, along with `AzureOpenAIConfig`, `OpenAISdkConfig` and `ResponsesApiConfig`.
  Spring AI's OpenAI chat/embedding/image/audio/moderation auto-configurations are
  **excluded** in `DbaAgentApplication` so they cannot build a second, independently
  configured model. Do not reintroduce them.

**LLM Providers (`com.dbaagent.llm`)**

`LlmProviderRegistry` auto-discovers `LlmChatProvider` / `LlmEmbeddingProvider` beans,
exactly like `DatabaseProviderRegistry`. Chat and embedding are indexed in **separate**
maps — Anthropic publishes no embeddings API, so a single index would force an
if/else-on-provider-type. Ids and aliases are case-insensitive; a collision throws at
startup. Startup logs:

```
Registered 1 LLM chat providers [openai] and 1 embedding providers [openai]
```

| Type | File | Notes |
|------|------|-------|
| Chat | `llm/openai/OpenAiCompatibleChatProvider.java` | id `openai`; aliases `azure`, `azure-openai`, `openai-compatible`, `self-hosted` |
| Chat transport | `llm/openai/ResponsesApiChatModel.java` | Responses API or chat completions, picked per model |
| Embedding | `llm/openai/OpenAiCompatibleEmbeddingProvider.java` | id `openai`; aliases `azure`, `azure-openai` |
| Spring bridge | `llm/spring/ProviderBackedEmbeddingModel.java` | `@Primary` `EmbeddingModel` for `VectorStore` / `QuestionAnswerAdvisor` |
| Resolution | `llm/LlmConfigResolver.java` | DB tier, then environment tier |

One provider covers OpenAI, Azure OpenAI and self-hosted vLLM/Ollama/LM Studio/TGI because
it dispatches on **endpoint shape**, not on a provider id: an `.azure.com` /
`.azure-api.net` base URL selects Azure's `api-key` header, everything else uses
`Authorization: Bearer`.

**Configuration resolution — two tiers, no property-default tier.** A credential default
in a properties file is how the production Azure key reached git history;
`SelfHostPropertiesSafetyTest` now guards against it.

1. **Database** — `llm.<role>.provider`, then `llm.<role>.<providerId>.<field>` in
   `system_config`, where `<role>` is `chat` or `embedding`. Namespaced per provider
   because model ids are not portable between providers, and so that evaluating a provider
   and reverting does not discard the original credentials.
2. **Environment** — `DEEPSQL_{CHAT,EMBEDDING}_{PROVIDER,API_KEY,ENDPOINT,MODEL,API_VERSION,USE_RESPONSES_API,TEMPERATURE}`.
   `PROVIDER` gates the bundle: unset, nothing else in it is read.

> **The DB tier is written by the onboarding wizard.** `SetupController`'s
> `POST /setup/llm-config` writes `llm.chat.provider` / `llm.chat.<providerId>.<field>`
> and the matching `llm.embedding.*` twins from the one credential it collects (an
> operator filling that form expects RAG to work too). `GET /setup/status` derives
> `hasLlmConfig` from `LlmConfigResolver.resolveChat() != null`.
>
> Until this was fixed the wizard wrote a flat pre-BYO namespace (`llm.provider`,
> `llm.openai.api-key`, `llm.openai.endpoint`, `llm.chat-model`, `llm.embedding-model`)
> that intersected **nothing** `LlmConfigResolver` reads: keys were stored, `{"success":
> true}` was returned, and nothing changed — while `hasLlmConfig`, read from
> `llm.openai.api-key`, stayed `false` on every correctly env-configured install.
> `V109__drop_legacy_llm_config.sql` removes those orphaned rows — apply it by hand,
> since nothing executes `db/migration/` (see the changelog note below).

**Chat resolution is per call.** `RefreshableChatModel` asks the resolver for credentials
on every call and rebuilds the delegate only when they change, so key rotation needs no
restart. `getDefaultOptions()` deliberately swallows `LlmNotConfiguredException` and
`UnsupportedLlmProviderException` and returns neutral options — an operator who cannot
start the app cannot fix the setting that stopped it. `call()` / `stream()` still raise.

**`/api/llm/v1` resolves like everything else.** `LlmProxyController` — the OpenAI-shaped
gateway the DeepSQL CLI agent points at — calls `LlmConfigResolver.resolveChat()` and picks
its auth header with `OpenAiEndpoints.isAzure(endpoint)`, so the chat bundle configures it
too. Unresolvable credentials give a 503 naming `DEEPSQL_CHAT_*`, never a 200 with an error
body. It previously bound `azure.openai.endpoint` / `azure.openai.key` via `@Value`, which
401'd for every OpenAI-key self-hoster; `azure.openai.*` is now read by no code at all,
though the properties themselves still sit in `application*.properties`.

6. **Slow Query Log Ingestion System**
   - Supports multiple log source providers:
     - **AWS S3**: Fetch slow query logs from S3 buckets
     - **AWS CloudWatch Logs**: Pull logs from CloudWatch log groups
     - **Azure Blob Storage**: Download logs from Azure storage containers
     - **GCP Cloud Logging**: Query Cloud SQL slow logs via Stackdriver
     - **Datadog APM**: Fetch database query logs from Datadog Log Search API
     - **Elasticsearch/ELK**: Search slow query logs indexed in Elasticsearch
   - `SlowLogIngestionService` orchestrates fetching, parsing, and storing slow query logs
   - `IngestionJobService` handles async ingestion with progress tracking and cancellation
   - `SlowLogIngestionScheduler` runs per-connection auto-scheduled ingestion
   - `SlowLogSourceConfig` stores per-connection settings (provider, credentials, schedule)
   - Integrates with `QueryPerformanceService` for regression detection
   - All credentials are encrypted at rest using `EncryptionService`

7. **Query Performance & Regression Detection**
   - `QueryPerformanceService` tracks query execution times over time
   - Detects performance regressions by comparing current vs baseline execution times
   - `QueryPerformanceRegression` stores detected regressions with severity (CRITICAL, SEVERE, MODERATE, MINOR)
   - Supports acknowledgment and resolution workflow for regressions
   - Feeds into Brain Score calculation (regressions penalize Query Quality Score)

8. **AI-Powered Query Optimization System**
   - `QueryOptimizationService` generates AI-powered optimization suggestions using Azure OpenAI
   - Analyzes slow queries and provides: query rewrites, index recommendations, schema suggestions
   - Includes EXPLAIN plan analysis for SELECT queries
   - Batch optimization for top N slow queries
   - Estimated performance improvement percentages

9. **Query Fingerprint Tracking**
   - `QueryFingerprintService` tracks normalized query patterns over time
   - `QueryFingerprint` entity stores: fingerprint hash, baseline/current metrics, performance history
   - Trend analysis: IMPROVING, STABLE, DEGRADING, CRITICAL
   - Regression detection at fingerprint level
   - Historical performance data points for trend visualization

10. **Slow Query Alerts & Notifications**
    - `SlowQueryAlertService` integrates with PlaybookAlert system
    - Configurable thresholds for critical/high severity queries
    - Creates alerts for: individual critical queries, summary alerts, health degradation
    - Supports acknowledgment workflow
    - Alert summary with counts by severity
    - Multi-channel notifications: Email (`EmailService.sendSlowQueryAlert()`) and Slack/Webhook (`WebhookService.sendSlowQueryAlert()`)
    - Slack Block Kit formatting for rich alert messages
    - Alert throttling and channel configuration per connection

11. **Slow Query Dashboard Widgets**
    - `SlowQueryDashboardService` provides aggregated widget data
    - Widgets: Overview, Trend, Top Offenders, Regressions, Health
    - Time series data for trend visualization
    - Top queries by: execution time, frequency, impact score
    - Health score history with distribution

12. **Enhanced Schema Classification System**
    - Comprehensive table and schema analysis with 8 specialized classification services
    - **Prerequisites (Order of Operations)**: Schema Classification depends on Key Column Analysis data. If you see 0 tables:
      1. First, ingest slow query logs (Brain → Slow Query Source)
      2. Run Key Column Analysis (Brain → Key Columns → Analyze)
      3. Then run Schema Classification (Brain → Schema Overview → Analyze Schema)
    - **Table Role Classification** (in `SchemaClassificationService.classifyTables()`):
      - FACT: Tables with many outbound FKs, high row counts, or transaction-like names (payment, order, booking)
      - DIMENSION: Tables with many inbound joins, few outbound FKs, smaller row counts
      - BRIDGE: Tables connecting other tables (both inbound and outbound joins)
      - LOOKUP: Small reference tables (<1000 rows) with inbound joins
      - EVENT_LOG: Log/audit/event tables detected by naming patterns (*_log, *_audit, *_history, *_event, *_trail)
      - ORPHANED: Tables with no detected relationships
    - **Access Pattern Classification** (`AccessPatternClassificationService`):
      - Analyzes pg_stat_user_tables for read/write patterns
      - Types: READ_HEAVY, WRITE_HEAVY, APPEND_ONLY, UPDATE_INTENSIVE, MIXED_WORKLOAD, RARELY_ACCESSED
      - Returns read/write ratios and operation counts
    - **Anti-Pattern Detection** (`AntiPatternDetectionService`):
      - Detects 10 schema anti-patterns: GOD_TABLE, WIDE_TABLE, SPARSE_TABLE, POLYMORPHIC_ASSOCIATION, EAV_PATTERN, MISSING_TIMESTAMP, OVER_INDEXED, UNDER_INDEXED, DUPLICATE_INDEXES, UNUSED_COLUMNS
      - Severity levels: CRITICAL, HIGH, MEDIUM, LOW, NONE
      - Generates specific remediation recommendations
    - **Temporal Classification** (`TemporalClassificationService`):
      - Identifies temporal data patterns using column name analysis
      - Types: TIME_SERIES, SCD_TYPE_1, SCD_TYPE_2, AUDIT_LOG, EVENT_SOURCING, SNAPSHOT, NONE
      - Suggests appropriate retention and archival strategies
    - **Table Health Score** (`TableHealthScoreService`):
      - Calculates composite health score (0-100) from 5 components (20% each):
        - Index Efficiency, Data Quality, Schema Design, Access Efficiency, Maintenance Health
      - Provides detailed breakdown with per-component scores
      - Identifies tables needing attention
    - **Business Domain Classification** (`BusinessDomainClassificationService`):
      - Classifies tables into business domains using name/column pattern matching
      - Domains: CUSTOMER, PRODUCT, TRANSACTION, LOCATION, TEMPORAL, CONFIGURATION, SECURITY, COMMUNICATION, ANALYTICS, CONTENT, UNKNOWN
      - Confidence scores for classification accuracy
    - **Data Sensitivity Classification** (`DataSensitivityClassificationService`):
      - Detects sensitive columns using regex patterns (PII, financial, health data)
      - Sensitivity levels: PII_HIGH, PII_MEDIUM, FINANCIAL, HEALTH, REGULATED, PUBLIC
      - Compliance flag detection: GDPR, CCPA, PCI-DSS, SOX, HIPAA, HITECH, SOC2
      - Lists specific sensitive columns found
    - **Partition Readiness** (`PartitionReadinessService`):
      - Evaluates tables for partitioning suitability
      - Types: PARTITION_CANDIDATE_TIME, PARTITION_CANDIDATE_RANGE, PARTITION_CANDIDATE_LIST, ALREADY_PARTITIONED, NOT_PARTITION_CANDIDATE
      - Identifies partition key candidates with estimated benefit (0-100%)
      - Row count and size-based recommendations
    - **Relationship Classification** (`RelationshipClassificationService`):
      - Combines FK constraints, naming patterns, and query analysis
      - Strength levels: STRONG (FK exists), INFERRED (naming pattern), WEAK (join pattern only)
      - Relationship types: ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY
      - Data integrity metrics: orphan counts, referential integrity percentage
      - Index coverage analysis for join performance

13. **Key Column Analysis System**
    - `KeyColumnAnalysisService` analyzes query patterns to identify important columns
    - Tracks column usage across: JOINs, WHERE clauses, GROUP BY, ORDER BY operations
    - Calculates importance scores (0-100) based on weighted usage counts
    - **Case Normalization**: All table and column names are normalized to lowercase to prevent duplicates (e.g., "Users" vs "users")
    - **Usage Metrics** (two columns in UI):
      - **Query Runs** (`slowQueryUsage`): Total weighted usage count from slow queries
      - **Unique Qry** (`distinctQueriesCount`): Number of distinct queries referencing the column
    - **Rate Limiting** (prevents CPU spikes on large databases):
      - `brain.key-columns.max-queries-per-source=1000` - Max queries processed from each source
      - `brain.key-columns.batch-size=100` - Process queries in batches
      - `brain.key-columns.delay-between-batches-ms=50` - Pause between batches
      - Progress logging every 100 queries: "Processed 100/500 slow queries..."
    - **Anti-Pattern Detection** with index-awareness:
      - UNINDEXED_FILTER: Columns used in WHERE ≥5 times without an index
      - UNINDEXED_JOIN: Columns used in JOINs ≥5 times without an index
      - UNINDEXED_ORDERBY: Columns used in ORDER BY ≥3 times without an index
      - **Important**: All index recommendations skip columns that already have an index (`indexName != null`)
    - Additional anti-patterns: HEAVY_SKEW_JOIN, HEAVY_SKEW_GROUPBY (data distribution issues)
    - Composite index detection for frequently co-occurring columns
    - Index stats enrichment from `pg_stat_user_indexes`
    - Supports Brain Rules for custom column behaviors (NO_INDEX_RECOMMENDATION, etc.)
    - **Column Value Collection**: Automatically triggers `ColumnValueCollectionService` after analysis completes (see section 16)

14. **Advanced Classification Services (Phase 6)**
    - 10 additional classification services for comprehensive table analysis
    - **Data Lifecycle Classification** (`DataLifecycleClassificationService`):
      - Classifies tables as HOT, WARM, COLD, or FROZEN based on access patterns
      - Analyzes last access timestamps and access frequency per day
      - Thresholds: HOT (7 days, >100 ops/day), WARM (30 days), COLD (90 days), FROZEN (90+ days)
      - Recommendations for storage tier optimization and archival strategies
    - **Cache Affinity Classification** (`CacheAffinityClassificationService`):
      - Identifies tables suitable for caching based on read/write ratios and size
      - Levels: HIGH_CACHE_VALUE, MEDIUM_CACHE_VALUE, LOW_CACHE_VALUE, NO_CACHE_BENEFIT
      - Factors: read frequency (35%), write volatility (25%), size (25%), update frequency (15%)
      - Provides estimated cache hit rate predictions
    - **Query Complexity Classification** (`QueryComplexityClassificationService`):
      - Analyzes slow query history to classify query patterns per table
      - Types: SIMPLE_LOOKUP, RANGE_SCAN, COMPLEX_JOIN, AGGREGATION_HEAVY, FULL_SCAN, MIXED
      - Uses regex patterns to detect JOINs, aggregations, subqueries
      - Provides complexity scores and pattern breakdown percentages
    - **Schema Evolution Risk** (`SchemaEvolutionRiskService`):
      - Assesses risk of modifying tables based on multiple factors
      - Risk levels: CRITICAL, HIGH, MEDIUM, LOW, MINIMAL
      - Factors: table size (40%), dependencies (30%), index count (20%), column count (10%)
      - Provides DDL impact estimates and migration strategy recommendations
    - **Denormalization Candidates** (`DenormalizationCandidateService`):
      - Identifies tables that could benefit from denormalization
      - Analyzes join frequency patterns from InferredTableRelationship data
      - Criteria: high join frequency (>100), small lookup tables (<10K rows)
      - Recommends specific strategies: redundant columns or materialized views
    - **Cost Attribution** (`CostAttributionService`):
      - Calculates estimated storage and compute costs per table
      - Cost tiers: HIGH_COST, MEDIUM_COST, LOW_COST, MINIMAL_COST
      - Breaks down costs: storage, IOPS, compute (based on AWS RDS pricing)
      - Provides cost per row and cost as percentage of total database
    - **Sharding Readiness** (`ShardingReadinessService`):
      - Evaluates tables for horizontal sharding suitability
      - Readiness levels: READY, NEEDS_REFACTORING, COMPLEX, NOT_RECOMMENDED, NOT_NEEDED
      - Identifies shard key candidates (tenant_id, user_id, region, date columns)
      - Analyzes FK constraints that would complicate cross-shard queries
      - Recommends optimal shard count based on table size
    - **Data Quality Score** (`DataQualityScoreService`):
      - Calculates comprehensive data quality scores (0-100)
      - Quality levels: EXCELLENT (80+), GOOD (60-80), FAIR (40-60), POOR (<40)
      - Dimensions: completeness (25%), validity (25%), uniqueness (20%), consistency (15%), timeliness (15%)
      - Analyzes null ratios, constraint coverage, and FK relationships
    - **Dependency Criticality** (`DependencyCriticalityService`):
      - Assesses the criticality of tables based on dependency graph
      - Criticality levels: CRITICAL, HIGH, MEDIUM, LOW, ISOLATED
      - Calculates cascade impact (transitive closure of dependents)
      - Provides dependency depth analysis and change impact areas
    - **Growth Prediction** (`GrowthPredictionService`):
      - Predicts table growth using historical TableGrowthHistory data
      - Growth categories: EXPLOSIVE (>50%/mo), RAPID (20-50%), STEADY (5-20%), SLOW (1-5%), STABLE (<1%), SHRINKING
      - Predictions for 30/90/365 days out
      - Alerts for approaching size thresholds (10GB warning, 100GB critical)
      - Confidence scores based on historical data availability

15. **SSH Tunneling for VPC/Firewall Access**
    - `SshTunnelService` manages SSH tunnels for database connections behind firewalls/VPCs
    - Enables connecting to production databases that are not directly accessible
    - **Recommended Access Pattern**: Use `ConnectionService.getJdbcTemplate(connectionId, request)` for all database operations:
      ```java
      // This works seamlessly for BOTH SSH-tunneled AND direct connections
      JdbcTemplate jdbc = connectionService.getJdbcTemplate(connectionId, request);
      jdbc.queryForList("SELECT * FROM table");
      ```
    - **Authentication Types**:
      - `PASSWORD`: Username/password authentication
      - `PRIVATE_KEY`: SSH private key authentication (PEM format), with optional passphrase
    - **Tunnel Lifecycle**:
      - Tunnels are established when creating connection pools
      - Automatic port forwarding from localhost to remote database host
      - Tunnels are closed when connection pools are destroyed
      - Thread-safe tunnel management with ConcurrentHashMap
    - **Connection Flow with SSH**:
      ```
      Client Request → SSH Tunnel (localhost:localPort → remoteHost:dbPort) → Database
      ```
    - **Configuration Fields**:
      - `sshEnabled`: Boolean to enable/disable SSH tunneling
      - `sshAuthType`: PASSWORD or PRIVATE_KEY
      - `sshHost`: Bastion/jump host address
      - `sshPort`: SSH port (default 22)
      - `sshUsername`: SSH username
      - `sshPassword`: Password for PASSWORD auth
      - `sshPrivateKey`: PEM-formatted private key for PRIVATE_KEY auth
      - `sshPassphrase`: Passphrase for encrypted private keys
    - **Security**:
      - All SSH credentials are encrypted at rest using `EncryptionService` (AES-GCM)
      - SSL is automatically disabled for tunneled connections (SSH provides encryption)
      - Strict host key checking disabled by default (can be made configurable)
    - **JSch Library**: Uses `com.github.mwiede:jsch:2.27.7` for SSH operations
    - **Frontend UI**: SSH tunnel configuration section in DBConfigModal with collapsible panel

16. **Column Value Embedding for Better SQL Filtering**
    - `ColumnValueCollectionService` collects and caches distinct values for low-cardinality columns
    - **Integration with Key Column Analysis**:
      - Automatically triggered after Key Column Analysis completes (async)
      - Analyzes columns with `distinctCount < 100` (configurable via `spring.ai.column-values.low-cardinality-threshold`)
    - **Azure AI Search Integration**:
      - Document type: `COLUMN_VALUES`
      - Document ID format: `{connectionId}::COLUMN_VALUES::{tableName}::{columnName}`
      - Semantic embedding text includes table, column, data type, valid values, and example filters
      - Enables RAG retrieval when users ask about filtering or column values
    - **Chat Context Enhancement** (`ChatService.buildKeyColumnContext()`):
      - Key columns now display actual values for low-cardinality columns
      - Example output:
        ```
        • orders.status (importance: 92) | 5 distinct | HIGH selectivity
          VALUES: 'pending', 'confirmed', 'shipped', 'delivered', 'cancelled'
        ```
    - **REST API Endpoints** (in `BrainController`):
      - `GET /api/brain/column-values/{connectionId}` - List cached column values
      - `GET /api/brain/column-values/{connectionId}/stats` - Get statistics
      - `POST /api/brain/column-values/{connectionId}/refresh` - Trigger manual refresh
      - `POST /api/brain/column-values/embed-all` - Embed all unembedded values
    - **Configuration Properties**:
      - `spring.ai.column-values.low-cardinality-threshold=100` - Max distinct values to consider "low cardinality"
      - `spring.ai.column-values.sample-size=20` - Number of sample values to store
      - `spring.ai.column-values.background-sampling.enabled=true` - Enable daily refresh
      - `spring.ai.column-values.background-sampling.cron=0 0 3 * * *` - Refresh schedule
    - **Rate Limiting** (prevents CPU spikes on large databases):
      - `spring.ai.column-values.batch-size=50` - Max columns processed per run
      - `spring.ai.column-values.delay-between-columns-ms=100` - Pause between DB queries
      - `spring.ai.column-values.delay-between-embeddings-ms=500` - Pause between Azure OpenAI calls
    - **Database Storage**: Values cached in `column_value_cache` table with embedded flag for Azure Search sync
    - **Frontend UI** (`ColumnValuesPanel.js`):
      - Located in Brain tab → Overview section (below 3D ER diagram and Key Columns)
      - Shows all cached column values grouped by table
      - Displays embedding status (Embedded/Pending) for each column
      - Stats summary: Total Cached, Low Cardinality, Embedded in AI Search
      - Actions: Refresh Values, Embed All, Reload
      - Values displayed as clickable tags with overflow handling
      - Explains how column values improve SQL generation

17. **Cloud Provider Context for Feature Gating**
    - Connections can optionally specify cloud provider and managed service type
    - Enables feature gating based on deployment environment (e.g., CloudWatch log ingestion only for AWS)
    - **Fields** (stored in `encrypted_credentials` table):
      - `cloudProvider`: `aws`, `azure`, `gcp`, `self-hosted`, or `null`
      - `managedService`: Service-specific type (see table below)
    - **Supported Configurations**:
      | Cloud Provider | Managed Service Options |
      |----------------|------------------------|
      | `aws` | `rds`, `aurora`, `ec2` |
      | `azure` | `azure-flexible`, `azure-single`, `azure-vm` |
      | `gcp` | `cloud-sql`, `alloydb`, `gce` |
      | `self-hosted` | (none) |
    - **Backend Implementation**:
      - `DatabaseConnection.cloudProvider` and `DatabaseConnection.managedService` entity fields
      - `ConnectionRequest` DTO includes both fields
      - `CredentialService` saves/loads fields during connection create/update/read
      - Fields are plain text (not encrypted) - they're metadata, not secrets
    - **Frontend UI** (`DBConfigModal.js`):
      - "Deployment (Optional)" dropdown after SSL toggle
      - Conditional service type selector based on cloud provider choice
      - Values reset when cloud provider changes
    - **Use Cases**:
      - Show CloudWatch log source option only for `cloudProvider: 'aws'`
      - Show Azure Monitor integration only for `cloudProvider: 'azure'`
      - Enable RDS Performance Insights features for `managedService: 'rds'` or `'aurora'`
      - Adjust default SSL settings based on managed service type
    - **Migration**: `V40__add_cloud_provider_fields.sql` adds columns with indexes

18. **Invite Code System for Private Beta**
    - Enables controlled user registration during private beta
    - `InviteCodeService` generates and manages invite codes
    - `InviteCodeController` provides REST API for code management
    - **Code Format**: 8-character uppercase alphanumeric (excludes confusing chars: 0, O, 1, I, L)
    - **Example Code**: `BETA2K7X`, `MZGE685Q`
    - **Shareable Link**: `https://yourapp.com/signup?code=BETA2K7X`
    - **Features**:
      - Configurable max uses per code (1 to unlimited)
      - Optional expiration dates
      - Usage tracking (current uses, remaining uses)
      - Code deactivation for immediate invalidation
      - Admin notes for tracking code purpose
    - **REST API Endpoints**:
      - `POST /api/invite-codes` - Generate new code (admin, requires auth)
      - `GET /api/invite-codes` - List all codes (admin, requires auth)
      - `GET /api/invite-codes/valid` - List valid codes only (admin)
      - `DELETE /api/invite-codes/{id}` - Deactivate code (admin)
      - `GET /api/invite-codes/validate/{code}` - Validate code (public, no auth)
    - **Signup Flow**:
      1. User visits `/signup?code=BETA2K7X` (code pre-filled from URL)
      2. Frontend validates code in real-time via public API
      3. On submit, backend validates code and creates user
      4. Code usage count incremented after successful registration
      5. User linked to invite code via `invite_code_id` foreign key
    - **Configuration**:
      - `security.signup.enabled=true` - Enable/disable signup entirely
      - `security.signup.invite-only=true` - Require invite code for signup
    - **Frontend Components**:
      - `Signup.jsx` - Registration page with invite code field
      - `authAPI.validateInviteCode(code)` - Public validation
      - `inviteCodeAPI` - Admin operations (generate, list, deactivate)

19. **Role-Based Access Control (RBAC)**
    - Expanded role system with configurable access levels
    - **Roles**:
      | Role | Description |
      |------|-------------|
      | VIEWER | Read-only access. Can view dashboards, browse schema, view slow queries, but cannot execute queries or modify anything. |
      | EDITOR | Standard user. Can execute queries, use chat, run analysis, but cannot manage users or system settings. |
      | ADMIN | Full access. Can manage users, roles, connections, and all system settings. |
    - **Permissions**:
      - VIEW_DASHBOARD, VIEW_SCHEMA, VIEW_SLOW_QUERIES, VIEW_BRAIN (all roles)
      - EXECUTE_QUERIES, USE_CHAT, RUN_ANALYSIS, EXECUTE_PLAYBOOKS, USE_INDEX_ADVISOR (EDITOR+)
      - MANAGE_CONNECTIONS, MANAGE_USERS, MANAGE_INVITE_CODES, MANAGE_SETTINGS (ADMIN only)
    - **Backend Components**:
      - `Role.java` - Enum with VIEWER, EDITOR, ADMIN and permission mappings
      - `Permission.java` - Enum of all system permissions
      - `UserManagementService` - User listing, role updates, deletion
      - `AdminController` - REST API for admin operations (`/api/admin/*`)
    - **Security Configuration**:
      - `@EnableMethodSecurity` for `@PreAuthorize` annotations
      - `/api/admin/**` endpoints require ADMIN role
      - JWT tokens include `role` and `permissions` claims
    - **REST API Endpoints**:
      - `GET /api/admin/users` - List all users (ADMIN only)
      - `GET /api/admin/users/{id}` - Get user details (ADMIN only)
      - `PUT /api/admin/users/{id}/role` - Update user role (ADMIN only)
      - `DELETE /api/admin/users/{id}` - Delete user (ADMIN only)
      - `GET /api/admin/roles` - Get all roles with permissions (ADMIN only)
      - `GET /api/admin/impersonate` - List switchable users and current profile-switch status (ADMIN only)
      - `POST /api/admin/impersonate` - `{ userId }` start viewing the product as that user (ADMIN only; cannot target admins or self)
      - `DELETE /api/admin/impersonate` - Stop profile switch and restore the admin session
      - `GET /api/auth/me` - Get current user's profile including role/permissions; while switching, this is the **target** user plus `impersonating` / `impersonatorUsername`
    - **Frontend Components**:
      - `PermissionGuard.jsx` - Wrapper component for permission-based rendering
      - `UsersTab.jsx` - Admin user management tab in Workspace
      - `useAuth.jsx` - Updated with `hasPermission()`, `hasRole()`, `isAdmin`, `canExecute`, etc.
      - `adminAPI` in `client.js` - API methods for user management
    - **localStorage Additions**:
      - `userRole`: User's role (VIEWER, EDITOR, ADMIN)
      - `userPermissions`: JSON array of permission strings
    - **Default Behavior**:
      - New signups default to EDITOR role
      - Admin user (username: 'admin') cannot have role changed or be deleted

20. **Brain 2.0: ML-Based Database Optimization**
    - ML-based database tuning inspired by CMU OtterTune and optd research projects
    - **Key Concepts**:
      - **Workload Characterization**: Classifies workloads using statistical analysis (OLTP, OLAP, MIXED, WRITE_HEAVY, READ_HEAVY, BATCH)
      - **Knob Identification**: Ranks configuration parameters by impact using Lasso-inspired approach
      - **Adaptive Query Optimization**: Learns from actual execution to calibrate cost models
      - **Plan Pattern Memoization**: Caches query plan patterns with optimization suggestions
      - **Knowledge Transfer**: Leverages learnings across similar workloads
    - **Service Architecture** (consolidated into `com.dbaagent.service.brain.*`):
      | Service | Package | Purpose |
      |---------|---------|---------|
      | `WorkloadMetricsCollectorService` | brain.workload | Collects 100+ database metrics (pg_stat_*, performance_schema) |
      | `WorkloadCharacterizationService` | brain.workload | Classifies workload type with fingerprint vectors |
      | `KnobIdentificationService` | brain.config | Ranks 14+ knobs per database type by impact |
      | `ConfigTuningService` | brain.config | ML + AI hybrid configuration recommendations |
      | `CardinalityEstimationService` | brain.query | Independent cardinality estimation using TDigest/HyperLogLog |
      | `AdaptivePlanScoringService` | brain.query | Learns from actual vs estimated execution times |
      | `PlanPatternLibraryService` | brain.query | Manages query plan pattern cache |
      | `BrainLearningScheduler` | brain | Orchestrates continuous background learning |
    - **Entity Classes** (`com.dbaagent.model.brain`):
      - `WorkloadMetricsSnapshot` - Raw and reduced metrics per snapshot
      - `WorkloadProfile` - Workload classification with fingerprint vectors
      - `KnobRanking` - Ranked configuration parameters with metadata
      - `ConfigurationObservation` - Records of configuration experiments
      - `TuningExperiment` - A/B testing lifecycle for config changes
      - `ColumnStatistics` - Independent cardinality estimation data
      - `PlanExecution` - Tracks actual vs estimated execution
      - `PlanPattern` - Query plan patterns with cached suggestions
      - `CostCalibration` - Learned cost model adjustments
      - `BrainLearningProgress` - Tracks overall Brain 2.0 readiness
      - `BrainV2Alert` - Alerts for workload changes, config drift, etc.
    - **Brain Score Enhancement** (6 dimensions, updated weights):
      | Dimension | Weight | Source |
      |-----------|--------|--------|
      | Schema Design | 15% | Original |
      | Query Quality | 15% | Original |
      | Index & Access | 20% | Original |
      | Scalability | 10% | Original |
      | Config Tuning | 15% | Brain 2.0 (knob rankings, experiments) |
      | Query Intelligence | 15% | Brain 2.0 (cardinality accuracy, patterns) |
      | Workload Understanding | 5% | Brain 2.0 (classification confidence) |
      | Learning Progress | 5% | Brain 2.0 (milestone achievements) |
    - **REST API** (consolidated into `BrainController` at `/api/brain`):
      - Workload: `POST /workload/collect/{id}`, `POST /workload/characterize/{id}`, `GET /workload/profile/{id}`, `GET /workload/status/{id}`, `GET /workload/similar/{id}`
      - Config: `POST /config/knobs/{id}`, `GET /config/top-knobs/{id}`, `GET /config/rankings/{id}`, `POST /config/recommendations/{id}`, `POST /config/experiments/{id}`
      - Statistics: `POST /statistics/{id}/tables/{table}`, `GET /statistics/{id}`, `POST /statistics/{id}/estimate`, `POST /statistics/{id}/refresh`
      - Executions: `POST /executions/{id}`, `GET /executions/{id}`, `GET /executions/{id}/cardinality-errors`, `GET /calibration/{id}`
      - Patterns: `POST /patterns/{id}/suggestions`, `GET /patterns/{id}/reliable`, `GET /patterns/{id}/most-used`, `GET /patterns/{id}/stats`
      - Overview: `GET /ml-overview/{id}` (combined workload, config, and query intelligence status)
    - **Configuration Properties**:
      ```properties
      brain.v2.learning.enabled=true
      brain.v2.learning.metrics-interval-minutes=15
      brain.v2.learning.characterization-interval-hours=4
      brain.v2.learning.statistics-refresh-days=7
      ```
    - **Database Migrations**:
      - V42: `brain_v2_workload_intelligence.sql` - workload_metrics_snapshot, workload_profile, workload_similarity
      - V43: `brain_v2_config_tuning.sql` - knob_ranking, configuration_observation, tuning_experiment, cost_calibration
      - V44: `brain_v2_query_intelligence.sql` - column_statistics, plan_execution, plan_pattern
      - V45: `brain_v2_score_enhancement.sql` - brain_score new columns, brain_learning_progress, brain_v2_alert

21. **Performance Action System (ROI-Based Recommendations)**
    - Unified performance recommendation system that aggregates suggestions from multiple sources
    - Actions are ranked by ROI (Return on Investment) = impact / effort × 100
    - **Entity Classes** (`com.dbaagent.model`):
      - `PerformanceAction` - Unified action with impact/effort scores, status, and metadata
      - `QueryOptimizationCache` - Caches AI optimization results to prevent redundant API calls
    - **Action Categories**:
      | Category | Description | Examples |
      |----------|-------------|----------|
      | `INDEX` | Create/modify indexes | Missing index, composite index |
      | `QUERY_REWRITE` | Rewrite slow queries | Query optimization suggestions |
      | `CONFIG` | Configuration tuning | Memory settings, connection pool |
      | `SCHEMA` | Schema changes | Anti-patterns, normalization |
      | `MAINTENANCE` | Maintenance tasks | VACUUM, ANALYZE, REINDEX |
    - **Action Sources**:
      | Source | Service | Description |
      |--------|---------|-------------|
      | `INDEX_ADVISOR` | IndexAdvisorService | Index recommendations |
      | `SLOW_QUERY_ANALYSIS` | QueryOptimizationService | AI query optimization |
      | `BRAIN_CONFIG_TUNING` | ConfigTuningService | Configuration recommendations |
      | `ANTI_PATTERN_DETECTION` | AntiPatternDetectionService | Schema anti-patterns |
      | `KEY_COLUMN_ANALYSIS` | KeyColumnAnalysisService | Missing indexes on key columns |
      | `BRAIN_INSIGHTS` | BrainScoreService | General recommendations |
    - **Action Status Workflow**: `PENDING` → `IN_PROGRESS` → `COMPLETED` / `DISMISSED`
    - **Services**:
      - `PerformanceActionAggregatorService` - Collects and aggregates actions from all sources
      - `QueryOptimizationService` - Enhanced with caching and query sanitization
      - `QueryNormalizer.sanitize()` - Centralized query text cleanup (removes `USE db;`, `SET timestamp=?;`, etc.)
    - **REST API** (`PerformanceActionController` at `/api/performance-actions`):
      - `GET /{connectionId}` - Get all pending actions sorted by ROI
      - `GET /{connectionId}/top?limit=N` - Get top N actions by ROI
      - `GET /{connectionId}/by-category/{category}` - Filter by category
      - `GET /{connectionId}/by-source/{source}` - Filter by source
      - `GET /{connectionId}/summary` - Get action counts by category/status
      - `POST /{connectionId}/refresh` - Re-collect actions from all sources
      - `PUT /{actionId}/status` - Update action status (complete/dismiss)
    - **Query Optimization Caching** (`QueryOptimizationCacheRepository`):
      - Caches AI optimization results by connection + query fingerprint
      - Prevents redundant Azure OpenAI API calls for the same query patterns
      - Tracks access count and last accessed timestamp
      - `GET /api/slow-queries/{connectionId}/optimizations/cached` - Batch fetch cached optimizations
    - **Frontend Components**:
      - `PerformanceInsightsTab` - Redesigned with action-focused UI
      - `PerformanceActionCard` - Displays action with impact/effort/ROI visualization
      - `TableHeatmap` - Visualizes table usage patterns (read/write heat)
      - `QueryDetailDialog` - Detailed slow query analysis with optimization view
      - TanStack Query hooks: `usePerformanceActions`, `usePerformanceActionsSummary`, `useRefreshPerformanceActions`, `useUpdateActionStatus`, `useCachedOptimizations`
    - **Database Migrations**:
      - V48: `create_query_optimization_cache.sql` - query_optimization_cache table
      - V49: `create_performance_action.sql` - performance_action table with ROI indexes

### Frontend Architecture

**API Layer** (`src/lib/api/client.js`):
- Centralized axios client with 120s timeout
- Request interceptor: Auto-adds JWT Bearer token from localStorage
- Response interceptor: Handles 401/403 with redirect to login
- 25+ API modules: authAPI, connectionAPI, schemaAPI, trainingAPI, brainAPI, chatAPI, slowLogSourceAPI, queryPerformanceAPI, slowQueriesAPI (with optimization, alerts, fingerprints, dashboard, cached optimizations), performanceActionsAPI, etc.

**State Management**:
- **Zustand** for client-side UI state (preferred over Context API)
- **TanStack Query v5** for server state (data fetching/caching)
- **DashboardContext** (legacy) - now delegates to Zustand store

**Zustand Stores** (`src/lib/stores/`):
| Store | Purpose | Persistence |
|-------|---------|-------------|
| `useDashboardStore` | activeTab, connectionId, dashboardConfig, isBuildingDashboard | localStorage (connectionId, activeTab) |
| `useConnectionStore` | selectedConnectionId, lastConnections (recent history) | localStorage |
| `useChatStore` | messages, chatIds, sendingTabs (per connection/tab) | localStorage (per tab) |
| `useUIStore` | modals, panels, menus, drag state, capture state | None (ephemeral) |

**Zustand Usage Patterns**:
```javascript
// 1. Selector hooks (recommended - optimized re-renders)
import { useActiveTab, useDashboardActions } from '@/lib/stores'
const activeTab = useActiveTab()
const { setActiveTab } = useDashboardActions()

// 2. Direct store access
import { useDashboardStore } from '@/lib/stores'
const activeTab = useDashboardStore((state) => state.activeTab)

// 3. Outside React (imperative updates)
useDashboardStore.getState().setActiveTab('brain')

// 4. Legacy compatibility (during migration)
import { useDashboard } from '@/contexts/DashboardContext'
const { activeTab, setActiveTab } = useDashboard() // delegates to Zustand
```

**Migration Status**: `DashboardContext` now uses Zustand under the hood. Components can be migrated incrementally to use Zustand directly for better performance.

**Zustand Anti-Patterns to Avoid**:
- ❌ Creating new action objects in render: `const actions = { setActiveTab: store.setActiveTab }` - causes infinite loops
- ❌ Calling setters in useEffect without guard conditions - use `if (currentValue !== newValue)` checks
- ❌ Using `useDashboard()` context in new code - use Zustand hooks directly
- ✅ Use cached action references: `const { setActiveTab } = useDashboardActions()`
- ✅ Use selector hooks for values: `const activeTab = useActiveTab()`
- ✅ Add `data-testid` attributes to interactive elements for E2E testing

- **Independent Chat Threads Per Tab**: Each tab maintains its own independent chat thread per connection. Multiple tabs can send requests simultaneously without interference. Chat messages are stored per tab in localStorage (`chat-{connectionId}-{tab}`) and chat IDs per tab (`chatId-{connectionId}-{tab}`). Responses are always routed to the originating tab's localStorage, regardless of which tab is currently active. Component state (`messages`) only updates if the response is for the currently active tab. When switching **connection** or **tab**, messages are automatically reloaded from localStorage for that connection+tab. A `pendingContextChangeRef` guard in PromptPanel prevents the save effects from writing the previous connection's messages/chatId to the new key during the same commit (see `PromptPanel.js`). The `sendingTabsRef` Set tracks which tabs are currently sending requests, allowing concurrent requests from different tabs.
- **localStorage Persistence**:
  - `dashboard-store`: Zustand persisted state (activeTab, connectionId) - auto-managed by Zustand persist middleware
  - `selectedConnectionId`: Currently selected database connection (persists across page reloads)
  - `dashboard-{connectionId}`: Dashboard configuration per connection
  - `chat-{connectionId}-{tab}`: Chat history per connection and tab
  - `chatId-{connectionId}-{tab}`: Chat IDs per connection and tab
  - `authToken`: JWT authentication token
  - `username`: Logged-in username
  - `userRole`: User's role (VIEWER, EDITOR, ADMIN) for RBAC
  - `userPermissions`: JSON array of permission strings for RBAC

**TanStack Query v5 (Server State)**:
- `@tanstack/react-query` v5.90.16 with DevTools
- Query client configured in `src/lib/queryClient.js` (5min staleTime, 10min gcTime)
- **Query Keys** (`src/lib/queryKeys.js`):
  - Factory pattern for consistent, hierarchical keys
  - Supports all 29 API domains: connections, brain, training, slowQueries, etc.
- **Helper Hooks** (`src/lib/hooks/`):
  - `useApiQuery.js`: Generic query/mutation wrappers with connection-based enabling
  - `usePollingQuery.js`: Auto-refresh queries with configurable intervals
  - `useStreamingQuery.js`: SSE integration for training status
  - `useParallelQueries.js`: Replace Promise.all patterns
- **Domain Hooks** (`src/lib/hooks/queries/`):
  - `useConnections.js`: Connection CRUD operations
  - `useBrain.js`: 20+ hooks for brain features (understanding, key columns, schema classification, etc.)
  - `useTraining.js`: Training status, history, streaming
  - `useActiveQueries.js`: Active query monitoring with polling
  - `useSlowQueries.js`: 20+ hooks for slow query features (includes `useCachedOptimizations`, `useBatchOptimize`)
  - `usePerformanceActions.js`: Performance action CRUD, summary, refresh (ROI-based recommendations)
  - `useGrowthMonitoring.js`, `usePlaybooks.js`, `usePerformance.js`, `useSentinel.js`, `useIndex.js`, `useSavedItems.js`
- **Usage Pattern**:
  ```javascript
  import { useConnections, useActiveQueries } from '@/lib/hooks/queries'

  // Fetch connections (auto-cached)
  const { data: connections, isLoading } = useConnections()

  // Polling example (auto-refresh every 3s)
  const { data: queries } = useActiveQueries(connectionId, { autoRefresh: true, interval: 3000 })

  // Mutations with auto-invalidation
  const { mutate: saveConnection } = useSaveConnection()
  ```
- **Migration Status**: Foundation complete, Wave 1 components migrated (PromptPanel, ActiveQueryTab). Remaining components can be migrated incrementally.

**Component Structure**:
- `src/pages/Home.jsx` - Main layout with resizable panels
- `src/components/PromptPanel.js` - Left sidebar (chat, connections, query results)
- `src/components/Workspace.js` - Main content area with tab switching
- `src/components/tabs/` - 40+ specialized tabs organized by category:
  - Core: SqlRunnerTab, PreviewTab, TablesOverviewTab
  - Analysis: SlowQueryAnalysisTab, ExplainPlanTab, QueryPerformanceTab
  - Brain: 35+ subcomponents for database intelligence
  - Monitoring: GrowthMonitoringTab, ActiveQueryTab, PerformanceInsightsTab
  - Admin: PlaybooksTab, ConfigurationTunerTab, IndexRecommendationsTab

**Key UI Libraries**:
- Monaco Editor for SQL editing
- Recharts for data visualization
- React-window for virtual scrolling
- React-resizable-panels for split views
- React-markdown for formatted output
- React-force-graph-2d for dependency graphs
- React-force-graph-3d for 3D ER diagram (Brain Overview)

### UX Style Guidelines

**Design Philosophy:**
- **Sleek & Modern**: Clean, minimalist interface with focus on content
- **Lovable**: Delightful micro-interactions and thoughtful details
- **Minimal Chrome**: Reduce UI chrome to maximize content space
- **Smooth Transitions**: All interactions should feel fluid and responsive

**Typography:**
- **Primary Font**: Inter (Google Fonts or system fallback)
- **Font Stack**: `'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Roboto', 'Oxygen', 'Ubuntu', 'Cantarell', sans-serif`
- **Base Font Size**: 16px (1rem) for body text
- **Font Weights**: 
  - Regular (400) for body text
  - Medium (500) for emphasis
  - Semibold (600) for headings and labels
  - Bold (700) for strong emphasis (use sparingly)
- **Line Height**: 1.5 for body text, 1.2-1.3 for headings
- **Letter Spacing**: Default for body, slightly tighter (-0.01em to -0.02em) for headings

**Color Palette:**
- **Primary Background**: `#FFFFFF` (white)
- **Secondary Background**: `#F9FAFB` (very light grey)
- **Tertiary Background**: `#F3F4F6` (light grey)
- **Primary Text**: `#111827` (near black)
- **Secondary Text**: `#6B7280` (medium grey)
- **Tertiary Text**: `#9CA3AF` (light grey)
- **Borders/Dividers**: `#E5E7EB` (light grey)
- **Hover States**: `#F3F4F6` (light grey background)
- **Active/Focus**: `#111827` with subtle outline or background change
- **Accent/Interactive**: `#111827` (black) for primary actions, `#6B7280` for secondary
- **Error States**: `#DC2626` (red) - use sparingly
- **Success States**: `#059669` (green) - use sparingly
- **Avoid**: Color accents beyond black/white/grey unless absolutely necessary

**Spacing & Layout:**
- **Base Unit**: 4px (0.25rem)
- **Spacing Scale**: 4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 80, 96px
- **Container Padding**: 16-24px for mobile, 24-32px for desktop
- **Component Padding**: 12-16px for compact, 16-20px for comfortable
- **Border Radius**: 
  - Small: 4-6px (buttons, badges)
  - Medium: 8px (cards, inputs)
  - Large: 12px (modals, large cards)
- **Max Content Width**: 1200-1400px for main content areas
- **Grid Gaps**: 16-24px between grid items

**Transitions & Animations:**
- **Duration**: 
  - Fast: 150ms (hover states, quick feedback)
  - Normal: 200-300ms (most interactions)
  - Slow: 400-500ms (page transitions, complex animations)
- **Easing**: 
  - Default: `cubic-bezier(0.4, 0, 0.2, 1)` (ease-in-out)
  - Smooth: `cubic-bezier(0.25, 0.46, 0.45, 0.94)` (ease-out)
  - Snappy: `cubic-bezier(0.4, 0, 1, 1)` (ease-in)
- **Properties to Animate**: `transform`, `opacity`, `background-color`, `border-color`
- **Avoid Animating**: `width`, `height`, `top`, `left` (use `transform` instead)
- **Hover States**: Subtle scale (1.01-1.02) or background color change
- **Loading States**: Smooth skeleton screens or subtle pulse animations

**Component Patterns:**

**Buttons:**
- Primary: Black background (`#111827`), white text, 8px border radius
- Secondary: Transparent/white background, black text, grey border
- Minimal padding: 10-12px vertical, 16-20px horizontal
- Smooth hover transition (200ms)
- Disabled: 40% opacity

**Cards:**
- White background, subtle border (`#E5E7EB`)
- 8-12px border radius
- Padding: 16-24px
- Subtle shadow on hover: `0 1px 3px rgba(0, 0, 0, 0.1)`
- Smooth transition on hover

**Inputs:**
- Minimal border: 1px solid `#E5E7EB`
- Border radius: 6-8px
- Padding: 10-12px vertical, 12-16px horizontal
- Focus: Border color change to `#111827` with smooth transition
- Placeholder: `#9CA3AF` (tertiary text color)

**Tables:**
- Minimal borders: 1px solid `#E5E7EB` between rows
- Row hover: `#F9FAFB` background
- Header: Semibold (600), `#6B7280` text
- Cell padding: 12-16px
- Striped rows: Optional, use `#F9FAFB` for even rows

**Modals/Dialogs:**
- Backdrop: `rgba(0, 0, 0, 0.5)` with smooth fade-in
- Content: White background, 12px border radius
- Padding: 24-32px
- Smooth scale + fade animation (300ms)
- Max width: 500-600px for standard modals

**Icons:**
- Size: 16px (small), 20px (medium), 24px (large)
- Color: Inherit from parent or use `#6B7280` for secondary icons
- Stroke width: 1.5-2px for outline icons
- Smooth color transition on hover

**Badges/Tags:**
- Small border radius: 4-6px
- Padding: 4-6px vertical, 8-12px horizontal
- Background: `#F3F4F6`, text: `#111827`
- Font size: 12-14px
- Font weight: Medium (500)

**Tooltips (IMPORTANT - Use Rich Tooltips):**
- **ALWAYS use HelpTooltip component** from `src/components/tabs/Brain/components/HelpTooltip.js` for tooltips
- **NEVER use plain `title` attribute** - it provides poor UX with limited formatting
- **Define help content in `src/components/tabs/Brain/utils/helpText.js`** - centralized, reusable, consistent
- Rich tooltip content structure:
  ```javascript
  {
    title: 'Feature Name',           // Required: Term or feature name
    description: 'What it does...',  // Required: Main explanation
    impact: 'Effect on performance', // Optional: Performance/behavior impact
    recommendation: 'Best practice', // Optional: What to do
    optimization: 'How to optimize', // Optional: Optimization tips
    examples: 'code or values'       // Optional: Code examples
  }
  ```
- Usage pattern:
  ```jsx
  import { HelpTooltip } from './components/HelpTooltip'
  import { FEATURE_HELP } from './utils/helpText'

  <HelpTooltip content={FEATURE_HELP.buttonName}>
    <button>Action</button>
  </HelpTooltip>
  ```
- Visual style (handled by HelpTooltip component):
  - Dark background: `#1f2937`
  - White text with grey description
  - 6px border radius
  - Sections with dividers for impact/recommendation
  - Smooth fade-in animation (150ms)
  - Fixed positioning via portal (avoids overflow issues)

**Loading States:**
- Skeleton screens: `#F3F4F6` background with subtle pulse
- Spinners: Minimal, use Inter font or simple geometric shapes
- Progress bars: Thin (2-3px), black background
- Avoid flashy animations; keep it subtle

**Accessibility:**
- **Contrast**: Maintain WCAG AA contrast ratios (4.5:1 for text)
- **Focus States**: Visible outline (2px solid `#111827` with offset)
- **Keyboard Navigation**: All interactive elements must be keyboard accessible
- **Screen Readers**: Proper ARIA labels and semantic HTML
- **Motion**: Respect `prefers-reduced-motion` media query
- **Touch Targets**: Minimum 44x44px for mobile interactions

**Implementation Notes:**
- Use Tailwind CSS utility classes aligned with these guidelines
- Create reusable component variants (e.g., `Button`, `Card`, `Input`) that enforce these patterns
- Avoid inline styles; use Tailwind classes or CSS modules
- Keep component styles co-located with components when possible
- Use CSS variables for colors if custom styling is needed: `--color-primary: #111827`
- Test transitions on lower-end devices to ensure smoothness

**Anti-Patterns to Avoid:**
- ❌ Heavy shadows or excessive depth
- ❌ Bright colors or gradients (stick to black/white/grey)
- ❌ Abrupt state changes (always use transitions)
- ❌ Cluttered interfaces (maintain minimal chrome)
- ❌ Inconsistent spacing (use the spacing scale)
- ❌ Multiple font families (stick to Inter)
- ❌ Over-animated elements (keep animations subtle and purposeful)
- ❌ Plain `title` attributes for tooltips (use HelpTooltip component instead)

### Key Integration Points

**Schema ER Diagram & Inferred Relationships:**
- `GET /api/connections/{connectionId}/visualization` returns ER diagram and dependency graph.
- **VisualizationService** builds entities from schema scan and relationships from:
  1. **Foreign keys** from schema metadata (explicit DB relationships).
  2. **Inferred relationships** from slow query JOINs, stored in `inferred_table_relationship` (indexed by connection_id for fast loading).
- Inferred data is populated by **JoinRelationshipInferenceService** (Brain → Inferred Relationships → Analyze), which parses QueryLineage and SlowQueryHistory SQL with JSQLParser, extracts JOINs, and persists them. Run "Analyze inferred relationships" after slow query ingestion so the ER diagram shows both FK and JOIN-inferred links.

**Chat Query Flow:**
```
User Message
  ↓
ChatController.chat()
  ↓
ChatService.processMessage()
  ├─ SchemaScannerService.scanSchema()           [DB metadata]
  ├─ TrainingService.cachedRetrieveRelevant()    [RAG examples]
  ├─ buildClassificationContext()                [Schema Intelligence]
  │   ├─ SchemaClassificationService.getLatestClassification()
  │   └─ TableClassificationRepository (roles, access patterns, anti-patterns)
  ├─ buildPerformanceInsightsContext()           [Performance Intelligence]
  │   ├─ SlowQueryHistoryRepository (slow query health)
  │   ├─ QueryPerformanceRegressionRepository (degraded queries)
  │   ├─ IndexRecommendationRepository (missing indexes)
  │   ├─ KeyColumnAnalysisRepository (key columns for filtering/joining)
  │   ├─ InferredTableRelationshipRepository (JOIN paths)
  │   └─ GrowthAnomalyRepository (tables with growth issues)
  ├─ buildBrainContext()                         [Brain ML Intelligence]
  │   ├─ WorkloadProfileRepository (workload type, metrics, recommendations)
  │   ├─ KnobRankingRepository (tunable parameters by impact)
  │   ├─ ColumnStatisticsRepository (skipped if Key Column data exists - avoids redundancy)
  │   └─ PlanPatternRepository (query optimization patterns)
  └─ Builds system prompt with database rules + all insights
  ↓
OpenAIClient.chat() [Azure OpenAI: gpt-5.4-pro]
  ↓
Extract SQL from markdown code blocks
  ↓
QueryExecutorService.execute()                   [Query execution]
  ↓
Results → ChatResponse → User display
```

**Classification Context Includes:**
- Schema design pattern (STAR, SNOWFLAKE, HYBRID)
- Table role groupings (FACT, DIMENSION, BRIDGE, LOOKUP, EVENT_LOG, ORPHANED)
- Per-table insights: access patterns, health scores, anti-patterns, sensitivity, business domain
- Query optimization hints: read-heavy tables, write-heavy tables, large tables, critical issues

**Performance Insights Context Includes:**
- Slow query health status and counts (CRITICAL, HIGH severity)
- Performance regressions (queries that degraded with slowdown %)
- Index recommendations (columns that need indexes)
- Key columns ranked by importance (best for WHERE/JOIN)
- Inferred JOIN paths with confidence scores
- Growth anomalies (tables needing LIMIT/pagination)

**Brain ML Context Includes (lazy-loaded for tuning/optimization questions):**
- Workload characterization (OLTP/OLAP/MIXED/READ_HEAVY/WRITE_HEAVY) with confidence %
- Performance metrics (throughput QPS, P50/P99 latency)
- Workload-specific optimization recommendations
- Top tunable configuration parameters ranked by impact score
- Column statistics with cardinality (highly selective vs low-cardinality columns)
- Reliable query plan patterns with proven optimization suggestions

**Playbook Execution Flow:**
```
User/Scheduler Trigger
  ↓
PlaybookExecutionService creates PlaybookRun (RUNNING)
  ↓
For each PlaybookStep:
  PlaybookToolsService.executeTool()
  ↓
  Captures results → Findings
  ↓
  Apply thresholds → Recommendations
  ↓
Update PlaybookRun (COMPLETED)
  ↓
If alerts needed: PlaybookAlert creation + Notification dispatch
```

**RAG Pipeline (Dual-Path Architecture):**

The system supports two RAG approaches that can run independently or together:

1. **Manual RAG (Primary)** - Custom implementation with full features:
   - Hybrid search (vector + BM25 keyword scoring)
   - Connection-specific filtering (ODATA: `connectionId eq '{id}'`)
   - Document type filtering (see table below)
   - Multi-tier caching (Redis → Memory → Database)
   - Query success/execution time weighting

2. **QuestionAnswerAdvisor (Optional)** - Spring AI's automatic RAG:
   - Pure vector similarity search via VectorStore
   - Automatic context injection via advisor pattern
   - Simpler but less feature-rich

**RAG Document Types:**
| Type | Description | Source |
|------|-------------|--------|
| `SCHEMA_DDL` | Table DDL (CREATE TABLE statements) | TrainingService on schema training |
| `QUERY_EXAMPLE` | Sample SQL queries with natural language | Manual training examples |
| `DOCUMENTATION` | Custom documentation about schema/domain | Manual training |
| `COLUMN_VALUES` | Low-cardinality column values for filtering | ColumnValueCollectionService |
| `QUERY_PATTERN` | Reliable query plan patterns with optimization tips | BrainInsightEmbeddingService |
| `WORKLOAD_INSIGHT` | Workload characterization and tuning recommendations | BrainInsightEmbeddingService |
| `CARDINALITY_INSIGHT` | Cardinality accuracy recommendations (ANALYZE TABLE, histograms) | BrainInsightEmbeddingService |

**Brain Insight Embedding:**
- `BrainInsightEmbeddingService` embeds Brain insights into Azure AI Search for semantic retrieval
- Endpoint: `POST /api/brain/insights/{connectionId}/embed` - Embeds all insights
- Individual endpoints: `/embed/patterns`, `/embed/workload`, `/embed/cardinality`
- Enables chat to find relevant optimization suggestions when users ask about tuning

```
Manual RAG Flow:
Schema Analysis → DDL Generation → Embedding Creation (Azure OpenAI)
  ↓
Azure AI Search Storage (persistent vector DB)
  ↓
TrainingService.cachedRetrieveRelevant() → Hybrid Search (Vector + Keyword)
  ↓
trainingContext injected into system prompt

QuestionAnswerAdvisor Flow (when enabled):
User Question → QuestionAnswerAdvisor.before()
  ↓
VectorStore.similaritySearch() → Top-K documents
  ↓
Auto-injected into prompt by advisor
```

**Configuration:**
```properties
# Enable QuestionAnswerAdvisor (default: false, manual RAG is primary)
spring.ai.rag.advisor.enabled=false

# Enable comparison mode to log both approaches (for A/B testing)
spring.ai.rag.advisor.comparison-mode=false

# RAG parameters
spring.ai.rag.top-k=10
spring.ai.rag.similarity-threshold=0.7
```

**Slow Query Ingestion Flow:**
```
Manual Trigger or Auto-Scheduler
  ↓
SlowLogIngestionService.ingestNow() or IngestionJobService (async)
  ↓
Fetch logs based on provider type:
  ├── S3LogFetchService (AWS S3)
  ├── CloudWatchLogFetchService (AWS CloudWatch)
  ├── AzureBlobLogFetchService (Azure Blob Storage)
  ├── GcpCloudLoggingFetchService (GCP Cloud Logging)
  ├── DatadogLogFetchService (Datadog APM)
  └── ElasticsearchLogFetchService (Elasticsearch/ELK)
  ↓
SlowQueryLogParserService.parseAndAnalyze()
  ↓
SlowQueryHistoryService.saveAnalysis()
  ↓
QueryPerformanceService.recordQueryExecution() [for regression detection]
  ↓
QueryFingerprintService.processAnalysis() [auto-fingerprint tracking]
  ↓
SlowQueryAlertService.processAnalysisForAlerts() [auto-alert creation]
  ↓
Update SlowLogSourceConfig (lastProcessedAt, nextScheduledRunAt)
```

**Brain Score Calculation:**
```
BrainScoreService.calculateBrainScore()
  ↓
├─ Schema Design Score (25%) - FK constraints, normalization
├─ Query Quality Score (25%) - Slow queries, regressions, severity
├─ Index & Access Score (30%) - Key column coverage, anti-patterns
└─ Scalability Score (20%) - Large tables, partitioning readiness
  ↓
Weighted aggregate → Overall Brain Score (0-100)
  ↓
Stored in BrainScore entity with detailed breakdown JSON
```

**AI Query Optimization Flow:**
```
User selects slow query → SlowQueryController.optimizeQuery()
  ↓
QueryOptimizationService.optimizeQuery()
  ├─ Get database context (connection type, schema)
  ├─ Run EXPLAIN if SELECT query
  ├─ Build prompt with: query stats, schema context, EXPLAIN results
  ↓
Azure OpenAI (gpt-5.4-pro) analysis
  ↓
Parse AI response → OptimizationResult
  ├─ Optimized query (rewritten)
  ├─ Suggestions (categorized: QUERY_REWRITE, INDEX, SCHEMA, CONFIG)
  ├─ Index recommendations
  └─ Estimated improvement percentage
```

**Query Fingerprint Tracking Flow:**
```
SlowQueryAnalysis completed → QueryFingerprintService.processAnalysis()
  ↓
For each slow query:
  ├─ Normalize query (replace literals with ?)
  ├─ Generate SHA-256 fingerprint hash
  ├─ Find or create QueryFingerprint entity
  ├─ Update current metrics from slow query
  ├─ Add to performance history (last 50 data points)
  └─ Calculate trend (compare current vs baseline)
  ↓
Store updated fingerprints with trend analysis
  ↓
Detect regressions (>50% degradation triggers alert)
```

**Slow Query Alert Notification Flow:**
```
SlowQueryAlertService.processAnalysisForAlerts()
  ↓
For each slow query exceeding thresholds:
  ├─ CRITICAL (>5s avg): Create individual alert
  └─ HIGH (>1s avg): Track for summary
  ↓
Create summary alert if critical count > threshold
  ↓
SlowQueryAlertService.sendNotifications(alert, config)
  ├─ Email: EmailService.sendSlowQueryAlert() → HTML formatted email
  ├─ Slack/Webhook: WebhookService.sendSlowQueryAlert() → Block Kit formatted
  └─ Browser: PlaybookAlert stored for UI display
  ↓
Update alert.channelsSent list
```

**Enhanced Schema Classification Flow:**
```
SchemaClassificationService.classifySchema(connectionId)
  ↓
For each table in schema:
  ├─ AccessPatternClassificationService.classifyAccessPattern()
  │   → Analyzes pg_stat_user_tables read/write stats
  ├─ AntiPatternDetectionService.detectAntiPatterns()
  │   → Checks 10 anti-patterns with severity scoring
  ├─ TemporalClassificationService.classifyTemporalType()
  │   → Identifies time-series, SCD, audit patterns
  ├─ TableHealthScoreService.calculateHealthScore()
  │   → Computes 5-component health score (0-100)
  ├─ BusinessDomainClassificationService.classifyDomain()
  │   → Matches table to business domain
  ├─ DataSensitivityClassificationService.classifySensitivity()
  │   → Detects PII, financial, health data columns
  └─ PartitionReadinessService.assessPartitionReadiness()
      → Evaluates partitioning suitability
  ↓
RelationshipClassificationService.classifyRelationships()
  ├─ Analyze FK constraints (STRONG)
  ├─ Detect naming patterns (INFERRED)
  └─ Check join patterns from slow queries (WEAK)
  ↓
Store TableClassification + TableRelationshipClassification
  ↓
Update SchemaClassification aggregates
```

## Testing

### Backend Tests

**Test Structure:**
- Base class: `backend/src/test/java/com/dbaagent/integration/BaseIntegrationTest.java`
- Uses `@SpringBootTest` with `@ActiveProfiles("test")`
- Test configuration: `backend/src/test/resources/application-test.properties`

**Test Coverage:**
- ConnectionController: 6 tests
- GrowthMonitoringController: 8 tests
- SlowQueryController: 19 tests (6 original + 13 slow query improvements)
- BrainController: 11 tests
- ChatPromptIntegrationTest: 20 tests (chat accuracy and speed)
- Unit tests: SlowQueryLogParserService, S3LogFetchService, KeyColumnAnalysisScoringTest, JoinRelationshipInferenceServiceTest
- **Total: 64 integration tests**

**Important:** Integration tests use real database connections (no mocking). Update `TEST_CONNECTION_ID` in `application-test.properties` with a valid connection ID from your database.

### Chat Prompt Integration Tests

**Test File:** `backend/src/test/java/com/dbaagent/integration/ChatPromptIntegrationTest.java`

Tests DBA chat prompts for accuracy and response speed. Categories:

| Category | Tests | Description | Target Speed |
|----------|-------|-------------|--------------|
| Schema Questions | 5 | Table count, largest tables, overview, database type | < 500ms (fast-path) |
| Slow Query Questions | 3 | Slowest query, top N queries, performance health | < 500ms (fast-path) |
| Index Recommendations | 2 | Missing indexes, index suggestions | < 500ms (fast-path) |
| Workload Questions | 2 | Workload type, OLTP vs OLAP | < 500ms (fast-path) |
| LLM-Routed Questions | 4 | DB version, table-specific indexes, temporal queries, SQL generation | < 30s (LLM) |
| Edge Cases | 3 | Empty message, missing connection, long question | N/A (error handling) |
| Performance Benchmarks | 1 | Batch fast-path benchmark with assertions | < 500ms avg |

**Running Chat Prompt Tests:**
```bash
# Run all chat prompt tests
mvn test -Dtest=ChatPromptIntegrationTest

# Run specific test by name
mvn test -Dtest=ChatPromptIntegrationTest#testTableCount
mvn test -Dtest=ChatPromptIntegrationTest#testFastPathBatch

# Run with verbose output
mvn test -Dtest=ChatPromptIntegrationTest -DtrimStackTrace=false
```

**Test Configuration:**
- Requires valid `TEST_CONNECTION_ID` for any database connection (MySQL, PostgreSQL, etc.)
- Set in `application-test.properties` or as environment variable
- Fast-path tests validate response time < 500ms (includes test overhead)
- LLM tests validate response time < 30s
- Tests assert `response.isSuccess()` for accuracy validation
- Tests check for expected keywords in responses

**Performance Thresholds:**
- `FAST_PATH_THRESHOLD_MS = 500` - Fast-path questions should complete under 500ms
- `LLM_THRESHOLD_MS = 30000` - LLM-routed questions should complete under 30s

**Test Output:**
Tests print timing and response details to stdout for debugging:
```
=== TEST: How many tables? ===
Duration: 45ms
Response: ### Database Information...
>>> FAST-PATH response detected
```

### Frontend Tests

- ESLint configured for code quality: `npm run lint`
- Manual testing via Vite dev server

## Configuration

### Environment Variables

**Required for Backend:**
```bash
# Vault database (PostgreSQL)
DB_URL=jdbc:postgresql://localhost:5432/dba_agent
DB_USERNAME=postgres
DB_PASSWORD=postgres

# Security
SECURITY_JWT_SECRET=<your-secret>
ADMIN_BOOTSTRAP_SECRET=<bootstrap-secret>

# LLM — bring your own. Read by LlmConfigResolver from the environment.
# PROVIDER gates the bundle: unset, no other DEEPSQL_CHAT_*/DEEPSQL_EMBEDDING_*
# value is read. `openai` is the only id shipped and covers OpenAI, Azure OpenAI
# and any OpenAI-compatible server. None of these is needed to boot.
DEEPSQL_CHAT_PROVIDER=openai
DEEPSQL_CHAT_API_KEY=<key>
DEEPSQL_CHAT_ENDPOINT=https://api.openai.com/v1   # no working fallback — set it
DEEPSQL_CHAT_MODEL=gpt-4o
DEEPSQL_EMBEDDING_PROVIDER=openai
DEEPSQL_EMBEDDING_API_KEY=<key>
DEEPSQL_EMBEDDING_ENDPOINT=https://api.openai.com/v1   # defaulted
DEEPSQL_EMBEDDING_MODEL=text-embedding-3-large         # defaulted
# Optional: DEEPSQL_CHAT_TEMPERATURE, DEEPSQL_CHAT_API_VERSION,
#           DEEPSQL_CHAT_USE_RESPONSES_API (true|false|auto)

# /api/llm/v1 — the OpenAI-shaped gateway the DeepSQL CLI agent points at — takes no
# variables of its own. LlmProxyController resolves through LlmConfigResolver.resolveChat()
# and dispatches auth on OpenAiEndpoints.isAzure(endpoint), so the DEEPSQL_CHAT_* bundle
# above configures it too. AZURE_OPENAI_* is read by no code at all.

# Azure AI Search (RAG)
azure.search.api-key=<key>
azure.search.endpoint=https://<resource>.search.windows.net
azure.search.index-name=dba-agent-training-data

# Redis/Valkey (caching)
spring.data.redis.host=localhost
spring.data.redis.port=6379

# Email notifications (optional)
EMAIL_HOST=smtp.gmail.com
EMAIL_USERNAME=<email>
EMAIL_PASSWORD=<password>

# Credential encryption
ENCRYPTION_KEY=<32-char-key>
```

**Application Properties:**
- Development: `backend/src/main/resources/application.properties` (auth disabled)
- Production: `backend/src/main/resources/application-prod.properties` (auth enabled)
- Test: `backend/src/test/resources/application-test.properties`

**Feature Flags:**
- `security.auth.enabled=false` - Disable auth for development
- `growth-monitoring.enabled=true` - Enable growth monitoring
- `azure.search.enabled=true` - Enable RAG with Azure Search
- `slow-query.lineage.backfill.enabled=true` - Enable slow query lineage

### Frontend Configuration

Environment-based API URL:
- Development: `http://localhost:8080/api` (via Vite proxy)
- Production: Set via `VITE_API_URL` environment variable

Build modes:
- `npm run build` - Development build
- `npm run build:production` - Production build with production API URL
- `npm run build:aws-production` - AWS-specific production build

## Database Schema

**JPA/Hibernate Configuration:**
- Dialect: PostgreSQLDialect
- DDL Mode: `update` (auto-migrate)
- Connection Pool: HikariCP (75 max, 15 min idle)

**Key Entities:**
- DatabaseConnection - Database credentials with SSH tunnel config (encrypted at rest)
- Playbook, PlaybookRun, PlaybookAlert - Automation framework
- TrainingDataEmbedding - Cached RAG embeddings
- SchemaMetadata - Schema snapshots
- SlowQuery, SlowQueryHistory, QueryPerformanceHistory - Query performance tracking
- QueryPerformanceRegression - Performance regression detection
- QueryFingerprint - Normalized query pattern tracking with trend analysis
- SlowLogSourceConfig, IngestionJob - Slow query log ingestion
- BrainTask, BrainScore - Database intelligence features
- GrowthAlertConfiguration - Growth monitoring rules
- ChatFeedback - User feedback on AI responses (corrections, teachings, thumbs up/down) for cross-session memory
- ColumnValueCache - Cached low-cardinality column values for RAG embedding
- IndexRecommendation - DBA-grade index suggestions backed by a multi-source workload-weighted pipeline. Sources: JSQLParser-AST slow-query analysis (`Σ calls × mean_exec_time` per role-tagged column, the pganalyze "total time" ROI metric); parsed `ExplainPlanNode` walks (Seq Scan / Full Table Scan / Nested Loop / filesort findings — replaces dead regex); inferred JOIN relationships (`InferredTableRelationship.joinCount`); composite co-occurrence (`CompositeIndexRecommendation`); column anti-patterns with persisted `totalExecutionTimeMs` (`ColumnAntiPattern`); FK + schema-walk hints; partial-index hints when `KeyColumnAnalysis.skewCoefficient > 0.7`; redundant-prefix DROP candidates (`getDuplicateIndexes`); unused-index DROP with `pg_stat_database.stats_reset` guard. Composite column ordering follows industry rules — equality before range, selectivity-ranked, ORDER BY suffix only with full-equality prefix, 3-column cap (see `CompositeIndexPlanner`). Per-candidate fields: `occurrenceCount`/`firstSeenAt`/`lastSeenAt` (cycle recurrence), `workloadScoreMs` (total query time saved), `writeCostScore` (write-amplification penalty from `pg_stat_user_tables.n_tup_*`), `evidenceCount`, `kind` (CREATE_INDEX / DROP_INDEX). The 6-hour scheduler accumulates evidence across the configured lookback (default 30d) instead of wiping; ≥3-cycle recurrence auto-promotes to HIGH priority; rows not re-observed within staleness-days (default 14) are aged out. `IndexRecommendationService.getTopRecommendationsWithEvidence(connectionId, limit)` powers the `GET /index-recommendations/{id}/top` endpoint and the `get_index_recommendations` MCP tool, ranked by `priority → (workloadScoreMs − writeCostScore) → occurrenceCount → lastSeenAt`. Each top-N row carries up to 5 `topEvidence` records from the V96 `index_recommendation_evidence` table — fingerprint, calls, mean/total exec time, rows examined, role — so callers can audit *why* each recommendation exists. A stubbed `HypotheticalCostEstimator` interface is in place for a future HypoPG-backed real-cost-delta validator (Postgres only). Configurable via `index-recommendations.lookback-days`, `index-recommendations.staleness-days`, `index-recommendations.composite.min-workload-ms` (default 60000), `index-recommendations.composite.max-columns` (default 3). See V93 (recurrence), V94 (kind), V95 (workload-score columns), V96 (evidence table) migrations.
- SavedQuery, SavedDashboard - User persistence
- TableClassification, SchemaClassification - Enhanced schema analysis with access patterns, anti-patterns, health scores, domains, sensitivity, partitioning
- TableRelationshipClassification - Relationship strength, data integrity metrics, join frequency tracking
- InviteCode - Invite codes for private beta signup (code, max_uses, current_uses, expires_at, active)
- User - Extended with invite_code_id and invited_at for tracking signups

**SQL Changelog (`db/migration/`) — NOT executed:**

> ⚠️ **This project has no Flyway.** `backend/pom.xml` declares no `flyway-core`
> dependency and `mvn dependency:list` finds no `org.flywaydb` artifact, so nothing in
> `backend/src/main/resources/db/migration/` runs at startup. Schema is managed by
> `spring.jpa.hibernate.ddl-auto=update`, backstopped by `SchemaColumnCompatibilityInitializer`,
> `BrainInitSchemaCompatibilityInitializer` and `PgVectorRagStoreInitializer` for the cases
> Hibernate cannot express. The directory is a **hand-maintained changelog**: it carries
> duplicate version numbers (V31, V63, V103) that a real Flyway runtime would refuse to
> start on, which is itself proof it never ran. Apply anything here by hand with `psql`.
> Files now run to V109.

- Located in `backend/src/main/resources/db/migration/`
- V5: Playbook tables
- V6: Growth monitoring tables
- V7: Sentinel analytics tables
- V8-V27: Brain features, key columns, scalability simulation
- V28: Inferred table relationships
- V29: Enhanced table classification (access patterns, anti-patterns, temporal, health scores, business domains, data sensitivity, partition readiness, relationship classification)
- V30: Query fingerprints table for trend analysis
- V31-V34: Multi-provider slow log sources, schema change tracking, query plan cache, advanced table classification
- V35: SSH tunnel support for database connections (sshEnabled, sshAuthType, encrypted SSH credentials)
- V36: Spring AI feedback and column value tables (chat_feedback, column_value_cache)
- V37: Chat feedback additional columns (table_name, column_name, user_message, sql, feedback_text)
- V38: Invite codes table for private beta registration
- V39: Roles and permissions tables for RBAC
- V40: Cloud provider context fields (cloudProvider, managedService) for feature gating
- V38: Invite code system for private beta (invite_codes table, users.invite_code_id)
- V39: Role-Based Access Control (users.role column, role_permissions table)

## Important Development Notes

### Backend

1. **Mandatory SQL Rule**: All generated SQL queries MUST use table-qualified column names (table.column_name or alias.column_name). This is enforced in ChatService system prompts.

2. **Connection Pool Management**: Each database connection maintains its own HikariCP pool. Never close pools manually; ConnectionService handles lifecycle.

3. **SSH-Aware Database Access**: Always use `ConnectionService.getJdbcTemplate(connectionId, request)` when services need to execute queries. This method:
   - Automatically handles SSH tunneling for connections with `sshEnabled=true`
   - Works seamlessly for direct connections (no SSH)
   - Ensures connection pools are properly managed
   - **Do NOT** create `DriverManagerDataSource` or direct JDBC connections - they bypass SSH tunnels

4. **RAG Caching**: Three-tier cache (memory → Redis → Azure Search). Clear cache when updating training data via TrainingService.

5. **Redis Graceful Degradation**: The application continues working even when Redis is unavailable. `CacheConfig` provides:
   - Custom `CacheErrorHandler` that logs cache operation failures without throwing
   - Wrapper `CacheManager` that returns null instead of throwing when `getCache()` fails
   - All services using caching will work without Redis (just skip caching)

6. **Redis Cluster Mode (Azure)**: `RedisConfig` provides Azure Redis cluster configuration:
   - Activated when `spring.data.redis.cluster.nodes` is set
   - Configures SSL, cluster topology refresh, and auto-reconnect
   - Required for Azure Cache for Redis in cluster mode

7. **Virtual Threads**: Enabled via Spring Boot 3.2+ configuration for better concurrency with JDK 25.

8. **CLI Components Excluded**: The `com.dbaagent.cli.*` package is excluded from component scanning (see DbaAgentApplication.java).

9. **LLM Provider Registry**: For provider-specific LLM behavior, use `LlmProviderRegistry`:
   - **Do NOT** add `if/else` or `switch` on provider type. The shipped
     `OpenAiCompatibleChatProvider` dispatches on **endpoint shape** (an `.azure.com` /
     `.azure-api.net` base URL selects Azure's `api-key` header auth), not on a provider id
   - Chat and embedding providers are indexed independently — some providers offer only one
   - Providers are *factories over credentials*, not `ChatModel`s, so credentials resolve
     per call and key rotation needs no restart
   - Configuration is `DEEPSQL_CHAT_*` / `DEEPSQL_EMBEDDING_*` (see **LLM Providers**).
     The per-service `azure.openai.*-deployment` properties are gone: `chat-deployment`,
     `codex-deployment` and `embedding-deployment` are read by no code at all
   - ⚠️ The onboarding wizard (`SetupController`) writes a flat pre-BYO key namespace that
     `LlmConfigResolver` does not read, so **it does not configure the LLM**. Environment
     variables are the only working path today

10. **Database Provider Registry**: For database-specific operations, use the provider registry pattern:
    - **Do NOT** add `if/else` or `switch` statements for database types in services
    - Inject `DatabaseProviderRegistry` and call `registry.getDialect(dbType).provider().method()`
    - Example: `registry.getDialect("mysql").connection().buildJdbcUrl(request, port)`
    - All providers are Spring beans supporting future dependency injection
    - See section 6 "Database Provider Registry Pattern" for full architecture

### Frontend

1. **API Centralization**: ALL API calls must go through `src/lib/api/client.js`. Never create direct axios instances.

2. **TanStack Query for Data Fetching**: Use hooks from `@/lib/hooks/queries` for server state. Benefits:
   - Automatic caching and background refetching
   - Loading/error states handled automatically
   - Query invalidation on mutations
   - DevTools available in browser (bottom-right corner)
   - Example: `const { data, isLoading } = useConnections()` instead of manual useState/useEffect

3. **Zustand for UI State**: Use stores from `@/lib/stores` for client-side state:
   - **Preferred**: Use selector hooks for optimized re-renders: `const activeTab = useActiveTab()`
   - **Actions**: Use action hooks to avoid re-renders: `const { setActiveTab } = useDashboardActions()`
   - **Legacy**: `useDashboard()` from DashboardContext still works (delegates to Zustand)
   - **Benefits**: No provider needed, selective subscriptions, built-in persistence middleware
   - **Stores**: `useDashboardStore`, `useConnectionStore`, `useChatStore`, `useUIStore`

4. **Auth State**: Managed via `useAuth` hook with cross-tab logout sync via storage events.

5. **Dashboard Persistence**: Dashboards are stored in localStorage with connection-specific keys. Clear localStorage when testing dashboard features.

6. **Monaco Editor**: Uses `@monaco-editor/react` for SQL editing. Custom SQL syntax highlighting configured.

7. **Tab Management**: Active tab state stored in Zustand (`useDashboardStore`). Use `setActiveTab()` to switch tabs.

6. **Image Upload**: Screenshots sent as base64 in chat messages for AI analysis. Max size: 4MB.

7. **Connection Selection Persistence**: Selected connection ID is stored in localStorage (`selectedConnectionId`) and restored on page reload. Managed in `PromptPanel.js` with validation that the saved connection still exists.

8. **Database Connection Modal** (`DBConfigModal.js`):
   - Database icons use base64 images (configured in `DB_ICONS` object with placeholders)
   - SSH tunnel configuration with collapsible panel
   - Private key file upload support (.pem, .key, .txt files) using FileReader API
   - Cloud provider deployment selector (AWS/Azure/GCP/Self-Hosted) with conditional service type dropdowns

9. **Landing Feature Scroll** (`Landing.jsx`): The features section uses a sticky left illustration column with scroll-driven `activeSection`. Avoid adding overflow constraints to ancestors of the sticky container; keep `lg:sticky lg:top-24` on the illustration wrapper so it stays fixed while the right content scrolls.

10. **Brain Overview Schema ER Diagram** (`SchemaERD3D.js`): Renders at the top of Brain → Overview. Fetches `erDiagram` from `GET /api/connections/{connectionId}/visualization`; relationships include both FK-based (from schema) and **inferred from slow query JOINs** (from persisted `inferred_table_relationship`). Run Brain → Inferred Relationships → Analyze to populate inferred links so the diagram shows connections even when the DB has no FKs. Uses react-force-graph-2d (or 3d) for zoom/pan and node drag; click/hover a table node to show column detail. Includes a **New (24h)** toggle in the schema header (left of the role filter) that highlights recently added entities and relations using `GET /api/schema-changes/{connectionId}/changes` (`TABLE_ADDED`, `FOREIGN_KEY_ADDED` with `detectedAt` within 24 hours). Understanding, Key Columns, and Column Values panels sit below the diagram.

11. **Login Private Beta Request** (`Login.jsx`, `AuthController.java`): Login page includes an inline private beta request flow (Name, Title, Company name, Email). Submissions call `POST /api/auth/beta-signup`, persist to `private_beta_requests`, and the UI shows confirmation: "We have received your private beta request, we will get back to you shortly."

## Common Development Tasks

### Adding a New API Endpoint

1. Create REST controller method in `backend/src/main/java/com/dbaagent/controller/`
2. Implement business logic in corresponding service class
3. Add repository method if database access needed
4. Add API method to relevant module in `src/lib/api/client.js`
5. Update component to call new API method

### Adding a New Tab

1. Create component in `src/components/tabs/`
2. Add tab definition to `ALL_TABS` array in `Workspace.js` with id, icon, label, and permission
3. Add `data-testid={`tab-${tab.id}`}` attribute to tab button for E2E testing
4. Add icon from lucide-react
5. No provider registration needed - Zustand handles tab state automatically

### Adding a New Playbook Tool

1. Add tool definition to `PlaybookToolsService.executeTool()`
2. Implement tool logic (usually calls existing service methods)
3. Define expected parameters and return format
4. Update PlaybookStep model if new parameter types needed
5. Add UI for tool configuration in PlaybooksTab

### Updating RAG Training Data

1. Modify schema or add training examples
2. Call `TrainingService.trainConnectionIfNeeded()` or use /train API endpoint
3. Clear Redis cache: `redis-cli FLUSHALL` (or use Spring Cache eviction)
4. Azure Search index updates automatically via AzureSearchService

### Running Integration Tests

1. Start vault database: `docker compose up -d postgres`
2. Ensure test connection exists in database
3. Update `TEST_CONNECTION_ID` in `backend/src/test/resources/application-test.properties`
4. Run tests: `mvn test -Dtest="*IntegrationTest"`
5. Check results in `backend/target/surefire-reports/`

## Troubleshooting

**Backend won't start:**
- Check PostgreSQL vault database is running
- Verify `ENCRYPTION_KEY` or `ENCRYPTION_KEYS` is set — `EncryptionService` fails fast without it
- Check for port conflicts on 8080
- Note: a **missing LLM credential is not a startup failure**. The backend boots
  unconfigured and raises `LlmNotConfiguredException` when something first asks for a
  model. If it will not start, the LLM config is not the cause.

**Frontend can't connect to backend:**
- Verify backend is running on http://localhost:8080/api
- Check CORS configuration in SecurityConfig.java
- Clear browser localStorage and cookies

**Auth issues:**
- Development: Ensure `security.auth.enabled=false` in application.properties
- Production: Verify JWT secret is set and valid
- Check token expiry (default 10 hours)

**RAG not working:**
- Verify the vector store credentials (Azure AI Search, or pgvector reachability)
- Check if training data exists: call /api/training/status
- Ensure Redis is running for caching
- Verify `DEEPSQL_EMBEDDING_PROVIDER` and `DEEPSQL_EMBEDDING_API_KEY` are set. Without
  `DEEPSQL_EMBEDDING_PROVIDER`, no embedding bundle resolves at all and retrieval stays
  keyword-only. `AZURE_OPENAI_EMBEDDING_DEPLOYMENT` is dead — setting it does nothing.
- If `EMBEDDING_FAIL_OPEN=true` (the default outside the prod profile), embedding
  failures return an empty vector and degrade retrieval **silently**. Set it to `false`
  while debugging so the error surfaces.

**Tests failing:**
- Verify TEST_CONNECTION_ID exists in database
- Check test database connectivity
- Ensure test profile is active (`@ActiveProfiles("test")`)
- Review `application-test.properties` configuration

**SSH tunnel connection issues:**
- Verify SSH host is reachable from the application server
- Check SSH username and authentication credentials
- For private key auth: ensure key is in PEM format (starts with `-----BEGIN`)
- Verify the bastion host can reach the database host
- Check if SSH port (default 22) is open
- Review logs for JSch connection errors

**Brain Analysis showing stale/duplicate data:**
- Hard refresh browser (Cmd+Shift+R on Mac, Ctrl+Shift+R on Windows)
- Clear browser localStorage: `localStorage.clear()` in console
- Run fresh Brain Analysis to regenerate data
- Backend normalizes all table/column names to lowercase; if mixed case appears, it's cached UI data

**Schema Analysis network error:**
- Verify target database connection is accessible
- Check if database host is reachable (especially for localhost connections)
- For local MySQL: ensure MySQL server is running on expected port (default 3306)
- Check connection timeout settings in application.properties
- Use production connection if local database is unavailable
- Review backend logs for connection pool errors

## Documentation

Additional documentation in `docs/` folder:
- `SETUP.md` - Detailed setup instructions
- `QUICKSTART.md` - Quick start guide
- `API_CLIENT_USAGE.md` - Frontend API integration guide
- `RAG_SETUP.md` - RAG configuration details
- `QUICK_WINS_IMPLEMENTATION_GUIDE.md` - Feature implementation guides


<claude-mem-context>
# Recent Activity

<!-- This section is auto-generated by claude-mem. Edit content outside the tags. -->

*No recent activity*
</claude-mem-context>
