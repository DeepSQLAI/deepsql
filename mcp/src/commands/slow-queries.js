"use strict";

/**
 * `deepsql slow-queries` — read slow-query analyses, trigger new ones, stream
 * AI optimization steps live, and clean up history.
 *
 *   deepsql slow-queries latest --connection <name> [--json]
 *   deepsql slow-queries history --connection <name> [N] [--json]
 *   deepsql slow-queries analyze --connection <name>
 *       [--time-range LAST_24_HOURS|LAST_HOUR] [--threshold-ms <ms>] [--limit <n>]
 *   deepsql slow-queries optimize --connection <name> --query-id <id>
 *       (streams SSE; use --query-text to skip history lookup)
 *   deepsql slow-queries delete --history-id <id> [--yes]
 *   deepsql slow-queries trends --connection <name> [--json]
 *   deepsql slow-queries regressions --connection <name> [--min-factor <n>] [--json]
 *   deepsql slow-queries timeline <fingerprint> --connection <name> [--json]
 *   deepsql slow-queries customers --connection <name> [--json]
 *   deepsql slow-queries samples <fingerprint> --connection <name> [--limit <n>] [--json]
 *   deepsql slow-queries insights --connection <name>
 *       [--kind hotspots|remediation|tail-risk|plan-drift|skew]
 *       [--window LAST_7_DAYS|LAST_24_HOURS|LAST_30_DAYS] [--limit <n>] [--json]
 *   deepsql slow-queries trigger --connection <name> [--json]
 *
 * The optimize subcommand follows the SSE protocol from
 * /slow-queries/optimize/stream — `step` events go to stderr, the final
 * `result` event prints to stdout. Honours SIGINT.
 */

const { ApiError, request } = require("../api/client");
const { streamSse } = require("../ui/sse");
const { resolveSession } = require("./_session");
const { resolveConnectionId } = require("./_connections");
const ui = require("../ui/prompts");

const SUBCOMMANDS = {
  latest: cmdLatest,
  history: cmdHistory,
  analyze: cmdAnalyze,
  optimize: cmdOptimize,
  delete: cmdDelete,
  trends: cmdTrends,
  regressions: cmdRegressions,
  timeline: cmdTimeline,
  customers: cmdCustomers,
  samples: cmdSamples,
  insights: cmdInsights,
  trigger: cmdTrigger,
};

async function run(opts, io = {}) {
  const sub = opts.positional[0];
  if (!sub) {
    throw new Error(
      "Usage: deepsql slow-queries "
      + "<latest|history|analyze|optimize|delete|trends|regressions|timeline"
      + "|customers|samples|insights|trigger> ...");
  }
  const handler = SUBCOMMANDS[sub];
  if (!handler) throw new Error(`Unknown slow-queries subcommand: ${sub}.`);
  // The resolver provides default-connection fallback; we only let `delete`
  // run without one because it can also operate by --history-id.
  return wrap(handler)({ ...opts, positional: opts.positional.slice(1) }, io);
}

function wrap(handler) {
  return async (opts, io) => {
    try {
      return await handler(opts, io);
    } catch (err) {
      if (err instanceof ApiError && err.status === 403) {
        throw new Error("Access denied — slow-query operations require permissions on this connection.");
      }
      throw err;
    }
  };
}

// ─── latest ────────────────────────────────────────────────────────────────

async function cmdLatest(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  const result = await request(
    session.baseUrl,
    `/slow-queries/latest/${encodeURIComponent(connectionId)}`,
    { token: session.token },
  );
  stdout.write(`${JSON.stringify(result, null, 2)}\n`);
}

// ─── history ───────────────────────────────────────────────────────────────

async function cmdHistory(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  const summaries = await request(
    session.baseUrl,
    `/slow-queries/history/${encodeURIComponent(connectionId)}`,
    { token: session.token },
  );
  const items = Array.isArray(summaries) ? summaries : [];
  const limitArg = parseCount(opts.positional[0]) || parseCount(opts.count);
  const trimmed = limitArg ? items.slice(0, limitArg) : items;

  if (opts.json) {
    stdout.write(`${JSON.stringify(trimmed, null, 2)}\n`);
    return;
  }
  if (trimmed.length === 0) {
    stdout.write("No history.\n");
    return;
  }
  printHistory(stdout, trimmed);
}

