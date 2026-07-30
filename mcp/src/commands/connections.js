"use strict";

/**
 * `deepsql connections` — list, pin, and (Phase 4) full CRUD over connections.
 *
 *   list                       Tabular list with active default marker.
 *   use <name>                 Pin <name> as the active default for the profile.
 *   current                    Print the active default (exit 1 if none).
 *   unset                      Clear the active default for this profile.
 *   schema [--json]            Print the JSON Schema for the input file.
 *   add [--from-file <path>]   Create a connection (interactive or JSON).
 *       [--from-stdin]         Read JSON from stdin instead of a file.
 *       [--upsert]             PUT instead of POST if a name collision exists.
 *       [--no-test]            Skip pre-save POST /connections/test.
 *       [--wait]               Poll brain init to COMPLETED/FAILED.
 *       [--delete-after]       rm the --from-file path on success.
 *       [--allow-plaintext-secrets]
 *       [--cloud]              Prompt for cloud/instance metadata interactively.
 *   update <name> [--from-file <path>]   PUT /connections/{id} with merge.
 *   remove <name> [--yes]      DELETE /connections/{id}.
 *   test [<name> | --from-file <path>]   POST /connections/test, no save.
 *   show <name> [--json]       GET /connections/{id} with secrets masked.
 *   init <name> [--force] [--wait]       POST /connections/{id}/reinit.
 */

const fs = require("node:fs");
const { ApiError, request } = require("../api/client");
const store = require("../auth/store");
const { resolveSession } = require("./_session");
const { resolveConnectionId, listConnections } = require("./_connections");
const { resolveSecrets, maskSecrets, SECRET_FIELDS } = require("../connections/secrets");
const { SCHEMA, validate } = require("../connections/schema");
const cache = require("../connections/cache");
const ui = require("../ui/prompts");
const { promptPassword } = require("../auth/prompt");

async function run(opts, io = {}) {
  const sub = opts.positional[0] || "list";
  switch (sub) {
    case "list": return runList(opts, io);
    case "use": return runUse(opts, io);
    case "current": return runCurrent(opts, io);
    case "unset": return runUnset(opts, io);
    case "schema": return runSchema(opts, io);
    case "add": return runAdd(opts, io);
    case "update": return runUpdate(opts, io);
    case "remove":
    case "delete": return runRemove(opts, io);
    case "test": return runTest(opts, io);
    case "show": return runShow(opts, io);
    case "init": return runInit(opts, io);
    default:
      throw new Error(
        `Unknown connections subcommand: ${sub}. Try \`list\`, \`add\`, \`update\`, \`remove\`, \`test\`, \`show\`, \`init\`, \`schema\`, \`use\`, \`current\`, or \`unset\`.`,
      );
  }
}

// ─── existing list / use / current / unset (unchanged) ─────────────────────

async function runList(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  // Cached for speed; --refresh (or --no-cache) forces a live fetch.
  const data = await listConnections(session, { refresh: !!(opts.refresh || opts.noCache) });
  if (opts.json) {
    stdout.write(`${JSON.stringify(data, null, 2)}\n`);
    return;
  }
  if (!Array.isArray(data) || data.length === 0) {
    stdout.write("No connections.\n");
    return;
  }
  const active = session.defaultConnection;
  const rows = data.map((conn) => ({
    name: conn.connectionName || conn.name || "(unnamed)",
    type: conn.databaseType || conn.dbType || "?",
    id: conn.id || conn.connectionId || "",
  }));
  const widths = {
    name: Math.max(4, ...rows.map((r) => r.name.length)),
    type: Math.max(4, ...rows.map((r) => r.type.length)),
  };
  stdout.write(
    `  ${"NAME".padEnd(widths.name)}  ${"TYPE".padEnd(widths.type)}  ID\n` +
      `  ${"-".repeat(widths.name)}  ${"-".repeat(widths.type)}  ${"-".repeat(36)}\n`,
  );
  for (const row of rows) {
    const marker = active && (row.name === active || row.id === active) ? "* " : "  ";
    stdout.write(`${marker}${row.name.padEnd(widths.name)}  ${row.type.padEnd(widths.type)}  ${row.id}\n`);
  }
  if (active) {
    stdout.write(`\n* = active default. Switch: \`deepsql connections use <name>\`.\n`);
  }
}

