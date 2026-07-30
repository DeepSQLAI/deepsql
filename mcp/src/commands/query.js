"use strict";

/**
 * `deepsql query` — execute a SQL statement against a connection.
 *
 * In 0.13.0 this stopped being a read-only-only command. It now hits the same
 * canonical Editor endpoint (`/api/connections/{id}/query`) that the web UI
 * uses, so policy decisions are uniform across all DeepSQL surfaces:
 *
 *   - Developer + SELECT/WITH/SHOW/EXPLAIN → runs immediately
 *   - Developer + DML/DDL                  → backend returns 403 with a
 *                                            clear EDITOR_MUTATION_FORBIDDEN
 *   - Admin + DML/DDL (no --write)         → server returns
 *                                            requiresConfirmation; we print
 *                                            the warnings, prompt y/N, and
 *                                            re-send with confirmMutation=true
 *   - Admin + DML/DDL + --write            → confirmation flag is set
 *                                            upfront, no prompt; useful in
 *                                            scripts / CI
 *
 * `EXPLAIN` and `EXPLAIN ANALYZE` are just SQL — no special flag needed.
 * For the AI-enriched plan analysis, use `deepsql analyze "<sql>"`.
 *
 * The old phase-1 read-only parser has been removed; the backend is the
 * single source of truth on policy now. This also closes the
 * "client says no but server would have said yes" mismatches we kept
 * hitting in CI.
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
  const limit = clampInt(opts.limit, 1, 1000, 100);
  const timeoutSeconds = opts.timeoutSeconds == null ? null : clampInt(opts.timeoutSeconds, 1, 60, null);

  const response = await runOnce(session, connectionId, {
    query: sql,
    limit,
    timeoutSeconds,
    mutationConfirmed: !!opts.write,
  });

  // Two-step mutation flow: server returns 200 with requiresConfirmation=true
  // when the statement is a mutation and confirmMutation wasn't set. We show
  // the warning list, ask the human, then resubmit.
  if (response && response.success === false && response.requiresConfirmation) {
    if (opts.write) {
      // --write was passed but server still wants confirmation. That means
      // the request lost the flag somewhere — surface it.
      throw new Error(
        `Server still asked for confirmation despite --write. Message: ${response.message}`,
      );
    }
    const accepted = await promptForConfirmation(response, { stderr });
    if (!accepted) {
      stderr.write("Aborted.\n");
      return;
    }
    const confirmed = await runOnce(session, connectionId, {
      query: sql,
      limit,
      timeoutSeconds,
      mutationConfirmed: true,
    });
    printOutcome(confirmed, opts, { stdout, stderr });
    return;
  }

  printOutcome(response, opts, { stdout, stderr });
}

async function runOnce(session, connectionId, body) {
  return request(
    session.baseUrl,
    `/connections/${encodeURIComponent(connectionId)}/query`,
    {
      method: "POST",
      token: session.token,
      json: body,
    },
  );
}

async function promptForConfirmation(response, { stderr = process.stderr }) {
  stderr.write(`\n⚠ ${response.message || "This statement will modify the database."}\n`);
  if (response.queryType) stderr.write(`  statement: ${response.queryType}\n`);
  const warnings = Array.isArray(response.warnings) ? response.warnings : [];
  for (const w of warnings) {
    stderr.write(`  • ${w}\n`);
  }
  stderr.write("\n");
  return ui.confirm({ message: "Execute the statement?", default: false });
}

function printOutcome(response, opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  if (opts.json) {
    stdout.write(`${JSON.stringify(response, null, 2)}\n`);
    return;
  }
  if (response && response.success === false) {
    // Block or failure that didn't go via the throw path. Print message
    // + error code so a script can grep.
    const code = response.errorCode ? ` [${response.errorCode}]` : "";
    stderr.write(`${response.message || "Query failed."}${code}\n`);
    process.exitCode = 1;
    return;
  }
  printRows(stdout, response);
}

function readSqlInput(opts) {
  if (opts.file) return fs.readFileSync(opts.file, "utf8");
  if (opts.positional.length > 0) return opts.positional.join(" ");
  if (!process.stdin.isTTY) return fs.readFileSync(0, "utf8");
  throw new Error("Pass SQL as an argument, via --file <path>, or pipe it to stdin.");
}

function clampInt(value, min, max, fallback) {
  if (value == null) return fallback;
  const n = Number.parseInt(value, 10);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(max, Math.max(min, n));
}

function printRows(stdout, response) {
  // Backend returns: { result: { columns: [...], rows: [[v1,v2,...], ...],
  //                            rowCount, totalRowCount, isLimited, ... },
  //                   success, queryType }
  // Tolerate older shapes too — `rows` directly on the response, with rows as
  // either arrays or row-objects.
  const result = response?.result ?? response ?? {};
  const rawRows = result.rows ?? result.data ?? [];
  let columns = result.columns;
  if (!Array.isArray(columns) || columns.length === 0) {
    columns = rawRows[0] && !Array.isArray(rawRows[0]) ? Object.keys(rawRows[0]) : [];
  }
  if (columns.length === 0 || rawRows.length === 0) {
    stdout.write("(no rows)\n");
    return;
  }
  const cellAt = (row, idx, col) =>
    Array.isArray(row) ? row[idx] : row?.[col];
  const widths = columns.map((c, i) =>
    Math.max(
      String(c).length,
      ...rawRows.map((r) => String(cellAt(r, i, c) ?? "").length),
    ),
  );
  const sep = widths.map((w) => "-".repeat(w)).join("  ");
  stdout.write(
    `${columns.map((c, i) => String(c).padEnd(widths[i])).join("  ")}\n${sep}\n`,
  );
  for (const row of rawRows) {
    stdout.write(
      `${columns
        .map((c, i) => String(cellAt(row, i, c) ?? "").padEnd(widths[i]))
        .join("  ")}\n`,
    );
  }
  if (result.isLimited || result.truncated) {
    const shown = rawRows.length;
    const total = result.totalRowCount;
    stdout.write(
      total != null && total > shown
        ? `(showing ${shown} of ${total} rows)\n`
        : `(result limited to ${shown} rows)\n`,
    );
  }
}

module.exports = { run };