// ─── analyze ───────────────────────────────────────────────────────────────

async function cmdAnalyze(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  const timeRange = (opts.timeRange || "LAST_24_HOURS").toUpperCase();
  const thresholdMs = parseNum(opts.thresholdMs) || 100;
  const limit = parseNum(opts.limit) || 10;

  stderr.write(`Analyzing slow queries on ${opts.connection} (range=${timeRange}, threshold=${thresholdMs}ms)…\n`);
  const result = await request(session.baseUrl, "/slow-queries/analyze", {
    method: "POST",
    token: session.token,
    timeoutMs: 240000,
    json: { connectionId, timeRange, thresholdMs, limit },
  });
  if (opts.json) {
    stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    return;
  }
  const queries = (result && (result.queries || result.slowQueries)) || [];
  if (queries.length === 0) {
    stdout.write("No slow queries detected in the selected window.\n");
    return;
  }
  printQueries(stdout, queries);
}

// ─── optimize (SSE) ────────────────────────────────────────────────────────

async function cmdOptimize(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);

  let queryText = opts.queryText;
  let queryId = opts.queryId;

  // If --query-id is given, look up the queryText from the latest analysis so
  // the user doesn't have to paste raw SQL into the terminal.
  if (!queryText) {
    if (!queryId) throw new Error("Pass --query-id <id> or --query-text \"<sql>\".");
    queryText = await lookupQueryText(session, connectionId, queryId);
    if (!queryText) {
      throw new Error(`Could not find query ${queryId} in the latest analysis. Pass --query-text "<sql>" instead.`);
    }
  }

  const controller = new AbortController();
  const onSigint = () => {
    stderr.write("\n[deepsql] cancelling optimization…\n");
    controller.abort();
  };
  process.once("SIGINT", onSigint);

  try {
    const stream = streamSse(session.baseUrl, "/slow-queries/optimize/stream", {
      token: session.token,
      query: { connectionId, queryText, queryId, sampleQuery: opts.sampleQuery },
      signal: controller.signal,
    });
    for await (const message of stream) {
      if (message.event === "step") {
        const parsed = safeParse(message.data);
        const status = parsed?.status || "step";
        const text = parsed?.message || message.data;
        stderr.write(`[${status}] ${text}\n`);
      } else if (message.event === "result") {
        if (opts.json) {
          stdout.write(`${message.data}\n`);
        } else {
          const parsed = safeParse(message.data);
          if (parsed && parsed.optimizedQuery) {
            stdout.write(`\n--- Optimized query ---\n${parsed.optimizedQuery}\n`);
          }
          if (parsed && parsed.recommendations && parsed.recommendations.length) {
            stdout.write("\n--- Recommendations ---\n");
            for (const r of parsed.recommendations) {
              stdout.write(`• ${r.title || r.summary || JSON.stringify(r)}\n`);
            }
          }
          if (!parsed || (!parsed.optimizedQuery && !(parsed.recommendations || []).length)) {
            stdout.write(`${message.data}\n`);
          }
        }
      } else if (message.event === "error") {
        const parsed = safeParse(message.data);
        throw new Error(`Optimization failed: ${parsed?.message || message.data}`);
      }
    }
  } catch (err) {
    if (controller.signal.aborted) {
      process.exitCode = 130; // 128 + SIGINT
      return;
    }
    throw err;
  } finally {
    process.removeListener("SIGINT", onSigint);
  }
}

async function lookupQueryText(session, connectionId, queryId) {
  const latest = await request(
    session.baseUrl,
    `/slow-queries/latest/${encodeURIComponent(connectionId)}`,
    { token: session.token },
  );
  const data = latest?.analysisData || latest?.analysis || latest || {};
  const queries = data.queries || data.slowQueries || [];
  const hit = queries.find((q) => String(q.queryId || q.id) === String(queryId));
  return hit ? (hit.queryText || hit.normalizedQuery || hit.query) : null;
}

// ─── delete ────────────────────────────────────────────────────────────────

