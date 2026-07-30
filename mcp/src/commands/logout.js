"use strict";

const store = require("../auth/store");
const { request } = require("../api/client");

async function run(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const baseUrl = opts.url ? store.normalizeBaseUrl(opts.url) : store.defaultBaseUrl();
  if (!baseUrl) {
    stdout.write("No saved DeepSQL profile to log out of.\n");
    return;
  }
  const profile = store.getProfile(baseUrl);
  if (!profile) {
    stdout.write(`No saved profile for ${baseUrl}.\n`);
    return;
  }

  if (profile.tokenId) {
    try {
      await request(baseUrl, `/auth/mcp-tokens/${profile.tokenId}`, {
        method: "DELETE",
        token: profile.token,
      });
    } catch (err) {
      stderr.write(`[deepsql] Could not revoke token server-side: ${err.message}\n`);
    }
  }

  store.removeProfile(baseUrl);
  stdout.write(`Logged out of ${baseUrl}.\n`);
}

module.exports = { run };
