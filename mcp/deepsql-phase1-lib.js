const fs = require("fs");

const ALLOWED_READ_ONLY_KEYWORDS = new Set([
  "SELECT",
  "WITH",
  "SHOW",
  "DESCRIBE",
  "DESC",
  "EXPLAIN",
]);

const FORBIDDEN_SQL_KEYWORDS = [
  "INSERT",
  "UPDATE",
  "DELETE",
  "ALTER",
  "DROP",
  "TRUNCATE",
  "CREATE",
  "MERGE",
  "REPLACE",
  "GRANT",
  "REVOKE",
  "CALL",
  "COPY",
  "VACUUM",
  "COMMENT",
];

const FORBIDDEN_SQL_KEYWORD_SET = new Set(FORBIDDEN_SQL_KEYWORDS);
const FORBIDDEN_ALTERNATION = FORBIDDEN_SQL_KEYWORDS.join("|");
const CTE_MUTATION_PATTERN = new RegExp(
  `\\bAS(?:\\s+NOT)?(?:\\s+MATERIALIZED)?\\s*\\(\\s*(${FORBIDDEN_ALTERNATION})\\b`,
);
const FOR_UPDATE_PATTERN = /\bFOR\s+(?:NO\s+KEY\s+)?UPDATE\b/;
const TRAILING_DML_PATTERN = new RegExp(`\\)\\s*(${FORBIDDEN_ALTERNATION})\\b`);
const EXPLAIN_PREFIX_PATTERN = /^EXPLAIN(?:\s*\([^)]*\))?/;