async function cmdDelete(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const session = resolveSession(opts);

  if (opts.historyId) {
    if (!opts.yes) {
      const ok = await ui.confirm({
        message: `Delete history entry ${opts.historyId}?`,
        default: false,
      });
      if (!ok) {
        stderr.write("Aborted.\n");
        return;
      }
    }
    await request(session.baseUrl, `/slow-queries/history/${encodeURIComponent(opts.historyId)}`, {
      method: "DELETE",
      token: session.token,
    });
    stdout.write(`Deleted history entry ${opts.historyId}.\n`);
    return;
  }
  if (opts.connection) {
    if (!opts.yes) {
      const ok = await ui.confirm({
        message: `Delete ALL slow-query history for ${opts.connection}? This is destructive.`,
        default: false,
      });
      if (!ok) {
        stderr.write("Aborted.\n");
        return;
      }
    }
    const connectionId = await resolveConnectionId(session, opts.connection);
    await request(
      session.baseUrl,
      `/slow-queries/history/connection/${encodeURIComponent(connectionId)}`,
      { method: "DELETE", token: session.token },
    );
    stdout.write(`Deleted all history for ${opts.connection}.\n`);
    return;
  }
  throw new Error("Pass --history-id <id> or --connection <name>.");
}

// ─── helpers ───────────────────────────────────────────────────────────────

function parseCount(v) {
  if (v == null) return null;
  const n = Number.parseInt(v, 10);
  return Number.isFinite(n) && n > 0 ? n : null;
}

