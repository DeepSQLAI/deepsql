const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const {
  resolveApiUrl,
  summarizeApplyResult,
  summarizeGrowthAnomalies,
  summarizeIndexRecommendations,
  summarizeTableGrowth,
  validateReadOnlySql,
  TOOL_DEFINITIONS,
  handleToolCall,
  callDeepSqlApi,
  createConfigFromEnv,
  getAuthToken,
  invalidateTokenCache,
  buildCallerCapabilities,
  summarizeCallerCapabilities,
  summarizeConnections,
  summarizeMigrationRisk,
  buildToolResult,
  resetToolCaches,
} = require("./deepsql-phase1-lib");

function tmpTokenFile(contents) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "dsql-tok-"));
  const p = path.join(dir, "deepsql.token");
  fs.writeFileSync(p, contents);
  return p;
}

// Install a global.fetch stub that records the Authorization header on each
// request and returns queued responses. Returns { authHeaders, restore }.
// Each response entry is { status, ok, body, onCall? } — onCall fires before
// the response is returned, letting a test rotate the token file mid-flight.
function installFetchStub(responses) {
  const authHeaders = [];
  const original = global.fetch;
  let i = 0;
  global.fetch = async (_url, init) => {
    authHeaders.push(init && init.headers ? init.headers.Authorization : undefined);
    const spec = responses[Math.min(i, responses.length - 1)];
    i += 1;
    if (spec && typeof spec.onCall === "function") {
      spec.onCall();
    }
    const status = spec?.status ?? 200;
    return {
      ok: spec?.ok ?? (status >= 200 && status < 300),
      status,
      statusText: spec?.statusText ?? "OK",
      async text() {
        return JSON.stringify(spec?.body ?? {});
      },
    };
  };
  return { authHeaders, restore: () => { global.fetch = original; } };
}

test("resolveApiUrl keeps the /api prefix for absolute-looking tool paths", () => {
  const result = resolveApiUrl("http://localhost:8080/api/", "/connections");
  assert.equal(result, "http://localhost:8080/api/connections");
});

test("resolveApiUrl appends relative tool paths under the base URL", () => {
  const result = resolveApiUrl("http://localhost:8080/api/", "connections/123/schema");
  assert.equal(result, "http://localhost:8080/api/connections/123/schema");
});

test("validateReadOnlySql accepts a simple select", () => {
  const result = validateReadOnlySql("SELECT * FROM orders LIMIT 10;");
  assert.equal(result.ok, true);
  assert.equal(result.firstKeyword, "SELECT");
  assert.equal(result.normalizedQuery, "SELECT * FROM orders LIMIT 10");
});

test("validateReadOnlySql accepts CTE reads", () => {
  const result = validateReadOnlySql(`
    WITH recent_orders AS (
      SELECT * FROM orders
    )
    SELECT * FROM recent_orders
  `);

  assert.equal(result.ok, true);
  assert.equal(result.firstKeyword, "WITH");
});

test("validateReadOnlySql rejects multiple statements", () => {
  const result = validateReadOnlySql("SELECT 1; SELECT 2;");
  assert.equal(result.ok, false);
  assert.match(result.reason, /single SQL statement/i);
});

test("validateReadOnlySql rejects mutating SQL", () => {
  const result = validateReadOnlySql("DELETE FROM users WHERE id = 1");
  assert.equal(result.ok, false);
  assert.match(result.reason, /read-only SQL/i);
});

test("validateReadOnlySql rejects mutating CTEs", () => {
  const result = validateReadOnlySql(`
    WITH doomed AS (
      SELECT id FROM users
    )
    DELETE FROM users WHERE id IN (SELECT id FROM doomed)
  `);

  assert.equal(result.ok, false);
  assert.match(result.reason, /DELETE/i);
});

test("validateReadOnlySql rejects mutating CTE bodies", () => {
  const result = validateReadOnlySql(`
    WITH doomed AS (
      DELETE FROM users RETURNING id
    )
    SELECT * FROM doomed
  `);

  assert.equal(result.ok, false);
  assert.match(result.reason, /DELETE/i);
});

test("validateReadOnlySql accepts COMMENT and CALL as table names", () => {
  assert.equal(validateReadOnlySql("SELECT * FROM comment").ok, true);
  assert.equal(validateReadOnlySql("SELECT * FROM call").ok, true);
  assert.equal(
    validateReadOnlySql(
      "SELECT comment.id FROM public.comment JOIN call ON call.id = comment.call_id",
    ).ok,
    true,
  );
});

test("validateReadOnlySql accepts COMMENT columns and REPLACE()", () => {
  assert.equal(validateReadOnlySql("SELECT comment FROM posts").ok, true);
  assert.equal(validateReadOnlySql("SELECT COALESCE(comment, '') FROM posts").ok, true);
  assert.equal(validateReadOnlySql("SELECT REPLACE(name, 'a', 'b') FROM users").ok, true);
});

test("validateReadOnlySql still rejects top-level CALL/COMMENT/DELETE", () => {
  assert.equal(validateReadOnlySql("DELETE FROM comment").ok, false);
  assert.equal(validateReadOnlySql("CALL do_thing()").ok, false);
  assert.equal(validateReadOnlySql("COMMENT ON TABLE posts IS 'x'").ok, false);
});

test("validateReadOnlySql rejects FOR UPDATE but allows EXPLAIN of comment tables", () => {
  const locked = validateReadOnlySql("SELECT * FROM orders FOR UPDATE");
  assert.equal(locked.ok, false);
  assert.match(locked.reason, /UPDATE/i);

  const explainedDelete = validateReadOnlySql("EXPLAIN DELETE FROM users");
  assert.equal(explainedDelete.ok, false);

  const explainedComment = validateReadOnlySql("EXPLAIN SELECT * FROM comment");
  assert.equal(explainedComment.ok, true);
});

test("validateReadOnlySql rejects EXPLAIN ANALYZE", () => {
  const result = validateReadOnlySql("EXPLAIN ANALYZE SELECT * FROM orders");
  assert.equal(result.ok, false);
  assert.match(result.reason, /EXPLAIN ANALYZE/i);
});

test("validateReadOnlySql rejects EXPLAIN for explain tool mode", () => {
  const result = validateReadOnlySql("EXPLAIN SELECT * FROM orders", {
    allowExplain: false,
  });

  assert.equal(result.ok, false);
  assert.match(result.reason, /underlying SELECT\/WITH query/i);
});

test("get_index_recommendations tool is registered with sane bounds", () => {
  const tool = TOOL_DEFINITIONS.find((t) => t.name === "get_index_recommendations");
  assert.ok(tool, "tool should be registered");
  assert.ok(tool.inputSchema.required.includes("connectionId"));
  assert.equal(tool.inputSchema.properties.limit.minimum, 1);
  assert.equal(tool.inputSchema.properties.limit.maximum, 50);
});

test("summarizeIndexRecommendations distinguishes DROP and CREATE recommendations", () => {
  const summary = summarizeIndexRecommendations([
    { kind: "DROP_INDEX", tableName: "orders", indexName: "idx_orders_legacy", priority: "HIGH", occurrenceCount: 9, estimatedImpact: 60 },
    { kind: "CREATE_INDEX", tableName: "orders", columnNames: "status", priority: "HIGH", occurrenceCount: 5, estimatedImpact: 50 },
  ]);
  assert.match(summary, /\[DROP\] orders\.idx_orders_legacy \(unused\)/);
  assert.match(summary, /\[CREATE\] orders\(status\)/);
});

