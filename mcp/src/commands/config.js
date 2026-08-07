"use strict";

const store = require("../auth/store");

// Exported so the help-drift guard in src/cli.test.js can assert that every
// dispatchable subcommand is documented in `deepsql config -h`. Before this map
// existed `config` could not be covered by that guard at all.
const SUBCOMMANDS = {
  show: (args, io) => showConfig(io.stdout),
  "set-default": (args, io) => setDefault(args.positional[1], io.stdout),
  remove: (args, io) => removeProfile(args.positional[1], io.stdout),
  path: (args, io) => { io.stdout.write(`${store.authFilePath()}\n`); },
};

async function run(args, { stdout = process.stdout } = {}) {
  const sub = args.positional[0] || "show";
  const handler = SUBCOMMANDS[sub];
  if (!handler) {
    throw new Error(
      `Unknown config subcommand: ${sub}. Try ${Object.keys(SUBCOMMANDS)
        .map((s) => `\`${s}\``)
        .join(", ")}.`,
    );
  }
  return handler(args, { stdout });
}

function showConfig(stdout) {
  const state = store.listProfiles();
  const profiles = Object.keys(state.profiles || {});
  if (profiles.length === 0) {
    stdout.write("No saved profiles. Run `deepsql login --url <url>` to add one.\n");
    return;
  }
  stdout.write(`Default: ${state.default || "(none)"}\n`);
  stdout.write(`Profiles:\n`);
  for (const url of profiles) {
    const p = state.profiles[url];
    const marker = url === state.default ? " *" : "  ";
    stdout.write(`${marker} ${url}    user=${p.username || "?"}  tokenId=${p.tokenId || "?"}\n`);
  }
}

function setDefault(url, stdout) {
  if (!url) throw new Error("Pass the base URL: `deepsql config set-default <url>`.");
  store.setDefault(store.normalizeBaseUrl(url));
  stdout.write(`Default profile is now ${store.normalizeBaseUrl(url)}.\n`);
}

/**
 * Forget a saved profile. Until this existed, the only way to drop a stale entry
 * was to hand-edit auth.json, so dead hosts accumulated and every bare
 * `deepsql login` had to disambiguate between servers that no longer ran.
 */
function removeProfile(url, stdout) {
  if (!url) throw new Error("Pass the base URL: `deepsql config remove <url>`.");
  const key = store.normalizeBaseUrl(url);
  const before = store.listProfiles();
  if (!before.profiles || !before.profiles[key]) {
    const known = Object.keys(before.profiles || {});
    throw new Error(
      known.length
        ? `No saved profile for ${key}. Saved profiles:\n  - ${known.join("\n  - ")}`
        : `No saved profile for ${key}. There are no saved profiles.`,
    );
  }
  const wasDefault = before.default === key;

  store.removeProfile(key);

  stdout.write(`Removed profile ${key}.\n`);
  // Removing the default repoints bare `deepsql` somewhere else, which for a DBA
  // tool means a different database. Always say where it landed.
  if (wasDefault) {
    const after = store.listProfiles();
    stdout.write(
      after.default
        ? `Default is now ${after.default}.\n`
        : "No default profile is set. Pass --url, or run `deepsql config set-default <url>`.\n",
    );
  }
}

module.exports = { run, SUBCOMMANDS };