async function runUse(opts, { stdout = process.stdout } = {}) {
  const target = opts.positional[1];
  if (!target) throw new Error("Usage: deepsql connections use <name>");
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, target);
  store.setDefaultConnection(session.baseUrl, target);
  stdout.write(
    `Active connection set to "${target}"${connectionId !== target ? ` (id ${connectionId})` : ""} for ${session.baseUrl}.\n`,
  );
}

async function runCurrent(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const session = resolveSession(opts);
  if (session.defaultConnection) {
    stdout.write(`${session.defaultConnection}\n`);
    return;
  }
  stderr.write("No active connection set. Pin one with `deepsql connections use <name>`.\n");
  return 1;
}

async function runUnset(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  if (!session.defaultConnection) {
    stdout.write("No active connection was set.\n");
    return;
  }
  store.setDefaultConnection(session.baseUrl, null);
  stdout.write(`Cleared active connection for ${session.baseUrl}.\n`);
}

// ─── schema ────────────────────────────────────────────────────────────────

async function runSchema(opts, { stdout = process.stdout } = {}) {
  if (opts.json) {
    stdout.write(`${JSON.stringify(SCHEMA, null, 2)}\n`);
    return;
  }
  // Human cheat sheet: required, optional, conditional gates.
  stdout.write(`DeepSQL connection JSON shape (v${SCHEMA.$schema.match(/draft\/([^/]+)/)?.[1] || "?"})\n\n`);
  stdout.write(`Required:\n`);
  for (const k of SCHEMA.required) {
    const f = SCHEMA.properties[k];
    stdout.write(`  ${k.padEnd(20)} ${f.type}${f.enum ? ` (${f.enum.join(", ")})` : ""}\n`);
  }
  stdout.write(`\nSecrets ($VAR_NAME / @file:<path> supported):\n`);
  for (const k of SECRET_FIELDS) {
    if (SCHEMA.properties[k]) stdout.write(`  ${k}\n`);
  }
  stdout.write(`\nSSH (if sshEnabled=true):  sshHost, sshUsername, sshPort=22,\n`);
  stdout.write(`                            sshAuthType=PASSWORD|PRIVATE_KEY,\n`);
  stdout.write(`                            sshPassword | sshPrivateKey + sshPassphrase?\n`);
  stdout.write(`SSL (if sslMode=server-only/server-client): sslCaCertificate,\n`);
  stdout.write(`                            sslClientCertificate, sslClientKey, sslClientKeyPassphrase?\n`);
  stdout.write(`Cloud (informational): cloudProvider, managedService, instanceClass, instanceVcpus,\n`);
  stdout.write(`                       instanceMemoryGb, storageType, storageMaxIops\n\n`);
  stdout.write(`Use \`deepsql connections schema --json\` for the full machine-readable schema.\n`);
}

// ─── add ───────────────────────────────────────────────────────────────────