const TOOL_DEFINITIONS = [
  {
    name: "list_connections",
    description: "List DeepSQL database connections available to this user.",
    inputSchema: {
      type: "object",
      properties: {},
      additionalProperties: false,
    },
  },
  {
    name: "get_schema",
    description: "Fetch cached schema metadata for a DeepSQL connection.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: {
          type: "string",
          description: "DeepSQL connection ID.",
        },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "get_database_objects",
    description: "Fetch tables, views, functions, and procedures for a DeepSQL connection.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: {
          type: "string",
          description: "DeepSQL connection ID.",
        },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "get_brain_context",
    description:
      "Retrieve DeepSQL's brain context for a question: relevant tables, columns, FKs, training docs, business rules, anti-patterns, and embedding-ranked snippets. Use this to give your own coding agent the same retrieval context the DeepSQL agent uses, then have your agent generate the SQL/answer.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        question: {
          type: "string",
          description: "Natural-language question used for retrieval ranking.",
        },
        topK: {
          type: "integer",
          minimum: 1,
          maximum: 100,
          description:
            "Optional retrieval breadth. When provided, returns ranked diagnostic results from /training/retrieve; otherwise returns the rich /training/context payload.",
        },
      },
      required: ["connectionId", "question"],
      additionalProperties: false,
    },
  },
  {
    name: "list_business_rules",
    description:
      "List active business rules and SQL guardrails for a connection. Optional `question` filters to rules applicable to that question.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        question: {
          type: "string",
          description: "Optional natural-language question to scope rules.",
        },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "get_relationships",
    description:
      "Get inferred and validated foreign-key relationships for a connection (source/target table+column with confidence and inference method).",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "get_anti_patterns",
    description:
      "Get DeepSQL-detected anti-patterns. `kind=table` returns table/schema-level anti-patterns; `kind=query` returns query-level anti-patterns (with optional limit).",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        kind: {
          type: "string",
          enum: ["table", "query"],
          description: "Which anti-pattern catalog to fetch. Defaults to 'table'.",
        },
        limit: {
          type: "integer",
          minimum: 1,
          maximum: 500,
          description: "Optional row limit (only used for kind='query').",
        },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "list_brain_recommendations",
    description:
      "List the brain's AI-proposed recommendations for a connection — high-value tables/columns DeepSQL suggests documenting, each with a priority (P0/P1…), the reason, supporting indicators, and a suggested prompt to explore. This is the company-context review queue: an admin reviews these and accepts the good ones with save_brain_note. Returns { suggestions, totalCount } (totalCount reflects the requested limit, not an absolute total).",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        limit: { type: "integer", minimum: 1, maximum: 100, description: "Max recommendations to return (default 10)." },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "save_brain_note",
    description:
      "Accept/save a fact into the connection's BRAIN — shared, company-level context that grounds EVERY future answer for this connection (not a per-user preference). Use this to accept a recommendation from list_brain_recommendations, or to record any durable fact about a table/column. Scope is TABLE (tableName only) or COLUMN (tableName + columnName). Requires manage-content permission on the connection (admin) — the backend enforces and audits it. NOTE: an individual's personal preference (how *they* like answers formatted, a private shortcut) belongs in a DeepSQL skill on their own profile, NOT here — this writes to the shared brain everyone sees.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        tableName: { type: "string", description: "Table the note is about (required)." },
        columnName: { type: "string", description: "Column the note is about; omit for a table-scoped note." },
        noteText: { type: "string", description: "The fact/guidance to remember." },
      },
      required: ["connectionId", "tableName", "noteText"],
      additionalProperties: false,
    },
  },
  {
    name: "list_brain_notes",
    description:
      "List knowledge already saved to the connection's brain (the notes that ground answers). Optionally filter by tableName and/or columnName — a trained connection can hold thousands of notes, so filtering is recommended. Use this to check whether a fact is already remembered before saving a new one with save_brain_note.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        tableName: { type: "string", description: "Filter to one table." },
        columnName: { type: "string", description: "Filter to one column (use with tableName)." },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "apply_index_recommendation",
    description:
      "Apply (or dry-run) an index recommendation and measure the before/after benefit on the queries that motivated it. " +
      "Default mode is DRY_RUN — no writes; uses HypoPG (Postgres-only) to install a virtual index in the session, EXPLAIN-diffs the cost on each contributing query, then resets. " +
      "APPLY mode runs the real DDL (CREATE INDEX CONCURRENTLY / DROP INDEX CONCURRENTLY on Postgres so the operation doesn't lock the table). " +
      "APPLY_AND_MEASURE additionally runs EXPLAIN ANALYZE before and after for wall-clock timings — slowest, only opt in when you're OK executing the contributing queries against the target DB. " +
      "Both APPLY modes require `confirm: true` — write operations don't happen by accident. " +
      "Returns the executed DDL, the planner-cost delta, per-sample measurements (each contributing query's before/after cost), and an aggregate improvement percentage.",
    inputSchema: {
      type: "object",
      properties: {
        recommendationId: { type: "string", description: "Recommendation row id (from get_index_recommendations)." },
        mode: {
          type: "string",
          enum: ["DRY_RUN", "APPLY", "APPLY_AND_MEASURE"],
          description: "Default DRY_RUN. APPLY mutates the database; APPLY_AND_MEASURE also runs EXPLAIN ANALYZE."
        },
        confirm: {
          type: "boolean",
          description: "Required `true` for APPLY and APPLY_AND_MEASURE. Defaults to false."
        },
        concurrent: {
          type: "boolean",
          description: "Postgres-only. When true (default), CREATE/DROP runs CONCURRENTLY (no table lock, but waits for every pre-existing transaction). Set false on small dev tables when the brief ACCESS EXCLUSIVE lock is acceptable."
        }
      },
      required: ["recommendationId"],
      additionalProperties: false,
    },
  },
  {
    name: "get_index_recommendations",
    description:
      "Get DeepSQL's pre-computed top index recommendations for a connection. Recommendations are workload-weighted (Σ calls × mean_exec_time, the pganalyze / Microsoft DTA 'total time' ROI metric) and aggregated across many slow-query log fetches over a configurable lookback (default 30 days). Column ordering for composite indexes follows industry rules (equality before range, selectivity-ranked, ORDER BY suffix only with full-equality prefix, capped at 3 columns). Covers both CREATE_INDEX and DROP_INDEX (unused + redundant prefix-duplicate) candidates. Each result carries net benefit (workload − write cost), the contributing query fingerprints, and call/duration metrics — so a caller can audit *why* each suggestion exists rather than trust a heuristic. Returns top N (default 5) PENDING recommendations.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        limit: {
          type: "integer",
          minimum: 1,
          maximum: 50,
          description: "Number of recommendations to return. Defaults to 5.",
        },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "analyze_slow_queries",
    description:
      "Analyze recent slow queries for a connection over the last 24 hours, returning fingerprints, durations, and example statements.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        thresholdMs: {
          type: "number",
          minimum: 1,
          description: "Minimum query duration in milliseconds. Defaults to 100.",
        },
        limit: {
          type: "integer",
          minimum: 1,
          maximum: 500,
          description: "Maximum queries to return. Defaults to 10.",
        },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "get_slow_query_timeline",
    description:
      "Get the day-by-day timeline for one slow query from DeepSQL's 30-day analytics store. "
      + "The query is identified by its stable fingerprint (the MD5 of the normalized query — "
      + "the `queryId` field returned by analyze_slow_queries). Returns one point per day with "
      + "call count, mean/max execution time, and the regression factor versus the previous day. "
      + "Use this to answer 'is this query getting slower over time'.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        fingerprint: {
          type: "string",
          description: "Stable query fingerprint (MD5 of the normalized query).",
        },
      },
      required: ["connectionId", "fingerprint"],
      additionalProperties: false,
    },
  },
  {
    name: "get_query_regressions",
    description:
      "List slow queries that regressed (got slower) on the most recent daily analysis run. "
      + "Each result carries the fingerprint, normalized SQL, current mean execution time, and "
      + "the regression factor (this period's mean / the previous period's mean). Read-only.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        minFactor: {
          type: "number",
          minimum: 1,
          description: "Minimum slowdown multiple to report. Defaults to 1.5 (≥50% slower).",
        },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "list_tracked_queries",
    description:
      "List all slow query fingerprints tracked in DeepSQL's 30-day rolling analytics store. "
      + "Each entry carries the stable fingerprint (MD5 of normalized SQL), a normalized query "
      + "sample, delta call count, mean/max execution time, and the regression factor versus the "
      + "previous day. Use this to discover which queries are worth investigating before pulling "
      + "timelines or samples.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "get_slow_query_customers",
    description:
      "List all tenants / customers ranked by total slow-query time for a connection. "
      + "Each entry includes the raw customer id, resolved customer name (when a lookup "
      + "table is configured), total number of distinct slow queries attributed to them, "
      + "and the total cumulative execution time. Use this to answer 'which customer is "
      + "driving the most database load?'",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "get_query_samples",
    description:
      "Retrieve literal SQL samples (with actual bind values substituted in) for a specific "
      + "query fingerprint, ordered slowest-first. Samples are captured from the slow-query log "
      + "or performance-schema and stored in DeepSQL's analytics store. Use these to reproduce "
      + "a slow execution, get a real EXPLAIN plan, or understand how different callers use the "
      + "same query shape.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        fingerprint: {
          type: "string",
          description: "Stable query fingerprint (MD5 of the normalized query).",
        },
      },
      required: ["connectionId", "fingerprint"],
      additionalProperties: false,
    },
  },
  {
    name: "get_slow_query_insights",
    description:
      "Retrieve AI-enriched slow-query insights for a connection. Insights are pre-computed "
      + "by DeepSQL's daily analysis and grouped into kinds: `hotspots` (queries consuming "
      + "the most total DB time), `remediation` (actionable fix recommendations), `tail-risk` "
      + "(high-variance queries with dangerous p95/max outliers), `plan-drift` (queries whose "
      + "execution plan changed), and `skew` (queries showing disproportionate load from one "
      + "tenant). Use `kind=all` (default) for the combined list.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        kind: {
          type: "string",
          enum: ["all", "hotspots", "remediation", "tail-risk", "plan-drift", "skew"],
          description: "Insight category to fetch. Defaults to 'all'.",
        },
        window: {
          type: "string",
          enum: ["LAST_24_HOURS", "LAST_7_DAYS", "LAST_30_DAYS"],
          description: "Time window for the insights. Defaults to 'LAST_7_DAYS'.",
        },
        limit: {
          type: "integer",
          minimum: 1,
          maximum: 100,
          description: "Maximum number of insights to return. Defaults to 10.",
        },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "optimize_slow_query",
    description:
      "Get an AI query REWRITE and plan diagnosis for one specific slow query. "
      + "Single-query scoped: returns a rewritten SQL (validated against the live DB), "
      + "the plan bottleneck, and an estimated improvement. Does NOT recommend indexes — "
      + "index and pre-aggregation recommendations require the whole workload and come "
      + "from the holistic Workload Analysis (and the get_index_recommendations tool). "
      + "Pass `avgExecutionTimeMs` to anchor the impact estimate to a real baseline.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        queryText: {
          type: "string",
          description: "The SQL query to optimize. Literal or normalized form both accepted.",
        },
        avgExecutionTimeMs: {
          type: "number",
          minimum: 0,
          description: "Optional observed average execution time in ms — anchors impact estimates.",
        },
      },
      required: ["connectionId", "queryText"],
      additionalProperties: false,
    },
  },
  {
    name: "get_table_growth",
    description:
      "Get table size / row-count growth trends for a connection from DeepSQL's persistent stats history. "
      + "Returns three parallel time series (sizeOverTime, growthOverTime, rowCountOverTime) plus per-table "
      + "headline rollups suitable for answering questions like \"which tables are growing fastest?\" or "
      + "\"how much has `orders` grown in the last month?\". Backed by snapshots stored in DeepSQL's "
      + "`table_stats_history`, not live `pg_total_relation_size()` probes — so it can show growth velocity "
      + "and bloat over time without re-scanning the customer's database.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        tableName: {
          type: "string",
          description: "Optional table name to scope the trends to a single table. Omit for all tables.",
        },
        days: {
          type: "integer",
          minimum: 1,
          maximum: 365,
          description: "Lookback window in days. Defaults to 30.",
        },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "get_growth_anomalies",
    description:
      "Get growth anomalies DeepSQL flagged on a connection — sudden size or row spikes that exceeded the "
      + "configured thresholds (percentage growth, absolute byte growth, statistical z-score). Each anomaly "
      + "carries severity (CRITICAL / WARNING / INFO), the before/after sizes, an anomaly type "
      + "(PERCENTAGE_GROWTH, ABSOLUTE_GROWTH, STATISTICAL_ANOMALY, ROW_SPIKE, NEW_TABLE), a human-readable "
      + "description, and a confidence score. Use this BEFORE walking the user through a plan to optimize a "
      + "table — the anomaly may be the root cause they should investigate first.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        tableName: {
          type: "string",
          description: "Optional table name to scope to one table. Omit for the whole connection.",
        },
        unacknowledgedOnly: {
          type: "boolean",
          description: "When true, only return anomalies the operator hasn't acked yet. Defaults to false.",
        },
        days: {
          type: "integer",
          minimum: 1,
          maximum: 365,
          description: "Lookback window in days. Defaults to 30.",
        },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "execute_sql",
    description:
      "Execute a SQL statement through DeepSQL. Routes through the same policy "
      + "gate as the SQL Editor: developers can run SELECT/WITH/SHOW/EXPLAIN; admins "
      + "can additionally run DML/DDL with a two-step confirmation. Pass `confirmMutation: "
      + "true` to confirm a mutation. EXPLAIN and EXPLAIN ANALYZE are valid SQL — just "
      + "type them as the query, no separate mode flag needed. Multi-statement input "
      + "and unsafe DELETE/UPDATE without WHERE are still rejected.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: {
          type: "string",
          description: "DeepSQL connection ID.",
        },
        query: {
          type: "string",
          description:
            "SQL to execute. Any single-statement SQL the connection's actor is "
            + "allowed to run: SELECT/WITH/SHOW/EXPLAIN for any role, plus DML/DDL "
            + "for admins.",
        },
        limit: {
          type: "integer",
          minimum: 1,
          maximum: 1000,
          description: "Row limit for SELECT results. Defaults to 100.",
        },
        timeoutSeconds: {
          type: "integer",
          minimum: 1,
          maximum: 60,
          description: "Per-query timeout. Defaults to the backend default.",
        },
        confirmMutation: {
          type: "boolean",
          description:
            "Required `true` on the second call when running DML/DDL — the first "
            + "call returns `requiresConfirmation: true` with a warnings list; "
            + "review and re-send with confirmMutation=true to actually execute.",
        },
      },
      required: ["connectionId", "query"],
      additionalProperties: false,
    },
  },
  {
    name: "analyze_query_plan",
    description:
      "Get DeepSQL's AI-enriched analysis of a query's execution plan. Returns the "
      + "parsed plan tree, performance issues, index recommendations, and a written "
      + "summary that takes into account the connection's schema, business rules, "
      + "and anti-patterns. With `useAnalyze: true` the query is actually executed "
      + "(EXPLAIN ANALYZE semantics) — mutating statements then go through the same "
      + "admin/WHERE/confirmation gates as execute_sql.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: {
          type: "string",
          description: "DeepSQL connection ID.",
        },
        query: {
          type: "string",
          description:
            "The underlying SQL to plan. Do NOT wrap in EXPLAIN — the server does "
            + "that based on `useAnalyze`.",
        },
        useAnalyze: {
          type: "boolean",
          description:
            "If true, run EXPLAIN ANALYZE (actually executes the query for real "
            + "timings). For mutating statements this requires admin role + confirm.",
        },
        confirmMutation: {
          type: "boolean",
          description:
            "Required `true` to confirm a mutating useAnalyze=true call. Same "
            + "two-step flow as execute_sql.",
        },
      },
      required: ["connectionId", "query"],
      additionalProperties: false,
    },
  },

  // ─── Phase A symmetry: tools added to match the `deepsql` CLI surface ───
  //
  // Each tool below mirrors a CLI subcommand that previously had no MCP
  // equivalent. Connection write operations (add/update/remove) are
  // intentionally NOT exposed as MCP tools — they require DB credentials
  // and we don't want secrets crossing the agent's conversation history.
  // Customers manage connections via `deepsql connections add` at a TTY.

  {
    name: "get_current_user",
    description:
      "Return the authenticated user behind the current MCP token: username, role, "
      + "and the DeepSQL host this MCP server is bound to. Use this when the agent "
      + "needs to know whether the caller is admin-capable before suggesting a "
      + "DDL/DML run, or when explaining role-based restrictions to the user.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
  },
  {
    name: "test_connection",
    description:
      "Validate a saved connection: contacts the database, runs the privilege "
      + "report, and returns whether the connection (and SSH tunnel, if any) is "
      + "reachable. Read-only on the customer's DB. Use when diagnosing a `?:` "
      + "Connected status from `list_connections` or before suggesting a SQL run "
      + "against a connection the agent hasn't touched yet. Takes a saved "
      + "connectionId only — does NOT accept ad-hoc credentials (those go through "
      + "`deepsql connections add` at a terminal, not chat).",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "show_connection",
    description:
      "Return the full saved configuration for one connection with all secret "
      + "fields masked as `(set)`. Useful for diagnosing connection issues "
      + "(host/port/SSL/SSH config). Will never echo a password back; use "
      + "`deepsql connections show` at a TTY if you genuinely need to see "
      + "a secret value.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "reinit_connection_brain",
    description:
      "Trigger a fresh brain initialization for a connection: re-scans the "
      + "schema, re-runs key-column / FK inference, re-embeds training context. "
      + "Use after the user reports DeepSQL's schema knowledge is stale (e.g., "
      + "they just ran a migration). Returns immediately with an init-status "
      + "row; the actual reinit runs in the background — poll connection state "
      + "via `list_connections` to see when it transitions back to COMPLETED.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        force: {
          type: "boolean",
          description: "Restart even if a previous init is currently RUNNING. Defaults to false.",
        },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "get_latest_digest",
    description:
      "Return the most recent DeepSQL daily digest for a connection (or the "
      + "workspace if no connectionId). Digests are nightly summaries of slow "
      + "queries + AI commentary written to Slack. Useful for context when the "
      + "user asks 'what changed in the database recently?' without needing a "
      + "fresh slow-query analysis.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: {
          type: "string",
          description: "Optional. Filter to one connection. Omit for workspace-wide.",
        },
      },
      additionalProperties: false,
    },
  },
  {
    name: "list_digests",
    description:
      "Return the N most recent DeepSQL daily digests (compact metadata, not full "
      + "body). Use to find the digest id for a date the user references "
      + "('what was in yesterday's digest?'), then fetch the full content via "
      + "`get_digest_by_id`.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "Optional. Filter to one connection." },
        count: { type: "integer", minimum: 1, maximum: 100, description: "Defaults to 10." },
      },
      additionalProperties: false,
    },
  },
  {
    name: "get_digest_by_id",
    description:
      "Return the full body of one DeepSQL daily digest by id, including the AI "
      + "narrative and the slow-query list it was built from.",
    inputSchema: {
      type: "object",
      properties: {
        digestId: { type: "string", description: "Digest id (from list_digests)." },
        connectionId: {
          type: "string",
          description: "Optional. Required only if multiple connections share digest ids.",
        },
      },
      required: ["digestId"],
      additionalProperties: false,
    },
  },
  {
    name: "get_missing_indexes",
    description:
      "Catalog probe: returns indexes the DeepSQL advisor thinks are MISSING "
      + "based on live workload (joins to unindexed columns, sort/group on big "
      + "tables, etc.). This is the schema-walk view — for the workload-weighted "
      + "ROI-ranked recommendations (with HypoPG cost-delta), use "
      + "`get_index_recommendations` instead.",
    inputSchema: {
      type: "object",
      properties: { connectionId: { type: "string", description: "DeepSQL connection ID." } },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "get_index_health",
    description:
      "Comprehensive index health report for a connection: total indexes, "
      + "bloated indexes, unused indexes, duplicate-prefix indexes, biggest "
      + "indexes by size. Use as the first read when the user says 'audit my "
      + "indexes' or 'are my indexes healthy?'.",
    inputSchema: {
      type: "object",
      properties: { connectionId: { type: "string", description: "DeepSQL connection ID." } },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "get_unused_indexes",
    description:
      "Catalog probe: indexes the DB has reported zero (or near-zero) scans "
      + "against since last reset. Each returned row has the table, index name, "
      + "size, and scan count. Dropping these is a quick storage + write-cost "
      + "win, but verify scan counters aren't recent before suggesting.",
    inputSchema: {
      type: "object",
      properties: { connectionId: { type: "string", description: "DeepSQL connection ID." } },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "get_duplicate_indexes",
    description:
      "Catalog probe: indexes that are redundant prefixes of other indexes on "
      + "the same table. Returns groups — each group is a set of indexes the "
      + "optimizer would treat as interchangeable, with the recommendation to "
      + "keep the longest/widest one.",
    inputSchema: {
      type: "object",
      properties: { connectionId: { type: "string", description: "DeepSQL connection ID." } },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "get_table_index_usage",
    description:
      "Per-table index usage statistics: every index on the given table with its "
      + "scan count, tuples read, and tuples fetched. Use to diagnose 'why isn't "
      + "my index being used?' or to decide which of several composite indexes "
      + "to drop.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        tableName: { type: "string", description: "Table name (case-sensitive on Postgres)." },
      },
      required: ["connectionId", "tableName"],
      additionalProperties: false,
    },
  },
  {
    name: "list_index_recommendations",
    description:
      "List ALL index recommendations for a connection, optionally filtered by "
      + "status (PENDING / APPLIED / DISMISSED). For just the top-N pending ones "
      + "with full evidence, use `get_index_recommendations` instead — that's the "
      + "agent-facing entry point. This tool is for browsing the recommendation "
      + "history (e.g. 'what did we apply last week?').",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        status: {
          type: "string",
          enum: ["PENDING", "APPLIED", "DISMISSED"],
          description: "Optional. Defaults to all statuses.",
        },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "refresh_index_recommendations",
    description:
      "Force a fresh accumulation cycle: rescans the slow-query log for the "
      + "lookback window and rebuilds the top-N index recommendations. Use when "
      + "the user just deployed a new query pattern and wants to see if the "
      + "advisor picks it up without waiting for the next 6-hour scheduler tick.",
    inputSchema: {
      type: "object",
      properties: { connectionId: { type: "string", description: "DeepSQL connection ID." } },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "dismiss_index_recommendation",
    description:
      "Mark a recommendation as DISMISSED so it stops appearing in `top` / "
      + "default `list` queries. Use when the user explicitly rejects a "
      + "recommendation (not the same as APPLIED — dismissed means 'we decided "
      + "not to'). Reversible by an admin editing the row directly.",
    inputSchema: {
      type: "object",
      properties: {
        recommendationId: { type: "string", description: "Recommendation row id." },
      },
      required: ["recommendationId"],
      additionalProperties: false,
    },
  },
  {
    name: "get_latest_slow_query_analysis",
    description:
      "Return the most recently completed slow-query analysis run for a "
      + "connection (the persisted result, no fresh collection). Faster than "
      + "`analyze_slow_queries` because it doesn't trigger new work — use as "
      + "the first read when investigating 'what's slow right now?', then "
      + "fall back to `analyze_slow_queries` only if the latest is stale.",
    inputSchema: {
      type: "object",
      properties: { connectionId: { type: "string", description: "DeepSQL connection ID." } },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "list_slow_query_history",
    description:
      "List past slow-query analysis runs for a connection (compact metadata: "
      + "id, timestamp, count, severity breakdown, AI-summary length). Use to "
      + "find an older analysis to compare current state against, or to spot "
      + "trends in how many slow queries are firing day-over-day.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        limit: {
          type: "integer",
          minimum: 1,
          maximum: 100,
          description: "Number of past analyses to return. Defaults to 10.",
        },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "acknowledge_growth_anomaly",
    description:
      "Mark a detected growth anomaly as acknowledged so it stops appearing "
      + "in `get_growth_anomalies(unacknowledgedOnly=true)`. Use after the user "
      + "confirms the growth was expected (e.g., 'yes, we did a big backfill "
      + "yesterday'). Does NOT delete the anomaly — it remains in history for "
      + "audit/timeline purposes.",
    inputSchema: {
      type: "object",
      properties: {
        anomalyId: { type: "string", description: "Anomaly row id (from get_growth_anomalies)." },
      },
      required: ["anomalyId"],
      additionalProperties: false,
    },
  },
  {
    name: "get_growth_config",
    description:
      "Return the alert thresholds and detection sensitivity currently "
      + "configured for table-growth monitoring on a connection. Useful to "
      + "explain to the user why a particular growth event did or didn't fire "
      + "an anomaly.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
      },
      required: ["connectionId"],
      additionalProperties: false,
    },
  },
  {
    name: "set_growth_config",
    description:
      "Update the alert thresholds / sensitivity for growth monitoring on a "
      + "connection. Admin-gated. The config body shape matches what "
      + "`get_growth_config` returns — use that first to fetch current values, "
      + "then submit a modified copy.",
    inputSchema: {
      type: "object",
      properties: {
        connectionId: { type: "string", description: "DeepSQL connection ID." },
        config: {
          type: "object",
          description: "Full config object (see get_growth_config for shape).",
          additionalProperties: true,
        },
      },
      required: ["connectionId", "config"],
      additionalProperties: false,
    },
  },
];

