"use strict";

/**
 * `deepsql access` — per-connection access grants and chat-policy editing.
 *
 *   deepsql access list --user <ref>            → connections this user can see
 *   deepsql access list --connection <name>     → users who can see this connection
 *   deepsql access grant --user <ref> --connection <name> --level read|write|admin
 *   deepsql access revoke --user <ref> --connection <name>
 *   deepsql access policy <user> <connection>   → opens $EDITOR with the
 *     plain-English chat policy; on save, validates via the preview endpoint
 *     and PUTs the result.
 */

const { ApiError, request } = require("../api/client");
const { resolveSession } = require("./_session");
const { resolveUser } = require("./_users");
const { resolveConnectionId, listConnections } = require("./_connections");
const { editText } = require("../ui/editor");

const SUBCOMMANDS = {
  list: cmdList,
  grant: cmdGrant,
  revoke: cmdRevoke,
  policy: cmdPolicy,
};

async function run(opts, io = {}) {
  const sub = opts.positional[0];
  if (!sub) throw new Error("Usage: deepsql access <list|grant|revoke|policy> ...");
  const handler = SUBCOMMANDS[sub];
  if (!handler) throw new Error(`Unknown access subcommand: ${sub}.`);
  return wrap(handler)(
    { ...opts, positional: opts.positional.slice(1) },
    io,
  );
}

function wrap(handler) {
  return async (opts, io) => {
    try {
      return await handler(opts, io);
    } catch (err) {
      if (err instanceof ApiError && err.status === 403) {
        throw new Error("Access denied — managing access requires ADMIN role.");
      }
      throw err;
    }
  };
}

// ─── list ──────────────────────────────────────────────────────────────────

async function cmdList(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  if (opts.user) {
    const user = await resolveUser(session, opts.user);
    const grants = await request(session.baseUrl, `/admin/users/${user.id}/connection-access`, {
      token: session.token,
    });
    if (opts.json) {
      stdout.write(`${JSON.stringify(grants, null, 2)}\n`);
      return;
    }
    if (!Array.isArray(grants) || grants.length === 0) {
      stdout.write(`${user.email || user.username} has no connection grants.\n`);
      return;
    }
    printGrants(stdout, grants, "user");
    return;
  }
  if (opts.connection) {
    const connectionId = await resolveConnectionId(session, opts.connection);
    const grants = await request(
      session.baseUrl,
      `/admin/connections/${encodeURIComponent(connectionId)}/connection-access`,
      { token: session.token },
    );
    if (opts.json) {
      stdout.write(`${JSON.stringify(grants, null, 2)}\n`);
      return;
    }
    if (!Array.isArray(grants) || grants.length === 0) {
      stdout.write(`No grants on ${opts.connection}.\n`);
      return;
    }
    printGrants(stdout, grants, "connection");
    return;
  }
  throw new Error("Pass --user <ref> or --connection <name>.");
}

// ─── grant ─────────────────────────────────────────────────────────────────

async function cmdGrant(opts, { stdout = process.stdout } = {}) {
  if (!opts.user) throw new Error("--user <ref> is required.");
  const level = (opts.level || "READ").toUpperCase();
  if (!["READ", "WRITE", "ADMIN"].includes(level)) {
    throw new Error(`Invalid --level "${level}". Pick read, write, or admin.`);
  }

  const session = resolveSession(opts);
  const user = await resolveUser(session, opts.user);
  const connectionId = await resolveConnectionId(session, opts.connection);

  await request(
    session.baseUrl,
    `/admin/users/${user.id}/connection-access/${encodeURIComponent(connectionId)}`,
    {
      method: "PUT",
      token: session.token,
      json: { accessLevel: level },
    },
  );
  stdout.write(
    `Granted ${level} on ${opts.connection} to ${user.email || user.username}.\n`,
  );
}

// ─── revoke ────────────────────────────────────────────────────────────────