async function runAdd(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const session = resolveSession(opts);
  const log = (m) => stderr.write(`[deepsql] ${m}\n`);

  let cfg;
  let sourcePath = null;

  if (opts.fromFile) {
    sourcePath = opts.fromFile;
    cfg = readJsonFile(sourcePath);
  } else if (opts.fromStdin) {
    cfg = JSON.parse(await readStdin());
  } else {
    cfg = await promptInteractive({ withCloud: !!opts.cloud });
  }

  cfg = resolveSecrets(cfg, {
    sourcePath,
    allowPlaintextSecrets: !!opts.allowPlaintextSecrets,
    log: (m) => stderr.write(`${m}\n`),
  });
  // Strip id if present — backend assigns it on create.
  delete cfg.id;

  const validation = validate(cfg);
  if (!validation.ok) {
    throw new Error(`Invalid connection config:\n  ${formatErrors(validation.errors)}`);
  }

  if (!opts.noTest) {
    log("Testing connection before save…");
    const testResult = await request(session.baseUrl, "/connections/test", {
      method: "POST",
      token: session.token,
      json: cfg,
      timeoutMs: 60000,
    });
    printPrivilegeReport(stderr, testResult);
    if (!testResult?.connectionSuccessful) {
      throw new Error(testResult?.message || "Pre-save connection test failed.");
    }
  }

  let saved;
  if (opts.upsert) {
    const existing = await findExistingByName(session, cfg.connectionName);
    if (existing) {
      log(`Updating existing connection "${cfg.connectionName}" (id ${existing.id})…`);
      saved = await request(session.baseUrl, `/connections/${encodeURIComponent(existing.id)}`, {
        method: "PUT",
        token: session.token,
        json: cfg,
        timeoutMs: 60000,
      });
    } else {
      log(`No existing connection named "${cfg.connectionName}", creating…`);
      saved = await request(session.baseUrl, "/connections", {
        method: "POST",
        token: session.token,
        json: cfg,
        timeoutMs: 60000,
      });
    }
  } else {
    saved = await request(session.baseUrl, "/connections", {
      method: "POST",
      token: session.token,
      json: cfg,
      timeoutMs: 60000,
    });
  }

  if (saved && saved.success === false) {
    throw new Error(saved.message || "Connection save failed.");
  }
  const id = saved?.connectionId || saved?.id;
  cache.invalidate(session.baseUrl);
  if (saved?.privileges) printPrivilegeReport(stderr, saved);
  stdout.write(`Saved "${cfg.connectionName}" (id ${id || "?"}).\n`);

  if (opts.deleteAfter && opts.fromFile) {
    try {
      fs.unlinkSync(opts.fromFile);
      log(`Deleted ${opts.fromFile}.`);
    } catch (err) {
      log(`Could not delete ${opts.fromFile}: ${err.message}`);
    }
  }

  if (opts.wait && id) {
    await pollInitStatus(session, id, stderr);
  }
}

// ─── update ────────────────────────────────────────────────────────────────

async function runUpdate(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const target = opts.positional[1];
  if (!target) throw new Error("Usage: deepsql connections update <name> --from-file <path>");
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, target);

  let cfg;
  if (opts.fromFile) {
    cfg = readJsonFile(opts.fromFile);
  } else if (opts.fromStdin) {
    cfg = JSON.parse(await readStdin());
  } else {
    throw new Error("`update` requires --from-file or --from-stdin (interactive update not supported in 0.10.0).");
  }
  cfg = resolveSecrets(cfg, {
    sourcePath: opts.fromFile,
    allowPlaintextSecrets: !!opts.allowPlaintextSecrets,
    log: (m) => stderr.write(`${m}\n`),
  });
  delete cfg.id;

  const saved = await request(session.baseUrl, `/connections/${encodeURIComponent(connectionId)}`, {
    method: "PUT",
    token: session.token,
    json: cfg,
    timeoutMs: 60000,
  });
  if (saved?.success === false) {
    throw new Error(saved.message || "Connection update failed.");
  }
  cache.invalidate(session.baseUrl);
  if (saved?.privileges) printPrivilegeReport(stderr, saved);
  stdout.write(`Updated "${target}".\n`);
}

// ─── remove ────────────────────────────────────────────────────────────────