test("summarizeIndexRecommendations handles empty and populated payloads", () => {
  const empty = summarizeIndexRecommendations([]);
  assert.match(empty, /No pending index recommendations/i);

  const summary = summarizeIndexRecommendations([
    { tableName: "orders", columnNames: "status", priority: "HIGH", occurrenceCount: 7, estimatedImpact: 60 },
    { tableName: "users", columnNames: "email", priority: "MEDIUM", occurrenceCount: 2, estimatedImpact: 30 },
  ]);
  assert.match(summary, /Top 2 pending/);
  assert.match(summary, /orders\(status\)/);
  assert.match(summary, /seen 7×/);
  assert.match(summary, /users\(email\)/);
});

test("summarizeIndexRecommendations renders net-benefit line when workload signal exists", () => {
  // 4823000 ms = 1.34 hours; the formatter prefers the hour scale at >=1h.
  const summary = summarizeIndexRecommendations([
    {
      kind: "CREATE_INDEX",
      tableName: "orders",
      columnNames: "customer_id,status",
      priority: "HIGH",
      occurrenceCount: 4,
      netBenefitMs: 4823000,
      writeCostScore: 142000,
      evidenceCount: 3,
    },
  ]);
  assert.match(summary, /net=1\.3h saved/);
  assert.match(summary, /write=2\.4m/);
  assert.match(summary, /3 ev/);
});

test("apply_index_recommendation tool is registered with confirm requirement documented", () => {
  const tool = TOOL_DEFINITIONS.find((t) => t.name === "apply_index_recommendation");
  assert.ok(tool, "tool should be registered");
  assert.ok(tool.inputSchema.required.includes("recommendationId"));
  assert.deepEqual(tool.inputSchema.properties.mode.enum, ["DRY_RUN", "APPLY", "APPLY_AND_MEASURE"]);
  assert.match(tool.description, /confirm: true/);
});

test("summarizeApplyResult renders the blocked-confirmation message clearly", () => {
  const out = summarizeApplyResult({
    status: "BLOCKED_NEEDS_CONFIRMATION",
    mode: "APPLY",
    recommendationId: "r1",
  });
  assert.match(out, /\[APPLY\] blocked/);
  assert.match(out, /confirm=true/);
});

test("summarizeApplyResult shows planner cost delta when present", () => {
  const out = summarizeApplyResult({
    status: "OK",
    mode: "DRY_RUN",
    executedDdl: "CREATE INDEX idx_orders_status ON orders (status);",
    beforeCost: 1000,
    afterCost: 250,
    costReductionPct: 75,
    samples: [
      { fingerprint: "abcdef123456", beforeCost: 1000, afterCost: 250 },
    ],
  });
  assert.match(out, /planner cost: 1000 → 250/);
  assert.match(out, /−75\.0%/);
  assert.match(out, /CREATE INDEX idx_orders_status/);
});

test("summarizeApplyResult shows wall-time when APPLY_AND_MEASURE returned it", () => {
  const out = summarizeApplyResult({
    status: "OK",
    mode: "APPLY_AND_MEASURE",
    executedDdl: "CREATE INDEX CONCURRENTLY idx_orders_status ON orders (status);",
    beforeCost: 1000,
    afterCost: 250,
    costReductionPct: 75,
    beforeWallTimeMs: 482,
    afterWallTimeMs: 12,
    wallTimeImprovementPct: 97.5,
    samples: [],
  });
  assert.match(out, /wall time: 482\.0ms → 12\.0ms/);
  assert.match(out, /−97\.5%/);
});

test("summarizeIndexRecommendations falls back to impact when no workload signal", () => {
  // Pure schema-walk candidate — netBenefitMs absent. The line still shows
  // the legacy "impact N" so older callers see something useful.
  const summary = summarizeIndexRecommendations([
    {
      kind: "CREATE_INDEX",
      tableName: "users",
      columnNames: "email",
      priority: "MEDIUM",
      occurrenceCount: 1,
      estimatedImpact: 25,
    },
  ]);
  assert.match(summary, /impact 25/);
  assert.doesNotMatch(summary, /net=/);
});

test("validateReadOnlySql preserves string literals in the executed query", () => {
  const sql = `
    SELECT table_name
    FROM information_schema.tables
    WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
    ORDER BY table_name
    LIMIT 25;
  `;
  const result = validateReadOnlySql(sql);

  assert.equal(result.ok, true);
  assert.match(result.normalizedQuery, /table_schema = 'public'/);
  assert.match(result.normalizedQuery, /table_type = 'BASE TABLE'/);
});

// ─── tool definitions and dispatch (0.13.0 consolidation) ──────────────────

test("TOOL_DEFINITIONS exposes execute_sql + analyze_query_plan, not the old read-only pair", () => {
  const names = TOOL_DEFINITIONS.map((t) => t.name);
  assert.ok(names.includes("execute_sql"), "execute_sql must be present");
  assert.ok(names.includes("analyze_query_plan"), "analyze_query_plan must be present");
  // Old read-only pair removed in 0.13.0 — agents migrate to the new names.
  assert.equal(names.includes("execute_readonly_sql"), false);
  assert.equal(names.includes("explain_readonly_sql"), false);
});

test("execute_sql tool schema advertises mutation flow (confirmMutation) and bounded limits", () => {
  const def = TOOL_DEFINITIONS.find((t) => t.name === "execute_sql");
  assert.ok(def);
  assert.equal(def.inputSchema.required.includes("connectionId"), true);
  assert.equal(def.inputSchema.required.includes("query"), true);
  assert.ok(def.inputSchema.properties.confirmMutation, "needs confirmMutation hint for agents");
  assert.equal(def.inputSchema.properties.limit.maximum, 1000);
  assert.match(def.description, /DROP and TRUNCATE are blocked/i);
});

test("analyze_query_plan schema advertises useAnalyze + confirmMutation", () => {
  const def = TOOL_DEFINITIONS.find((t) => t.name === "analyze_query_plan");
  assert.ok(def);
  assert.equal(def.inputSchema.properties.useAnalyze.type, "boolean");
  assert.equal(def.inputSchema.properties.confirmMutation.type, "boolean");
  // The plan analyzer must NOT accept pre-wrapped EXPLAIN — that's a
  // common LLM mistake and we describe the contract in the schema.
  assert.match(def.inputSchema.properties.query.description, /Do NOT wrap in EXPLAIN/);
});

test("handleToolCall(execute_sql) routes to /connections/{id}/query and propagates confirmMutation", async () => {
  const calls = [];
  const fakeFetchConfig = makeFakeConfig(calls, [{ success: true, result: { rowCount: 1 } }]);
  const result = await handleToolCall(fakeFetchConfig, "execute_sql", {
    connectionId: "00000000-0000-0000-0000-000000000001",
    query: "UPDATE t SET x=1 WHERE id=1",
    confirmMutation: true,
  });
  assert.equal(calls.length, 1);
  assert.match(calls[0].url, /\/connections\/00000000-0000-0000-0000-000000000001\/query$/);
  assert.equal(calls[0].body.mutationConfirmed, true);
  assert.equal(result.structuredContent.success, true);
});

