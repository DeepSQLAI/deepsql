"use strict";

const store = require("../auth/store");

async function run(args, { stdout = process.stdout } = {}) {
  const sub = args.positional[0] || "show";
  switch (sub) {
    case "show":
      return showConfig(stdout);
    case "set-default":
      return setDefault(args.positional[1], stdout);
    case "path":
      stdout.write(`${store.authFilePath()}\n`);
      return;
    default:
      throw new Error(`Unknown config subcommand: ${sub}. Try \`show\`, \`set-default <url>\`, or \`path\`.`);
  }
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

module.exports = { run };
