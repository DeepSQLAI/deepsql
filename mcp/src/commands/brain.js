"use strict";

// `deepsql brain` — review what DeepSQL has learned about a connection and teach
// it more. The "remember things as people use it" admin loop, on the CLI:
//   recommendations  AI-proposed things to document/investigate (the inbox)
//   notes            knowledge already saved to the brain (filterable)
//   remember         save a fact to the brain (admin: needs manage-content)
//
// Backed by existing endpoints — no new backend:
//   GET  /brain/notes/suggestions/{connectionId}?limit=N   → { suggestions, totalCount }
//   GET  /brain/notes/{connectionId}[?tableName=&columnName=]
//   POST /brain/notes   { connectionId, scopeType, tableName, columnName, noteText }

const { ApiError, request } = require("../api/client");
const { resolveSession } = require("./_session");
const { resolveConnectionId } = require("./_connections");

// Canonical subcommands (the drift guard compares these keys to the help rows).
const SUBCOMMANDS = {
  recommendations: cmdRecommendations,
  notes: cmdNotes,
  remember: cmdRemember,
};
// Convenience aliases resolved before dispatch (kept out of SUBCOMMANDS so the
// help-drift test stays clean).
const ALIASES = { recs: "recommendations", save: "remember" };

async function run(opts, io = {}) {
  const raw = opts.positional[0];
  if (!raw) throw new Error("Usage: deepsql brain <recommendations|notes|remember> [options]");
  const sub = ALIASES[raw] || raw;
  const handler = SUBCOMMANDS[sub];
  if (!handler) throw new Error(`Unknown brain subcommand: ${raw}.`);
  return wrap(handler)({ ...opts, positional: opts.positional.slice(1) }, io);
}

function wrap(handler) {
  return async (opts, io) => {
    try {
      return await handler(opts, io);
    } catch (err) {
      if (err instanceof ApiError && err.status === 403) {
        throw new Error("Access denied — this brain operation requires manage-content permission on the connection.");
      }
      throw err;
    }
  };
}

// ─── recommendations ─────────────────────────────────────────────────────────
async function cmdRecommendations(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  const limit = parseInt(opts.limit, 10) || 10;
  const res = await request(
    session.baseUrl,
    `/brain/notes/suggestions/${encodeURIComponent(connectionId)}?limit=${limit}`,
    { token: session.token }
  );
  const suggestions = (res && res.suggestions) || [];
  if (opts.json) {
    stdout.write(JSON.stringify(suggestions, null, 2) + "\n");
    return 0;
  }
  if (!suggestions.length) {
    stdout.write("No recommendations — the brain has nothing pending to review for this connection.\n");
    return 0;
  }
  stdout.write(`\nBrain recommendations (${res.totalCount ?? suggestions.length}):\n\n`);
  for (const s of suggestions) {
    const target = s.columnName ? `${s.tableName}.${s.columnName}` : s.tableName;
    stdout.write(`  ${String(s.priority || "").padEnd(3)} ${target}\n`);
    if (s.reason) stdout.write(`      ${s.reason}\n`);
    if (Array.isArray(s.indicators) && s.indicators.length) {
      stdout.write(`      ${s.indicators.join(" · ")}\n`);
    }
    if (s.suggestedPrompt) stdout.write(`      explore: deepsql agent "${s.suggestedPrompt}"\n`);
    stdout.write("\n");
  }
  stdout.write('Save a fact with:  deepsql brain remember "<note>" --table <t> [--column <c>]\n');
  return 0;
}

// ─── notes ───────────────────────────────────────────────────────────────────
async function cmdNotes(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  const qs = [];
  if (opts.table) qs.push(`tableName=${encodeURIComponent(opts.table)}`);
  if (opts.column) qs.push(`columnName=${encodeURIComponent(opts.column)}`);
  const notes = await request(
    session.baseUrl,
    `/brain/notes/${encodeURIComponent(connectionId)}${qs.length ? `?${qs.join("&")}` : ""}`,
    { token: session.token }
  );
  const list = Array.isArray(notes) ? notes : [];
  if (opts.json) {
    stdout.write(JSON.stringify(list, null, 2) + "\n");
    return 0;
  }
  if (!list.length) {
    stdout.write("No saved notes for that scope.\n");
    return 0;
  }
  const limit = parseInt(opts.limit, 10) || 20;
  stdout.write(`\nBrain notes (${list.length}${list.length > limit ? `, showing ${limit}` : ""}):\n\n`);
  for (const n of list.slice(0, limit)) {
    const target = n.columnName ? `${n.tableName}.${n.columnName}` : n.tableName;
    const tags = `${n.source ? `  [${n.source}]` : ""}${n.stale ? "  (stale)" : ""}`;
    stdout.write(`  ${target}${tags}\n      ${n.noteText}\n\n`);
  }
  if (list.length > limit) {
    stdout.write(`… +${list.length - limit} more — narrow with --table <t> [--column <c>], or --limit N\n`);
  }
  return 0;
}

// ─── remember ────────────────────────────────────────────────────────────────
async function cmdRemember(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  const noteText = (opts.positional || []).join(" ").trim();
  if (!noteText) {
    stderr.write('Usage: deepsql brain remember "<note text>" --table <t> [--column <c>]\n');
    return 2;
  }
  if (!opts.table) {
    stderr.write("A --table is required (add --column for a column-scoped note).\n");
    return 2;
  }
  const body = {
    connectionId,
    scopeType: opts.column ? "COLUMN" : "TABLE",
    tableName: opts.table,
    columnName: opts.column || null,
    noteText,
  };
  await request(session.baseUrl, "/brain/notes", { method: "POST", token: session.token, json: body });
  const target = body.columnName ? `${body.tableName}.${body.columnName}` : body.tableName;
  stdout.write(`✓ Saved to brain — ${target}: ${noteText}\n`);
  return 0;
}

module.exports = { run, SUBCOMMANDS };