test("handleToolCall(execute_sql) defaults confirmMutation=false when caller omits it", async () => {
  const calls = [];
  const fakeFetchConfig = makeFakeConfig(calls, [{ success: true, result: { rowCount: 0 } }]);
  await handleToolCall(fakeFetchConfig, "execute_sql", {
    connectionId: "00000000-0000-0000-0000-000000000001",
    query: "SELECT 1",
  });
  assert.equal(calls[0].body.mutationConfirmed, false);
});

test("handleToolCall(execute_sql) rejects empty inputs cleanly (no network call)", async () => {
  const calls = [];
  const fakeFetchConfig = makeFakeConfig(calls, []);
  const noConn = await handleToolCall(fakeFetchConfig, "execute_sql", { query: "SELECT 1" });
  assert.equal(noConn.isError, true);
  const noQuery = await handleToolCall(fakeFetchConfig, "execute_sql", { connectionId: "x" });
  assert.equal(noQuery.isError, true);
  assert.equal(calls.length, 0, "validation must short-circuit before the network call");
});

test("handleToolCall(analyze_query_plan) routes to /explain/analyze with useAnalyze + confirmMutation", async () => {
  const calls = [];
  const fakeFetchConfig = makeFakeConfig(calls, [{ planText: "Seq Scan", nodeCount: 1 }]);
  await handleToolCall(fakeFetchConfig, "analyze_query_plan", {
    connectionId: "00000000-0000-0000-0000-000000000001",
    query: "SELECT * FROM orders",
    useAnalyze: true,
    confirmMutation: true,
  });
  assert.equal(calls.length, 1);
  assert.match(calls[0].url, /\/explain\/analyze$/);
  assert.equal(calls[0].body.useAnalyze, true);
  assert.equal(calls[0].body.mutationConfirmed, true);
});

test("handleToolCall returns a clean error for the retired execute_readonly_sql name", async () => {
  const calls = [];
  const fakeFetchConfig = makeFakeConfig(calls, []);
  const result = await handleToolCall(fakeFetchConfig, "execute_readonly_sql", {
    connectionId: "x", query: "SELECT 1",
  });
  assert.equal(result.isError, true);
  assert.match(result.structuredContent.error, /Unknown tool/);
});

// ─── analyze_migration ──────────────────────────────────────────────────────

test("analyze_migration schema requires connectionId + sql", () => {
  const def = TOOL_DEFINITIONS.find((t) => t.name === "analyze_migration");
  assert.ok(def);
  assert.deepEqual(def.inputSchema.required, ["connectionId", "sql"]);
  assert.match(def.description, /PostgreSQL only/);
  assert.match(def.description, /trust this verdict/i);
});

test("handleToolCall(analyze_migration) routes to /migrations/analyze", async () => {
  const calls = [];
  const fakeFetchConfig = makeFakeConfig(calls, [{ verdict: "SAFE", operation: "ADD COLUMN", table: "t" }]);
  await handleToolCall(fakeFetchConfig, "analyze_migration", {
    connectionId: "00000000-0000-0000-0000-000000000001",
    sql: "ALTER TABLE t ADD COLUMN y text",
  });
  assert.equal(calls.length, 1);
  assert.match(calls[0].url, /\/migrations\/analyze$/);
  assert.equal(calls[0].body.sql, "ALTER TABLE t ADD COLUMN y text");
});

test("handleToolCall(analyze_migration) rejects empty inputs with no network call", async () => {
  const calls = [];
  const fakeFetchConfig = makeFakeConfig(calls, []);
  const noConn = await handleToolCall(fakeFetchConfig, "analyze_migration", { sql: "ALTER TABLE t ADD COLUMN y text" });
  assert.equal(noConn.isError, true);
  const noSql = await handleToolCall(fakeFetchConfig, "analyze_migration", { connectionId: "x" });
  assert.equal(noSql.isError, true);
  assert.equal(calls.length, 0);
});

test("summarizeMigrationRisk renders a DANGER verdict with every lock entry (FK case)", () => {
  const text = summarizeMigrationRisk({
    verdict: "DANGER", operation: "ADD FOREIGN KEY", table: "orders",
    locks: [
      { table: "orders", mode: "SHARE ROW EXCLUSIVE", blocks: ["writes"] },
      { table: "customers", mode: "ROW SHARE", blocks: [] },
    ],
    rewritesTable: false, tableRows: 1200000, estimatedDuration: "~2s",
    reason: "Validates existing rows against customers.",
    saferAlternative: "Add NOT VALID then VALIDATE CONSTRAINT separately.",
  });
  assert.match(text, /^✗ DANGER — ADD FOREIGN KEY on orders\./);
  assert.match(text, /orders: SHARE ROW EXCLUSIVE \(blocks writes\)/);
  assert.match(text, /customers: ROW SHARE/);
  assert.match(text, /Table has ~1,200,000 rows\./);
  assert.match(text, /Safer: Add NOT VALID/);
  assert.doesNotMatch(text, /undefined|NaN|\bnull\b/);
});

test("summarizeMigrationRisk on UNKNOWN never leaks a null table", () => {
  const text = summarizeMigrationRisk({
    verdict: "UNKNOWN", table: null, operation: null,
    reason: "Statement could not be parsed as a supported DDL form.",
  });
  assert.equal(
    text,
    "UNKNOWN — Statement could not be parsed as a supported DDL form. Treat as unsafe until reviewed by hand.",
  );
  assert.doesNotMatch(text, /undefined|NaN|\bnull\b/);
});

test("summarizeMigrationRisk treats the Long.MAX_VALUE tableRows sentinel as unknown size, not a huge number", () => {
  const text = summarizeMigrationRisk({
    verdict: "CAUTION", operation: "ADD COLUMN", table: "huge_table",
    locks: [{ table: "huge_table", mode: "ACCESS EXCLUSIVE", blocks: ["reads", "writes"] }],
    rewritesTable: true, tableRows: 9223372036854775807,
    reason: "Could not measure table size.",
  });
  assert.match(text, /Table size unknown — treated as large\./);
  assert.doesNotMatch(text, /9,223,372,036,854/);
});

test("summarizeMigrationRisk treats a JS-float-imprecise near-sentinel value the same way", () => {
  const text = summarizeMigrationRisk({
    verdict: "CAUTION", operation: "ADD COLUMN", table: "huge_table",
    locks: [], rewritesTable: true, tableRows: 9223372036854775808,
    reason: "Could not measure table size.",
  });
  assert.match(text, /Table size unknown — treated as large\./);
});

test("summarizeMigrationRisk marks SAFE with no warning glyph", () => {
  const text = summarizeMigrationRisk({ verdict: "SAFE", operation: "ADD COLUMN", table: "t" });
  assert.match(text, /^SAFE — ADD COLUMN on t\./);
});

test("summarizeMigrationRisk marks FAILS the same as DANGER", () => {
  const text = summarizeMigrationRisk({ verdict: "FAILS", operation: "ADD COLUMN", table: "t", reason: "Column already exists." });
  assert.match(text, /^✗ FAILS — ADD COLUMN on t\./);
});

