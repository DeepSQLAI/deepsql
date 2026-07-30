"use strict";

/**
 * `deepsql digest` — surface the daily DeepSQL digest from the terminal.
 *
 *   deepsql digest                   → show the single most recent digest, full body
 *   deepsql digest <N>               → list the last N digests (compact, one per row)
 *   deepsql digest list [--count N]  → same as above; explicit form
 *   deepsql digest show <id>         → show a specific digest by id
 *   --connection <id>                → filter to one connection
 *   --json                           → raw JSON output
 *
 * Backend: GET /admin/slack/digests?connectionId=&page=0&size=N
 *   Returns a Spring Data Page<SlackDigestLog>: { content: [...], totalElements, ... }
 *   Requires ADMIN role on the calling user's MCP token.
 */

const { ApiError, request } = require("../api/client");
const { resolveSession } = require("./_session");
const { resolveConnectionId } = require("./_connections");

const DEFAULT_LIST_COUNT = 10;
const MAX_COUNT = 100;

async function run(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const session = resolveSession(opts);
  // Digests are per-connection. The resolver pulls from --connection, then
  // DEEPSQL_CONNECTION, then the saved default; throws a friendly hint if
  // none of those are set.
  const connectionId = await resolveConnectionId(session, opts.connection);
  const sub = opts.positional[0];

  // `deepsql digest <N>` shorthand — first positional is a number.
  if (sub && /^\d+$/.test(sub)) {
    return runList(session, connectionId, parseCount(sub), opts, { stdout, stderr });
  }
  if (!sub || sub === "latest") {
    return runLatest(session, connectionId, opts, { stdout, stderr });
  }
  if (sub === "list") {
    const count = parseCount(opts.count) || parseCount(opts.positional[1]) || DEFAULT_LIST_COUNT;
    return runList(session, connectionId, count, opts, { stdout, stderr });
  }
  if (sub === "show") {
    const id = opts.positional[1];
    if (!id) throw new Error("Pass the digest id: `deepsql digest show <id> --connection <name>`.");
    return runShow(session, connectionId, id, opts, { stdout, stderr });
  }
  throw new Error(`Unknown digest subcommand: ${sub}. Try \`latest\`, \`list\`, \`show <id>\`, or pass a number.`);
}

async function runLatest(session, connectionId, opts, { stdout }) {
  const page = await fetchPage(session, connectionId, 0, 1);
  const digest = page.content?.[0];
  if (!digest) {
    stdout.write(`No digests yet for connection "${opts.connection}".\n`);
    return;
  }
  if (opts.json) {
    stdout.write(`${JSON.stringify(digest, null, 2)}\n`);
    return;
  }
  printFull(stdout, digest);
}

async function runList(session, connectionId, requestedCount, opts, { stdout }) {
  const count = Math.min(requestedCount, MAX_COUNT);
  const page = await fetchPage(session, connectionId, 0, count);
  const items = page.content || [];
  if (opts.json) {
    stdout.write(`${JSON.stringify(items, null, 2)}\n`);
    return;
  }
  if (items.length === 0) {
    stdout.write(`No digests yet for connection "${opts.connection}".\n`);
    return;
  }
  printTable(stdout, items);
  if (page.totalElements && page.totalElements > items.length) {
    stdout.write(`\n${items.length} of ${page.totalElements} shown — pass a larger N to see more.\n`);
  }
}

async function runShow(session, connectionId, id, opts, { stdout }) {
  // No single-digest backend endpoint exists yet, so locate by paging.
  // Cheap enough for typical digest counts; we cap at a few pages.
  const target = String(id);
  for (let page = 0; page < 10; page++) {
    const result = await fetchPage(session, connectionId, page, 50);
    const hit = (result.content || []).find((d) => String(d.id) === target);
    if (hit) {
      if (opts.json) {
        stdout.write(`${JSON.stringify(hit, null, 2)}\n`);
        return;
      }
      printFull(stdout, hit);
      return;
    }
    if (result.last || (result.content || []).length === 0) break;
  }
  throw new Error(`Digest ${id} not found in the most recent 500 entries for "${opts.connection}".`);
}

async function fetchPage(session, connectionId, page, size) {
  try {
    return await request(session.baseUrl, "/admin/slack/digests", {
      token: session.token,
      query: {
        connectionId,
        page,
        size,
      },
    });
  } catch (err) {
    if (err instanceof ApiError && err.status === 403) {
      throw new Error(
        "Access denied — fetching digests requires ADMIN role. Ask an administrator to mint a token, or run `deepsql login` as an admin.",
      );
    }
    throw err;
  }
}

function printTable(stdout, items) {
  const rows = items.map((d) => ({
    id: String(d.id ?? ""),
    sentAt: formatTimestamp(d.sentAt),
    status: d.status || "?",
    connection: trim(d.connectionName || d.connectionId || "—", 24),
    headline: trim(d.headline || firstLine(d.content) || "(no headline)", 60),
  }));
  const cols = [
    { key: "id", label: "ID" },
    { key: "sentAt", label: "Sent" },
    { key: "status", label: "Status" },
    { key: "connection", label: "Connection" },
    { key: "headline", label: "Headline" },
  ];
  const widths = cols.map((c) => Math.max(c.label.length, ...rows.map((r) => r[c.key].length)));
  const header = cols.map((c, i) => c.label.padEnd(widths[i])).join("  ");
  const sep = widths.map((w) => "-".repeat(w)).join("  ");
  stdout.write(`${header}\n${sep}\n`);
  for (const row of rows) {
    stdout.write(`${cols.map((c, i) => row[c.key].padEnd(widths[i])).join("  ")}\n`);
  }
}

function printFull(stdout, d) {
  const sent = formatTimestamp(d.sentAt);
  stdout.write(`Digest #${d.id}  ·  ${sent}  ·  ${d.status || "?"}\n`);
  if (d.connectionName || d.connectionId) {
    stdout.write(`Connection: ${d.connectionName || d.connectionId}\n`);
  }
  if (d.headline) {
    stdout.write(`Headline:   ${d.headline}\n`);
  }
  stdout.write("\n");
  if (d.status === "FAILED" && d.errorMessage) {
    stdout.write(`Error: ${d.errorMessage}\n`);
    return;
  }
  stdout.write(`${d.content || "(empty)"}\n`);
}

function parseCount(value) {
  if (value == null) return null;
  const n = Number.parseInt(value, 10);
  if (!Number.isFinite(n) || n <= 0) return null;
  return n;
}

function formatTimestamp(value) {
  if (!value) return "—";
  try {
    const d = new Date(value);
    if (Number.isNaN(d.getTime())) return String(value);
    return d.toISOString().replace("T", " ").slice(0, 16) + "Z";
  } catch {
    return String(value);
  }
}

function trim(text, maxLen) {
  if (!text) return "";
  const s = String(text).replace(/\s+/g, " ").trim();
  return s.length > maxLen ? s.slice(0, maxLen - 1) + "…" : s;
}

function firstLine(text) {
  if (!text) return "";
  const idx = text.indexOf("\n");
  return idx === -1 ? text : text.slice(0, idx);
}

module.exports = { run };