class DeepSqlApiError extends Error {
  constructor(message, status, payload) {
    super(message);
    this.name = "DeepSqlApiError";
    this.status = status;
    this.payload = payload;
  }
}

function compactWhitespace(value) {
  return String(value || "")
    .replace(/\s+/g, " ")
    .trim();
}

function stripSqlComments(sql) {
  return String(sql || "")
    .replace(/\/\*[\s\S]*?\*\//g, " ")
    .replace(/--.*$/gm, " ");
}

function stripSqlStringLiterals(sql) {
  return String(sql || "")
    .replace(/'(?:''|[^'])*'/g, "''")
    .replace(/"(?:[""]|[^"])*"/g, '""')
    .replace(/`(?:``|[^`])*`/g, "``");
}

function normalizeSqlForInspection(sql) {
  return compactWhitespace(stripSqlStringLiterals(stripSqlComments(sql)));
}

function firstKeyword(sql) {
  const match = normalizeSqlForInspection(sql).match(/^([A-Za-z]+)/);
  return match ? match[1].toUpperCase() : null;
}

function splitStatements(sql) {
  const normalized = normalizeSqlForInspection(sql);
  return normalized
    .split(";")
    .map((part) => part.trim())
    .filter(Boolean);
}

function stripTrailingSemicolons(sql) {
  return String(sql || "")
    .trim()
    .replace(/;+\s*$/, "");
}

function firstWord(sql) {
  const match = String(sql || "").trim().match(/^([A-Za-z]+)/);
  return match ? match[1].toUpperCase() : null;
}

function isIdentChar(ch) {
  return /[A-Za-z0-9_]/.test(ch);
}

function skipWhitespace(sql, i) {
  while (i < sql.length && /\s/.test(sql[i])) {
    i += 1;
  }
  return i;
}

function regionMatches(sql, offset, token) {
  if (offset < 0 || offset + token.length > sql.length) {
    return false;
  }
  if (!sql.startsWith(token, offset)) {
    return false;
  }
  const next = offset + token.length;
  return next === sql.length || !isIdentChar(sql[next]);
}

function skipIdent(sql, i) {
  if (i >= sql.length) {
    return -1;
  }
  const c = sql[i];
  if (c === '"' || c === "`" || c === "'") {
    const quote = c;
    i += 1;
    while (i < sql.length && sql[i] !== quote) {
      i += 1;
    }
    return i >= sql.length ? -1 : i + 1;
  }
  if (!/[A-Za-z_]/.test(c)) {
    return -1;
  }
  i += 1;
  while (i < sql.length && isIdentChar(sql[i])) {
    i += 1;
  }
  return i;
}

function skipBalancedParens(sql, openAt) {
  if (sql[openAt] !== "(") {
    return -1;
  }
  let depth = 0;
  for (let i = openAt; i < sql.length; i += 1) {
    if (sql[i] === "(") {
      depth += 1;
    } else if (sql[i] === ")") {
      depth -= 1;
      if (depth === 0) {
        return i + 1;
      }
    }
  }
  return -1;
}

function remainderAfterWithClause(sql) {
  if (!sql || !sql.startsWith("WITH")) {
    return null;
  }
  let i = skipWhitespace(sql, 4);
  if (regionMatches(sql, i, "RECURSIVE")) {
    i = skipWhitespace(sql, i + 9);
  }
  while (i < sql.length) {
    const next = skipIdent(sql, i);
    if (next < 0) {
      return null;
    }
    i = skipWhitespace(sql, next);
    if (sql[i] === "(") {
      i = skipBalancedParens(sql, i);
      if (i < 0) {
        return null;
      }
      i = skipWhitespace(sql, i);
    }
    if (!regionMatches(sql, i, "AS")) {
      return null;
    }
    i = skipWhitespace(sql, i + 2);
    if (regionMatches(sql, i, "NOT")) {
      i = skipWhitespace(sql, i + 3);
    }
    if (regionMatches(sql, i, "MATERIALIZED")) {
      i = skipWhitespace(sql, i + 12);
    }
    if (sql[i] !== "(") {
      return null;
    }
    i = skipBalancedParens(sql, i);
    if (i < 0) {
      return null;
    }
    i = skipWhitespace(sql, i);
    if (sql[i] === ",") {
      i = skipWhitespace(sql, i + 1);
      continue;
    }
    return i < sql.length ? sql.slice(i) : "";
  }
  return null;
}

