"use strict";

/**
 * `deepsql analyze` — AI-enriched query plan analysis.
 *
 * Hits the canonical Editor endpoint `/api/explain/analyze`. The response is
 * an ExplainPlanAnalysis carrying:
 *   - the parsed plan tree (planTree), raw plan text/JSON
 *   - performance issues (slow nodes, missing index hints, bad estimates)
 *   - index recommendations
 *   - an LLM-written summary that takes the connection's schema, business
 *     rules, and detected anti-patterns into account
 *
 * Two modes:
 *   - default (`useAnalyze=false`)  Plain EXPLAIN, no execution. Safe for any
 *                                   actor with read access to the connection.
 *   - `--analyze` (`useAnalyze=true`) EXPLAIN ANALYZE — actually runs the
 *                                   query. For mutations this requires the
 *                                   same admin role + WHERE + confirmation
 *                                   gate as `deepsql query`.
 *
 * Pass `--write` to confirm an EXPLAIN ANALYZE of a mutation upfront (skips
 * the interactive prompt).
 */

const fs = require("node:fs");
const { request } = require("../api/client");
const { resolveSession } = require("./_session");
const { resolveConnectionId } = require("./_connections");
const ui = require("../ui/prompts");

async function run(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const sql = readSqlInput(opts);
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);

  const body = {
    connectionId,
    query: sql,
    useAnalyze: !!opts.analyze,
    mutationConfirmed: !!opts.write,
  };

  let response;
  try {
    response = await runOnce(session, body);
  } catch (err) {
    // Two-step mutation: the policy gate throws when useAnalyze=true with
    // a mutation and no confirm. The server packs requiresConfirmation
    // into the error response body.
    if (err.body && err.body.requiresConfirmation && !opts.write) {
      const accepted = await promptForConfirmation(err.body, { stderr });
      if (!accepted) {
        stderr.write("Aborted.\n");
        return;
      }
      response = await runOnce(session, { ...body, mutationConfirmed: true });
    } else {
      throw err;
    }
  }

  if (opts.json) {
    stdout.write(`${JSON.stringify(response, null, 2)}\n`);
    return;
  }
  renderAnalysis(stdout, response);
}

async function runOnce(session, body) {
  return request(session.baseUrl, "/explain/analyze", {
    method: "POST",
    token: session.token,
    json: body,
  });
}

async function promptForConfirmation(errorBody, { stderr = process.stderr }) {
  stderr.write(
    `\n⚠ ${errorBody.message || "EXPLAIN ANALYZE will actually execute this statement."}\n`,
  );
  if (errorBody.queryType) stderr.write(`  statement: ${errorBody.queryType}\n`);
  const warnings = Array.isArray(errorBody.warnings) ? errorBody.warnings : [];
  for (const w of warnings) {
    stderr.write(`  • ${w}\n`);
  }
  stderr.write("\n");
  return ui.confirm({ message: "Run EXPLAIN ANALYZE on the statement?", default: false });
}

function renderAnalysis(stdout, response) {
  if (!response || typeof response !== "object") {
    stdout.write(`${JSON.stringify(response, null, 2)}\n`);
    return;
  }

  // Plain EXPLAIN responses set wasExecuted=false; EXPLAIN ANALYZE sets it
  // to true. Surface the distinction at the top.
  const heading = response.wasExecuted
    ? "Plan (executed via EXPLAIN ANALYZE):"
    : "Plan (EXPLAIN, not executed):";
  stdout.write(`${heading}\n`);

  if (response.planText) {
    stdout.write(`${String(response.planText).trim()}\n\n`);
  } else if (response.planJson) {
    stdout.write(`${response.planJson}\n\n`);
  }

  const numbers = [];
  if (response.totalTimeMs != null) numbers.push(`total ${response.totalTimeMs}ms`);
  if (response.executionTimeMs != null) numbers.push(`exec ${response.executionTimeMs}ms`);
  if (response.planningTimeMs != null) numbers.push(`plan ${response.planningTimeMs}ms`);
  if (response.estimatedRows != null) numbers.push(`est ${response.estimatedRows} rows`);
  if (response.actualRows != null) numbers.push(`actual ${response.actualRows} rows`);
  if (response.nodeCount != null) numbers.push(`${response.nodeCount} nodes`);
  if (numbers.length) stdout.write(`Timings: ${numbers.join(", ")}\n\n`);

  if (response.aiSummary) {
    stdout.write(`Summary:\n${response.aiSummary.trim()}\n\n`);
  }

  const issues = Array.isArray(response.issues) ? response.issues : [];
  if (issues.length > 0) {
    stdout.write(`Issues (${issues.length}):\n`);
    for (const issue of issues) {
      const label = issue.severity ? `[${issue.severity}] ` : "";
      stdout.write(`  • ${label}${issue.title || issue.message || JSON.stringify(issue)}\n`);
      if (issue.message && issue.title) stdout.write(`      ${issue.message}\n`);
    }
    stdout.write("\n");
  }

  const recs = Array.isArray(response.indexRecommendations) ? response.indexRecommendations : [];
  if (recs.length > 0) {
    stdout.write(`Suggested indexes (${recs.length}):\n`);
    for (const r of recs) {
      const cols = Array.isArray(r.columns) ? r.columns.join(", ") : (r.columnNames || "?");
      stdout.write(`  • ${r.tableName || "?"}(${cols})`);
      if (r.estimatedImpact != null) stdout.write(`  impact≈${r.estimatedImpact}`);
      stdout.write("\n");
      if (r.suggestedSql) stdout.write(`      ${r.suggestedSql.trim()}\n`);
    }
    stdout.write("\n");
  }

  const tips = Array.isArray(response.optimizationSuggestions) ? response.optimizationSuggestions : [];
  if (tips.length > 0) {
    stdout.write(`Suggestions:\n`);
    for (const t of tips) stdout.write(`  • ${t}\n`);
  }

  if (response.aiOptimization) {
    stdout.write(`\nOptimization narrative:\n${response.aiOptimization.trim()}\n`);
  }
}

function readSqlInput(opts) {
  if (opts.file) return fs.readFileSync(opts.file, "utf8");
  if (opts.positional.length > 0) return opts.positional.join(" ");
  if (!process.stdin.isTTY) return fs.readFileSync(0, "utf8");
  throw new Error("Pass SQL as an argument, via --file <path>, or pipe it to stdin.");
}

module.exports = { run };