async function runRemove(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const target = opts.positional[1];
  if (!target) throw new Error("Usage: deepsql connections remove <name> [--yes]");
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, target);

  if (!opts.yes) {
    const ok = await ui.confirm({
      message: `Delete connection "${target}" (id ${connectionId})? This cannot be undone.`,
      default: false,
    });
    if (!ok) {
      stderr.write("Aborted.\n");
      return;
    }
  }
  await request(session.baseUrl, `/connections/${encodeURIComponent(connectionId)}`, {
    method: "DELETE",
    token: session.token,
  });
  cache.invalidate(session.baseUrl);
  if (session.defaultConnection === target) {
    store.setDefaultConnection(session.baseUrl, null);
    stderr.write(`[deepsql] Cleared the active-connection pin (was "${target}").\n`);
  }
  stdout.write(`Deleted "${target}".\n`);
}

// ─── test ──────────────────────────────────────────────────────────────────

async function runTest(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const session = resolveSession(opts);
  let cfg;
  if (opts.fromFile || opts.fromStdin) {
    cfg = opts.fromFile ? readJsonFile(opts.fromFile) : JSON.parse(await readStdin());
    cfg = resolveSecrets(cfg, {
      sourcePath: opts.fromFile,
      allowPlaintextSecrets: !!opts.allowPlaintextSecrets,
      log: (m) => stderr.write(`${m}\n`),
    });
    delete cfg.id;
    const validation = validate(cfg);
    if (!validation.ok) {
      throw new Error(`Invalid connection config:\n  ${formatErrors(validation.errors)}`);
    }
  } else {
    const target = opts.positional[1];
    if (!target) {
      throw new Error("Usage: deepsql connections test <name> | --from-file <path>");
    }
    const connectionId = await resolveConnectionId(session, target);
    // Send only the id for saved connections. The list/show APIs intentionally
    // omit secrets, so posting that masked summary back to /connections/test
    // breaks SSH/SSL/password-backed connections. The backend hydrates the
    // saved decrypted config when an id is present.
    cfg = { id: connectionId };
  }

  const result = await request(session.baseUrl, "/connections/test", {
    method: "POST",
    token: session.token,
    json: cfg,
    timeoutMs: 60000,
  });
  if (opts.json) {
    stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    return;
  }
  printPrivilegeReport(stdout, result);
  if (!result?.connectionSuccessful) {
    process.exitCode = 1;
    return 1;
  }
  return 0;
}

// ─── show ──────────────────────────────────────────────────────────────────

async function runShow(opts, { stdout = process.stdout } = {}) {
  const target = opts.positional[1];
  if (!target) throw new Error("Usage: deepsql connections show <name> [--json]");
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, target);
  const conn = await findConnectionRecord(session, connectionId);
  if (!conn) throw new Error(`Connection ${target} not found.`);
  const masked = maskSecrets(conn);
  if (opts.json) {
    stdout.write(`${JSON.stringify(masked, null, 2)}\n`);
    return;
  }
  for (const [key, value] of Object.entries(masked)) {
    if (value == null || value === "") continue;
    stdout.write(`${key.padEnd(24)} ${typeof value === "object" ? JSON.stringify(value) : value}\n`);
  }
}

// ─── init ──────────────────────────────────────────────────────────────────

async function runInit(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const target = opts.positional[1];
  if (!target) throw new Error("Usage: deepsql connections init <name> [--force] [--wait]");
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, target);

  await request(session.baseUrl, `/connections/${encodeURIComponent(connectionId)}/reinit`, {
    method: "POST",
    token: session.token,
    json: { force: !!opts.force },
  });
  cache.invalidate(session.baseUrl);
  stdout.write(`Brain reinit triggered for "${target}".\n`);
  if (opts.wait) await pollInitStatus(session, connectionId, stderr);
}

// ─── helpers ───────────────────────────────────────────────────────────────