function findForbiddenMutation(sql) {
  if (FOR_UPDATE_PATTERN.test(sql)) {
    return "UPDATE";
  }

  const cteMutation = sql.match(CTE_MUTATION_PATTERN);
  if (cteMutation) {
    return cteMutation[1];
  }

  const first = firstWord(sql);
  if (first === "EXPLAIN") {
    const inner = sql.replace(EXPLAIN_PREFIX_PATTERN, "").trim();
    const innerFirst = firstWord(inner);
    if (!innerFirst) {
      return null;
    }
    if (!ALLOWED_READ_ONLY_KEYWORDS.has(innerFirst)) {
      return innerFirst;
    }
    return findForbiddenMutation(inner);
  }

  if (first === "WITH") {
    const main = remainderAfterWithClause(sql);
    if (main != null) {
      const mainFirst = firstWord(main);
      if (mainFirst && FORBIDDEN_SQL_KEYWORD_SET.has(mainFirst)) {
        return mainFirst;
      }
    } else {
      const trailing = sql.match(TRAILING_DML_PATTERN);
      if (trailing) {
        return trailing[1];
      }
    }
  }

  return null;
}

/**
 * Detect mutating statements nested inside an otherwise read-only wrapper.
 * Do not scan bare \bKEYWORD\b — COMMENT/CALL are common table names
 * (`SELECT * FROM comment`) and REPLACE() is a function.
 */
function containsForbiddenKeyword(sql) {
  const inspect = normalizeSqlForInspection(sql).toUpperCase();
  return findForbiddenMutation(inspect);
}

function validateReadOnlySql(sql, { allowExplain = true } = {}) {
  if (!sql || !String(sql).trim()) {
    return {
      ok: false,
      reason: "Query is required.",
    };
  }

  const statements = splitStatements(sql);
  if (statements.length !== 1) {
    return {
      ok: false,
      reason: "Phase 1 MCP only allows a single SQL statement.",
    };
  }

  const statement = statements[0];
  const keyword = firstKeyword(statement);
  if (!keyword || !ALLOWED_READ_ONLY_KEYWORDS.has(keyword)) {
    return {
      ok: false,
      reason: "Only read-only SQL is allowed (SELECT, WITH, SHOW, DESCRIBE, DESC, EXPLAIN).",
    };
  }

  if (!allowExplain && keyword === "EXPLAIN") {
    return {
      ok: false,
      reason: "Pass the underlying SELECT/WITH query, not EXPLAIN itself.",
    };
  }

  if (keyword === "EXPLAIN" && /\bANALYZ[EA]\b/i.test(statement)) {
    return {
      ok: false,
      reason: "EXPLAIN ANALYZE is blocked in phase 1 MCP because it executes the query.",
    };
  }

  const forbiddenKeyword = containsForbiddenKeyword(statement);
  if (forbiddenKeyword) {
    return {
      ok: false,
      reason: `Blocked potentially mutating SQL keyword: ${forbiddenKeyword}.`,
    };
  }

  return {
    ok: true,
    normalizedQuery: stripTrailingSemicolons(sql),
    firstKeyword: keyword,
  };
}

function clampInteger(value, min, max, fallback) {
  if (value == null) {
    return fallback;
  }

  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed)) {
    return fallback;
  }

  return Math.min(max, Math.max(min, parsed));
}

// Live bearer-token resolution. The MCP server is a long-lived stdio
// subprocess; inside the DeepSQL Agent container the provisioner rotates the
// token on disk (DEEPSQL_TOKEN_FILE) WITHOUT respawning us. Reading the token
// per request — cached by mtime so we only touch disk when the file actually
// changes — lets a rotated token take effect with no container restart. Editor
// and CLI installs set no token file and keep using the env snapshot
// (config.authToken), so their behaviour is unchanged.
let _tokenFileCache = null; // { path, mtimeMs, token }

function readTokenFile(tokenFile) {
  const stat = fs.statSync(tokenFile);
  if (
    _tokenFileCache &&
    _tokenFileCache.path === tokenFile &&
    _tokenFileCache.mtimeMs === stat.mtimeMs
  ) {
    return _tokenFileCache.token;
  }
  const token = fs.readFileSync(tokenFile, "utf8").trim();
  _tokenFileCache = { path: tokenFile, mtimeMs: stat.mtimeMs, token };
  return token;
}

function getAuthToken(config) {
  if (config && config.tokenFile) {
    try {
      const token = readTokenFile(config.tokenFile);
      if (token) {
        return token;
      }
    } catch {
      // Any stat/read error → fall back to the env token snapshot so we never
      // hard-fail just because the file is momentarily missing mid-rewrite.
    }
  }
  return (config && config.authToken) || "";
}

// Force the next getAuthToken() to re-read from disk regardless of mtime. Used
// by the 401 self-heal path, where the provisioner may have just rewritten the
// token file (same second → identical mtime granularity on some filesystems).
function invalidateTokenCache() {
  _tokenFileCache = null;
}

function buildHeaders(config, extraHeaders = {}) {
  const headers = {
    Accept: "application/json",
    ...extraHeaders,
  };

  const authToken = getAuthToken(config);
  if (authToken) {
    headers.Authorization = `Bearer ${authToken}`;
  }

  // Origin-tracking headers so the backend audit row can distinguish
  // CLI/MCP traffic and identify which editor invoked the MCP server.
  // `clientAgent` carries the value of DEEPSQL_MCP_USER_ID — editor configs
  // set this to "claude-desktop", "cursor-mcp", "codex-mcp", etc.
  if (config.clientType) {
    headers["X-DeepSQL-Client-Type"] = config.clientType;
  }
  if (config.clientAgent) {
    headers["X-DeepSQL-Client-Agent"] = config.clientAgent;
  }
  if (config.clientVersion) {
    headers["X-DeepSQL-Client-Version"] = config.clientVersion;
  }

  return headers;
}

function resolveApiUrl(baseUrl, path) {
  const normalizedPath = String(path || "").replace(/^\/+/, "");
  return new URL(normalizedPath, baseUrl).toString();
}