test("summarizeMigrationRisk degrades gracefully on a null payload", () => {
  assert.equal(summarizeMigrationRisk(null), "No analysis returned.");
});

test("buildToolResult(analyze_migration) dispatches to summarizeMigrationRisk", () => {
  const result = buildToolResult("analyze_migration", { verdict: "SAFE", operation: "ADD COLUMN", table: "t" });
  assert.match(result.content[0].text, /^SAFE — ADD COLUMN on t\./);
});

// ─── analyze_slow_queries — sourceTruncated surfacing ──────────────────────

test("analyze_slow_queries summary warns when there are unrecovered truncations", async () => {
  // pg_stat_statements/performance_schema both default to 1024B query
  // storage. When the user's backend hits that ceiling AND no log-file
  // copy is available for recovery, the SlowQuery carries
  // `sourceTruncated: true` and `queryTextRecoveredFromLogs !== true`.
  // The summary MUST mention this — otherwise the agent will happily
  // EXPLAIN against a truncated query and confuse the user.
  const calls = [];
  const fakeFetchConfig = makeFakeConfig(calls, [{
    topSlowQueries: [
      { queryText: "SELECT 1", sourceTruncated: false },
      { queryText: "SELECT ... (long)", sourceTruncated: true /* no recovery */ },
      { queryText: "DELETE ... (long)", sourceTruncated: true /* no recovery */ },
    ],
    totalCount: 3,
  }]);

  const result = await handleToolCall(fakeFetchConfig, "analyze_slow_queries", {
    connectionId: "00000000-0000-0000-0000-000000000001",
  });

  const text = result.content[0].text;
  assert.match(text, /3 slow query/, "should report total count");
  assert.match(text, /⚠ 2 queries are still truncated/, "should flag still-truncated count");
  assert.match(text, /track_activity_query_size/, "should point at the PG knob to raise");
  assert.match(text, /performance_schema_max_sql_text_length/, "should point at the MySQL knob too");
  assert.match(text, /ingest the slow query log file/, "should suggest log-file ingestion as the fix");
  assert.equal(result.structuredContent.topSlowQueries.length, 3,
    "the structured payload must still carry every query — the flag is for the human-readable summary");
});

test("analyze_slow_queries summary distinguishes recovered queries from still-truncated", async () => {
  // The recovery story: server truncated this query, but DeepSQL pulled
  // the full text out of vault DB's query_lineage table (from a
  // previous slow-log ingestion). EXPLAIN will work; the agent should
  // know this is fine.
  const fakeFetchConfig = makeFakeConfig([], [{
    topSlowQueries: [
      { queryText: "SELECT 1", sourceTruncated: false },
      // Recovered: truncated at the server BUT we have the full text.
      { queryText: "SELECT ... (full from logs)", sourceTruncated: true,
        queryTextRecoveredFromLogs: true },
      // Still truncated.
      { queryText: "DELETE ... (cut)", sourceTruncated: true },
    ],
    totalCount: 3,
  }]);
  const result = await handleToolCall(fakeFetchConfig, "analyze_slow_queries", {
    connectionId: "00000000-0000-0000-0000-000000000001",
  });
  const text = result.content[0].text;
  assert.match(text, /ℹ 1 query was truncated.*recovered/,
    "should note the recovered one with an info marker");
  assert.match(text, /⚠ 1 query is still truncated/,
    "should warn about the still-truncated one");
});

test("analyze_slow_queries summary stays clean when nothing is truncated", async () => {
  const fakeFetchConfig = makeFakeConfig([], [{
    topSlowQueries: [
      { queryText: "SELECT 1", sourceTruncated: false },
    ],
    totalCount: 1,
  }]);

  const result = await handleToolCall(fakeFetchConfig, "analyze_slow_queries", {
    connectionId: "00000000-0000-0000-0000-000000000001",
  });

  const text = result.content[0].text;
  assert.equal(/truncated/.test(text), false, "no truncation warning when none was detected");
});

test("analyze_slow_queries summary stays clean when ALL truncations were recovered", async () => {
  // Pure happy path: live stats were truncated, but every single row had
  // a matching log-file entry in query_lineage and got recovered. The
  // ℹ informational line shows up but no ⚠ warning.
  const fakeFetchConfig = makeFakeConfig([], [{
    topSlowQueries: [
      { queryText: "SELECT 1 (recovered)", sourceTruncated: true, queryTextRecoveredFromLogs: true },
      { queryText: "SELECT 2 (recovered)", sourceTruncated: true, queryTextRecoveredFromLogs: true },
    ],
    totalCount: 2,
  }]);
  const result = await handleToolCall(fakeFetchConfig, "analyze_slow_queries", {
    connectionId: "00000000-0000-0000-0000-000000000001",
  });
  const text = result.content[0].text;
  assert.match(text, /ℹ 2 queries were truncated/, "should note the recovered count");
  assert.match(text, /recovered the full SQL/, "should explain the recovery");
  assert.equal(/still truncated/.test(text), false, "no still-truncated warning when all were recovered");
});

// Lightweight fetch stub used by the dispatch tests above. We hijack global
// fetch via the config's `baseUrl` — handleToolCall calls
// `callDeepSqlApi(config, path, …)` which builds a URL and uses fetch(). We
// stub global fetch to record the URL + body and return a canned JSON
// payload. Tests share the stub via the array passed in.
// ─── growth tools (get_table_growth + get_growth_anomalies) ────────────────

test("get_table_growth tool is registered with sane bounds", () => {
  const tool = TOOL_DEFINITIONS.find((t) => t.name === "get_table_growth");
  assert.ok(tool, "get_table_growth tool should be registered");
  assert.ok(tool.inputSchema.required.includes("connectionId"));
  assert.equal(tool.inputSchema.properties.days.minimum, 1);
  assert.equal(tool.inputSchema.properties.days.maximum, 365);
  assert.equal(tool.inputSchema.properties.tableName.type, "string");
});

test("get_growth_anomalies tool is registered with severity-aware framing", () => {
  const tool = TOOL_DEFINITIONS.find((t) => t.name === "get_growth_anomalies");
  assert.ok(tool);
  assert.match(tool.description, /CRITICAL/);
  assert.match(tool.description, /confidence score/);
  assert.equal(tool.inputSchema.properties.unacknowledgedOnly.type, "boolean");
  assert.equal(tool.inputSchema.properties.days.maximum, 365);
});

test("summarizeTableGrowth reports per-table rollups sorted by absolute change", () => {
  const out = summarizeTableGrowth({
    trends: {
      sizeOverTime: [
        { table: "orders",     timestamp: "2026-04-15T00:00:00", sizeBytes: 100_000_000 },
        { table: "orders",     timestamp: "2026-05-15T00:00:00", sizeBytes: 110_000_000 },
        { table: "events_log", timestamp: "2026-04-15T00:00:00", sizeBytes: 1_000_000_000 },
        { table: "events_log", timestamp: "2026-05-15T00:00:00", sizeBytes: 5_000_000_000 },
      ],
    },
    days: 30,
  });
  assert.match(out, /2 table\(s\) with growth data over 30d/);
  // events_log (4GB delta) is the largest — must appear first in the
  // "Most-changed" list so the agent sees the most important table first.
  const evIdx = out.indexOf("events_log");
  const ordIdx = out.indexOf("orders");
  assert.ok(evIdx > 0 && ordIdx > evIdx, "events_log should sort before orders");
});

