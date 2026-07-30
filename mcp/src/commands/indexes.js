"use strict";

/**
 * `deepsql indexes` — unified index advisor namespace.
 *
 * Two categories of subcommand live here, sharing the same `--connection` /
 * `--json` flag plumbing:
 *
 *   Advisor (workload-weighted, evidence-bearing — mirrors the MCP tools):
 *     top      [--limit N]                            GET    /index-recommendations/{cid}/top
 *     list     [--all | --status PENDING|APPLIED|…]   GET    /index-recommendations/{cid}[?status=]
 *     show     <id>                                   GET    /index-recommendations/{cid}/top (client-side filter)
 *     refresh                                         POST   /index-recommendations/generate/{cid}
 *     apply    <id> [--mode … --confirm --no-concurrent]  POST  /index-recommendations/{id}/apply
 *     dismiss  <id>                                   PUT    /index-recommendations/{id}/dismiss
 *
 *   Catalog diagnostics (read-only — live pg_stat_* / sys.* probes):
 *     missing                                         GET    /advisor/indexes/{cid}
 *     health                                          GET    /index-advisor/{cid}/health-report
 *     unused                                          GET    /index-advisor/{cid}/unused
 *     duplicates                                      GET    /index-advisor/{cid}/duplicates
 *     usage    <table>                                GET    /index-advisor/{cid}/usage/{tableName}
 *
 * The `apply` subcommand is the one mutation here. Default mode is dry-run
 * (HypoPG-based, no writes on Postgres). `apply` / `apply-and-measure`
 * require `--confirm`; `--no-concurrent` opts out of CREATE/DROP INDEX
 * CONCURRENTLY.
 */

const { ApiError, request } = require("../api/client");
const { resolveSession } = require("./_session");
const { resolveConnectionId } = require("./_connections");

const VALID_STATUSES = new Set(["PENDING", "APPLIED", "DISMISSED"]);

const SUBCOMMANDS = {
  // Advisor surface (workload-weighted)
  top: cmdTop,
  list: cmdList,
  show: cmdShow,
  refresh: cmdRefresh,
  apply: cmdApply,
  dismiss: cmdDismiss,
  // Catalog diagnostics
  missing: cmdMissing,
  health: cmdHealth,
  unused: cmdUnused,
  duplicates: cmdDuplicates,
  usage: cmdUsage,
};

async function run(opts, io = {}) {
  const sub = opts.positional[0] || "list";
  const handler = SUBCOMMANDS[sub];
  if (!handler) {
    throw new Error(
      `Unknown indexes subcommand: ${sub}. ` +
        `Try one of: top, list, show, refresh, apply, dismiss, ` +
        `missing, health, unused, duplicates, usage <table>.`,
    );
  }
  return wrap(handler)({ ...opts, positional: opts.positional.slice(1) }, io);
}

function wrap(handler) {
  return async (opts, io) => {
    try {
      return await handler(opts, io);
    } catch (err) {
      if (err instanceof ApiError && err.status === 403) {
        throw new Error(
          "Access denied — index operations require permissions on this connection.",
        );
      }
      if (err instanceof ApiError && err.status === 404) {
        throw new Error(err.message || "Recommendation not found.");
      }
      throw err;
    }
  };
}

// ═════════════════════════════════════════════════════════════════════════════
// ADVISOR SURFACE — workload-weighted, evidence-bearing
// (mirrors the `get_index_recommendations` + `apply_index_recommendation` MCP tools)
// ═════════════════════════════════════════════════════════════════════════════

// ─── top ───────────────────────────────────────────────────────────────────