async function performFetch(config, path, { method = "GET", json, headers } = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), config.timeoutMs);
  const url = resolveApiUrl(config.baseUrl, path);

  try {
    return await fetch(url, {
      method,
      headers: buildHeaders(
        config,
        json == null
          ? headers
          : {
              "Content-Type": "application/json",
              ...headers,
            },
      ),
      body: json == null ? undefined : JSON.stringify(json),
      signal: controller.signal,
    });
  } catch (error) {
    if (error.name === "AbortError") {
      throw new DeepSqlApiError(
        `DeepSQL API request timed out after ${config.timeoutMs}ms.`,
        408,
      );
    }

    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

async function callDeepSqlApi(config, path, options = {}) {
  const tokenBefore = getAuthToken(config);
  let response = await performFetch(config, path, options);

  // 401 self-heal: our long-lived subprocess may be holding a token the agent
  // provisioner has since rotated on disk. Re-read the token file (bypassing
  // the mtime cache) and retry exactly once if it actually changed. We
  // deliberately do NOT retry 403 — that's an RBAC denial, not stale creds —
  // and only retry when a tokenFile is configured (editor/CLI installs aren't).
  if (response.status === 401 && config && config.tokenFile) {
    invalidateTokenCache();
    const tokenAfter = getAuthToken(config);
    if (tokenAfter && tokenAfter !== tokenBefore) {
      response = await performFetch(config, path, options);
    }
  }

  const rawBody = await response.text();
  let payload = null;
  if (rawBody) {
    try {
      payload = JSON.parse(rawBody);
    } catch {
      payload = rawBody;
    }
  }

  if (!response.ok) {
    const message =
      (payload && typeof payload === "object" && payload.message) ||
      response.statusText ||
      "DeepSQL API request failed";
    throw new DeepSqlApiError(message, response.status, payload);
  }

  return payload;
}

function summarizeConnections(connections) {
  const lines = connections.map((connection) => {
    const name = connection.connectionName || connection.name || connection.id;
    const type = connection.dbType || "unknown";
    return `- ${name} (${type}) — ${connection.id}`;
  });

  return lines.length
    ? `Found ${connections.length} connection(s):\n${lines.join("\n")}`
    : "No connections were returned by DeepSQL.";
}

function summarizeSchema(payload) {
  const schema = payload?.schema || payload;
  const tableCount = schema?.totalTables ?? schema?.tables?.length ?? 0;
  const viewCount = schema?.totalViews ?? 0;
  const databaseName = schema?.databaseName || "unknown";
  const dbType = schema?.dbType || "unknown";

  return `Schema for ${databaseName} (${dbType}) with ${tableCount} tables and ${viewCount} views.`;
}

function summarizeObjects(payload) {
  const objects = payload?.objects || [];
  const preview = objects
    .slice(0, 10)
    .map((object) => `${object.type}:${object.name}`)
    .join(", ");
  return preview
    ? `Fetched ${objects.length} database object(s). Preview: ${preview}`
    : "No database objects were returned.";
}

function summarizeBrainContext(payload) {
  if (!payload || typeof payload !== "object") {
    return "Brain context unavailable.";
  }
  // /training/retrieve diagnostic shape
  if (Array.isArray(payload?.results) || payload?.totalResults != null) {
    const total =
      payload.totalResults ?? (Array.isArray(payload.results) ? payload.results.length : 0);
    const tableCount = Array.isArray(payload.tablesCovered) ? payload.tablesCovered.length : 0;
    return `Retrieved ${total} ranked snippet(s) covering ${tableCount} table(s).`;
  }
  // /training/context rich shape (RetrievedContextResult)
  const tables = Array.isArray(payload.ragTableNames)
    ? payload.ragTableNames.length
    : payload.ragTableNames
      ? Object.keys(payload.ragTableNames).length
      : 0;
  const types = payload.typeCounts
    ? Object.entries(payload.typeCounts).map(([k, v]) => `${k}=${v}`).join(", ")
    : "";
  const intent = payload.retrievalIntent || "n/a";
  const skipped = payload.skipped ? ` (skipped: ${payload.skipReason || "?"})` : "";
  return `Brain context: intent=${intent}, topK=${payload.retrievalTopK ?? "?"}, results=${payload.resultCount ?? 0}, tables=${tables}${types ? `, types[${types}]` : ""}${skipped}.`;
}

function summarizeBusinessRules(payload) {
  const active = payload?.activeRuleCount ?? (payload?.activeRules?.length ?? 0);
  const guards = payload?.applicableGuardrailCount ?? (payload?.applicableGuardrails?.length ?? 0);
  return `Business rules: ${active} active, ${guards} applicable guardrail(s).`;
}

function summarizeRelationships(payload) {
  const list = Array.isArray(payload) ? payload : payload?.relationships || [];
  const high = list.filter((r) => (r.confidence ?? 0) >= 0.8).length;
  return `${list.length} relationship(s) (${high} high-confidence).`;
}

function summarizeBrainRecommendations(payload) {
  const list = (payload && payload.suggestions) || [];
  if (!list.length) return "No brain recommendations pending review for this connection.";
  const top = list
    .slice(0, 5)
    .map((s) => `${s.priority || ""} ${s.columnName ? `${s.tableName}.${s.columnName}` : s.tableName}`.trim());
  return `${payload.totalCount ?? list.length} recommendation(s) to review. Top: ${top.join("; ")}. Accept the good ones with save_brain_note.`;
}

function summarizeBrainNoteSaved(payload) {
  const target = payload && (payload.columnName ? `${payload.tableName}.${payload.columnName}` : payload.tableName);
  return `Saved to brain${target ? ` — ${target}` : ""}. It now grounds future answers for this connection (shared, company-level).`;
}

function summarizeBrainNotes(payload) {
  const list = Array.isArray(payload) ? payload : (payload && payload.notes) || [];
  return `${list.length} brain note(s) for the requested scope.`;
}

function summarizeAntiPatterns(payload, kind) {
  if (kind === "table") {
    const tables = payload && typeof payload === "object" ? Object.keys(payload).length : 0;
    return `Anti-patterns across ${tables} table(s).`;
  }
  const list = Array.isArray(payload) ? payload : payload?.patterns || [];
  const sev = list.reduce((acc, p) => {
    const s = p.severity || "UNKNOWN";
    acc[s] = (acc[s] || 0) + 1;
    return acc;
  }, {});
  const sevStr = Object.entries(sev).map(([k, v]) => `${k}=${v}`).join(", ");
  return `${list.length} query anti-pattern(s)${sevStr ? ` (${sevStr})` : ""}.`;
}

function formatMillisHuman(ms) {
  if (ms == null || !Number.isFinite(ms) || ms <= 0) return null;
  if (ms >= 86_400_000) return `${(ms / 86_400_000).toFixed(1)}d`;
  if (ms >= 3_600_000) return `${(ms / 3_600_000).toFixed(1)}h`;
  if (ms >= 60_000) return `${(ms / 60_000).toFixed(1)}m`;
  if (ms >= 1_000) return `${(ms / 1_000).toFixed(1)}s`;
  return `${ms}ms`;
}

function summarizeApplyResult(payload) {
  if (!payload || typeof payload !== "object") {
    return "Apply call returned no body.";
  }
  const status = payload.status || "?";
  const mode = payload.mode || "?";
  if (status === "BLOCKED_NEEDS_CONFIRMATION") {
    return `[${mode}] blocked — pass confirm=true to mutate the database.`;
  }
  if (status === "NOT_FOUND") {
    return `[${mode}] recommendation not found: ${payload.recommendationId || "?"}`;
  }
  if (status === "NO_USABLE_SAMPLES") {
    return `[${mode}] no literal-bearing contributing queries available; cannot measure.`;
  }
  if (status === "FAILED") {
    return `[${mode}] failed: ${payload.message || "(no message)"}`;
  }

  const lines = [];
  lines.push(`[${mode}] ${status} — ${payload.executedDdl || "(no ddl)"}`);
  if (payload.beforeCost != null && payload.afterCost != null) {
    const pct = payload.costReductionPct;
    lines.push(
      `  planner cost: ${payload.beforeCost.toFixed(0)} → ${payload.afterCost.toFixed(0)}` +
        (pct != null ? ` (${pct >= 0 ? "−" : "+"}${Math.abs(pct).toFixed(1)}%)` : "")
    );
  }
  if (payload.beforeWallTimeMs != null && payload.afterWallTimeMs != null) {
    const pct = payload.wallTimeImprovementPct;
    lines.push(
      `  wall time: ${payload.beforeWallTimeMs.toFixed(1)}ms → ${payload.afterWallTimeMs.toFixed(1)}ms` +
        (pct != null ? ` (${pct >= 0 ? "−" : "+"}${Math.abs(pct).toFixed(1)}%)` : "")
    );
  }
  if (Array.isArray(payload.samples) && payload.samples.length) {
    lines.push(`  ${payload.samples.length} contributing query sample(s):`);
    for (const s of payload.samples.slice(0, 5)) {
      const before = s.beforeCost != null ? s.beforeCost.toFixed(0) : "?";
      const after = s.afterCost != null ? s.afterCost.toFixed(0) : "?";
      lines.push(
        `    fp=${(s.fingerprint || "?").slice(0, 12)} cost ${before} → ${after}` +
          (s.error ? ` (error: ${s.error})` : "")
      );
    }
  }
  return lines.join("\n");
}

function summarizeIndexRecommendations(payload) {
  const list = Array.isArray(payload) ? payload : payload?.recommendations || [];
  if (!list.length) {
    return "No pending index recommendations. The scheduler may not have run yet, or the workload has none worth flagging.";
  }
  const lines = list.slice(0, 10).map((rec, idx) => {
    const table = rec.tableName || "?";
    const prio = rec.priority || "?";
    const occ = rec.occurrenceCount != null ? `seen ${rec.occurrenceCount}×` : "";
    const net = formatMillisHuman(rec.netBenefitMs);
    const writeCost = formatMillisHuman(rec.writeCostScore);
    const evidence = rec.evidenceCount != null && rec.evidenceCount > 0
      ? `${rec.evidenceCount} ev` : "";
    const isDrop = rec.kind === "DROP_INDEX";
    const action = isDrop ? "DROP" : "CREATE";
    const target = isDrop
      ? `${table}.${rec.indexName || "?"} (unused)`
      : `${table}(${rec.columnNames || "?"})`;
    // Net benefit is the DBA-grade signal — surface it prominently.
    const benefitClause = net
      ? `net=${net} saved` + (writeCost ? `, write=${writeCost}` : "")
      : (rec.estimatedImpact != null ? `impact ${rec.estimatedImpact}` : "");
    const meta = [prio, occ, benefitClause, evidence].filter(Boolean).join(", ");
    return `${idx + 1}. [${action}] ${target}${meta ? ` — ${meta}` : ""}`;
  });
  return `Top ${list.length} pending index recommendation(s):\n${lines.join("\n")}`;
}

function summarizeSlowQueries(payload) {
  // Backend returns SlowQueryAnalysis with `topSlowQueries` (the field name
  // varies; tolerate both `queries` and `topSlowQueries`).
  const list = Array.isArray(payload?.topSlowQueries)
    ? payload.topSlowQueries
    : Array.isArray(payload?.queries) ? payload.queries : [];
  const total = payload?.totalCount ?? list.length;
  const avg = payload?.avgDurationMs;
  const max = payload?.maxDurationMs;

  // Three counts matter to a calling agent that's about to EXPLAIN one of
  // these queries:
  //
  //   recovered = sourceTruncated AND queryTextRecoveredFromLogs
  //     → the live stats source truncated this query, but DeepSQL recovered
  //       the full SQL from previously-ingested slow-log data in
  //       query_lineage. EXPLAIN will work.
  //
  //   stillTruncated = sourceTruncated AND NOT queryTextRecoveredFromLogs
  //     → still truncated; EXPLAIN will fail or return a partial plan.
  //
  //   neither → normal full-text query, no warning needed.
  const recovered = list.filter((q) =>
    q && q.sourceTruncated === true && q.queryTextRecoveredFromLogs === true
  ).length;
  const stillTruncated = list.filter((q) =>
    q && q.sourceTruncated === true && q.queryTextRecoveredFromLogs !== true
  ).length;

  const parts = [];
  if (recovered > 0) {
    parts.push(
      ` ℹ ${recovered} ${recovered === 1 ? "query was" : "queries were"} truncated by `
      + `the database server (default 1024B) but DeepSQL recovered the full SQL `
      + `from previously-ingested slow-log data — EXPLAIN against \`queryText\` will work.`
    );
  }
  if (stillTruncated > 0) {
    parts.push(
      ` ⚠ ${stillTruncated} ${stillTruncated === 1 ? "query is" : "queries are"} still `
      + `truncated and DeepSQL has no full-text copy on file. EXPLAIN will be unreliable. `
      + `Fix: ingest the slow query log file for this connection, OR raise `
      + `\`pg_stat_statements.track_activity_query_size\` (PG) / `
      + `\`performance_schema_max_sql_text_length\` (MySQL) and restart, then re-collect.`
    );
  }

  return `${total} slow query/queries`
    + `${avg != null ? `, avg=${avg}ms` : ""}`
    + `${max != null ? `, max=${max}ms` : ""}.`
    + parts.join("");
}

function summarizeTrackedQueries(payload) {
  const list = Array.isArray(payload) ? payload : [];
  if (list.length === 0) {
    return "No tracked queries yet. The daily analysis runs at 01:30, or trigger one with trigger_slow_query_analysis.";
  }
  const top = list
    .slice()
    .sort((a, b) => (b.meanExecMs ?? 0) - (a.meanExecMs ?? 0))
    .slice(0, 5)
    .map((q) => {
      const fp = String(q.fingerprint || "").slice(0, 8);
      const ms = q.meanExecMs != null ? `${Math.round(q.meanExecMs)}ms` : "?ms";
      const calls = q.callsDelta != null ? ` ×${q.callsDelta}` : "";
      const reg = q.regressionFactor != null && q.regressionFactor > 1
        ? ` ⚠ ${q.regressionFactor.toFixed(2)}x slower` : "";
      const sql = String(q.normalizedSql || "").replace(/\s+/g, " ").slice(0, 60);
      return `  ${fp}… avg=${ms}${calls}${reg} — ${sql}`;
    });
  const regCount = list.filter((q) => q.regressionFactor != null && q.regressionFactor > 1).length;
  return `${list.length} tracked query/queries (30-day window)${regCount > 0 ? `, ${regCount} regressed` : ""}.\nSlowest:\n${top.join("\n")}`;
}

function summarizeSlowQueryCustomers(payload) {
  const list = Array.isArray(payload) ? payload : [];
  if (list.length === 0) {
    return "No customer attribution data yet. Configure a tenant column in slow-query analytics settings.";
  }
  const top = list.slice(0, 5).map((c, i) => {
    const name = c.customerName || c.customerId || "unknown";
    const queries = c.queryCount != null ? `${c.queryCount} queries` : "";
    const totalMs = c.totalExecMs != null ? `, ${Math.round(c.totalExecMs / 1000)}s total` : "";
    return `  ${i + 1}. ${name}${queries ? ` — ${queries}` : ""}${totalMs}`;
  });
  return `${list.length} customer(s) with slow queries. Top by load:\n${top.join("\n")}`;
}

function summarizeQuerySamples(payload) {
  const list = Array.isArray(payload) ? payload : [];
  if (list.length === 0) {
    return "No samples found for this query fingerprint.";
  }
  const times = list.map((s) => s.execMs).filter((t) => t != null);
  const minMs = times.length ? Math.min(...times) : null;
  const maxMs = times.length ? Math.max(...times) : null;
  const avgMs = times.length ? Math.round(times.reduce((a, b) => a + b, 0) / times.length) : null;
  const sources = [...new Set(list.map((s) => s.source).filter(Boolean))].join(", ");
  return `${list.length} sample(s)`
    + (minMs != null ? `, min=${minMs}ms avg=${avgMs}ms max=${maxMs}ms` : "")
    + (sources ? `, source: ${sources}` : "")
    + `. Use rawSql for EXPLAIN.`;
}

function summarizeSlowQueryInsights(payload) {
  const list = Array.isArray(payload) ? payload : [];
  if (list.length === 0) {
    return "No insights found for the requested window/kind. Run analyze-now or wait for the next daily pass.";
  }
  const bySeverity = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0 };
  for (const ins of list) {
    const sev = (ins.severity || "LOW").toUpperCase();
    if (sev in bySeverity) bySeverity[sev]++;
  }
  const sevLine = Object.entries(bySeverity)
    .filter(([, n]) => n > 0)
    .map(([k, n]) => `${n} ${k.toLowerCase()}`)
    .join(", ");
  const top = list.slice(0, 3).map((ins, i) => {
    const sev = ins.severity ? `[${ins.severity}] ` : "";
    const title = ins.title || ins.insightType || "insight";
    const desc = String(ins.description || ins.message || "").replace(/\s+/g, " ").slice(0, 120);
    return `  ${i + 1}. ${sev}${title}${desc ? ` — ${desc}` : ""}`;
  });
  return `${list.length} insight(s) (${sevLine || "none by severity"}).\n${top.join("\n")}`;
}