test("summarizeTableGrowth handles empty data with an actionable hint", () => {
  const out = summarizeTableGrowth({});
  assert.match(out, /No table-growth history/);
  assert.match(out, /stats-snapshot collection/);
});

test("summarizeGrowthAnomalies surfaces severity counts + worst-case headline", () => {
  const out = summarizeGrowthAnomalies({
    anomalies: [
      {
        id: "a1",
        tableName: "events_log",
        severity: "CRITICAL",
        anomalyType: "PERCENTAGE_GROWTH",
        sizeGrowthPercent: 312.5,
      },
      { id: "a2", tableName: "orders", severity: "WARNING", anomalyType: "ROW_SPIKE" },
      { id: "a3", tableName: "logs", severity: "INFO" },
    ],
    statistics: { total: 3, critical: 1, warning: 1, unacknowledged: 2 },
  });
  assert.match(out, /3 growth anomalies \(1 critical, 1 warning, 2 unacknowledged\)/);
  // Worst-case (CRITICAL) is surfaced inline so the agent has something
  // concrete to reference.
  assert.match(out, /\[CRITICAL\] events_log/);
  assert.match(out, /\+312\.5%/);
});

test("summarizeGrowthAnomalies stays clean when nothing was flagged", () => {
  const out = summarizeGrowthAnomalies({ anomalies: [] });
  assert.match(out, /No growth anomalies detected/);
});

test("summarizeGrowthAnomalies promotes WARNING when no CRITICAL exists", () => {
  // The summary's "worst-case" line should fall back to the highest
  // severity present — picking the first WARNING when no CRITICAL is
  // around, so the agent doesn't have to dig through the list.
  const out = summarizeGrowthAnomalies({
    anomalies: [
      { id: "a1", tableName: "users", severity: "INFO" },
      { id: "a2", tableName: "orders", severity: "WARNING", anomalyType: "PERCENTAGE_GROWTH", sizeGrowthPercent: 45 },
    ],
    statistics: { total: 2, critical: 0, warning: 1, unacknowledged: 2 },
  });
  assert.match(out, /\[WARNING\] orders/);
});

test("handleToolCall(get_table_growth) hits /growth-monitoring/trends with days + tableName", async () => {
  const calls = [];
  const config = makeFakeConfig(calls, [{ trends: { sizeOverTime: [] }, days: 14 }]);
  const result = await handleToolCall(config, "get_table_growth", {
    connectionId: "00000000-0000-0000-0000-000000000001",
    days: 14,
    tableName: "orders",
  });
  assert.equal(calls.length, 1);
  assert.match(calls[0].url, /\/growth-monitoring\/trends\/00000000-0000-0000-0000-000000000001\?/);
  assert.match(calls[0].url, /days=14/);
  assert.match(calls[0].url, /tableName=orders/);
  assert.ok(result.structuredContent);
  calls.__restore();
});

test("handleToolCall(get_table_growth) clamps days into [1,365] with 30 as the default", async () => {
  const calls = [];
  const config = makeFakeConfig(calls, [{ trends: { sizeOverTime: [] } }]);
  await handleToolCall(config, "get_table_growth", {
    connectionId: "00000000-0000-0000-0000-000000000001",
  });
  assert.match(calls[0].url, /days=30/);
  calls.__restore();
});

test("handleToolCall(get_table_growth) rejects missing connectionId without a network call", async () => {
  const calls = [];
  const config = makeFakeConfig(calls, []);
  const result = await handleToolCall(config, "get_table_growth", { days: 7 });
  assert.equal(result.isError, true);
  assert.equal(calls.length, 0, "validation must short-circuit before the network call");
  calls.__restore();
});

test("handleToolCall(get_growth_anomalies) wires unacknowledgedOnly into the query string", async () => {
  const calls = [];
  const config = makeFakeConfig(calls, [{ anomalies: [], statistics: { total: 0 } }]);
  await handleToolCall(config, "get_growth_anomalies", {
    connectionId: "00000000-0000-0000-0000-000000000001",
    unacknowledgedOnly: true,
    days: 7,
  });
  assert.match(calls[0].url, /unacknowledgedOnly=true/);
  assert.match(calls[0].url, /days=7/);
  calls.__restore();
});

test("handleToolCall(get_growth_anomalies) omits unacknowledgedOnly when not explicitly true", async () => {
  const calls = [];
  const config = makeFakeConfig(calls, [{ anomalies: [], statistics: { total: 0 } }]);
  await handleToolCall(config, "get_growth_anomalies", {
    connectionId: "00000000-0000-0000-0000-000000000001",
  });
  assert.equal(/unacknowledgedOnly/.test(calls[0].url), false,
    "unacknowledgedOnly should not be passed at all when caller omits it");
  calls.__restore();
});

function makeFakeConfig(callsOut, responses) {
  resetToolCaches();
  let i = 0;
  const originalFetch = global.fetch;
  global.fetch = async (url, init) => {
    const body = init && init.body ? JSON.parse(init.body) : null;
    callsOut.push({ url: String(url), body, method: init && init.method });
    const payload = responses[i] ?? {};
    i++;
    return {
      ok: true,
      status: 200,
      statusText: "OK",
      async text() { return JSON.stringify(payload); },
    };
  };
  // restore on next event loop tick so test ordering doesn't get tangled
  process.nextTick(() => { /* leave the stub alive for this run */ });
  // Reset via a node:test hook would be cleaner; here each test makes a
  // fresh config and the runner is single-threaded, so we just remember
  // the original.
  callsOut.__restore = () => { global.fetch = originalFetch; };
  return {
    baseUrl: "http://test/api/",
    authToken: "t",
    timeoutMs: 5000,
    clientType: "mcp",
    clientAgent: "cursor",
    clientVersion: "0.13.0",
  };
}

// ═════════════════════════════════════════════════════════════════════════════
// Phase A symmetry — 20 new tools added to match the `deepsql` CLI surface
// (connection diagnostics, digest reads, index catalog probes, slow-query
// reads, growth ops). Only the connection WRITE tools (add/update/remove)
// were intentionally NOT added — credentials shouldn't cross agent history.
// ═════════════════════════════════════════════════════════════════════════════

test("Phase A tools are all registered with required schemas", () => {
  const expected = [
    "get_current_user",
    "test_connection",
    "show_connection",
    "reinit_connection_brain",
    "get_latest_digest",
    "list_digests",
    "get_digest_by_id",
    "get_missing_indexes",
    "get_index_health",
    "get_unused_indexes",
    "get_duplicate_indexes",
    "get_table_index_usage",
    "list_index_recommendations",
    "refresh_index_recommendations",
    "dismiss_index_recommendation",
    "get_latest_slow_query_analysis",
    "list_slow_query_history",
    "acknowledge_growth_anomaly",
    "get_growth_config",
    "set_growth_config",
  ];
  for (const name of expected) {
    const tool = TOOL_DEFINITIONS.find((t) => t.name === name);
    assert.ok(tool, `tool ${name} should be registered`);
    assert.ok(tool.description && tool.description.length > 50,
      `${name} needs a real description (LLMs pick tools by reading these)`);
    assert.ok(tool.inputSchema, `${name} needs an inputSchema`);
    assert.equal(tool.inputSchema.additionalProperties, false,
      `${name} should reject unknown args`);
  }
});