async function cmdTop(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  const limit = clampLimit(opts.limit, 5);
  const result = await request(
    session.baseUrl,
    `/index-recommendations/${encodeURIComponent(connectionId)}/top`,
    { token: session.token, query: { limit } },
  );

  if (opts.json) {
    stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    return;
  }

  const list = Array.isArray(result) ? result : [];
  if (list.length === 0) {
    stdout.write(
      "No pending index recommendations. The 6-hour refresh scheduler may not have run yet, " +
        "or the workload has none worth flagging. Try `deepsql indexes refresh` " +
        "to force a fresh accumulation cycle.\n",
    );
    return;
  }

  stdout.write(`Top ${list.length} pending index recommendation(s):\n\n`);
  list.forEach((r, idx) => {
    const action = r.kind === "DROP_INDEX" ? "DROP" : "CREATE";
    const target =
      r.kind === "DROP_INDEX"
        ? `${r.tableName}.${r.indexName} (unused)`
        : `${r.tableName}(${r.columnNames})`;

    const meta = [
      r.priority,
      r.occurrenceCount != null ? `seen ${r.occurrenceCount}×` : null,
      formatNet(r),
      r.evidenceCount > 0 ? `${r.evidenceCount} ev` : null,
    ]
      .filter(Boolean)
      .join(", ");

    stdout.write(`${idx + 1}. [${action}] ${target} — ${meta}\n`);
    stdout.write(`   id: ${r.id}\n`);
    if (r.hypopgReductionPct != null) {
      stdout.write(
        `   HypoPG cost: ${formatCost(r.hypopgBeforeCost)} → ${formatCost(r.hypopgAfterCost)} ` +
          `(${signedPct(r.hypopgReductionPct)})\n`,
      );
    }
    if (r.reason) {
      stdout.write(`   ${truncate(r.reason, 200)}\n`);
    }
    if (Array.isArray(r.topEvidence) && r.topEvidence.length) {
      const ev = r.topEvidence[0];
      stdout.write(
        `   top evidence: ${ev.calls} calls × ${formatMs(ev.meanExecTimeMs)}` +
          ` mean = ${formatMs(ev.totalExecTimeMs)} total (${ev.role})\n`,
      );
    }
    stdout.write("\n");
  });
}

// ─── list ──────────────────────────────────────────────────────────────────

async function cmdList(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);

  const wantAll = !!opts.all || (opts.status && opts.status !== "PENDING");
  const statusFilter = opts.status ? String(opts.status).toUpperCase() : null;
  if (statusFilter && !VALID_STATUSES.has(statusFilter)) {
    throw new Error(
      `Invalid --status "${opts.status}". One of: ${[...VALID_STATUSES].join(", ")}.`,
    );
  }

  const path = wantAll
    ? `/index-recommendations/${encodeURIComponent(connectionId)}`
    : `/index-recommendations/pending/${encodeURIComponent(connectionId)}`;

  const response = await request(session.baseUrl, path, { token: session.token });
  let list = Array.isArray(response) ? response : [];
  if (statusFilter && statusFilter !== "PENDING") {
    list = list.filter((r) => String(r.status || "").toUpperCase() === statusFilter);
  }

  if (opts.json) {
    stdout.write(`${JSON.stringify(list, null, 2)}\n`);
    return;
  }

  if (list.length === 0) {
    const scope = statusFilter || (wantAll ? "any" : "pending");
    stdout.write(`No ${scope.toLowerCase()} index recommendations for this connection.\n`);
    return;
  }

  const byPriority = (a, b) => priorityWeight(b.priority) - priorityWeight(a.priority);
  list.sort(byPriority);

  const scopeLabel = statusFilter || (wantAll ? "all" : "pending");
  const noun = list.length === 1 ? "recommendation" : "recommendations";
  stdout.write(`${list.length} ${scopeLabel} ${noun}:\n\n`);

  for (const r of list) {
    const prio = r.priority ? `[${r.priority}]` : "[?]";
    const cols = r.columnNames || "?";
    const status = r.status ? ` (${r.status})` : "";
    const impact = r.estimatedImpact != null ? `, impact≈${r.estimatedImpact}%` : "";
    const affected = r.affectedQueries != null ? `, ${r.affectedQueries} queries` : "";
    stdout.write(`${prio} ${r.tableName || "?"}(${cols})${status}\n`);
    if (r.indexName) stdout.write(`  name:   ${r.indexName}\n`);
    if (r.reason) stdout.write(`  reason: ${r.reason}${impact}${affected}\n`);
    if (r.createStatement) stdout.write(`  ddl:    ${oneLine(r.createStatement)}\n`);
    if (r.id) stdout.write(`  id:     ${r.id}\n`);
    stdout.write("\n");
  }
}

