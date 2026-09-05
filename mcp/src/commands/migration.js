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

const { request } = require("../api/client");
const { resolveSession } = require("./_session");
const { resolveConnectionId } = require("./_connections");

const SUBCOMMANDS = {
  analyze: cmdAnalyze,
};

async function run(opts, io = {}) {
  const sub = opts.positional[0] || "analyze";
  const handler = SUBCOMMANDS[sub];
  if (!handler) {
    throw new Error(`Unknown migration subcommand: ${sub}. Try: analyze.`);
  }
  return handler({ ...opts, positional: opts.positional.slice(1) }, io);
}

async function cmdAnalyze(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const sql = opts.sql || opts.positional.join(" ");
  if (!sql) {
    stderr.write('Usage: deepsql migration analyze --connection <name> --sql "ALTER TABLE ..."\n');
    process.exitCode = 1;
    return;
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

  stdout.write(`${r.verdict}  ${r.operation} on ${r.table}\n`);
  stdout.write(`  rewrites table : ${r.rewritesTable}\n`);
  for (const l of Array.isArray(r.locks) ? r.locks : []) {
    const blocks = Array.isArray(l.blocks) && l.blocks.length ? ` (blocks ${l.blocks.join(" + ")})` : "";
    stdout.write(`  lock           : ${l.table} ${l.mode}${blocks}\n`);
  }
  if (r.tableRows != null) stdout.write(`  table rows     : ${Number(r.tableRows).toLocaleString()}\n`);
  if (r.estimatedDuration) stdout.write(`  duration       : ${r.estimatedDuration}\n`);
  if (r.reason) stdout.write(`  why            : ${r.reason}\n`);
  if (r.saferAlternative) stdout.write(`  safer          : ${r.saferAlternative}\n`);
  if (r.docsUrl) stdout.write(`  docs           : ${r.docsUrl}\n`);
}

module.exports = { run, SUBCOMMANDS };
