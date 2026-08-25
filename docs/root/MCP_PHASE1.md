# DeepSQL MCP Phase 1

Phase 1 MCP is a **stdio MCP server** that wraps DeepSQL backend APIs. Schema/retrieval tools stay read-only; `execute_sql` is role-gated (developers read-only, admins can run DML and CREATE/ALTER with confirmation; DROP/TRUNCATE stay blocked).

## Goals

- Reuse DeepSQL’s existing connection management, chat orchestration, RAG, and guardrails
- Keep database credentials inside DeepSQL
- Expose a small, high-signal MCP tool surface for self-hosted customer rollout
- Keep MCP usable from laptops/workstations that are different from the DeepSQL server
- Block write SQL in MCP even if a caller bypasses the local shim

## Transport Model

Phase 1 is **local stdio transport only**:

- Each Claude Desktop or Codex user runs the MCP process locally on their own machine
- That local process talks to the customer-hosted DeepSQL backend over HTTPS using `DEEPSQL_API_BASE_URL`
- This works even when the MCP client machine is different from the DeepSQL server
- It is **not** a centrally hosted remote MCP server yet

If you want a single shared MCP URL instead of per-user local shims, that is a phase 2 item and should use Streamable HTTP.

## Local Repo Command

```bash
npm run mcp:phase1
```

## Customer Package Command

The customer-facing distribution is prepared as an npm package under `mcp/package.json`:

```bash
npx -y @deepsql/mcp
```

## Client Configs

### Cursor

Project-level Cursor config is checked in at:

- `.cursor/mcp.json`

It uses:

- `type: "stdio"`
- `command: "npm"`
- `args: ["run", "mcp:phase1"]`
- `envFile: "${workspaceFolder}/.env"`

Set backend-specific values such as `DEEPSQL_API_BASE_URL` and `DEEPSQL_AUTH_TOKEN` in the workspace `.env` file if needed.

### Claude Desktop

Customer example:

- `mcp/claude_desktop_config.customer.example.json`

Repo-local development example:

- `mcp/claude_desktop_config.example.json`

Claude Desktop config location on macOS is typically `~/Library/Application Support/Claude/claude_desktop_config.json`.

### Codex

Customer example:

- `mcp/codex_config.customer.example.toml`

## Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `DEEPSQL_API_BASE_URL` | `http://localhost:8080/api/` | DeepSQL backend base URL. In self-host deployments this is usually the customer's HTTPS DeepSQL URL. |
| `DEEPSQL_AUTH_TOKEN` | unset | Optional bearer token for authenticated backends. Can be a DeepSQL JWT or an MCP personal access token created via `/api/auth/mcp-tokens`. |
| `DEEPSQL_MCP_TIMEOUT_MS` | `120000` | HTTP timeout per backend call |
| `DEEPSQL_MCP_USER_ID` | `mcp-phase1` | Default user ID for attribution (legacy) |
| `DEEPSQL_MCP_PROJECT_ID` | `mcp-phase1` | Default project ID for attribution (legacy) |

Customers running Claude or Codex on separate machines should:

1. Create an MCP personal access token in DeepSQL
2. Set `DEEPSQL_API_BASE_URL=https://<customer-host>/api/`
3. Set `DEEPSQL_AUTH_TOKEN=<mcp-token>`
4. Run the stdio MCP process locally on the user workstation

## Tools

### `list_connections`
Lists available DeepSQL connection IDs for the current user.

### `get_schema`
Fetches cached schema metadata from `/api/connections/{connectionId}/schema`.

### `get_database_objects`
Fetches database objects from `/api/connections/{connectionId}/objects`.

### Brain tools (V1 — agentless)

These tools expose DeepSQL's brain directly so coding agents (Claude Code, Cursor, Codex) can ground their own SQL/answer generation in retrieved schema knowledge — without going through DeepSQL's chat agent. The brain is the moat; the generator is a commodity.

#### `get_brain_context`
Retrieves the same context the chat pipeline uses: relevant tables, columns, FK relationships, training docs, business rules, and embedding-ranked snippets.
- Without `topK` → `POST /api/training/context/{connectionId}` (rich `RetrievedContextResult`).
- With `topK` → `GET /api/training/retrieve/{connectionId}?q=&topK=` (ranked diagnostic snippets).

#### `list_business_rules`
`GET /api/business-rules/connection/{connectionId}?question=…` — active business rules and SQL guardrails, optionally filtered by question scope.

#### `get_relationships`
`GET /api/brain/inferred-relationships/{connectionId}` — inferred and validated foreign-key relationships with confidence scores.

