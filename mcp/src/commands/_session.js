"use strict";

const store = require("../auth/store");

/**
 * Resolves the (baseUrl, token) tuple a command should use.
 *
 * Precedence — highest first:
 *   1. --token CLI flag
 *   2. DEEPSQL_AUTH_TOKEN env var (back-compat with the legacy MCP setup)
 *   3. Saved profile for --url, or the default profile
 *
 * The base URL falls back from --url → DEEPSQL_API_BASE_URL → saved profile.
 */
function resolveSession(opts = {}) {
  const explicitToken = opts.token || process.env.DEEPSQL_AUTH_TOKEN || null;

  let baseUrl = opts.url ? store.normalizeBaseUrl(opts.url) : null;
  if (!baseUrl && process.env.DEEPSQL_API_BASE_URL) {
    baseUrl = store.normalizeBaseUrl(stripApiSuffix(process.env.DEEPSQL_API_BASE_URL));
  }
  if (!baseUrl) baseUrl = store.defaultBaseUrl();

  let profile = null;
  if (baseUrl) profile = store.getProfile(baseUrl);

  const token = explicitToken || profile?.token || null;
  if (!baseUrl) {
    throw new Error("No DeepSQL URL configured. Run `deepsql login --url <url>`.");
  }
  if (!token) {
    throw new Error(`No auth token for ${baseUrl}. Run \`deepsql login --url ${baseUrl}\`.`);
  }
  // Surface the saved active connection so the connection resolver can fall
  // back to it when --connection isn't passed. Set via `deepsql connections use`.
  const defaultConnection = profile?.defaultConnection || null;
  return { baseUrl, token, profile, defaultConnection };
}

function stripApiSuffix(url) {
  return url.replace(/\/?api\/?$/, "");
}

module.exports = { resolveSession };
