"use strict";

/**
 * `deepsql permissions` — global role-based permission overrides.
 *
 *   deepsql permissions list [--role <r>] [--json]
 *   deepsql permissions override --role <r> --permission <p> --grant|--revoke [--reason "..."]
 *   deepsql permissions reset --role <r> --permission <p>
 *
 * Backed by /permissions/** (overrides require ROLE_ADMIN).
 */

const { ApiError, request } = require("../api/client");
const { resolveSession } = require("./_session");

const SUBCOMMANDS = {
  list: cmdList,
  override: cmdOverride,
  reset: cmdReset,
};

async function run(opts, io = {}) {
  const sub = opts.positional[0];
  if (!sub) throw new Error("Usage: deepsql permissions <list|override|reset> ...");
  const handler = SUBCOMMANDS[sub];
  if (!handler) throw new Error(`Unknown permissions subcommand: ${sub}.`);
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
        throw new Error("Access denied — managing permissions requires ADMIN role.");
      }
      throw err;
    }
  };
}

// ─── list ──────────────────────────────────────────────────────────────────

async function cmdList(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const [registry, roles, overrides] = await Promise.all([
    request(session.baseUrl, "/permissions/registry", { token: session.token }),
    request(session.baseUrl, "/permissions/roles", { token: session.token }),
    request(session.baseUrl, "/permissions/overrides", { token: session.token }),
  ]);

  if (opts.json) {
    stdout.write(`${JSON.stringify({ registry, roles, overrides }, null, 2)}\n`);
    return;
  }

  const roleList = Array.isArray(roles) ? roles : roles?.roles || [];
  const overrideList = Array.isArray(overrides) ? overrides : overrides?.overrides || [];

  if (opts.role) {
    const wanted = String(opts.role).toUpperCase();
    const role = roleList.find(
      (r) => (r.code || r.role || r.name || "").toUpperCase() === wanted,
    );
    if (!role) {
      const available = roleList.map((r) => r.code || r.role || r.name).filter(Boolean).join(", ");
      throw new Error(`Role "${opts.role}" not found. Available: ${available}.`);
    }
    const code = role.code || role.role || role.name;
    stdout.write(`${code} permissions (effective):\n`);
    const perms = role.effectivePermissions || role.permissions || [];
    for (const p of perms) {
      stdout.write(`  - ${typeof p === "string" ? p : p.code || p.name}\n`);
    }
    return;
  }

  if (overrideList.length === 0) {
    stdout.write("No active overrides — every role has its default permissions.\n");
  } else {
    stdout.write("Active overrides:\n");
    for (const o of overrideList) {
      const role = o.role || "";
      const perm = o.permissionCode || o.permission || "";
      const granted = o.granted ? "GRANT" : "REVOKE";
      const reason = o.reason ? ` — ${o.reason}` : "";
      stdout.write(`  ${role.padEnd(12)} ${granted.padEnd(7)} ${perm}${reason}\n`);
    }
  }

  if (roleList.length > 0) {
    stdout.write("\nRoles:\n");
    for (const r of roleList) {
      const name = r.code || r.role || r.name || "?";
      const count = (r.effectivePermissions || r.permissions || []).length;
      stdout.write(`  ${name.padEnd(12)} ${count} permission(s)\n`);
    }
    stdout.write("\nUse `--role <NAME>` to see one role's full permission list.\n");
  }
}

// ─── override ──────────────────────────────────────────────────────────────

async function cmdOverride(opts, { stdout = process.stdout } = {}) {
  if (!opts.role) throw new Error("--role <ROLE> is required.");
  if (!opts.permission) throw new Error("--permission <PERMISSION> is required.");
  if (!opts.grant && !opts.revoke) {
    throw new Error("Pass --grant or --revoke.");
  }
  if (opts.grant && opts.revoke) {
    throw new Error("--grant and --revoke are mutually exclusive.");
  }
  const granted = !!opts.grant;
  const session = resolveSession(opts);
  const result = await request(session.baseUrl, "/permissions/overrides", {
    method: "POST",
    token: session.token,
    json: {
      role: String(opts.role).toUpperCase(),
      permission: String(opts.permission).toUpperCase(),
      granted,
      reason: opts.reason || null,
    },
  });
  stdout.write(`${result?.message || "Override applied."}\n`);
}

// ─── reset ─────────────────────────────────────────────────────────────────

async function cmdReset(opts, { stdout = process.stdout } = {}) {
  if (!opts.role) throw new Error("--role <ROLE> is required.");
  if (!opts.permission) throw new Error("--permission <PERMISSION> is required.");
  const session = resolveSession(opts);
  const result = await request(session.baseUrl, "/permissions/overrides", {
    method: "DELETE",
    token: session.token,
    json: {
      role: String(opts.role).toUpperCase(),
      permission: String(opts.permission).toUpperCase(),
    },
  });
  stdout.write(`${result?.message || "Override removed."}\n`);
}

module.exports = { run };