#### `get_anti_patterns`
- `kind=table` → `GET /api/brain/table-anti-patterns/{connectionId}` (schema-level patterns).
- `kind=query` → `GET /api/brain/query-anti-patterns/{connectionId}?limit=` (query-level patterns with severity).

#### `analyze_slow_queries`
`GET /api/slow-queries/analyze/{connectionId}?threshold=&limit=` — slow query analysis over the last 24 hours with fingerprints, durations, and example statements.

#### `get_index_recommendations`
`GET /api/index-recommendations/{connectionId}/top?limit=N` — DBA-grade, workload-weighted top index recommendations (default 5, max 50). Covers **both halves** of index hygiene:

- `kind = CREATE_INDEX` — missing index suggestions inferred from JSQLParser-parsed slow-query workload (`Σ calls × mean_exec_time` per (table, role-tagged columns) — the pganalyze / Microsoft DTA "total time" ROI metric), EXPLAIN plan-tree walks (Seq Scan / Full Table Scan / expensive Nested Loop / filesort), inferred JOIN relationships, anti-pattern columns persisted by `KeyColumnAnalysisService`, FK + schema-walk hints, and partial-index hints for heavily skewed columns.
- `kind = DROP_INDEX` — indexes with zero scans (`pg_stat_user_indexes` / `sys.schema_index_statistics`) AND indexes that are redundant prefixes of other indexes. Demoted to LOW priority when `pg_stat_database.stats_reset` fired within `index-recommendations.staleness-days` (default 14) — fresh-stats "unused" verdicts are unreliable.

**Workload weighting** uses every counter `pg_stat_statements` exports — `calls`, `total_exec_time`, `mean_exec_time`, `rows`. Column ordering for composite indexes follows industry rules: equality before range, selectivity-ranked, ORDER BY suffix only with full-equality prefix, capped at 3 columns. Composite candidates are cross-checked against `KeyColumnAnalysisService.detectCompositeIndexes` for confirmation.

**Net benefit ranking**: each candidate's `workloadScoreMs` is offset by `writeCostScore` (approximated from `pg_stat_user_tables.n_tup_ins + n_tup_upd + n_tup_del`). The top-N ordering is `priority → (workloadScoreMs − writeCostScore) DESC → occurrenceCount DESC → lastSeenAt DESC`.

**Evidence payload**: each recommendation carries up to 5 `topEvidence` rows — the contributing query fingerprints, call counts, mean / total execution times, rows examined, and role (WHERE_EQ / WHERE_RANGE / JOIN / GROUP / ORDER). This is the "why" — a caller can audit each suggestion rather than trust the heuristic.

A background scheduler refreshes the ledger every 6h (`performance.recommendations.refresh.cron`). Re-observed candidates bump `occurrenceCount` instead of duplicating; ≥3 cycles auto-promotes to HIGH priority; rows not re-observed within the staleness window are aged out so the accumulator follows workload drift.

**Response fields**: `id`, `kind`, `tableName`, `columnNames`, `indexName`, `createStatement` (CREATE INDEX … / DROP INDEX … / partial CREATE INDEX … WHERE …), `priority`, `occurrenceCount`, `firstSeenAt`, `lastSeenAt`, `estimatedImpact` (0–100 cosmetic), `workloadScoreMs`, `writeCostScore`, `netBenefitMs`, `evidenceCount`, `affectedQueries`, `reason`, `topEvidence[]`, plus optional `hypopgBeforeCost` / `hypopgAfterCost` / `hypopgReductionPct` / `hypopgEvaluatedAt` when HypoPG is installed on the target Postgres connection.

#### `apply_index_recommendation`
`POST /api/index-recommendations/{recommendationId}/apply?mode=&confirm=` — apply (or dry-run) a recommendation against its target connection and measure the before/after benefit on the contributing queries that motivated it.

**This is the only MCP path that can drop an index.** `execute_sql` blocks every `DROP`/`TRUNCATE`. All other tools are read-only or confirm-gated writes that cannot remove objects.

Modes:

- `DRY_RUN` (default — no writes). Postgres-only. Installs a virtual index via `SELECT hypopg_create_index(<ddl>)`, EXPLAINs each contributing query with and without the hypothetical index, and resets HypoPG state at the end. Returns `FAILED` on a Postgres connection without HypoPG, and on MySQL connections (no HypoPG equivalent). The phase-2 PR may add `EXPLAIN FORMAT=JSON`-based cost estimation for MySQL.
- `APPLY` — runs the real DDL. Postgres uses `CREATE INDEX CONCURRENTLY` / `DROP INDEX CONCURRENTLY` so the operation doesn't lock the table. MySQL gets the recommendation's `createStatement` as-is (InnoDB online DDL is implicit). Measurement is the planner-cost delta on contributing queries.
- `APPLY_AND_MEASURE` — `APPLY` plus `EXPLAIN ANALYZE` before and after for wall-clock timings. Slowest mode. Only opt in when running the contributing queries against the target is acceptable.

