"use strict";

/**
 * Resolve a user-supplied connection identifier (name or UUID) to the
 * canonical connection ID the backend expects.
 *
 * Backend `/connections` returns rows shaped roughly like:
 *   { id: "<uuid>", connectionName: "mylocalpg", databaseType: "postgresql", ... }
 *
 * Resolution rules:
 *   - If input matches a UUID pattern, treat it as an ID (one fetch saved).
 *     We still verify it exists so we can fail fast with a useful message,
 *     but only if a list fetch is cheap — for now, trust UUIDs.
 *   - Otherwise, fetch the list and match `connectionName` case-insensitively.
 *   - On ambiguous matches (rare — names are not unique by schema constraint),
 *     prefer an exact-case match; otherwise raise.
 */

const { request } = require("../api/client");
const cache = require("../connections/cache");

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

let inProcess = null; // L1: within a single command invocation

// Connections list with two cache tiers: the in-process memo (L1) and a short-
// TTL on-disk cache (L2) shared across `deepsql` invocations. Pass refresh:true
// to bypass both and fetch live (then refresh the caches).
async function listConnections(session, { refresh = false } = {}) {
  if (!refresh) {
    if (inProcess) return inProcess;
    const disk = cache.read(session.baseUrl);
    if (disk) {
      inProcess = disk;
      return disk;
    }
  }
  const fetched = await request(session.baseUrl, "/connections", { token: session.token });
  const list = Array.isArray(fetched) ? fetched : [];
  inProcess = list;
  cache.write(session.baseUrl, list);
  return list;
}

function matchConnection(connections, trimmed) {
  const exact = connections.filter((c) => (c.connectionName || c.name) === trimmed);
  if (exact.length === 1) return { id: exact[0].id || exact[0].connectionId };
  const ci = connections.filter(
    (c) => String(c.connectionName || c.name || "").toLowerCase() === trimmed.toLowerCase(),
  );
  if (ci.length === 1) return { id: ci[0].id || ci[0].connectionId };
  if (ci.length > 1) return { ambiguous: ci };
  return null;
}

/**
 * Resolution chain for the connection a command should hit:
 *
 *   1. explicit `input` argument (i.e. opts.connection from --connection flag)
 *   2. DEEPSQL_CONNECTION env var
 *   3. session.defaultConnection (set via `deepsql connections use <name>`)
 *
 * If none of those produce a value, throw a friendly message that points the
 * user at all three escape hatches.
 */
async function resolveConnectionId(session, input) {
  let raw = input;
  let source = "--connection";
  if (raw == null || raw === "") {
    raw = process.env.DEEPSQL_CONNECTION || null;
    source = "DEEPSQL_CONNECTION";
  }
  if (raw == null || raw === "") {
    raw = session && session.defaultConnection ? session.defaultConnection : null;
    source = "saved default";
  }
  if (!raw || typeof raw !== "string") {
    throw new Error(
      "No connection specified. Pass --connection <name>, set DEEPSQL_CONNECTION, " +
        "or run `deepsql connections use <name>` to pin a default.",
    );
  }
  const trimmed = raw.trim();
  if (UUID_RE.test(trimmed)) return trimmed;

  // Try the (possibly cached) list first; on a miss, refetch live once before
  // failing — so a connection added since the cache was written still resolves.
  let connections = await listConnections(session);
  let m = matchConnection(connections, trimmed);
  if (!m) {
    connections = await listConnections(session, { refresh: true });
    m = matchConnection(connections, trimmed);
  }
  if (m && m.id) return m.id;
  if (m && m.ambiguous) {
    const names = m.ambiguous.map((c) => `${c.connectionName} (${c.id})`).join(", ");
    throw new Error(
      `Multiple connections match "${trimmed}" by case-insensitive name: ${names}. Pass the exact name or the id.`,
    );
  }

  // No match — show what's available so the user can pick.
  const available = connections
    .map((c) => c.connectionName || c.name)
    .filter(Boolean)
    .slice(0, 20);
  const hint = available.length
    ? ` Available: ${available.join(", ")}.`
    : " (no connections visible to this token).";
  throw new Error(`Connection "${trimmed}" not found.${hint}`);
}

module.exports = { resolveConnectionId, listConnections };
