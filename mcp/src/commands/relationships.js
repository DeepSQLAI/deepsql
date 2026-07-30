"use strict";

/**
 * `deepsql relationships --connection <name> [--json]`
 *
 * Returns inferred and validated foreign-key relationships for a connection,
 * with confidence scores. Wraps GET /brain/inferred-relationships/{cid}.
 */

const { request } = require("../api/client");
const { resolveSession } = require("./_session");
const { resolveConnectionId } = require("./_connections");

async function run(opts, { stdout = process.stdout } = {}) {

  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);

  const response = await request(
    session.baseUrl,
    `/brain/inferred-relationships/${encodeURIComponent(connectionId)}`,
    { token: session.token },
  );

  if (opts.json) {
    stdout.write(`${JSON.stringify(response, null, 2)}\n`);
    return;
  }

  const list = Array.isArray(response) ? response : [];
  if (list.length === 0) {
    stdout.write("No relationships inferred for this connection yet.\n");
    return;
  }

  const high = list.filter((r) => (r.confidence ?? 0) >= 0.8).length;
  const noun = list.length === 1 ? "relationship" : "relationships";
  const header = high > 0
    ? `${list.length} ${noun} (${high} high-confidence):`
    : `${list.length} ${noun}:`;
  stdout.write(`${header}\n`);

  for (const r of list) {
    const parts = [];
    if (r.confidence != null) parts.push(`conf=${r.confidence.toFixed(2)}`);
    if (r.inferenceMethod) parts.push(`via ${r.inferenceMethod}`);
    const meta = parts.length ? ` (${parts.join(", ")})` : "";
    const status = r.validationStatus ? ` [${r.validationStatus}]` : "";
    stdout.write(
      `  • ${r.sourceTable}.${r.sourceColumn} → ${r.targetTable}.${r.targetColumn}${meta}${status}\n`,
    );
  }
}

module.exports = { run };