`APPLY` and `APPLY_AND_MEASURE` require `confirm: true`. Calls without confirmation return `BLOCKED_NEEDS_CONFIRMATION` with no side effects.

**Response shape**:

```json
{
  "recommendationId": "abc-123",
  "executedDdl": "CREATE INDEX CONCURRENTLY idx_orders_status ON orders (status);",
  "mode": "APPLY",
  "status": "OK",
  "beforeCost": 4823.0,
  "afterCost": 122.0,
  "costReductionPct": 97.5,
  "beforeWallTimeMs": 482.0,
  "afterWallTimeMs": 12.0,
  "wallTimeImprovementPct": 97.5,
  "samples": [
    { "fingerprint": "ab12c0", "beforeCost": 4823, "afterCost": 122, "beforeWallTimeMs": 482, "afterWallTimeMs": 12, "error": null }
  ],
  "message": "APPLY complete — planner cost −97.5% (4823 → 122), wall-time −97.5% (482.0ms → 12.0ms)",
  "executedAt": "2026-05-14T18:42:00"
}
```

Statuses: `OK` (measurement succeeded), `BLOCKED_NEEDS_CONFIRMATION` (write mode without `confirm=true`), `NOT_FOUND` (no recommendation with that id), `NO_USABLE_SAMPLES` (all contributing queries had parameter placeholders we can't EXPLAIN), `FAILED` (DDL execution or measurement raised).

On `APPLY` / `APPLY_AND_MEASURE` success the recommendation is marked `APPLIED` and the cost-delta is stamped onto the row so it won't re-surface in the next refresh cycle.

### `execute_sql`
Executes SQL through `POST /api/connections/{connectionId}/query` — the same
canonical endpoint the web SQL Editor uses, with the same `QueryExecutionPolicyService`
enforcement.

What the backend allows depends on the actor's role:

- **Developer (`ROLE_DEVELOPER`)** — SELECT / WITH / SHOW / EXPLAIN only. Any
  DML or DDL is rejected with `EDITOR_MUTATION_FORBIDDEN` (HTTP 403).
- **Admin (`ROLE_ADMIN`)** — DML and non-destructive DDL (`CREATE`, `ALTER`,
  `CREATE INDEX`) accepted but gated by a two-step confirmation flow:
  - First call with `confirmMutation=false` returns
    `requiresConfirmation: true` with a warnings list (e.g. "DELETE without
    WHERE is blocked" for unsafe shapes).
  - Client re-sends with `confirmMutation=true` to actually execute.
- **DROP / TRUNCATE** — blocked on this surface (`UNSAFE_MUTATION_BLOCKED`)
  even for confirmed admins, including EXPLAIN-wrapped forms. Use database
  admin tooling, or `apply_index_recommendation` for advisor-sourced index
  drops. The web SQL Editor still blocks only `DROP TABLE`.

`EXPLAIN` and `EXPLAIN ANALYZE` are valid SQL — pass them as the query.
`EXPLAIN ANALYZE` of a mutating statement (`EXPLAIN ANALYZE DELETE FROM …`)
goes through the same admin/WHERE/confirm policy because ANALYZE actually
runs the inner statement.

Multi-statement input is still rejected (single statement per call). Per-call
audit rows include `clientType` (cli/mcp/editor), `clientAgent`
(claude-code/cursor/codex/terminal/web), and `clientVersion` so admins can
trace which surface ran which statement.

The older `execute_readonly_sql` tool (and the `/api/mcp/query-readonly`
endpoint behind it) is kept as a deprecation alias for one cycle; it forwards
to the canonical endpoint and logs a deprecation warning. It will be removed
in the 0.14.0 backend.

### `analyze_query_plan`
Runs `POST /api/explain/analyze` for AI-enriched plan analysis. Returns the
parsed plan tree, performance issues, index recommendations, and an
LLM-written summary that takes the connection's schema, business rules,
and detected anti-patterns into account.

The input query must be the underlying SQL, **not** wrapped in `EXPLAIN` —
the server wraps it based on the `useAnalyze` boolean:

- `useAnalyze: false` (default) — runs `EXPLAIN` only; no execution. Safe
  for any actor with read access to the connection.
- `useAnalyze: true` — runs `EXPLAIN ANALYZE` (the query is actually
  executed for real timings). For mutating statements this requires admin
  role + the WHERE-clause guard + the two-step confirmation, same as
  `execute_sql`.

`explain_readonly_sql` (and `/api/mcp/explain-readonly`) is a one-cycle
deprecation alias, same lifecycle as `execute_readonly_sql`.

## `deepsql indexes` CLI

The same advisor surface is exposed on the human-facing `deepsql` CLI binary
(`mcp/bin/deepsql.js`) so an on-call DBA gets identical functionality without
the IDE-side MCP plumbing. Catalog diagnostics and the workload-weighted
advisor share one namespace as of 0.15.0.

```
deepsql indexes top      [--limit N] --connection <name> [--json]
deepsql indexes list     [--all | --status PENDING|APPLIED|DISMISSED] --connection <name> [--json]
deepsql indexes show     <id> --connection <name> [--json]
deepsql indexes refresh  --connection <name> [--json]
deepsql indexes apply    <id> [--mode dry-run|apply|apply-and-measure]
                              [--confirm] [--no-concurrent] [--json]
deepsql indexes dismiss  <id> [--json]
deepsql indexes missing  --connection <name> [--json]
deepsql indexes health   --connection <name> [--json]
deepsql indexes unused   --connection <name> [--json]
deepsql indexes duplicates --connection <name> [--json]
deepsql indexes usage    <table> --connection <name> [--json]
```

Mapping:

| CLI | Backend call | Notes |
|---|---|---|
| `top` | `GET /index-recommendations/{cid}/top?limit=N` | Workload-weighted, recurrence-ranked, evidence-bearing — same payload as the MCP `get_index_recommendations` tool |
| `list` | `GET /index-recommendations/pending/{cid}` (or `/{cid}` with `--all`) | Simpler view of the same persisted recommendations |
| `show <id>` | filters the top-50 client-side | Full detail render |
| `refresh` | `POST /index-recommendations/generate/{cid}` | Force a new accumulation cycle |
| `apply <id>` | `POST /index-recommendations/{id}/apply?mode=&confirm=&concurrent=` | Same contract as the MCP `apply_index_recommendation` tool |
| `dismiss <id>` | `PUT /index-recommendations/{id}/dismiss` | |
| `missing` | `GET /advisor/indexes/{cid}` | Catalog-level missing-index suggestions |
| `health` | `GET /index-advisor/{cid}/health-report` | Catalog health snapshot |
| `unused` | `GET /index-advisor/{cid}/unused` | Live `pg_stat_user_indexes` / `sys.*` probe |
| `duplicates` | `GET /index-advisor/{cid}/duplicates` | Redundant prefix-duplicates |
| `usage <table>` | `GET /index-advisor/{cid}/usage/{tableName}` | Per-table scan / fetch counters |

The `apply` subcommand's safety contract matches the MCP tool exactly: default
mode is `dry-run` (HypoPG-based, no writes); `apply` / `apply-and-measure`
require `--confirm`. `--no-concurrent` opts out of `CREATE/DROP INDEX
CONCURRENTLY` on Postgres for dev workflows where the brief ACCESS EXCLUSIVE
lock is acceptable.

Default output is a terminal-friendly summary with net-benefit, evidence
counts, and HypoPG cost-delta when present. `--json` passes the raw backend
payload through for scripting / CI.

## Protocol

The server speaks **JSON-RPC 2.0 over stdio** using `Content-Length` framed messages, which is compatible with MCP clients that use the standard stdio transport.

Implemented methods:

- `initialize`
- `ping`
- `tools/list`
- `tools/call`
- `shutdown`
- `exit`

`resources/list` and `prompts/list` return empty arrays in phase 1.

## Rollout Guidance

Phase 1 is intentionally minimal:

- no raw write SQL — `execute_readonly_sql` is the only SQL execution path and it rejects mutating verbs at the client and the backend
- one server-mediated write tool: `apply_index_recommendation` (write requires `confirm: true`; the DDL is server-generated from a previously-fetched recommendation, never client-supplied SQL)
- no direct credential exposure
- no raw multi-statement SQL
- `EXPLAIN ANALYZE` is reserved for the `apply_index_recommendation` `APPLY_AND_MEASURE` mode, which runs the contributing queries against the target DB only when the caller explicitly opts in
- no shared remote MCP endpoint yet

For phase 2, the next step is a remote Streamable HTTP MCP endpoint with OAuth/PKCE for shared connectors.

## MCP Personal Access Tokens

DeepSQL supports MCP personal access tokens that are bound to an existing DeepSQL user identity.

- Create/list/revoke your own MCP tokens via:
  - `GET /api/auth/mcp-tokens`
  - `POST /api/auth/mcp-tokens`
  - `DELETE /api/auth/mcp-tokens/{tokenId}`
- Tokens authenticate as the owning user and inherit that user’s current role/permissions.
- Connection access remains owner-scoped in the backend.
- Store the raw token when it is created; it is returned only once.