// ─── show <id> ─────────────────────────────────────────────────────────────

async function cmdShow(opts, { stdout = process.stdout } = {}) {
  const id = opts.positional[0];
  if (!id) throw new Error("Usage: deepsql indexes show <id> --connection <name>");
  const session = resolveSession(opts);
  const connectionId = opts.connection
    ? await resolveConnectionId(session, opts.connection)
    : null;
  if (!connectionId) {
    throw new Error(
      "`show` needs --connection <name> to look up the recommendation (or pin one with `deepsql connections use`).",
    );
  }
  const list = await request(
    session.baseUrl,
    `/index-recommendations/${encodeURIComponent(connectionId)}/top`,
    { token: session.token, query: { limit: 50 } },
  );
  const found = (list || []).find((r) => r.id === id);
  if (!found) {
    throw new Error(`Recommendation ${id} not in the top-50 for this connection.`);
  }
  if (opts.json) {
    stdout.write(`${JSON.stringify(found, null, 2)}\n`);
    return;
  }
  renderRecommendationDetail(stdout, found);
}

// ─── refresh ───────────────────────────────────────────────────────────────

async function cmdRefresh(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  stderr.write(`Refreshing index recommendations on ${opts.connection || connectionId}…\n`);
  const result = await request(
    session.baseUrl,
    `/index-recommendations/generate/${encodeURIComponent(connectionId)}`,
    { method: "POST", token: session.token, timeoutMs: 600000 },
  );
  if (opts.json) {
    stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    return;
  }
  if (result && result.success) {
    stdout.write(
      `Refresh complete: ${result.count} candidate(s) merged. ` +
        `Use \`deepsql indexes top\` to see the top-N.\n`,
    );
  } else {
    stdout.write(`Refresh response: ${JSON.stringify(result)}\n`);
  }
}

// ─── apply <id> (the one mutation) ─────────────────────────────────────────

async function cmdApply(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const id = opts.positional[0];
  if (!id) {
    throw new Error(
      "Usage: deepsql indexes apply <id> [--mode dry-run|apply|apply-and-measure] [--confirm] [--no-concurrent]",
    );
  }

  const mode = normalizeMode(opts.mode);
  const isWriteMode = mode === "APPLY" || mode === "APPLY_AND_MEASURE";
  if (isWriteMode && !opts.confirm) {
    throw new Error(
      `Mode ${mode} mutates the target database. Re-run with --confirm to proceed.`,
    );
  }
  const concurrent = !opts.noConcurrent;

  const session = resolveSession(opts);
  if (isWriteMode) {
    stderr.write(`Running ${mode} on recommendation ${id}…\n`);
  }

  const result = await request(
    session.baseUrl,
    `/index-recommendations/${encodeURIComponent(id)}/apply`,
    {
      method: "POST",
      token: session.token,
      timeoutMs: 600000,
      query: { mode, confirm: opts.confirm === true, concurrent },
    },
  );

  if (opts.json) {
    stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    return;
  }
  renderApplyResult(stdout, result);
}

// ─── dismiss <id> ──────────────────────────────────────────────────────────

async function cmdDismiss(opts, { stdout = process.stdout } = {}) {
  const id = opts.positional[0];
  if (!id) throw new Error("Usage: deepsql indexes dismiss <id>");
  const session = resolveSession(opts);
  const result = await request(
    session.baseUrl,
    `/index-recommendations/${encodeURIComponent(id)}/dismiss`,
    { method: "PUT", token: session.token },
  );
  if (opts.json) {
    stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    return;
  }
  stdout.write(`Dismissed recommendation ${id}.\n`);
}

// ═════════════════════════════════════════════════════════════════════════════
// CATALOG DIAGNOSTICS — live pg_stat_* / sys.* probes
// ═════════════════════════════════════════════════════════════════════════════

// ─── missing ───────────────────────────────────────────────────────────────