function readJsonFile(filePath) {
  // Mode-0600 enforcement applies when the file is the source of secrets;
  // we honor the same env-var escape hatch the auth store uses.
  if (process.platform !== "win32" && process.env.DEEPSQL_INSECURE_AUTH !== "1") {
    try {
      const stat = fs.statSync(filePath);
      if ((stat.mode & 0o077) !== 0) {
        throw new Error(
          `${filePath} has insecure permissions (${(stat.mode & 0o777).toString(8)}). ` +
            `Run \`chmod 600 ${filePath}\` or set DEEPSQL_INSECURE_AUTH=1.`,
        );
      }
    } catch (err) {
      if (err.code === "ENOENT") throw new Error(`File not found: ${filePath}`);
      if (!err.message.includes("insecure permissions")) throw err;
      throw err;
    }
  }
  try {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
  } catch (err) {
    throw new Error(`Could not parse JSON from ${filePath}: ${err.message}`);
  }
}

async function readStdin() {
  return new Promise((resolve, reject) => {
    let buf = "";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", (c) => (buf += c));
    process.stdin.on("end", () => resolve(buf));
    process.stdin.on("error", reject);
  });
}

function formatErrors(errors) {
  return errors.map((e) => `${e.path}: ${e.message}`).join("\n  ");
}

/**
 * Parse a port from interactive input. Throws on a missing/invalid value
 * rather than letting NaN propagate into the JSON payload (which the backend
 * would reject with a confusing "must be an integer" error after a network
 * round-trip).
 */
function parsePortPrompt(value, fallback, label) {
  if (value == null || value === "") return fallback;
  const n = parseInt(value, 10);
  if (!Number.isFinite(n) || n < 1 || n > 65535) {
    throw new Error(`${label} must be an integer between 1 and 65535 (got "${value}").`);
  }
  return n;
}

function printPrivilegeReport(stream, result) {
  if (!result || typeof result !== "object") return;
  if (result.message) stream.write(`${result.message}\n`);
  const privs = Array.isArray(result.privileges) ? result.privileges : [];
  if (privs.length === 0) return;
  for (const p of privs) {
    const mark = p.granted ? "✓" : "✗";
    const scope = p.scope ? ` [${p.scope}]` : "";
    const reason = p.reason ? ` — ${p.reason}` : "";
    stream.write(`  ${mark} ${p.name}${scope}${reason}\n`);
  }
  if (Array.isArray(result.missingPrivileges) && result.missingPrivileges.length > 0) {
    stream.write(`  Missing: ${result.missingPrivileges.join(", ")}\n`);
  }
}

async function findExistingByName(session, name) {
  const all = await request(session.baseUrl, "/connections", { token: session.token });
  if (!Array.isArray(all)) return null;
  return all.find((c) => (c.connectionName || c.name) === name) || null;
}

/**
 * The backend doesn't expose `GET /connections/{id}`; the list endpoint
 * already returns full per-connection records (without secrets). This is
 * cheap because the list is cached briefly server-side.
 */
async function findConnectionRecord(session, connectionId) {
  const all = await request(session.baseUrl, "/connections", { token: session.token });
  if (!Array.isArray(all)) return null;
  return all.find((c) => (c.id || c.connectionId) === connectionId) || null;
}

async function pollInitStatus(session, connectionId, stderr) {
  const startedAt = Date.now();
  const cap = 30 * 60 * 1000; // 30 min
  let lastStage = null;
  const onSigint = () => {
    stderr.write("\n[deepsql] Stopped polling (init still running server-side).\n");
    process.exit(130);
  };
  process.once("SIGINT", onSigint);
  try {
    while (Date.now() - startedAt < cap) {
      let status;
      try {
        status = await request(session.baseUrl, `/connections/${encodeURIComponent(connectionId)}/init-status`, {
          token: session.token,
        });
      } catch (err) {
        if (err instanceof ApiError && err.status === 404) return; // older backend, no init-status endpoint
        throw err;
      }
      const stage = status?.stage || status?.status || "UNKNOWN";
      if (stage !== lastStage) {
        stderr.write(`[deepsql] init: ${stage}${status?.progressPercent != null ? ` (${status.progressPercent}%)` : ""}\n`);
        lastStage = stage;
      }
      if (stage === "COMPLETED" || stage === "FAILED") {
        if (stage === "FAILED") process.exitCode = 1;
        return;
      }
      await new Promise((r) => setTimeout(r, 2000));
    }
    stderr.write(`[deepsql] init poll timed out after 30m. Use \`deepsql connections init ${connectionId} --wait\` to keep watching.\n`);
  } finally {
    process.removeListener("SIGINT", onSigint);
  }
}

