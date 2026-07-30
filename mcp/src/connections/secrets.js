"use strict";

/**
 * Secret resolution for connection-config JSON inputs.
 *
 * Three accepted forms for any string field:
 *
 *   "plain string"        — used as-is. If the source file lives in a git
 *                           tree we warn (suppressible with
 *                           `--allow-plaintext-secrets`).
 *   "$VAR_NAME"           — substitute from process.env at CLI runtime. The
 *                           variable name appears in logs; the value never
 *                           does.
 *   "@file:/path/to/key"  — read file contents at CLI runtime. `~/` is
 *                           expanded. File mode 0600 is enforced unless
 *                           DEEPSQL_INSECURE_AUTH=1 is set (matching the
 *                           existing auth-store convention).
 *
 * The list of fields treated as secrets is small and fixed — they're the
 * fields where leaking the value would let an attacker connect to a database.
 * Non-secret fields (host, port, etc.) pass through untouched.
 */

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const SECRET_FIELDS = new Set([
  "password",
  "sshPassword",
  "sshPrivateKey",
  "sshPassphrase",
  "sslCaCertificate",
  "sslClientCertificate",
  "sslClientKey",
  "sslClientKeyPassphrase",
]);

const ENV_REF = /^\$([A-Z_][A-Z0-9_]*)$/;
const FILE_REF = /^@file:(.+)$/;

/**
 * Resolve all secret references in a parsed connection-config object,
 * mutating-by-replacement (returns a new object). Throws on missing env vars
 * or unreadable files.
 *
 *   resolveSecrets(cfg, { sourcePath, allowPlaintextSecrets, log })
 *
 *   sourcePath           — original JSON file path (for plaintext warnings)
 *   allowPlaintextSecrets — true to suppress git-tree warnings
 *   log                   — function for emitting warnings to stderr
 */
function resolveSecrets(cfg, opts = {}) {
  const { sourcePath = null, allowPlaintextSecrets = false, log = () => {} } = opts;
  if (cfg == null || typeof cfg !== "object") return cfg;

  const inGitTree = sourcePath ? isInsideGitTree(sourcePath) : false;
  const out = { ...cfg };

  for (const key of Object.keys(out)) {
    const value = out[key];
    if (typeof value !== "string") continue;

    const envMatch = value.match(ENV_REF);
    if (envMatch) {
      const varName = envMatch[1];
      if (!(varName in process.env)) {
        throw new Error(
          `Field "${key}" references env var ${varName}, but it is not set.`,
        );
      }
      out[key] = process.env[varName];
      continue;
    }

    const fileMatch = value.match(FILE_REF);
    if (fileMatch) {
      const resolvedPath = expandHome(fileMatch[1].trim());
      assertSafeMode(resolvedPath, key);
      try {
        out[key] = fs.readFileSync(resolvedPath, "utf8");
      } catch (err) {
        throw new Error(
          `Field "${key}" references @file:${resolvedPath}, but the file could not be read: ${err.message}`,
        );
      }
      continue;
    }

    // Plaintext — warn if it's a non-empty secret in a git-tracked file.
    if (
      SECRET_FIELDS.has(key) &&
      value.length > 0 &&
      inGitTree &&
      !allowPlaintextSecrets
    ) {
      log(
        `[deepsql] Warning: field "${key}" contains a plaintext secret and ${sourcePath} is inside a git working tree. ` +
          `Move the value to an env var ($VAR) or a 0600 file (@file:path), or pass --allow-plaintext-secrets.`,
      );
    }
  }
  return out;
}

function expandHome(p) {
  if (!p) return p;
  if (p === "~") return os.homedir();
  if (p.startsWith("~/")) return path.join(os.homedir(), p.slice(2));
  return p;
}

function assertSafeMode(filePath, fieldName) {
  if (process.platform === "win32") return;
  if (process.env.DEEPSQL_INSECURE_AUTH === "1") return;
  let stat;
  try {
    stat = fs.statSync(filePath);
  } catch (err) {
    throw new Error(
      `Cannot read ${filePath} for field "${fieldName}": ${err.message}`,
    );
  }
  if ((stat.mode & 0o077) !== 0) {
    throw new Error(
      `${filePath} (referenced by "${fieldName}") has insecure permissions ${(stat.mode & 0o777).toString(8)}. ` +
        `Run \`chmod 600 ${filePath}\` or set DEEPSQL_INSECURE_AUTH=1.`,
    );
  }
}

function isInsideGitTree(filePath) {
  try {
    const dir = path.dirname(path.resolve(filePath));
    execFileSync("git", ["-C", dir, "rev-parse", "--is-inside-work-tree"], {
      stdio: ["ignore", "ignore", "ignore"],
    });
    return true;
  } catch {
    return false;
  }
}

/**
 * Mask secret values in a config for display. Useful before logging the
 * resolved object for debugging.
 */
function maskSecrets(cfg) {
  if (cfg == null || typeof cfg !== "object") return cfg;
  const out = { ...cfg };
  for (const key of Object.keys(out)) {
    if (SECRET_FIELDS.has(key) && out[key]) {
      out[key] = typeof out[key] === "string" && out[key].length > 0 ? "(set)" : out[key];
    }
  }
  return out;
}

module.exports = {
  SECRET_FIELDS,
  resolveSecrets,
  maskSecrets,
  // exported for testing
  _isInsideGitTree: isInsideGitTree,
  _expandHome: expandHome,
};