async function cmdMissing(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);

  const response = await request(
    session.baseUrl,
    `/advisor/indexes/${encodeURIComponent(connectionId)}`,
    { token: session.token },
  );
  const list = Array.isArray(response) ? response : [];

  if (opts.json) {
    stdout.write(`${JSON.stringify(list, null, 2)}\n`);
    return;
  }
  if (list.length === 0) {
    stdout.write("No missing-index suggestions for this connection.\n");
    return;
  }

  stdout.write(`${list.length} missing-index ${list.length === 1 ? "suggestion" : "suggestions"}:\n\n`);
  for (const r of list) {
    const prio = r.priority ? `[${r.priority}]` : "[?]";
    const cols = formatColumns(r.columnNames || r.columns);
    stdout.write(`${prio} ${r.tableName || "?"}(${cols})\n`);
    if (r.reason) stdout.write(`  reason: ${r.reason}\n`);
    if (r.estimatedImpact != null) stdout.write(`  impact: ${r.estimatedImpact}\n`);
    if (r.suggestedIndex) stdout.write(`  ddl:    ${oneLine(r.suggestedIndex)}\n`);
    stdout.write("\n");
  }
}

// ─── health ────────────────────────────────────────────────────────────────

async function cmdHealth(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);

  const response = await request(
    session.baseUrl,
    `/index-advisor/${encodeURIComponent(connectionId)}/health-report`,
    { token: session.token },
  );

  if (opts.json) {
    stdout.write(`${JSON.stringify(response, null, 2)}\n`);
    return;
  }
  if (!response || typeof response !== "object") {
    stdout.write("No health report available.\n");
    return;
  }

  stdout.write("Index health report\n");
  stdout.write("───────────────────\n");
  for (const [key, value] of Object.entries(response)) {
    if (value == null) continue;
    if (Array.isArray(value)) {
      stdout.write(`${pad(key)} ${value.length} entries\n`);
    } else if (typeof value === "object") {
      stdout.write(`${pad(key)} ${JSON.stringify(value)}\n`);
    } else {
      stdout.write(`${pad(key)} ${value}\n`);
    }
  }
}

// ─── unused ────────────────────────────────────────────────────────────────

async function cmdUnused(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);

  const response = await request(
    session.baseUrl,
    `/index-advisor/${encodeURIComponent(connectionId)}/unused`,
    { token: session.token },
  );
  const list = Array.isArray(response) ? response : [];

  if (opts.json) {
    stdout.write(`${JSON.stringify(list, null, 2)}\n`);
    return;
  }
  if (list.length === 0) {
    stdout.write("No unused indexes detected.\n");
    return;
  }

  stdout.write(`${list.length} unused ${list.length === 1 ? "index" : "indexes"}:\n\n`);
  for (const idx of list) {
    const size = idx.sizeMb != null ? `, ${idx.sizeMb} MB` : (idx.indexSize ? `, ${idx.indexSize}` : "");
    const scans = idx.scans != null ? `, scans=${idx.scans}` : (idx.indexScans != null ? `, scans=${idx.indexScans}` : "");
    stdout.write(
      `  • ${idx.tableName || idx.table || "?"}.${idx.indexName || idx.name || "?"}${size}${scans}\n`,
    );
  }
}

// ─── duplicates ────────────────────────────────────────────────────────────

async function cmdDuplicates(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);

  const response = await request(
    session.baseUrl,
    `/index-advisor/${encodeURIComponent(connectionId)}/duplicates`,
    { token: session.token },
  );
  const list = Array.isArray(response) ? response : [];

  if (opts.json) {
    stdout.write(`${JSON.stringify(list, null, 2)}\n`);
    return;
  }
  if (list.length === 0) {
    stdout.write("No duplicate or redundant indexes detected.\n");
    return;
  }

  stdout.write(`${list.length} duplicate ${list.length === 1 ? "group" : "groups"}:\n\n`);
  for (const dup of list) {
    const table = dup.tableName || dup.table || "?";
    const names = Array.isArray(dup.indexes)
      ? dup.indexes.map((i) => (typeof i === "string" ? i : i.indexName || i.name)).join(", ")
      : (dup.indexName || dup.name || "?");
    stdout.write(`  • ${table}: ${names}\n`);
    if (dup.reason) stdout.write(`      ${dup.reason}\n`);
  }
}

