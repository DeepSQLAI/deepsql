"use strict";

/**
 * `deepsql users` — manage workspace users (admin-only).
 *
 *   deepsql users list [--json]
 *   deepsql users get <email-or-id>
 *   deepsql users add [<email>] [--role <r>] [--name <n>] [--password <p>] [--password-stdin]
 *   deepsql users set-role <email> <role>
 *   deepsql users lock <email>
 *   deepsql users unlock <email>
 *   deepsql users disable <email>
 *   deepsql users resend-invite <email>
 *   deepsql users reset-password <email> [--password-stdin]
 *   deepsql users delete <email> [--yes]
 *
 * All endpoints under /admin/users/** require ROLE_ADMIN on the calling token.
 */

const { ApiError, request } = require("../api/client");
const { resolveSession } = require("./_session");
const { resolveUser, listUsers, clearUserCache } = require("./_users");
const { promptPassword, readSingleLineFromStdin } = require("../auth/prompt");
const ui = require("../ui/prompts");

async function run(opts, io = {}) {
  const sub = opts.positional[0];
  if (!sub) {
    throw new Error(
      "Usage: deepsql users <list|get|add|set-role|lock|unlock|disable|resend-invite|reset-password|delete> ...",
    );
  }
  const handler = SUBCOMMANDS[sub];
  if (!handler) {
    throw new Error(`Unknown users subcommand: ${sub}.`);
  }
  return wrapAdminErrors(handler)(
    {
      ...opts,
      // Drop the subcommand from positional so handlers see only their args.
      positional: opts.positional.slice(1),
    },
    io,
  );
}

function wrapAdminErrors(handler) {
  return async (opts, io) => {
    try {
      return await handler(opts, io);
    } catch (err) {
      if (err instanceof ApiError && err.status === 403) {
        throw new Error(
          "Access denied — managing users requires ADMIN role on the calling token.",
        );
      }
      throw err;
    }
  };
}

// ─── list ──────────────────────────────────────────────────────────────────

async function cmdList(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const users = await listUsers(session);
  if (opts.json) {
    stdout.write(`${JSON.stringify(users, null, 2)}\n`);
    return;
  }
  if (users.length === 0) {
    stdout.write("No users.\n");
    return;
  }
  printUsers(stdout, users);
}

// ─── get ───────────────────────────────────────────────────────────────────

async function cmdGet(opts, { stdout = process.stdout } = {}) {
  const ref = opts.positional[0];
  if (!ref) throw new Error("Pass an email, username, or id: `deepsql users get <ref>`.");
  const session = resolveSession(opts);
  const user = await resolveUser(session, ref);
  // Hit the by-id endpoint to get the full record (list returns a summary).
  const full = await request(session.baseUrl, `/admin/users/${user.id}`, { token: session.token });
  stdout.write(`${JSON.stringify(full, null, 2)}\n`);
}

// ─── add ───────────────────────────────────────────────────────────────────

async function cmdAdd(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);

  const email = opts.positional[0] || opts.email || (await ui.input({
    message: "Email:",
    validate: (v) => /.+@.+\..+/.test(v) ? true : "Looks invalid",
  }));
  const username = opts.name || opts.username || (await ui.input({
    message: "Display name:",
    default: email.split("@")[0],
  }));
  const role = (opts.role || (await ui.select({
    message: "Role:",
    choices: [
      { name: "ADMIN", value: "ADMIN" },
      { name: "DEVELOPER", value: "DEVELOPER" },
      { name: "VIEWER", value: "VIEWER" },
    ],
    default: "DEVELOPER",
  }))).toUpperCase();

  // `opts.password` is the login-flow toggle (boolean) or unset; it's never a
  // password value here on purpose — argv would expose it via `ps`. Always
  // prompt, or read from stdin with --password-stdin for CI.
  let password = null;
  if (opts.passwordStdin) {
    password = await readSingleLineFromStdin();
  } else if (process.stdin.isTTY) {
    password = await ui.password({
      message: "Initial password (leave blank to send invite by email):",
    });
  }

  const body = { email, username, role };
  if (password) body.password = password;

  const created = await request(session.baseUrl, "/admin/users", {
    method: "POST",
    token: session.token,
    json: body,
  });
  clearUserCache();
  if (opts.json) {
    stdout.write(`${JSON.stringify(created, null, 2)}\n`);
    return;
  }
  stdout.write(`Created user: ${created.email || email} (id ${created.id ?? "?"}, role ${created.role || role})\n`);
}

// ─── set-role ──────────────────────────────────────────────────────────────