// ─── interactive prompt for `add` ──────────────────────────────────────────

async function promptInteractive({ withCloud = false } = {}) {
  const cfg = {};
  cfg.connectionName = await ui.input({ message: "Connection name:", required: true });
  cfg.dbType = await ui.select({
    message: "Database type:",
    choices: [
      { name: "PostgreSQL", value: "postgres" },
      { name: "MySQL", value: "mysql" },
    ],
  });
  cfg.host = await ui.input({ message: "Host:", required: true });
  cfg.port = parsePortPrompt(
    await ui.input({
      message: "Port:",
      default: cfg.dbType === "postgres" ? "5432" : "3306",
      required: true,
    }),
    cfg.dbType === "postgres" ? 5432 : 3306,
    "Port",
  );
  cfg.database = await ui.input({ message: "Database name:", required: true });
  cfg.username = await ui.input({ message: "Username:", required: true });
  cfg.password = await promptPassword("Password: ");

  const wantSsl = await ui.confirm({ message: "Configure SSL?", default: false });
  if (wantSsl) {
    cfg.sslMode = await ui.select({
      message: "SSL mode:",
      choices: [
        { name: "server-only (verify server)", value: "server-only" },
        { name: "server-client (mutual TLS)", value: "server-client" },
        { name: "none", value: "none" },
      ],
      default: "server-only",
    });
    if (cfg.sslMode !== "none") {
      cfg.sslCaCertificate = await ui.input({ message: "CA cert (paste PEM, $VAR, or @file:<path>):", required: false });
      if (cfg.sslMode === "server-client") {
        cfg.sslClientCertificate = await ui.input({ message: "Client cert (PEM/$VAR/@file:):" });
        cfg.sslClientKey = await ui.input({ message: "Client key (PEM/$VAR/@file:):" });
      }
    }
  }

  const wantSsh = await ui.confirm({ message: "Configure SSH tunnel (bastion)?", default: false });
  if (wantSsh) {
    cfg.sshEnabled = true;
    cfg.sshHost = await ui.input({ message: "SSH host:", required: true });
    cfg.sshPort = parsePortPrompt(
      await ui.input({ message: "SSH port:", default: "22", required: true }),
      22,
      "SSH port",
    );
    cfg.sshUsername = await ui.input({ message: "SSH username:", required: true });
    cfg.sshAuthType = await ui.select({
      message: "SSH auth:",
      choices: [
        { name: "Private key", value: "PRIVATE_KEY" },
        { name: "Password", value: "PASSWORD" },
      ],
      default: "PRIVATE_KEY",
    });
    if (cfg.sshAuthType === "PRIVATE_KEY") {
      cfg.sshPrivateKey = await ui.input({ message: "Private key (PEM/$VAR/@file:):", required: true });
      const passphrase = await promptPassword("Key passphrase (blank for none): ");
      if (passphrase) cfg.sshPassphrase = passphrase;
    } else {
      cfg.sshPassword = await promptPassword("SSH password: ");
    }
  }

  if (withCloud) {
    cfg.cloudProvider = await ui.select({
      message: "Cloud provider:",
      choices: [
        { name: "AWS", value: "aws" },
        { name: "Azure", value: "azure" },
        { name: "GCP", value: "gcp" },
        { name: "Self-hosted", value: "self-hosted" },
      ],
    });
    cfg.managedService = await ui.input({ message: "Managed service (rds, aurora, cloud-sql, …):", required: false });
    cfg.instanceClass = await ui.input({ message: "Instance class (e.g. db.r6g.xlarge):", required: false });
  }

  return cfg;
}

module.exports = { run };