test("connection-write tools are intentionally NOT exposed (secrets-in-history risk)", () => {
  // Decision recorded in 0.18.0: add_connection, update_connection,
  // remove_connection require plaintext DB creds in the tool call,
  // which would land in the agent's conversation history. Customers
  // manage connections via `deepsql connections add` at a TTY where
  // the password prompt never echoes.
  const names = TOOL_DEFINITIONS.map((t) => t.name);
  assert.equal(names.includes("add_connection"), false);
  assert.equal(names.includes("update_connection"), false);
  assert.equal(names.includes("remove_connection"), false);
});

test("handleToolCall(get_current_user) hits /auth/me with no args", async () => {
  const calls = [];
  const cfg = makeFakeConfig(calls, [
    { username: "alice", role: "ADMIN" },
    [{ id: "c1", canManageContent: true, canManageConfig: true }],
  ]);
  const result = await handleToolCall(cfg, "get_current_user", {});
  assert.match(calls[0].url, /\/auth\/me$/);
  assert.equal(result.structuredContent.username, "alice");
  assert.equal(result.structuredContent.callerCapabilities.canWriteSharedBrainNotes, true);
});

test("handleToolCall(test_connection) sends only id — never plaintext creds", async () => {
  // Critical contract: the MCP test_connection tool must NEVER POST
  // user-supplied connection JSON. It sends only the saved id; backend
  // reuses the saved encrypted credentials. Verifying the request body
  // shape prevents a future "convenience" patch from leaking secrets.
  const calls = [];
  const cfg = makeFakeConfig(calls, [{ connectionSuccessful: true }]);
  await handleToolCall(cfg, "test_connection", {
    connectionId: "00000000-0000-0000-0000-000000000001",
    // Even if caller passes these, they MUST be ignored:
    password: "leak-me",
    host: "evil.example.com",
  });
  assert.match(calls[0].url, /\/connections\/test$/);
  assert.deepEqual(calls[0].body, { id: "00000000-0000-0000-0000-000000000001" });
  assert.equal(JSON.stringify(calls[0].body).includes("leak-me"), false);
});

test("handleToolCall(show_connection) filters list response and never returns secrets", async () => {
  const calls = [];
  const cfg = makeFakeConfig(calls, [[
    { id: "conn-1", connectionName: "prod-pg", password: "(set)" },
    { id: "conn-2", connectionName: "stage-pg", password: "(set)" },
  ]]);
  const result = await handleToolCall(cfg, "show_connection", { connectionId: "conn-2" });
  assert.match(calls[0].url, /\/connections$/);
  assert.equal(result.structuredContent.connectionName, "stage-pg");
  // The backend masks secrets to the literal "(set)" — verify our tool
  // doesn't accidentally unmask or rewrite them.
  assert.equal(result.structuredContent.password, "(set)");
});

test("handleToolCall(show_connection) returns a clean error when the id isn't found", async () => {
  const cfg = makeFakeConfig([], [[{ id: "conn-1" }]]);
  const result = await handleToolCall(cfg, "show_connection", { connectionId: "missing" });
  assert.equal(result.isError, true);
  assert.match(result.structuredContent.error, /not found/i);
});

test("handleToolCall(reinit_connection_brain) POSTs with force flag plumbed through", async () => {
  const calls = [];
  const cfg = makeFakeConfig(calls, [{ status: "RUNNING" }]);
  await handleToolCall(cfg, "reinit_connection_brain", { connectionId: "c1", force: true });
  assert.match(calls[0].url, /\/connections\/c1\/reinit$/);
  assert.equal(calls[0].body.force, true);
});

test("handleToolCall(get_latest_digest) unwraps Spring Page<>.content[0]", async () => {
  // Backend returns Page<SlackDigestLog> — agent shouldn't have to know
  // about Spring Data's content/totalElements shape.
  const cfg = makeFakeConfig([], [{
    content: [{ id: 42, summary: "1 critical query yesterday" }],
    totalElements: 1,
  }]);
  const result = await handleToolCall(cfg, "get_latest_digest", { connectionId: "c1" });
  assert.equal(result.structuredContent.id, 42);
  assert.match(result.structuredContent.summary, /critical/);
});

test("handleToolCall(list_digests) returns just the content array, capped by count", async () => {
  const calls = [];
  const cfg = makeFakeConfig(calls, [{
    content: [{ id: 1 }, { id: 2 }, { id: 3 }],
    totalElements: 3,
  }]);
  const result = await handleToolCall(cfg, "list_digests", { count: 50 });
  assert.match(calls[0].url, /size=50/);
  // structuredContent wraps top-level arrays as { items: [...] } for MCP spec
  // compliance (strict clients reject a top-level array).
  assert.equal(Array.isArray(result.structuredContent.items), true);
  assert.equal(result.structuredContent.items.length, 3);
});

test("handleToolCall(get_digest_by_id) filters the recent page by digestId", async () => {
  const cfg = makeFakeConfig([], [{
    content: [{ id: 100, summary: "a" }, { id: 101, summary: "b" }],
  }]);
  const result = await handleToolCall(cfg, "get_digest_by_id", { digestId: "101" });
  assert.equal(result.structuredContent.summary, "b");
});

test("handleToolCall(index catalog tools) hit the right /index-advisor/{cid}/* paths", async () => {
  const matrix = [
    ["get_missing_indexes",     /\/advisor\/indexes\/c1$/],
    ["get_index_health",        /\/index-advisor\/c1\/health-report$/],
    ["get_unused_indexes",      /\/index-advisor\/c1\/unused$/],
    ["get_duplicate_indexes",   /\/index-advisor\/c1\/duplicates$/],
  ];
  for (const [name, expected] of matrix) {
    const calls = [];
    const cfg = makeFakeConfig(calls, [[]]);
    await handleToolCall(cfg, name, { connectionId: "c1" });
    assert.match(calls[0].url, expected, `${name} should hit ${expected}`);
  }
});

test("handleToolCall(get_table_index_usage) encodes the table name into the path", async () => {
  const calls = [];
  const cfg = makeFakeConfig(calls, [[]]);
  await handleToolCall(cfg, "get_table_index_usage", {
    connectionId: "c1",
    tableName: "user accounts",  // space — must be URL-encoded
  });
  assert.match(calls[0].url, /\/index-advisor\/c1\/usage\/user%20accounts$/);
});

test("handleToolCall(list_index_recommendations) filters by status client-side", async () => {
  const cfg = makeFakeConfig([], [[
    { id: "a", status: "PENDING" },
    { id: "b", status: "APPLIED" },
    { id: "c", status: "DISMISSED" },
  ]]);
  const result = await handleToolCall(cfg, "list_index_recommendations", {
    connectionId: "c1", status: "APPLIED",
  });
  assert.equal(result.structuredContent.items.length, 1);
  assert.equal(result.structuredContent.items[0].id, "b");
});