function parseNum(v) {
  if (v == null) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function safeParse(text) {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

// ─── trends ──────────────────────────────────────────────────────────────
// 30-day analytics: tracked queries, regressions, and per-query timelines.

async function cmdTrends(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  const queries = await request(
    session.baseUrl,
    `/slow-query-analytics/${encodeURIComponent(connectionId)}/queries`,
    { token: session.token },
  );
  const items = Array.isArray(queries) ? queries : [];
  if (opts.json) {
    stdout.write(`${JSON.stringify(items, null, 2)}\n`);
    return;
  }
  if (items.length === 0) {
    stdout.write("No tracked queries yet. The daily analysis runs at 01:30, "
      + "or trigger one now from the UI / API.\n");
    return;
  }
  printTrendRows(stdout, items);
}

async function cmdRegressions(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  const minFactor = parseNum(opts["min-factor"]) || 1.5;
  const rows = await request(
    session.baseUrl,
    `/slow-query-analytics/${encodeURIComponent(connectionId)}/regressions?minFactor=${minFactor}`,
    { token: session.token },
  );
  const items = Array.isArray(rows) ? rows : [];
  if (opts.json) {
    stdout.write(`${JSON.stringify(items, null, 2)}\n`);
    return;
  }
  if (items.length === 0) {
    stdout.write(`No queries regressed by ${minFactor}x or more on the latest run.\n`);
    return;
  }
  printTable(stdout, [
    { key: "fingerprint", label: "QUERY ID" },
    { key: "factor", label: "SLOWDOWN" },
    { key: "meanMs", label: "MEAN MS" },
    { key: "calls", label: "CALLS" },
    { key: "sql", label: "QUERY" },
  ], items.map((r) => ({
    fingerprint: trim(r.fingerprint || "", 16),
    factor: r.regressionFactor != null ? `${r.regressionFactor.toFixed(2)}x` : "?",
    meanMs: r.meanExecMs != null ? Math.round(r.meanExecMs).toString() : "?",
    calls: String(r.callsDelta ?? "?"),
    sql: trim(r.normalizedSql || "", 56),
  })));
}

async function cmdTimeline(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  const fingerprint = opts.positional[0];
  if (!fingerprint) {
    throw new Error("Usage: deepsql slow-queries timeline <fingerprint> --connection <name>");
  }
  const points = await request(
    session.baseUrl,
    `/slow-query-analytics/${encodeURIComponent(connectionId)}`
      + `/timeline/${encodeURIComponent(fingerprint)}`,
    { token: session.token },
  );
  const items = Array.isArray(points) ? points : [];
  if (opts.json) {
    stdout.write(`${JSON.stringify(items, null, 2)}\n`);
    return;
  }
  if (items.length === 0) {
    stdout.write("No timeline data for that query fingerprint.\n");
    return;
  }
  printTable(stdout, [
    { key: "day", label: "DAY" },
    { key: "calls", label: "CALLS" },
    { key: "meanMs", label: "MEAN MS" },
    { key: "maxMs", label: "MAX MS" },
    { key: "factor", label: "VS PREV" },
  ], items.map((p) => ({
    day: String(p.day || ""),
    calls: String(p.callsDelta ?? "?"),
    meanMs: p.meanExecMs != null ? Math.round(p.meanExecMs).toString() : "?",
    maxMs: p.maxExecMs != null ? Math.round(p.maxExecMs).toString() : "?",
    factor: p.regressionFactor != null ? `${p.regressionFactor.toFixed(2)}x` : "—",
  })));
}

// ─── customers ─────────────────────────────────────────────────────────────

async function cmdCustomers(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  const rows = await request(
    session.baseUrl,
    `/slow-query-analytics/${encodeURIComponent(connectionId)}/customers`,
    { token: session.token },
  );
  const items = Array.isArray(rows) ? rows : [];
  if (opts.json) {
    stdout.write(`${JSON.stringify(items, null, 2)}\n`);
    return;
  }
  if (items.length === 0) {
    stdout.write("No customer attribution data yet. Configure a tenant column in analytics settings.\n");
    return;
  }
  printTable(stdout, [
    { key: "rank",       label: "#" },
    { key: "customer",   label: "CUSTOMER" },
    { key: "name",       label: "NAME" },
    { key: "queries",    label: "QUERIES" },
    { key: "totalSec",   label: "TOTAL (s)" },
  ], items.map((c, i) => ({
    rank:     String(i + 1),
    customer: trim(c.customerId || "", 22),
    name:     trim(c.customerName || "—", 28),
    queries:  String(c.queryCount ?? "?"),
    totalSec: c.totalExecMs != null ? (c.totalExecMs / 1000).toFixed(1) : "?",
  })));
}

// ─── samples ───────────────────────────────────────────────────────────────

async function cmdSamples(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  const fingerprint = opts.positional[0];
  if (!fingerprint) {
    throw new Error("Usage: deepsql slow-queries samples <fingerprint> --connection <name>");
  }
  const limit = parseNum(opts.limit) || 10;
  const rows = await request(
    session.baseUrl,
    `/slow-query-analytics/${encodeURIComponent(connectionId)}`
      + `/query/${encodeURIComponent(fingerprint)}/samples`,
    { token: session.token },
  );
  const items = Array.isArray(rows) ? rows.slice(0, limit) : [];
  if (opts.json) {
    stdout.write(`${JSON.stringify(items, null, 2)}\n`);
    return;
  }
  if (items.length === 0) {
    stdout.write("No samples found for that fingerprint.\n");
    return;
  }
  printTable(stdout, [
    { key: "execMs",   label: "EXEC MS" },
    { key: "examined", label: "ROWS EXAM" },
    { key: "sent",     label: "ROWS SENT" },
    { key: "source",   label: "SOURCE" },
    { key: "captured", label: "CAPTURED AT" },
    { key: "sql",      label: "SQL" },
  ], items.map((s) => ({
    execMs:   s.execMs != null ? String(Math.round(s.execMs)) : "?",
    examined: String(s.rowsExamined ?? "?"),
    sent:     String(s.rowsSent ?? "?"),
    source:   trim(s.source || "?", 14),
    captured: formatTime(s.capturedAt),
    sql:      trim(s.rawSql || "", 60),
  })));
}

// ─── insights ──────────────────────────────────────────────────────────────

async function cmdInsights(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  const kind = (opts.kind || "").toLowerCase();
  const window = (opts.window || "LAST_7_DAYS").toUpperCase();
  const limit = parseNum(opts.limit) || 10;
  const subPath = kind && kind !== "all" ? `/${encodeURIComponent(kind)}` : "";
  const rows = await request(
    session.baseUrl,
    `/slow-queries/insights/${encodeURIComponent(connectionId)}${subPath}`
      + `?window=${encodeURIComponent(window)}&limit=${limit}`,
    { token: session.token },
  );
  const items = Array.isArray(rows) ? rows : [];
  if (opts.json) {
    stdout.write(`${JSON.stringify(items, null, 2)}\n`);
    return;
  }
  if (items.length === 0) {
    stdout.write(`No insights for window=${window}${kind ? ` kind=${kind}` : ""}.\n`);
    return;
  }
  for (let i = 0; i < items.length; i++) {
    const ins = items[i];
    const sev = ins.severity ? `[${ins.severity}] ` : "";
    const title = ins.title || ins.insightType || "insight";
    const desc = String(ins.description || ins.message || "").replace(/\s+/g, " ").trim();
    stdout.write(`${i + 1}. ${sev}${title}\n`);
    if (desc) stdout.write(`   ${desc}\n`);
  }
}

// ─── trigger ───────────────────────────────────────────────────────────────

async function cmdTrigger(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  stderr.write(`Triggering slow-query analysis for ${opts.connection}…\n`);
  const result = await request(
    session.baseUrl,
    `/slow-query-analytics/${encodeURIComponent(connectionId)}/analyze-now`,
    { method: "POST", token: session.token, timeoutMs: 300000 },
  );
  if (opts.json) {
    stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    return;
  }
  const runId = result?.analysisRunId || result?.runId || "?";
  const health = result?.overallHealth || result?.health || "";
  stdout.write(`Analysis started. Run ID: ${runId}${health ? `  Health: ${health}` : ""}\n`);
}

function printTrendRows(stdout, items) {
  printTable(stdout, [
    { key: "fingerprint", label: "QUERY ID" },
    { key: "meanMs", label: "MEAN MS" },
    { key: "calls", label: "CALLS" },
    { key: "factor", label: "VS PREV" },
    { key: "sql", label: "QUERY" },
  ], items.map((q) => ({
    fingerprint: trim(q.fingerprint || "", 16),
    meanMs: q.meanExecMs != null ? Math.round(q.meanExecMs).toString() : "?",
    calls: String(q.callsDelta ?? "?"),
    factor: q.regressionFactor != null ? `${q.regressionFactor.toFixed(2)}x` : "—",
    sql: trim(q.normalizedSql || "", 54),
  })));
}

function printHistory(stdout, items) {
  const rows = items.map((h) => ({
    id: String(h.id ?? h.historyId ?? ""),
    createdAt: formatTime(h.createdAt || h.timestamp || h.analysisTime),
    queries: String(h.queryCount ?? h.totalQueries ?? "?"),
    summary: trim(h.summary || h.headline || "", 70),
  }));
  const cols = [
    { key: "id", label: "ID" },
    { key: "createdAt", label: "WHEN" },
    { key: "queries", label: "#" },
    { key: "summary", label: "SUMMARY" },
  ];
  printTable(stdout, cols, rows);
}

function printQueries(stdout, queries) {
  const rows = queries.map((q) => ({
    id: String(q.queryId || q.id || ""),
    severity: q.severity || "?",
    avgMs: q.avgExecutionTimeMs != null ? Math.round(q.avgExecutionTimeMs).toString() : "?",
    calls: String(q.callCount ?? q.executionCount ?? "?"),
    sample: trim(q.queryText || q.normalizedQuery || q.query || "", 60),
  }));
  const cols = [
    { key: "id", label: "QUERY ID" },
    { key: "severity", label: "SEVERITY" },
    { key: "avgMs", label: "AVG MS" },
    { key: "calls", label: "CALLS" },
    { key: "sample", label: "QUERY" },
  ];
  printTable(stdout, cols, rows);
}

function printTable(stdout, cols, rows) {
  const widths = cols.map((c) => Math.max(c.label.length, ...rows.map((r) => r[c.key].length)));
  const header = cols.map((c, i) => c.label.padEnd(widths[i])).join("  ");
  const sep = widths.map((w) => "-".repeat(w)).join("  ");
  stdout.write(`${header}\n${sep}\n`);
  for (const row of rows) {
    stdout.write(`${cols.map((c, i) => row[c.key].padEnd(widths[i])).join("  ")}\n`);
  }
}

function formatTime(value) {
  if (!value) return "—";
  try {
    const d = new Date(value);
    if (Number.isNaN(d.getTime())) return String(value);
    return d.toISOString().replace("T", " ").slice(0, 16) + "Z";
  } catch {
    return String(value);
  }
}

function trim(text, max) {
  if (!text) return "";
  const s = String(text).replace(/\s+/g, " ").trim();
  return s.length > max ? s.slice(0, max - 1) + "…" : s;
}

module.exports = { run, SUBCOMMANDS };
