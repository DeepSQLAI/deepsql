"use strict";

/**
 * `deepsql anti-patterns --connection <name> [--kind table|query] [--limit N] [--json]`
 *
 * Returns DeepSQL-detected anti-patterns. Two flavors:
 *   - kind=table (default) → GET /brain/table-anti-patterns/{cid}
 *   - kind=query           → GET /brain/query-anti-patterns/{cid}?limit=
 */

const { request } = require("../api/client");
const { resolveSession } = require("./_session");
const { resolveConnectionId } = require("./_connections");

async function run(opts, { stdout = process.stdout } = {}) {

  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);

  const kind = opts.kind === "query" ? "query" : "table";
  const path =
    kind === "query"
      ? `/brain/query-anti-patterns/${encodeURIComponent(connectionId)}`
      : `/brain/table-anti-patterns/${encodeURIComponent(connectionId)}`;

  const query = {};
  if (kind === "query" && opts.limit != null) query.limit = opts.limit;

  const response = await request(session.baseUrl, path, {
    token: session.token,
    query,
  });

  if (opts.json) {
    stdout.write(`${JSON.stringify(response, null, 2)}\n`);
    return;
  }

  if (kind === "table") {
    const tableMap =
      response && typeof response === "object" && !Array.isArray(response) ? response : {};
    const tables = Object.keys(tableMap);
    if (tables.length === 0) {
      stdout.write("No table-level anti-patterns detected.\n");
      return;
    }
    const noun = tables.length === 1 ? "table" : "tables";
    stdout.write(`${tables.length} ${noun} with anti-patterns:\n`);
    for (const t of tables) {
      const entry = tableMap[t] || {};
      const patterns = entry.patterns || entry.antiPatterns || entry || [];
      const n = Array.isArray(patterns) ? patterns.length : 0;
      stdout.write(`  • ${t}: ${n} ${n === 1 ? "pattern" : "patterns"}\n`);
    }
    return;
  }

  const list = Array.isArray(response) ? response : response?.patterns || [];
  if (list.length === 0) {
    stdout.write("No query anti-patterns detected.\n");
    return;
  }
  const sev = list.reduce((acc, p) => {
    const s = p.severity || "UNKNOWN";
    acc[s] = (acc[s] || 0) + 1;
    return acc;
  }, {});
  const sevStr = Object.entries(sev).map(([k, v]) => `${k}=${v}`).join(", ");
  const noun = list.length === 1 ? "anti-pattern" : "anti-patterns";
  stdout.write(`${list.length} query ${noun}${sevStr ? ` (${sevStr})` : ""}:\n`);
  for (const p of list.slice(0, 20)) {
    stdout.write(`  • [${p.severity || "?"}] ${p.patternType || p.name || "pattern"}: ${p.description || ""}\n`);
  }
}

module.exports = { run };