async function cmdSetRole(opts, { stdout = process.stdout } = {}) {
  const ref = opts.positional[0];
  const role = (opts.positional[1] || opts.role || "").toUpperCase();
  if (!ref || !role) {
    throw new Error("Usage: deepsql users set-role <email|id> <role>");
  }
  const session = resolveSession(opts);
  const user = await resolveUser(session, ref);
  const updated = await request(session.baseUrl, `/admin/users/${user.id}/role`, {
    method: "PUT",
    token: session.token,
    json: { role },
  });
  stdout.write(`Updated ${user.email || user.username}: role=${updated.role || role}\n`);
}

// ─── state toggles ─────────────────────────────────────────────────────────

const cmdLock = makeStateToggle("lock");
const cmdUnlock = makeStateToggle("unlock");
const cmdDisable = makeStateToggle("disable");
const cmdResendInvite = makeStateToggle("resend-invite", "Sent invite email to");

function makeStateToggle(action, verbPrefix) {
  return async function (opts, { stdout = process.stdout } = {}) {
    const ref = opts.positional[0];
    if (!ref) throw new Error(`Usage: deepsql users ${action} <email|id>`);
    const session = resolveSession(opts);
    const user = await resolveUser(session, ref);
    await request(session.baseUrl, `/admin/users/${user.id}/${action}`, {
      method: "POST",
      token: session.token,
      json: {},
    });
    const verb = verbPrefix || `${action[0].toUpperCase()}${action.slice(1)}ed`;
    stdout.write(`${verb} ${user.email || user.username}.\n`);
  };
}

// ─── reset-password ────────────────────────────────────────────────────────

async function cmdResetPassword(opts, { stdout = process.stdout } = {}) {
  const ref = opts.positional[0];
  if (!ref) throw new Error("Usage: deepsql users reset-password <email|id> [--password-stdin]");
  const session = resolveSession(opts);
  const user = await resolveUser(session, ref);

  let password = null;
  if (opts.passwordStdin) {
    password = await readSingleLineFromStdin();
  } else {
    password = await promptPassword(`New password for ${user.email || user.username}: `);
    const confirmPw = await promptPassword("Confirm: ");
    if (password !== confirmPw) throw new Error("Passwords do not match.");
  }
  if (!password) throw new Error("Password is required.");

  await request(session.baseUrl, `/admin/users/${user.id}/password`, {
    method: "PUT",
    token: session.token,
    json: { password },
  });
  stdout.write(`Password reset for ${user.email || user.username}.\n`);
}

// ─── delete ────────────────────────────────────────────────────────────────

async function cmdDelete(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const ref = opts.positional[0];
  if (!ref) throw new Error("Usage: deepsql users delete <email|id> [--yes]");
  const session = resolveSession(opts);
  const user = await resolveUser(session, ref);

  if (!opts.yes) {
    const ok = await ui.confirm({
      message: `Delete ${user.email || user.username} (id ${user.id})? This cannot be undone.`,
      default: false,
    });
    if (!ok) {
      stderr.write("Aborted.\n");
      return;
    }
  }
  await request(session.baseUrl, `/admin/users/${user.id}`, {
    method: "DELETE",
    token: session.token,
  });
  clearUserCache();
  stdout.write(`Deleted ${user.email || user.username}.\n`);
}

// ─── helpers ───────────────────────────────────────────────────────────────

// Defined here, after all cmd* definitions, so the const-bound state-toggle
// functions are initialized before this object captures them.
const SUBCOMMANDS = {
  list: cmdList,
  get: cmdGet,
  add: cmdAdd,
  invite: cmdAdd, // alias — POST /admin/users/invite is identical to /admin/users
  "set-role": cmdSetRole,
  lock: cmdLock,
  unlock: cmdUnlock,
  disable: cmdDisable,
  "resend-invite": cmdResendInvite,
  "reset-password": cmdResetPassword,
  delete: cmdDelete,
};

function printUsers(stdout, users) {
  const rows = users.map((u) => ({
    id: String(u.id ?? ""),
    email: u.email || "",
    username: u.username || "",
    role: u.role || "",
    status: u.accountStatus || u.status || "",
  }));
  const cols = [
    { key: "id", label: "ID" },
    { key: "email", label: "EMAIL" },
    { key: "username", label: "NAME" },
    { key: "role", label: "ROLE" },
    { key: "status", label: "STATUS" },
  ];
  const widths = cols.map((c) => Math.max(c.label.length, ...rows.map((r) => r[c.key].length)));
  const header = cols.map((c, i) => c.label.padEnd(widths[i])).join("  ");
  const sep = widths.map((w) => "-".repeat(w)).join("  ");
  stdout.write(`${header}\n${sep}\n`);
  for (const row of rows) {
    stdout.write(`${cols.map((c, i) => row[c.key].padEnd(widths[i])).join("  ")}\n`);
  }
}

module.exports = { run };
