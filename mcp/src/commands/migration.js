"use strict";

/**
 * `deepsql migration analyze` — DDL migration risk analysis.
 *
 * Hits `/api/migrations/analyze`. The response is a MigrationRiskReport:
 * a deterministic verdict (SAFE/CAUTION/DANGER/FAILS/UNKNOWN) verified
 * against a real PostgreSQL, the exact locks taken (per table — ADD FOREIGN
 * KEY locks the referenced table too), whether the table is rewritten, a
 * coarse duration estimate scaled by live table size, and a safer
 * alternative where one exists. PostgreSQL only — MySQL connections get
 * UNKNOWN rather than a guess. Read-only: it never executes the statement.
 */

const { ApiError, request } = require("../api/client");
const { resolveSession } = require("./_session");
const { resolveConnectionId } = require("./_connections");

const SUBCOMMANDS = {
  analyze: cmdAnalyze,
};

// Task 4 returns Java's Long.MAX_VALUE for tableRows when it couldn't
// measure the table — "assume the worst, don't treat this as safe." JS
// numbers can't round-trip that value exactly, so treat anything
// implausibly close to it the same way rather than requiring an exact match.
const ROW_COUNT_SENTINEL_FLOOR = 9e18;

function isRowCountUnknown(tableRows) {
  if (tableRows == null) return false;
  const n = Number(tableRows);
  return Number.isFinite(n) && n >= ROW_COUNT_SENTINEL_FLOOR;
}

function verdictMarker(verdict) {
  if (verdict === "DANGER" || verdict === "FAILS") return "✗ ";
  if (verdict === "CAUTION") return "⚠ ";
  return "";
}

async function run(opts, io = {}) {
  const sub = opts.positional[0] || "analyze";
  const handler = SUBCOMMANDS[sub];
  if (!handler) {
    throw new Error(`Unknown migration subcommand: ${sub}. Try: analyze.`);
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
          "Access denied — migration analysis requires permissions on this connection.",
        );
      }
      if (err instanceof ApiError && err.status === 404) {
        throw new Error(err.message || "Connection not found.");
      }
      throw err;
    }
  };
}

async function cmdAnalyze(opts, { stdout = process.stdout } = {}) {
  const sql = opts.sql || opts.positional.join(" ");
  if (!sql) {
    throw new Error('Usage: deepsql migration analyze --connection <name> --sql "ALTER TABLE ..."');
  }
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);

  const r = await request(session.baseUrl, "/migrations/analyze", {
    method: "POST",
    token: session.token,
    json: { connectionId, sql },
  });

  if (opts.json) {
    stdout.write(`${JSON.stringify(r, null, 2)}\n`);
    return;
  }

  if (!r || r.verdict === "UNKNOWN") {
    stdout.write(`UNKNOWN — ${(r && r.reason) || "not supported for this dialect."}\n`);
    stdout.write("  No analysis was possible. Treat as unsafe until reviewed by hand.\n");
    return;
  }

  stdout.write(`${verdictMarker(r.verdict)}${r.verdict}  ${r.operation} on ${r.table}\n`);
  stdout.write(`  rewrites table : ${r.rewritesTable}\n`);
  for (const l of Array.isArray(r.locks) ? r.locks : []) {
    const blocks = Array.isArray(l.blocks) && l.blocks.length ? ` (blocks ${l.blocks.join(" + ")})` : "";
    stdout.write(`  lock           : ${l.table} ${l.mode}${blocks}\n`);
  }
  if (isRowCountUnknown(r.tableRows)) {
    stdout.write("  table rows     : unknown — treated as large\n");
  } else if (r.tableRows != null) {
    stdout.write(`  table rows     : ${Number(r.tableRows).toLocaleString()}\n`);
  }
  if (r.estimatedDuration) stdout.write(`  duration       : ${r.estimatedDuration}\n`);
  if (r.reason) stdout.write(`  why            : ${r.reason}\n`);
  if (r.saferAlternative) stdout.write(`  safer          : ${r.saferAlternative}\n`);
  if (r.docsUrl) stdout.write(`  docs           : ${r.docsUrl}\n`);
}

module.exports = { run, SUBCOMMANDS };