function summarizeSlowQueryOptimization(payload) {
  if (!payload) return "No optimization result returned.";
  const recList = Array.isArray(payload.recommendations) ? payload.recommendations : [];
  const idxList = Array.isArray(payload.suggestedIndexes) ? payload.suggestedIndexes : [];
  const parts = [];
  if (payload.optimizedQuery) {
    parts.push("Optimized query available in `optimizedQuery`.");
  }
  if (idxList.length > 0) {
    parts.push(`${idxList.length} index suggestion(s): ${idxList.map((x) => x.indexDdl || x.column || JSON.stringify(x)).slice(0, 3).join("; ")}`);
  }
  if (recList.length > 0) {
    parts.push(`${recList.length} recommendation(s): ${recList.map((r) => r.title || r.summary || JSON.stringify(r)).slice(0, 3).join("; ")}`);
  }
  if (payload.estimatedImprovementPercent != null) {
    parts.push(`Estimated improvement: ${payload.estimatedImprovementPercent.toFixed(0)}%`);
  }
  return parts.length > 0 ? parts.join(". ") + "." : "Optimization complete. See structuredContent for details.";
}

function summarizeTableGrowth(payload) {
  // Backend returns { success, trends: { sizeOverTime[], growthOverTime[],
  // rowCountOverTime[] }, days }. We don't want to dump the raw arrays into
  // the agent's context — collapse to a per-table headline and a top-3
  // "growing fastest" list.
  const trends = payload?.trends || {};
  const sizeOverTime = Array.isArray(trends.sizeOverTime) ? trends.sizeOverTime : [];
  if (sizeOverTime.length === 0) {
    return "No table-growth history for this connection in the requested window. "
      + "The customer may not have stats-snapshot collection enabled yet.";
  }

  // Roll up per-table: first vs last snapshot.
  const byTable = new Map();
  for (const point of sizeOverTime) {
    const t = point.table || "(unknown)";
    if (!byTable.has(t)) byTable.set(t, []);
    byTable.get(t).push(point);
  }
  const rows = [];
  for (const [table, points] of byTable.entries()) {
    points.sort((a, b) => String(a.timestamp).localeCompare(String(b.timestamp)));
    const first = points[0];
    const last = points[points.length - 1];
    const firstBytes = first?.sizeBytes ?? 0;
    const lastBytes = last?.sizeBytes ?? 0;
    const deltaBytes = lastBytes - firstBytes;
    rows.push({ table, firstBytes, lastBytes, deltaBytes });
  }
  rows.sort((a, b) => Math.abs(b.deltaBytes) - Math.abs(a.deltaBytes));

  const days = payload?.days != null ? `${payload.days}d` : "window";
  const top = rows.slice(0, 3).map((r) => {
    const arrow = r.deltaBytes >= 0 ? "↑" : "↓";
    const pct = r.firstBytes > 0
      ? ` (${r.deltaBytes >= 0 ? "+" : ""}${((r.deltaBytes / r.firstBytes) * 100).toFixed(1)}%)`
      : "";
    return `${r.table} ${arrow} ${formatBytesHumanLib(Math.abs(r.deltaBytes))}${pct}`;
  });
  return `${rows.length} table(s) with growth data over ${days}. `
    + `Most-changed: ${top.join("; ")}.`;
}

function summarizeGrowthAnomalies(payload) {
  // Backend returns { success, anomalies[], statistics: { total, warning,
  // critical, info, acknowledged, unacknowledged } }. Agents should know
  // BEFORE drilling into a slow query whether a recent growth anomaly is
  // the real root cause.
  const list = Array.isArray(payload?.anomalies) ? payload.anomalies : [];
  if (list.length === 0) {
    return "No growth anomalies detected in the requested window.";
  }
  const stats = payload?.statistics || {};
  const total = stats.total ?? list.length;
  const crit = stats.critical ?? 0;
  const warn = stats.warning ?? 0;
  const unack = stats.unacknowledged ?? 0;

  // Surface the worst recent one so the agent has something concrete to
  // reference without having to walk the structured payload.
  const worst = list.find((a) => a && a.severity === "CRITICAL")
    || list.find((a) => a && a.severity === "WARNING")
    || list[0];
  const worstLine = worst
    ? ` Top: [${worst.severity || "INFO"}] ${worst.tableName || "?"} — `
      + `${worst.anomalyType || "growth"}`
      + (worst.sizeGrowthPercent != null
        ? ` ${worst.sizeGrowthPercent > 0 ? "+" : ""}${worst.sizeGrowthPercent.toFixed(1)}%`
        : "")
    : "";
  return `${total} growth anomal${total === 1 ? "y" : "ies"} `
    + `(${crit} critical, ${warn} warning, ${unack} unacknowledged).${worstLine}`;
}

