"use strict";

/**
 * Persistent CLI auth store.
 *
 * Layout (XDG-friendly):
 *   ~/.config/deepsql/auth.json   (Linux/macOS)
 *   %APPDATA%\deepsql\auth.json   (Windows)
 *
 * File format:
 *   {
 *     "default": "http://localhost:8080",
 *     "profiles": {
 *       "<base-url>": {
 *         token, username, tokenId, createdAt,
 *         defaultConnection?: "<connection-name-or-uuid>"
 *       }
 *     }
 *   }
 *
 * `defaultConnection` is the active connection for commands that need one
 * (query/explain/schema/digest/slow-queries/brain-*). Set via
 * `deepsql connections use <name>`. Resolution order in commands:
 *   --connection flag  →  DEEPSQL_CONNECTION env  →  profile.defaultConnection
 *
 * The file is written with mode 0600 and the parent dir with 0700. We refuse to
 * read a file with looser perms unless DEEPSQL_INSECURE_AUTH=1 is set, since
 * tokens grant access to the user's databases.
 */

const fs = require("node:fs");
const path = require("node:path");
const { userHome } = require("../user-home");

function configDir() {
  const override = process.env.DEEPSQL_CONFIG_DIR;
  if (override) return override;
  if (process.platform === "win32") {
    const base = process.env.APPDATA || path.join(userHome(), "AppData", "Roaming");
    return path.join(base, "deepsql");
  }
  const xdg = process.env.XDG_CONFIG_HOME;
  if (xdg) return path.join(xdg, "deepsql");
  return path.join(userHome(), ".config", "deepsql");
}

function authFilePath() {
  return path.join(configDir(), "auth.json");
}

function normalizeBaseUrl(url) {
  if (!url || typeof url !== "string") return url;
  return url.replace(/\/+$/, "");
}

function emptyState() {
  return { default: null, profiles: {} };
}

function load() {
  const file = authFilePath();
  if (!fs.existsSync(file)) {
    return emptyState();
  }
  if (process.platform !== "win32" && process.env.DEEPSQL_INSECURE_AUTH !== "1") {
    const stat = fs.statSync(file);
    // Mode 0600 == 0o600. Reject if group/other have any bits set.
    if ((stat.mode & 0o077) !== 0) {
      throw new Error(
        `${file} has insecure permissions (${(stat.mode & 0o777).toString(8)}). ` +
          "Run `chmod 600` on it, or set DEEPSQL_INSECURE_AUTH=1 to override.",
      );
    }
  }
  try {
    const parsed = JSON.parse(fs.readFileSync(file, "utf8"));
    if (!parsed || typeof parsed !== "object") return emptyState();
    return {
      default: parsed.default || null,
      profiles: parsed.profiles || {},
    };
  } catch (err) {
    throw new Error(`Failed to parse ${file}: ${err.message}`);
  }
}

function save(state) {
  const dir = configDir();
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  if (process.platform !== "win32") {
    try {
      fs.chmodSync(dir, 0o700);
    } catch {
      // Best-effort — some filesystems (FAT, network mounts) don't support chmod.
    }
  }
  const file = authFilePath();
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(state, null, 2), { mode: 0o600 });
  fs.renameSync(tmp, file);
}

function getProfile(baseUrl) {
  const state = load();
  const key = normalizeBaseUrl(baseUrl) || state.default;
  if (!key) return null;
  return state.profiles[key] || null;
}

function setProfile(baseUrl, profile) {
  const state = load();
  const key = normalizeBaseUrl(baseUrl);
  state.profiles[key] = profile;
  if (!state.default) state.default = key;
  save(state);
}

function removeProfile(baseUrl) {
  const state = load();
  const key = normalizeBaseUrl(baseUrl);
  delete state.profiles[key];
  if (state.default === key) {
    const remaining = Object.keys(state.profiles);
    state.default = remaining[0] || null;
  }
  save(state);
}

function setDefault(baseUrl) {
  const state = load();
  const key = normalizeBaseUrl(baseUrl);
  if (!state.profiles[key]) {
    throw new Error(`No profile saved for ${key}. Run \`deepsql login --url ${key}\` first.`);
  }
  state.default = key;
  save(state);
}

function defaultBaseUrl() {
  const state = load();
  return state.default || null;
}

function getDefaultConnection(baseUrl) {
  const profile = getProfile(baseUrl);
  return profile && profile.defaultConnection ? profile.defaultConnection : null;
}

function setDefaultConnection(baseUrl, connectionName) {
  const state = load();
  const key = normalizeBaseUrl(baseUrl);
  if (!state.profiles[key]) {
    throw new Error(
      `No profile saved for ${key}. Run \`deepsql login --url ${key}\` first.`,
    );
  }
  if (connectionName == null || connectionName === "") {
    delete state.profiles[key].defaultConnection;
  } else {
    state.profiles[key].defaultConnection = connectionName;
  }
  save(state);
}

function listProfiles() {
  return load();
}

module.exports = {
  authFilePath,
  configDir,
  defaultBaseUrl,
  getDefaultConnection,
  getProfile,
  listProfiles,
  load,
  normalizeBaseUrl,
  removeProfile,
  save,
  setDefault,
  setDefaultConnection,
  setProfile,
};
