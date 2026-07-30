# AGENTS.md

Purpose: Persistent, high‑level understanding of this codebase for automation, refactors, and reviews.

## Project Overview
- Monorepo with a Java backend and a JS/TS frontend.
- Backend (Spring Boot) lives in `backend/` and provides APIs for database analysis, slow‑query ingestion, explain plans, brain intelligence, monitoring, and chat/rag features.
- Frontend lives in `src/` (Vite + React) and consumes backend APIs.

## Backend Architecture (Java)
- Entry point: `backend/src/main/java/com/dbaagent/DbaAgentApplication.java`
- Packages:
  - `controller/`: REST endpoints.
  - `service/`: Core business logic (slow query ingestion, explain, chat, monitoring, schema, etc.).
  - `service/brain/`: brain subdomains (classification, analysis, keycolumn, config, query, workload, core).
  - `model/`: JPA entities and domain models.
  - `repository/`: Spring Data repositories.
  - `provider/`: Database dialect/registry and introspection providers.
  - `security/`, `config/`: app configuration and security.
  - `util/`: shared utilities.

### Key Domains
- Slow Query:
  - Ingestion: `SlowLogIngestionService`, `IngestionJobService`.
  - Parsing: `SlowQueryLogParserService` (supports MySQL/Postgres, CloudWatch CSV).
  - History: `SlowQueryHistoryService`, `SlowQueryHistoryRepository`.
  - Fetchers: S3/CloudWatch/Azure/GCP/Datadog/Elasticsearch services.
- Explain Plan:
  - History: `AnalysisHistoryService`, `AnalysisHistoryRepository`.
  - Explain endpoints in `ExplainController` and services.
- Brain:
  - Intelligence features under `service/brain/*`.
  - Uses slow query history + schema metadata + query lineage.
- Monitoring & Schema:
  - `PerformanceMonitoringService`, `SchemaScannerService`, `SchemaIntrospectionService`.

## Data Flow (Slow Query Logs)
1. Fetch logs from provider (S3/CloudWatch/Azure/GCP/Datadog/Elasticsearch).
2. Stream into temp file and cap size.
3. Parse logs with `SlowQueryLogParserService` (cloudwatch CSV handling + top‑K selection).
4. Persist analysis in `SlowQueryHistory`.

## Performance & Safety Guardrails
- Log size cap (500MB) enforced via stream wrappers.
- Temp files used for large logs; delete‑on‑close behavior.
- History reads are paged + lookback‑bounded in heavy analysis services.
- Cleanup of history uses paged ID deletion to avoid full‑table loads.
- Query timeout + fetch size configured for schema and monitoring scans.

## Config Defaults (application*.properties)
- `slow-query.log.max-bytes=524288000` (500MB cap)
- `slow-query.log.temp-dir` (empty locally; `/tmp/dba-agent` in prod)
- `slow-query.log.min-free-disk-mb=1024`
- `slow-query.history.lookback-days=90`
- `slow-query.history.max-batch=1000`
- `analysis.history.max-batch=1000`
- `db.fetch-size=1000`
- `db.query-timeout-seconds=30`

## Key Files to Know
- Slow query ingestion: `backend/src/main/java/com/dbaagent/service/SlowLogIngestionService.java`
- Ingestion jobs: `backend/src/main/java/com/dbaagent/service/IngestionJobService.java`
- Log parsing: `backend/src/main/java/com/dbaagent/service/SlowQueryLogParserService.java`
- Provider fetchers:
  - `S3LogFetchService`, `CloudWatchLogFetchService`, `AzureBlobLogFetchService`,
    `GcpCloudLoggingFetchService`, `DatadogLogFetchService`, `ElasticsearchLogFetchService`
- History services:
  - `SlowQueryHistoryService`, `AnalysisHistoryService`
- Repositories:
  - `SlowQueryHistoryRepository`, `AnalysisHistoryRepository`

## Common Risks / Hotspots
- Large logs (100–500MB) → must stream and cap.
- Unbounded history reads → always page + lookback.
- Provider fetchers should avoid in‑memory concatenation.
- Schema scans and monitoring queries can be heavy without fetch‑size/timeout.

## Testing Notes
- Backend tests: `mvn test` from `backend/` (integration tests may be slow and rely on local Postgres).
- Brain API smoke: `backend/src/test/java/com/dbaagent/integration/BrainEndpointsSmokeTest.java` checks every `/api/brain/*` endpoint for 500s using the test connection ID.
- Test profile uses local encryption key `local-2025-01` to decrypt stored credentials in the shared dev DB.
- For large log ingestion, verify heap stays bounded and temp files are cleaned.