test("handleToolCall(list_brain_recommendations) GETs suggestions with the limit", async () => {
  const calls = [];
  const cfg = makeFakeConfig(calls, [
    { suggestions: [{ priority: "P0", tableName: "orders", columnName: "id" }], totalCount: 1 },
  ]);
  const result = await handleToolCall(cfg, "list_brain_recommendations", { connectionId: "c1", limit: 5 });
  assert.match(calls[0].url, /\/brain\/notes\/suggestions\/c1\?limit=5$/);
  assert.equal(result.structuredContent.totalCount, 1);
  assert.match(result.content[0].text, /recommendation\(s\) to review/);
});

test("handleToolCall(save_brain_note) POSTs a COLUMN-scoped note", async () => {
  const calls = [];
  const cfg = makeFakeConfig(calls, [
    [{ id: "c1", canManageContent: true }],
    { username: "admin", role: "ADMIN" },
    { tableName: "orders", columnName: "status", noteText: "x" },
  ]);
  const result = await handleToolCall(cfg, "save_brain_note", {
    connectionId: "c1", tableName: "orders", columnName: "status", noteText: "x",
  });
  const noteCall = calls.find((c) => /\/brain\/notes$/.test(c.url));
  assert.ok(noteCall, "should POST /brain/notes after the capability check");
  assert.equal(noteCall.method, "POST");
  assert.equal(noteCall.body.scopeType, "COLUMN");
  assert.equal(noteCall.body.tableName, "orders");
  assert.match(result.content[0].text, /Saved to brain/);
});

test("handleToolCall(save_brain_note) requires tableName + noteText", async () => {
  const cfg = makeFakeConfig([], [{}]);
  const r = await handleToolCall(cfg, "save_brain_note", { connectionId: "c1", noteText: "x" });
  assert.match(r.content[0].text, /tableName is required/);
});

test("handleToolCall(list_brain_notes) filters by table + column", async () => {
  const calls = [];
  const cfg = makeFakeConfig(calls, [[{ id: "n1" }]]);
  await handleToolCall(cfg, "list_brain_notes", { connectionId: "c1", tableName: "orders", columnName: "id" });
  assert.match(calls[0].url, /\/brain\/notes\/c1\?tableName=orders&columnName=id$/);
});

test("handleToolCall(refresh_index_recommendations) POSTs to /generate/{cid}", async () => {
  const calls = [];
  const cfg = makeFakeConfig(calls, [{ status: "QUEUED" }]);
  await handleToolCall(cfg, "refresh_index_recommendations", { connectionId: "c1" });
  assert.match(calls[0].url, /\/index-recommendations\/generate\/c1$/);
  assert.equal(calls[0].method, "POST");
});

test("handleToolCall(dismiss_index_recommendation) PUTs to /{id}/dismiss", async () => {
  const calls = [];
  const cfg = makeFakeConfig(calls, [{ status: "DISMISSED" }]);
  await handleToolCall(cfg, "dismiss_index_recommendation", { recommendationId: "rec-42" });
  assert.match(calls[0].url, /\/index-recommendations\/rec-42\/dismiss$/);
  assert.equal(calls[0].method, "PUT");
});

test("handleToolCall(slow-query reads) hit /slow-queries/latest|history/{cid}", async () => {
  const latestCfg = makeFakeConfig([], [{ id: "run-1" }]);
  const latest = await handleToolCall(latestCfg, "get_latest_slow_query_analysis", { connectionId: "c1" });
  assert.equal(latest.structuredContent.id, "run-1");

  const calls = [];
  const histCfg = makeFakeConfig(calls, [[{ id: "h1" }]]);
  await handleToolCall(histCfg, "list_slow_query_history", { connectionId: "c1", limit: 25 });
  assert.match(calls[0].url, /\/slow-queries\/history\/c1\?limit=25$/);
});

test("handleToolCall(acknowledge_growth_anomaly) POSTs to /anomalies/{id}/acknowledge", async () => {
  const calls = [];
  const cfg = makeFakeConfig(calls, [{ acknowledgedAt: "2026-05-26T12:00Z" }]);
  await handleToolCall(cfg, "acknowledge_growth_anomaly", { anomalyId: "anom-7" });
  assert.match(calls[0].url, /\/growth-monitoring\/anomalies\/anom-7\/acknowledge$/);
  assert.equal(calls[0].method, "POST");
});

test("handleToolCall(growth config) round-trips: get → modify → set", async () => {
  const getCfg = makeFakeConfig([], [{ thresholdPct: 25, enabled: true }]);
  const got = await handleToolCall(getCfg, "get_growth_config", { connectionId: "c1" });
  assert.equal(got.structuredContent.thresholdPct, 25);

  const calls = [];
  const setCfg = makeFakeConfig(calls, [{ thresholdPct: 50, enabled: true }]);
  await handleToolCall(setCfg, "set_growth_config", {
    connectionId: "c1",
    config: { thresholdPct: 50, enabled: true },
  });
  assert.match(calls[0].url, /\/growth-monitoring\/config$/);
  assert.equal(calls[0].body.thresholdPct, 50);
  assert.equal(calls[0].body.connectionId, "c1", "set_growth_config must propagate connectionId in body");
});

test("Phase A tools that require connectionId reject empty input cleanly (no network call)", async () => {
  // Validation-before-network is the contract for every tool that takes
  // connectionId — saves an HTTP round trip and gives the agent a fast
  // error to recover from.
  const tools = [
    "test_connection", "show_connection", "reinit_connection_brain",
    "get_missing_indexes", "get_index_health", "get_unused_indexes",
    "get_duplicate_indexes", "get_table_index_usage",
    "list_index_recommendations", "refresh_index_recommendations",
    "get_latest_slow_query_analysis", "list_slow_query_history",
    "get_growth_config",
  ];
  for (const name of tools) {
    const calls = [];
    const cfg = makeFakeConfig(calls, []);
    const result = await handleToolCall(cfg, name, {});
    assert.equal(result.isError, true, `${name} should reject empty args`);
    assert.match(result.structuredContent.error, /connectionId is required/);
    assert.equal(calls.length, 0, `${name} must not hit the network on validation failure`);
  }
});

// ─── Live token resolution + 401 self-heal ─────────────────────────────────
// The MCP server is a long-lived subprocess; in the agent container the token
// is rotated on disk without respawning us. These tests cover getAuthToken's
// mtime-cached live read, buildHeaders/callDeepSqlApi picking up a rotated
// token, and the one-shot 401 self-heal retry.

test("createConfigFromEnv wires DEEPSQL_TOKEN_FILE and keeps the env token fallback", () => {
  const withFile = createConfigFromEnv({ DEEPSQL_TOKEN_FILE: "/x/y.token", DEEPSQL_AUTH_TOKEN: "z" });
  assert.equal(withFile.tokenFile, "/x/y.token");
  assert.equal(withFile.authToken, "z");
  const without = createConfigFromEnv({});
  assert.equal(without.tokenFile, null);
});

