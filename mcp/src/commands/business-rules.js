"use strict";

/**
 * `deepsql business-rules --connection <name> [--question "..."] [--json]`
 *
 * Lists active business rules and SQL guardrails for a connection. Wraps
 * GET /business-rules/connection/{connectionId}?question=...
 */

const { request } = require("../api/client");
const { resolveSession } = require("./_session");
const { resolveConnectionId } = require("./_connections");

async function run(opts, { stdout = process.stdout } = {}) {

  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);

  const query = {};
  if (opts.question) query.question = opts.question;
  const response = await request(
    session.baseUrl,
    `/business-rules/connection/${encodeURIComponent(connectionId)}`,
    { token: session.token, query },
  );

  if (opts.json) {
    stdout.write(`${JSON.stringify(response, null, 2)}\n`);
    return;
  }

  const active = response?.activeRules || [];
  const guards = response?.applicableGuardrails || [];

  if (active.length === 0 && guards.length === 0) {
    stdout.write("No business rules or guardrails configured for this connection.\n");
    return;
  }

  stdout.write(
    `${plural(active.length, "active business rule", "active business rules")}, ` +
      `${plural(guards.length, "applicable guardrail", "applicable guardrails")}.\n`,
  );
  for (const r of active) {
    const name = r.name || r.ruleName || `rule#${r.id}`;
    const desc = r.description || r.ruleText || "";
    stdout.write(`  • ${name}${desc ? `: ${desc}` : ""}\n`);
  }
  if (guards.length) {
    stdout.write(`\nGuardrail context:\n${response?.guardrailContext || "(none)"}\n`);
  }
}

function plural(n, singular, many) {
  return `${n} ${n === 1 ? singular : many}`;
}

module.exports = { run };