function formatBytesHumanLib(bytes) {
  if (bytes == null) return "?";
  const abs = Math.abs(bytes);
  if (abs < 1024) return `${bytes} B`;
  if (abs < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (abs < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  if (abs < 1024 * 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  return `${(bytes / (1024 * 1024 * 1024 * 1024)).toFixed(2)} TB`;
}

function summarizeQueryResult(payload) {
  const result = payload?.result || payload?.data || payload;
  const rowCount = result?.rowCount ?? 0;
  const columns = Array.isArray(result?.columns) ? result.columns.join(", ") : "";
  const limited = result?.isLimited ? " (limited)" : "";
  return `Query returned ${rowCount} row(s)${limited}${columns ? ` with columns: ${columns}` : ""}.`;
}

function summarizeExplain(payload) {
  const summaryStats = payload?.summaryStats;
  if (summaryStats) {
    return `EXPLAIN completed. Summary: ${summaryStats}`;
  }
  const planType = payload?.queryType || "query";
  return `EXPLAIN completed for ${planType}.`;
}

function buildToolResult(name, payload, extra = {}) {
  let summary;

  switch (name) {
    case "list_connections":
      summary = summarizeConnections(payload);
      break;
    case "get_schema":
      summary = summarizeSchema(payload);
      break;
    case "get_database_objects":
      summary = summarizeObjects(payload);
      break;
    case "get_brain_context":
      summary = summarizeBrainContext(payload);
      break;
    case "list_business_rules":
      summary = summarizeBusinessRules(payload);
      break;
    case "get_relationships":
      summary = summarizeRelationships(payload);
      break;
    case "get_anti_patterns":
      summary = summarizeAntiPatterns(payload, extra.kind || "table");
      break;
    case "analyze_slow_queries":
      summary = summarizeSlowQueries(payload);
      break;
    case "list_tracked_queries":
      summary = summarizeTrackedQueries(payload);
      break;
    case "get_slow_query_customers":
      summary = summarizeSlowQueryCustomers(payload);
      break;
    case "get_query_samples":
      summary = summarizeQuerySamples(payload);
      break;
    case "get_slow_query_insights":
      summary = summarizeSlowQueryInsights(payload);
      break;
    case "optimize_slow_query":
      summary = summarizeSlowQueryOptimization(payload);
      break;
    case "get_table_growth":
      summary = summarizeTableGrowth(payload);
      break;
    case "get_growth_anomalies":
      summary = summarizeGrowthAnomalies(payload);
      break;
    case "list_brain_recommendations":
      summary = summarizeBrainRecommendations(payload);
      break;
    case "save_brain_note":
      summary = summarizeBrainNoteSaved(payload);
      break;
    case "list_brain_notes":
      summary = summarizeBrainNotes(payload);
      break;
    case "get_index_recommendations":
      summary = summarizeIndexRecommendations(payload);
      break;
    case "apply_index_recommendation":
      summary = summarizeApplyResult(payload);
      break;
    case "execute_sql":
      summary = summarizeQueryResult(payload);
      break;
    case "analyze_query_plan":
      summary = summarizeExplain(payload);
      break;
    default:
      summary = JSON.stringify(payload, null, 2);
      break;
  }

  return {
    content: [
      {
        type: "text",
        text: summary,
      },
    ],
    // MCP spec requires `structuredContent` to be a JSON object. Several tools
    // (list_connections, get_relationships, list_business_rules, …) return a
    // top-level array from the backend; wrap those so spec-strict clients
    // (e.g. the `mcp` Python SDK) don't reject the result.
    structuredContent: Array.isArray(payload) ? { items: payload } : payload,
  };
}

function buildToolError(message, extra = {}) {
  return {
    content: [
      {
        type: "text",
        text: message,
      },
    ],
    structuredContent: {
      error: message,
      ...extra,
    },
    isError: true,
  };
}

// Short-TTL in-memory cache for the connections list. The MCP server is a
// long-lived process and `list_connections` is the "call this first" tool, so an
// agent hits it repeatedly in a session — caching makes those calls instant. The
// list changes rarely; a 30s TTL keeps it fresh enough.
const CONNECTIONS_CACHE_TTL_MS = 30000;
let _connectionsCache = null; // { key, ts, payload }

async function fetchConnectionsCached(config) {
  const key = `${(config && config.baseUrl) || ""}|${getAuthToken(config)}`;
  if (_connectionsCache && _connectionsCache.key === key
      && Date.now() - _connectionsCache.ts < CONNECTIONS_CACHE_TTL_MS) {
    return _connectionsCache.payload;
  }
  const payload = await callDeepSqlApi(config, "/connections");
  _connectionsCache = { key, ts: Date.now(), payload };
  return payload;
}

async function handleToolCall(config, name, args = {}) {
  switch (name) {
    case "list_connections": {
      const payload = await fetchConnectionsCached(config);
      return buildToolResult(name, payload);
    }

    case "get_schema": {
      const connectionId = String(args.connectionId || "").trim();
      const payload = await callDeepSqlApi(
        config,
        `/connections/${encodeURIComponent(connectionId)}/schema`,
      );
      return buildToolResult(name, payload);
    }

    case "get_database_objects": {
      const connectionId = String(args.connectionId || "").trim();
      const payload = await callDeepSqlApi(
        config,
        `/connections/${encodeURIComponent(connectionId)}/objects`,
      );
      return buildToolResult(name, payload);
    }

    case "get_brain_context": {
      const connectionId = String(args.connectionId || "").trim();
      const question = String(args.question || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      if (!question) return buildToolError("question is required.");

      // Route based on whether the caller wants ranked diagnostics (topK) or
      // the rich training-context payload.
      let payload;
      if (args.topK != null) {
        const topK = clampInteger(args.topK, 1, 100, 20);
        const path =
          `/training/retrieve/${encodeURIComponent(connectionId)}` +
          `?q=${encodeURIComponent(question)}&topK=${topK}`;
        payload = await callDeepSqlApi(config, path);
      } else {
        payload = await callDeepSqlApi(
          config,
          `/training/context/${encodeURIComponent(connectionId)}`,
          { method: "POST", json: { question } },
        );
      }
      return buildToolResult(name, payload);
    }

    case "list_business_rules": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      let path = `/business-rules/connection/${encodeURIComponent(connectionId)}`;
      if (args.question) {
        path += `?question=${encodeURIComponent(String(args.question))}`;
      }
      const payload = await callDeepSqlApi(config, path);
      return buildToolResult(name, payload);
    }

    case "get_relationships": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const payload = await callDeepSqlApi(
        config,
        `/brain/inferred-relationships/${encodeURIComponent(connectionId)}`,
      );
      return buildToolResult(name, payload);
    }

    case "get_anti_patterns": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const kind = args.kind === "query" ? "query" : "table";
      let path;
      if (kind === "query") {
        path = `/brain/query-anti-patterns/${encodeURIComponent(connectionId)}`;
        if (args.limit != null) {
          path += `?limit=${clampInteger(args.limit, 1, 500, 50)}`;
        }
      } else {
        path = `/brain/table-anti-patterns/${encodeURIComponent(connectionId)}`;
      }
      const payload = await callDeepSqlApi(config, path);
      return buildToolResult(name, payload, { kind });
    }

    case "list_brain_recommendations": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const limit = clampInteger(args.limit, 1, 100, 10);
      const payload = await callDeepSqlApi(
        config,
        `/brain/notes/suggestions/${encodeURIComponent(connectionId)}?limit=${limit}`,
      );
      return buildToolResult(name, payload);
    }

    case "save_brain_note": {
      const connectionId = String(args.connectionId || "").trim();
      const tableName = String(args.tableName || "").trim();
      const noteText = String(args.noteText || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      if (!tableName) return buildToolError("tableName is required.");
      if (!noteText) return buildToolError("noteText is required.");
      const columnName = args.columnName ? String(args.columnName).trim() : null;
      // Backend enforces manage-content permission (admin) + audits the write.
      const payload = await callDeepSqlApi(config, "/brain/notes", {
        method: "POST",
        json: {
          connectionId,
          scopeType: columnName ? "COLUMN" : "TABLE",
          tableName,
          columnName,
          noteText,
        },
      });
      return buildToolResult(name, payload);
    }

    case "list_brain_notes": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const qs = [];
      if (args.tableName) qs.push(`tableName=${encodeURIComponent(String(args.tableName))}`);
      if (args.columnName) qs.push(`columnName=${encodeURIComponent(String(args.columnName))}`);
      const payload = await callDeepSqlApi(
        config,
        `/brain/notes/${encodeURIComponent(connectionId)}${qs.length ? `?${qs.join("&")}` : ""}`,
      );
      return buildToolResult(name, payload);
    }

    case "get_index_recommendations": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const limit = clampInteger(args.limit, 1, 50, 5);
      const payload = await callDeepSqlApi(
        config,
        `/index-recommendations/${encodeURIComponent(connectionId)}/top?limit=${limit}`,
      );
      return buildToolResult(name, payload);
    }

    case "apply_index_recommendation": {
      const recommendationId = String(args.recommendationId || "").trim();
      if (!recommendationId) return buildToolError("recommendationId is required.");
      const mode = String(args.mode || "DRY_RUN").toUpperCase();
      if (!["DRY_RUN", "APPLY", "APPLY_AND_MEASURE"].includes(mode)) {
        return buildToolError(`Unknown mode: ${mode}. Expected DRY_RUN, APPLY, or APPLY_AND_MEASURE.`);
      }
      const confirm = args.confirm === true;
      if ((mode === "APPLY" || mode === "APPLY_AND_MEASURE") && !confirm) {
        return buildToolError(
          `Mode ${mode} mutates the target database. Re-call with confirm=true to proceed.`,
        );
      }
      const concurrent = args.concurrent === false ? false : true;
      const qs = `?mode=${encodeURIComponent(mode)}&confirm=${confirm}&concurrent=${concurrent}`;
      const payload = await callDeepSqlApi(
        config,
        `/index-recommendations/${encodeURIComponent(recommendationId)}/apply${qs}`,
        { method: "POST" },
      );
      return buildToolResult(name, payload);
    }

    case "analyze_slow_queries": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const params = [];
      if (args.thresholdMs != null) {
        params.push(`threshold=${Number(args.thresholdMs)}`);
      }
      if (args.limit != null) {
        params.push(`limit=${clampInteger(args.limit, 1, 500, 10)}`);
      }
      const qs = params.length ? `?${params.join("&")}` : "";
      const payload = await callDeepSqlApi(
        config,
        `/slow-queries/analyze/${encodeURIComponent(connectionId)}${qs}`,
      );
      return buildToolResult(name, payload);
    }

    case "get_slow_query_timeline": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const fingerprint = String(args.fingerprint || "").trim();
      if (!fingerprint) return buildToolError("fingerprint is required.");
      const payload = await callDeepSqlApi(
        config,
        `/slow-query-analytics/${encodeURIComponent(connectionId)}/timeline/${encodeURIComponent(fingerprint)}`,
      );
      return buildToolResult(name, payload);
    }

    case "get_query_regressions": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const minFactor = args.minFactor != null ? Number(args.minFactor) : 1.5;
      const payload = await callDeepSqlApi(
        config,
        `/slow-query-analytics/${encodeURIComponent(connectionId)}/regressions?minFactor=${minFactor}`,
      );
      return buildToolResult(name, payload);
    }

    case "list_tracked_queries": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const payload = await callDeepSqlApi(
        config,
        `/slow-query-analytics/${encodeURIComponent(connectionId)}/queries`,
      );
      return buildToolResult(name, payload);
    }

    case "get_slow_query_customers": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const payload = await callDeepSqlApi(
        config,
        `/slow-query-analytics/${encodeURIComponent(connectionId)}/customers`,
      );
      return buildToolResult(name, payload);
    }

    case "get_query_samples": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const fingerprint = String(args.fingerprint || "").trim();
      if (!fingerprint) return buildToolError("fingerprint is required.");
      const payload = await callDeepSqlApi(
        config,
        `/slow-query-analytics/${encodeURIComponent(connectionId)}/query/${encodeURIComponent(fingerprint)}/samples`,
      );
      return buildToolResult(name, payload);
    }

    case "get_slow_query_insights": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const kind = String(args.kind || "all").toLowerCase();
      const window = String(args.window || "LAST_7_DAYS").toUpperCase();
      const limit = clampInteger(args.limit, 1, 100, 10);
      const subPath = kind === "all" ? "" : `/${encodeURIComponent(kind)}`;
      const payload = await callDeepSqlApi(
        config,
        `/slow-queries/insights/${encodeURIComponent(connectionId)}${subPath}?window=${encodeURIComponent(window)}&limit=${limit}`,
      );
      return buildToolResult(name, payload);
    }

    case "optimize_slow_query": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const queryText = String(args.queryText || "").trim();
      if (!queryText) return buildToolError("queryText is required.");
      const json = { connectionId, queryText };
      if (args.avgExecutionTimeMs != null) {
        json.avgExecutionTimeMs = Number(args.avgExecutionTimeMs);
      }
      const payload = await callDeepSqlApi(config, "/slow-queries/optimize", {
        method: "POST",
        json,
      });
      return buildToolResult(name, payload);
    }

    case "get_table_growth": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const params = [];
      const days = clampInteger(args.days, 1, 365, 30);
      params.push(`days=${days}`);
      if (args.tableName) {
        params.push(`tableName=${encodeURIComponent(String(args.tableName))}`);
      }
      const payload = await callDeepSqlApi(
        config,
        `/growth-monitoring/trends/${encodeURIComponent(connectionId)}?${params.join("&")}`,
      );
      return buildToolResult(name, payload);
    }

    case "get_growth_anomalies": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const params = [];
      const days = clampInteger(args.days, 1, 365, 30);
      params.push(`days=${days}`);
      if (args.tableName) {
        params.push(`tableName=${encodeURIComponent(String(args.tableName))}`);
      }
      if (args.unacknowledgedOnly === true) {
        params.push("unacknowledgedOnly=true");
      }
      const payload = await callDeepSqlApi(
        config,
        `/growth-monitoring/anomalies/${encodeURIComponent(connectionId)}?${params.join("&")}`,
      );
      return buildToolResult(name, payload);
    }

    case "execute_sql": {
      const connectionId = String(args.connectionId || "").trim();
      const query = String(args.query || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      if (!query) return buildToolError("query is required.");

      // Talk to the canonical Editor endpoint. Backend enforces role-based
      // mutation policy + per-connection ACL + chat data-access policy +
      // WHERE-clause guard + two-step confirmation, then audits the call.
      // Client-side parser validation removed in 0.13.0 — the backend is
      // the source of truth and was always going to be.
      const payload = await callDeepSqlApi(
        config,
        `/connections/${encodeURIComponent(connectionId)}/query`,
        {
          method: "POST",
          json: {
            query,
            limit: clampInteger(args.limit, 1, 1000, 100),
            timeoutSeconds: clampInteger(args.timeoutSeconds, 1, 60, null),
            mutationConfirmed: args.confirmMutation === true,
          },
        },
      );

      // Surface a "requiresConfirmation" response as a non-error structured
      // payload so the calling agent can read warnings and re-send with
      // confirmMutation=true without parsing tool-error text.
      return buildToolResult(name, payload);
    }

    case "analyze_query_plan": {
      const connectionId = String(args.connectionId || "").trim();
      const query = String(args.query || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      if (!query) return buildToolError("query is required.");

      // Route through the canonical Editor endpoint (ExplainController).
      // For useAnalyze=true the backend applies the same mutation policy
      // as execute_sql before wrapping in EXPLAIN ANALYZE.
      const payload = await callDeepSqlApi(config, "/explain/analyze", {
        method: "POST",
        json: {
          connectionId,
          query,
          useAnalyze: args.useAnalyze === true,
          mutationConfirmed: args.confirmMutation === true,
        },
      });

      return buildToolResult(name, payload);
    }

    // ─── Phase A symmetry — tools added to match the CLI surface ─────────

    case "get_current_user": {
      const payload = await callDeepSqlApi(config, "/auth/me");
      return buildToolResult(name, payload);
    }

    case "test_connection": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      // POST /connections/test with just { id } reuses the saved encrypted
      // creds server-side — no secrets cross the wire from the MCP client.
      const payload = await callDeepSqlApi(config, "/connections/test", {
        method: "POST",
        json: { id: connectionId },
      });
      return buildToolResult(name, payload);
    }

    case "show_connection": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      // Backend has no GET /connections/{id}; list + filter mirrors CLI behavior.
      // Server returns secrets already masked.
      const all = await callDeepSqlApi(config, "/connections");
      const list = Array.isArray(all) ? all : [];
      const found = list.find((c) => (c.id || c.connectionId) === connectionId);
      if (!found) return buildToolError(`Connection ${connectionId} not found.`);
      return buildToolResult(name, found);
    }

    case "reinit_connection_brain": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const payload = await callDeepSqlApi(
        config,
        `/connections/${encodeURIComponent(connectionId)}/reinit`,
        { method: "POST", json: { force: args.force === true } },
      );
      return buildToolResult(name, payload);
    }

    case "get_latest_digest": {
      const params = ["size=1"];
      if (args.connectionId) params.push(`connectionId=${encodeURIComponent(args.connectionId)}`);
      const payload = await callDeepSqlApi(config, `/admin/slack/digests?${params.join("&")}`);
      // Server returns a Page<>; surface the first element so consumers don't
      // have to know about Spring Data's content array.
      const first = Array.isArray(payload?.content) ? payload.content[0] : null;
      return buildToolResult(name, first || null);
    }

    case "list_digests": {
      const count = clampInteger(args.count, 1, 100, 10);
      const params = [`size=${count}`];
      if (args.connectionId) params.push(`connectionId=${encodeURIComponent(args.connectionId)}`);
      const payload = await callDeepSqlApi(config, `/admin/slack/digests?${params.join("&")}`);
      return buildToolResult(name, Array.isArray(payload?.content) ? payload.content : []);
    }

    case "get_digest_by_id": {
      const digestId = String(args.digestId || "").trim();
      if (!digestId) return buildToolError("digestId is required.");
      // No GET /admin/slack/digests/{id} — pull recent and filter.
      const params = ["size=100"];
      if (args.connectionId) params.push(`connectionId=${encodeURIComponent(args.connectionId)}`);
      const payload = await callDeepSqlApi(config, `/admin/slack/digests?${params.join("&")}`);
      const list = Array.isArray(payload?.content) ? payload.content : [];
      const found = list.find((d) => String(d.id) === digestId);
      if (!found) return buildToolError(`Digest ${digestId} not in the recent 100 digests.`);
      return buildToolResult(name, found);
    }

    case "get_missing_indexes": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const payload = await callDeepSqlApi(
        config, `/advisor/indexes/${encodeURIComponent(connectionId)}`);
      return buildToolResult(name, payload);
    }

    case "get_index_health": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const payload = await callDeepSqlApi(
        config, `/index-advisor/${encodeURIComponent(connectionId)}/health-report`);
      return buildToolResult(name, payload);
    }

    case "get_unused_indexes": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const payload = await callDeepSqlApi(
        config, `/index-advisor/${encodeURIComponent(connectionId)}/unused`);
      return buildToolResult(name, payload);
    }

    case "get_duplicate_indexes": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const payload = await callDeepSqlApi(
        config, `/index-advisor/${encodeURIComponent(connectionId)}/duplicates`);
      return buildToolResult(name, payload);
    }

    case "get_table_index_usage": {
      const connectionId = String(args.connectionId || "").trim();
      const tableName = String(args.tableName || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      if (!tableName) return buildToolError("tableName is required.");
      const payload = await callDeepSqlApi(
        config,
        `/index-advisor/${encodeURIComponent(connectionId)}/usage/${encodeURIComponent(tableName)}`,
      );
      return buildToolResult(name, payload);
    }

    case "list_index_recommendations": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      // The "list ALL" path returns every status; we filter client-side
      // for PENDING/APPLIED/DISMISSED to match the CLI semantics.
      const all = await callDeepSqlApi(
        config, `/index-recommendations/${encodeURIComponent(connectionId)}`);
      const list = Array.isArray(all) ? all : [];
      const filtered = args.status
        ? list.filter((r) => String(r.status || "").toUpperCase() === String(args.status).toUpperCase())
        : list;
      return buildToolResult(name, filtered);
    }

    case "refresh_index_recommendations": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const payload = await callDeepSqlApi(
        config,
        `/index-recommendations/generate/${encodeURIComponent(connectionId)}`,
        { method: "POST" },
      );
      return buildToolResult(name, payload);
    }

    case "dismiss_index_recommendation": {
      const recommendationId = String(args.recommendationId || "").trim();
      if (!recommendationId) return buildToolError("recommendationId is required.");
      const payload = await callDeepSqlApi(
        config,
        `/index-recommendations/${encodeURIComponent(recommendationId)}/dismiss`,
        { method: "PUT" },
      );
      return buildToolResult(name, payload);
    }

    case "get_latest_slow_query_analysis": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const payload = await callDeepSqlApi(
        config, `/slow-queries/latest/${encodeURIComponent(connectionId)}`);
      return buildToolResult(name, payload);
    }

    case "list_slow_query_history": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const limit = clampInteger(args.limit, 1, 100, 10);
      const payload = await callDeepSqlApi(
        config,
        `/slow-queries/history/${encodeURIComponent(connectionId)}?limit=${limit}`,
      );
      return buildToolResult(name, payload);
    }

    case "acknowledge_growth_anomaly": {
      const anomalyId = String(args.anomalyId || "").trim();
      if (!anomalyId) return buildToolError("anomalyId is required.");
      const payload = await callDeepSqlApi(
        config,
        `/growth-monitoring/anomalies/${encodeURIComponent(anomalyId)}/acknowledge`,
        { method: "POST" },
      );
      return buildToolResult(name, payload);
    }

    case "get_growth_config": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      const payload = await callDeepSqlApi(
        config, `/growth-monitoring/config/${encodeURIComponent(connectionId)}`);
      return buildToolResult(name, payload);
    }

    case "set_growth_config": {
      const connectionId = String(args.connectionId || "").trim();
      if (!connectionId) return buildToolError("connectionId is required.");
      if (!args.config || typeof args.config !== "object") {
        return buildToolError("config object is required (see get_growth_config for shape).");
      }
      const payload = await callDeepSqlApi(
        config,
        "/growth-monitoring/config",
        { method: "POST", json: { ...args.config, connectionId } },
      );
      return buildToolResult(name, payload);
    }

    default:
      return buildToolError(`Unknown tool: ${name}`);
  }
}