test("getAuthToken caches by mtime and re-reads only when the file changes", () => {
  invalidateTokenCache();
  const p = tmpTokenFile("tok1\n");
  const cfg = { tokenFile: p, authToken: "envtok" };
  const realRead = fs.readFileSync;
  let reads = 0;
  fs.readFileSync = (...a) => { reads += 1; return realRead(...a); };
  try {
    assert.equal(getAuthToken(cfg), "tok1");
    assert.equal(getAuthToken(cfg), "tok1");
    assert.equal(reads, 1, "unchanged mtime → served from cache, no second read");
    const later = new Date(Date.now() + 10000);
    fs.writeFileSync(p, "tok2\n");
    fs.utimesSync(p, later, later);
    assert.equal(getAuthToken(cfg), "tok2");
    assert.equal(reads, 2, "mtime advanced → re-read from disk");
  } finally {
    fs.readFileSync = realRead;
  }
});

test("getAuthToken falls back to the env token when no file / unreadable file", () => {
  invalidateTokenCache();
  assert.equal(getAuthToken({ authToken: "envtok" }), "envtok");
  assert.equal(getAuthToken({ tokenFile: "/no/such/deepsql.token", authToken: "envtok" }), "envtok");
  assert.equal(getAuthToken({ tokenFile: "/no/such/deepsql.token" }), "");
});

test("callDeepSqlApi sends the live token and reflects a rotation mid-process", async () => {
  invalidateTokenCache();
  const p = tmpTokenFile("tokA\n");
  const cfg = { baseUrl: "http://test/api/", tokenFile: p, timeoutMs: 5000 };
  const stub = installFetchStub([{ body: {} }, { body: {} }]);
  try {
    await callDeepSqlApi(cfg, "/connections");
    const later = new Date(Date.now() + 10000);
    fs.writeFileSync(p, "tokB\n");
    fs.utimesSync(p, later, later);
    await callDeepSqlApi(cfg, "/connections");
  } finally {
    stub.restore();
  }
  assert.equal(stub.authHeaders[0], "Bearer tokA");
  assert.equal(stub.authHeaders[1], "Bearer tokB", "rotated token used without restart");
});

test("callDeepSqlApi self-heals a 401 by re-reading a rotated token and retrying once", async () => {
  invalidateTokenCache();
  const p = tmpTokenFile("stale\n");
  const cfg = { baseUrl: "http://test/api/", tokenFile: p, timeoutMs: 5000 };
  const stub = installFetchStub([
    {
      status: 401,
      ok: false,
      statusText: "Unauthorized",
      body: { message: "unauthorized" },
      // Provisioner rotates the token concurrently with the failing call.
      onCall: () => {
        const later = new Date(Date.now() + 10000);
        fs.writeFileSync(p, "fresh\n");
        fs.utimesSync(p, later, later);
      },
    },
    { body: { ok: true } },
  ]);
  try {
    const result = await callDeepSqlApi(cfg, "/connections");
    assert.deepEqual(result, { ok: true });
  } finally {
    stub.restore();
  }
  assert.equal(stub.authHeaders.length, 2, "exactly one retry");
  assert.equal(stub.authHeaders[0], "Bearer stale");
  assert.equal(stub.authHeaders[1], "Bearer fresh");
});

test("callDeepSqlApi does not retry a 401 when no token file is configured", async () => {
  invalidateTokenCache();
  const cfg = { baseUrl: "http://test/api/", authToken: "envtok", timeoutMs: 5000 };
  const stub = installFetchStub([{ status: 401, ok: false, statusText: "Unauthorized", body: { message: "nope" } }]);
  try {
    await assert.rejects(() => callDeepSqlApi(cfg, "/connections"), /nope/);
  } finally {
    stub.restore();
  }
  assert.equal(stub.authHeaders.length, 1, "env-only install must not retry");
});

test("callDeepSqlApi does not retry a 401 when the token file is unchanged", async () => {
  invalidateTokenCache();
  const p = tmpTokenFile("same\n");
  const cfg = { baseUrl: "http://test/api/", tokenFile: p, timeoutMs: 5000 };
  const stub = installFetchStub([{ status: 401, ok: false, statusText: "Unauthorized", body: { message: "nope" } }]);
  try {
    await assert.rejects(() => callDeepSqlApi(cfg, "/connections"), /nope/);
  } finally {
    stub.restore();
  }
  assert.equal(stub.authHeaders.length, 1, "unchanged token → no pointless retry");
});

test("buildCallerCapabilities fail-closes when the connection cannot write notes", () => {
  const caps = buildCallerCapabilities(
    { id: "c1", canManageContent: false, accessLevel: "CHAT_EDITOR" },
    { username: "marts-editor", role: "DEVELOPER" },
  );
  assert.equal(caps.canWriteSharedBrainNotes, false);
  assert.ok(caps.doNotOffer.includes("save_brain_note"));
  assert.match(summarizeCallerCapabilities(caps), /Do not offer:.*save_brain_note/);
});

test("buildCallerCapabilities allows shared notes when canManageContent is true", () => {
  const caps = buildCallerCapabilities(
    { id: "c1", canManageContent: true, canManageConfig: true },
    { username: "admin", role: "ADMIN" },
  );
  assert.equal(caps.canWriteSharedBrainNotes, true);
  assert.equal(caps.canMutateSql, true);
  assert.equal(caps.doNotOffer.includes("save_brain_note"), false);
});

test("summarizeConnections surfaces read-only vs manage-content", () => {
  const text = summarizeConnections([
    { id: "c1", connectionName: "ACME", dbType: "postgresql", canManageContent: false },
    { id: "c2", connectionName: "Owned", dbType: "postgresql", canManageContent: true },
  ]);
  assert.match(text, /ACME.*\[read-only content\]/);
  assert.match(text, /Owned.*\[manage-content\]/);
});

test("handleToolCall(save_brain_note) refuses before POST when caller cannot write", async () => {
  const calls = [];
  const cfg = makeFakeConfig(calls, [
    [{ id: "c1", canManageContent: false, accessLevel: "CHAT_EDITOR" }],
    { username: "marts-editor", role: "DEVELOPER" },
  ]);
  const result = await handleToolCall(cfg, "save_brain_note", {
    connectionId: "c1", tableName: "marts.dim_person", noteText: "meditator_count_current",
  });
  assert.equal(result.isError, true);
  assert.match(result.content[0].text, /cannot write shared DeepSQL brain notes/);
  assert.match(result.content[0].text, /Do not offer to save/);
  assert.equal(result.structuredContent.errorCode, "POLICY_CONTENT_WRITE_DENIED");
  assert.equal(calls.some((c) => /\/brain\/notes$/.test(c.url) && c.method === "POST"), false);
});

test("handleToolCall(get_brain_context) stamps callerCapabilities onto the payload", async () => {
  const calls = [];
  const cfg = makeFakeConfig(calls, [
    { retrievalIntent: "metric", resultCount: 1, ragTableNames: ["marts.dim_person"] },
    [{ id: "c1", canManageContent: false, accessLevel: "CHAT_EDITOR" }],
    { username: "marts-editor", role: "DEVELOPER" },
  ]);
  const result = await handleToolCall(cfg, "get_brain_context", {
    connectionId: "c1",
    question: "how many meditators",
  });
  assert.equal(result.structuredContent.callerCapabilities.canWriteSharedBrainNotes, false);
  assert.ok(result.structuredContent.callerCapabilities.doNotOffer.includes("save_brain_note"));
  assert.match(result.content[0].text, /Do not offer:.*save_brain_note/);
});