async function cmdRevoke(opts, { stdout = process.stdout } = {}) {
  if (!opts.user) throw new Error("--user <ref> is required.");

  const session = resolveSession(opts);
  const user = await resolveUser(session, opts.user);
  const connectionId = await resolveConnectionId(session, opts.connection);

  await request(
    session.baseUrl,
    `/admin/users/${user.id}/connection-access/${encodeURIComponent(connectionId)}`,
    { method: "DELETE", token: session.token },
  );
  stdout.write(`Revoked ${opts.connection} for ${user.email || user.username}.\n`);
}

// ─── policy ────────────────────────────────────────────────────────────────

const POLICY_HEADER =
  "# Plain-English chat access policy. Lines starting with # are kept as-is —\n" +
  "# DeepSQL doesn't strip them. Save and quit to commit; quit without changes\n" +
  "# (e.g. :cq in vi) to abort.";

async function cmdPolicy(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const userRef = opts.positional[0];
  const connRef = opts.positional[1];
  if (!userRef || !connRef) {
    throw new Error("Usage: deepsql access policy <user> <connection>");
  }

  const session = resolveSession(opts);
  const user = await resolveUser(session, userRef);
  const connectionId = await resolveConnectionId(session, connRef);

  const existing = await request(
    session.baseUrl,
    `/admin/users/${user.id}/connection-access/${encodeURIComponent(connectionId)}/chat-policy`,
    { token: session.token },
  );

  const initial = (existing && existing.plainEnglishPolicy) || "";

  stderr.write(
    `Editing policy for ${user.email || user.username} on ${connRef}…\n`,
  );
  const { content, changed } = await editText(initial, {
    suffix: ".policy.md",
    header: POLICY_HEADER,
  });

  if (!changed) {
    stderr.write("No changes.\n");
    return;
  }

  // Validate via preview before committing.
  const preview = await request(session.baseUrl, "/admin/connection-chat-policies/preview", {
    method: "POST",
    token: session.token,
    json: { connectionId, plainEnglishPolicy: content },
  });
  if (preview && preview.error) {
    throw new Error(`Policy preview rejected: ${preview.error}`);
  }

  const saved = await request(
    session.baseUrl,
    `/admin/users/${user.id}/connection-access/${encodeURIComponent(connectionId)}/chat-policy`,
    {
      method: "PUT",
      token: session.token,
      json: { plainEnglishPolicy: content, active: true },
    },
  );
  stdout.write(`Saved policy for ${user.email || user.username} on ${connRef}.\n`);
  if (opts.json) {
    stdout.write(`${JSON.stringify(saved, null, 2)}\n`);
  }
}

// ─── helpers ───────────────────────────────────────────────────────────────

function printGrants(stdout, grants, mode) {
  const rows = grants.map((g) => ({
    a: mode === "user" ? (g.connectionName || g.connectionId || "") : (g.email || g.username || ""),
    level: (g.accessLevel || g.level || "").toUpperCase(),
    grantedBy: g.grantedBy || "",
  }));
  const headerA = mode === "user" ? "CONNECTION" : "USER";
  const widthA = Math.max(headerA.length, ...rows.map((r) => r.a.length));
  const widthLevel = Math.max("LEVEL".length, ...rows.map((r) => r.level.length));
  const widthGrantedBy = Math.max("GRANTED BY".length, ...rows.map((r) => r.grantedBy.length));
  stdout.write(
    `${headerA.padEnd(widthA)}  ${"LEVEL".padEnd(widthLevel)}  ${"GRANTED BY".padEnd(widthGrantedBy)}\n` +
      `${"-".repeat(widthA)}  ${"-".repeat(widthLevel)}  ${"-".repeat(widthGrantedBy)}\n`,
  );
  for (const r of rows) {
    stdout.write(`${r.a.padEnd(widthA)}  ${r.level.padEnd(widthLevel)}  ${r.grantedBy.padEnd(widthGrantedBy)}\n`);
  }
}

// listConnections used in __mocks elsewhere; expose for tests if needed
module.exports = { run, _internal: { listConnections } };