function createConfigFromEnv(env = process.env) {
  const rawBaseUrl = env.DEEPSQL_API_BASE_URL || "http://localhost:8080/api/";
  const baseUrl = rawBaseUrl.endsWith("/") ? rawBaseUrl : `${rawBaseUrl}/`;

  // Resolve our npm version once, lazily — `require("./package.json")`
  // would normally pull it in, but we use a try/catch so the lib still
  // works in test contexts where the package metadata isn't on disk.
  let clientVersion = null;
  try {
    clientVersion = require("./package.json").version;
  } catch {
    // best-effort
  }

  return {
    baseUrl,
    authToken: env.DEEPSQL_AUTH_TOKEN || "",
    // Optional path to a file holding just the bearer token. When set (the
    // agent-container case), getAuthToken reads it live per request so a
    // provisioner-rotated token takes effect without respawning this process.
    tokenFile: env.DEEPSQL_TOKEN_FILE || null,
    timeoutMs: clampInteger(env.DEEPSQL_MCP_TIMEOUT_MS, 1000, 600000, 120000),
    defaultUserId: env.DEEPSQL_MCP_USER_ID || "mcp-phase1",
    defaultProjectId: env.DEEPSQL_MCP_PROJECT_ID || "mcp-phase1",
    // Origin metadata for the backend audit row. The MCP server always
    // identifies as `mcp`; the agent name comes from DEEPSQL_MCP_USER_ID
    // which editor configs set to claude-desktop / cursor-mcp / codex-mcp.
    clientType: "mcp",
    clientAgent: env.DEEPSQL_MCP_USER_ID || null,
    clientVersion,
  };
}

module.exports = {
  DeepSqlApiError,
  TOOL_DEFINITIONS,
  buildToolError,
  buildToolResult,
  callDeepSqlApi,
  clampInteger,
  compactWhitespace,
  containsForbiddenKeyword,
  createConfigFromEnv,
  firstKeyword,
  getAuthToken,
  handleToolCall,
  invalidateTokenCache,
  normalizeSqlForInspection,
  resolveApiUrl,
  splitStatements,
  stripTrailingSemicolons,
  stripSqlComments,
  stripSqlStringLiterals,
  summarizeApplyResult,
  summarizeGrowthAnomalies,
  summarizeIndexRecommendations,
  summarizeQuerySamples,
  summarizeSlowQueryCustomers,
  summarizeSlowQueryInsights,
  summarizeSlowQueryOptimization,
  summarizeTableGrowth,
  summarizeTrackedQueries,
  validateReadOnlySql,
};