// ─── usage <table> ─────────────────────────────────────────────────────────

async function cmdUsage(opts, { stdout = process.stdout } = {}) {
  const tableName = opts.positional[0];
  if (!tableName) {
    throw new Error("Usage: deepsql indexes usage <tableName> --connection <name> [--json]");
  }
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);

  const response = await request(
    session.baseUrl,
    `/index-advisor/${encodeURIComponent(connectionId)}/usage/${encodeURIComponent(tableName)}`,
    { token: session.token },
  );
  const list = Array.isArray(response) ? response : [];

  if (opts.json) {
    stdout.write(`${JSON.stringify(list, null, 2)}\n`);
    return;
  }
  if (list.length === 0) {
    stdout.write(`No index usage stats for ${tableName}.\n`);
    return;
  }

  stdout.write(`Index usage for ${tableName} (${list.length} ${list.length === 1 ? "index" : "indexes"}):\n\n`);
  for (const idx of list) {
    const scans = idx.scans ?? idx.indexScans ?? "?";
    const reads = idx.tuplesRead ?? idx.tupReads ?? null;
    const fetched = idx.tuplesFetched ?? idx.tupFetched ?? null;
    const extra = [
      `scans=${scans}`,
      reads != null ? `reads=${reads}` : null,
      fetched != null ? `fetched=${fetched}` : null,
    ].filter(Boolean).join(", ");
    stdout.write(`  • ${idx.indexName || idx.name || "?"} — ${extra}\n`);
  }
}

// ═════════════════════════════════════════════════════════════════════════════
// helpers
// ═════════════════════════════════════════════════════════════════════════════

function renderRecommendationDetail(stdout, r) {
  const action = r.kind === "DROP_INDEX" ? "DROP" : "CREATE";
  stdout.write(`[${action}] ${r.tableName}(${r.columnNames || r.indexName})\n`);
  stdout.write(`  id            : ${r.id}\n`);
  stdout.write(`  priority      : ${r.priority}\n`);
  stdout.write(`  status        : ${r.status || "PENDING"}\n`);
  stdout.write(`  occurrenceCnt : ${r.occurrenceCount}\n`);
  stdout.write(`  workloadScore : ${formatMs(r.workloadScoreMs)}\n`);
  stdout.write(`  writeCost     : ${formatMs(r.writeCostScore)}\n`);
  stdout.write(`  netBenefit    : ${formatMs(r.netBenefitMs)}\n`);
  if (r.hypopgReductionPct != null) {
    stdout.write(
      `  HypoPG        : ${formatCost(r.hypopgBeforeCost)} → ${formatCost(r.hypopgAfterCost)} ` +
        `(${signedPct(r.hypopgReductionPct)})\n`,
    );
  }
  stdout.write(`  DDL           : ${r.createStatement}\n`);
  if (r.reason) stdout.write(`  reason        : ${r.reason}\n`);
  if (Array.isArray(r.topEvidence) && r.topEvidence.length) {
    stdout.write(`  contributing queries (${r.topEvidence.length}):\n`);
    for (const ev of r.topEvidence) {
      stdout.write(
        `    - fp=${(ev.fingerprint || "?").slice(0, 12)} calls=${ev.calls} mean=${formatMs(ev.meanExecTimeMs)} total=${formatMs(ev.totalExecTimeMs)} role=${ev.role}\n`,
      );
      if (ev.exampleSql) {
        stdout.write(`      ${truncate(ev.exampleSql, 200)}\n`);
      }
    }
  }
}

function renderApplyResult(stdout, r) {
  if (!r || typeof r !== "object") {
    stdout.write("Apply returned no body.\n");
    return;
  }
  const status = r.status || "?";
  if (status === "BLOCKED_NEEDS_CONFIRMATION") {
    stdout.write(`[${r.mode}] blocked — pass --confirm to mutate the database.\n`);
    return;
  }
  if (status === "NOT_FOUND") {
    stdout.write(`[${r.mode}] recommendation not found: ${r.recommendationId}\n`);
    return;
  }
  if (status === "NO_USABLE_SAMPLES") {
    stdout.write(
      `[${r.mode}] no literal-bearing contributing queries available; cannot measure.\n`,
    );
    return;
  }
  if (status === "FAILED") {
    stdout.write(`[${r.mode}] failed: ${r.message || "(no message)"}\n`);
    return;
  }

  stdout.write(`[${r.mode}] ${status}\n`);
  stdout.write(`  DDL: ${r.executedDdl}\n`);
  if (r.beforeCost != null && r.afterCost != null) {
    stdout.write(
      `  planner cost: ${formatCost(r.beforeCost)} → ${formatCost(r.afterCost)} ` +
        `(${signedPct(r.costReductionPct)})\n`,
    );
  }
  if (r.beforeWallTimeMs != null && r.afterWallTimeMs != null) {
    stdout.write(
      `  wall time: ${formatMs(r.beforeWallTimeMs)} → ${formatMs(r.afterWallTimeMs)} ` +
        `(${signedPct(r.wallTimeImprovementPct)})\n`,
    );
  }
  if (Array.isArray(r.samples) && r.samples.length) {
    stdout.write(`  ${r.samples.length} contributing query sample(s):\n`);
    for (const s of r.samples.slice(0, 5)) {
      stdout.write(
        `    fp=${(s.fingerprint || "?").slice(0, 12)} cost ${formatCost(s.beforeCost)} → ${formatCost(s.afterCost)}` +
          (s.error ? ` (error: ${s.error})` : "") +
          "\n",
      );
    }
  }
  if (r.message) stdout.write(`  ${r.message}\n`);
}

function priorityWeight(p) {
  switch (String(p || "").toUpperCase()) {
    case "CRITICAL": return 4;
    case "HIGH":     return 3;
    case "MEDIUM":   return 2;
    case "LOW":      return 1;
    default:         return 0;
  }
}

function clampLimit(raw, fallback) {
  const n = Number.parseInt(raw, 10);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(50, Math.max(1, n));
}

function normalizeMode(raw) {
  if (!raw) return "DRY_RUN";
  // Accept dash-form (CLI convention) and underscore-form (API enum).
  const m = String(raw).toUpperCase().replace(/-/g, "_");
  if (!["DRY_RUN", "APPLY", "APPLY_AND_MEASURE"].includes(m)) {
    throw new Error(`Unknown mode: ${raw}. Expected dry-run, apply, or apply-and-measure.`);
  }
  return m;
}

function formatMs(ms) {
  if (ms == null || !Number.isFinite(Number(ms))) return "—";
  const n = Number(ms);
  if (n <= 0) return "0ms";
  if (n >= 86_400_000) return `${(n / 86_400_000).toFixed(1)}d`;
  if (n >= 3_600_000) return `${(n / 3_600_000).toFixed(1)}h`;
  if (n >= 60_000) return `${(n / 60_000).toFixed(1)}m`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}s`;
  return `${n.toFixed(n >= 10 ? 0 : 1)}ms`;
}

function formatCost(c) {
  if (c == null || !Number.isFinite(Number(c))) return "—";
  return Number(c).toFixed(0);
}

function signedPct(pct) {
  if (pct == null || !Number.isFinite(Number(pct))) return "—";
  const sign = pct >= 0 ? "−" : "+";
  return `${sign}${Math.abs(pct).toFixed(1)}%`;
}

function formatNet(r) {
  if (r.netBenefitMs != null && r.netBenefitMs > 0) {
    return `net=${formatMs(r.netBenefitMs)} saved`;
  }
  if (r.estimatedImpact != null) return `impact ${r.estimatedImpact}`;
  return null;
}

function truncate(s, max) {
  if (!s) return s;
  return s.length <= max ? s : `${s.slice(0, max)}…`;
}

function oneLine(s) {
  return String(s).replace(/\s+/g, " ").trim();
}

function formatColumns(cols) {
  if (Array.isArray(cols)) return cols.join(", ");
  return cols || "?";
}

function pad(key) {
  return `${key}:`.padEnd(28);
}

module.exports = { run };
