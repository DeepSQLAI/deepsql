"use strict";

const os = require("node:os");
const { runBrowserFlow } = require("../auth/browser-flow");
const { runDeviceFlow } = require("../auth/device-flow");
const { runPasswordFlow } = require("../auth/password-flow");
const store = require("../auth/store");
const { ApiError } = require("../api/client");

function defaultClientLabel() {
  return `deepsql-cli@${process.versions.node}`;
}

/**
 * Resolve which DeepSQL URL `deepsql login` should target. Self-hosted
 * safety property: we never silently pick "the URL the user happens to
 * have logged into first" when their machine has multiple profiles.
 *
 * Rules:
 *   --url given      → use it (always wins).
 *   0 saved profiles → error, ask for --url with a concrete example.
 *   1 saved profile  → use it, print which one so the user can ^C if it's
 *                      not what they meant.
 *   ≥ 2 profiles     → error, list them, require --url. This is the case
 *                      that bit a user testing against multiple hosts —
 *                      bare `deepsql login` used to keep returning to
 *                      whichever URL was logged into FIRST, regardless
 *                      of which one the user actually wanted.
 *
 * Exported for tests.
 */
function resolveLoginBaseUrl(opts, { stderr = process.stderr } = {}) {
  if (opts.url) {
    return store.normalizeBaseUrl(opts.url);
  }
  const state = store.listProfiles();
  const urls = Object.keys(state.profiles || {});
  if (urls.length === 0) {
    throw new Error(
      "No saved DeepSQL profile yet. Pass --url <https://your-deepsql-host> "
      + "with your own DeepSQL host (e.g. `deepsql login --url https://deepsql.acme.com`).",
    );
  }
  if (urls.length === 1) {
    const only = urls[0];
    stderr.write(
      `[deepsql] Using saved profile: ${only}. Pass --url to log in against a different host.\n`,
    );
    return only;
  }
  // Multiple profiles — refuse to guess. List them so the user can pick.
  throw new Error(
    `Multiple saved DeepSQL profiles. Pass --url <host> to choose one:\n  - ${urls.join("\n  - ")}\n`
    + `Or run \`deepsql config set-default <url>\` to pin one as the default for future logins.`,
  );
}

function pickFlow(opts) {
  // Explicit user choice wins.
  if (opts.password) return "password";
  if (opts.device) return "device";
  if (opts.browser) return "browser";
  // Heuristic: prefer browser; fall back to device-code in headless envs.
  if (process.env.SSH_CONNECTION || process.env.SSH_CLIENT) return "device";
  if (process.platform === "linux" && !process.env.DISPLAY && !process.env.WAYLAND_DISPLAY) return "device";
  return "browser";
}

async function run(opts, { stderr = process.stderr, stdout = process.stdout } = {}) {
  const baseUrl = resolveLoginBaseUrl(opts, { stderr });
  const hostname = os.hostname();
  const label = opts.label || defaultClientLabel();
  const flow = pickFlow(opts);
  const log = (msg) => stderr.write(`[deepsql] ${msg}\n`);

  let issued;
  try {
    if (flow === "device") {
      log(`Starting device-code login against ${baseUrl}…`);
      issued = await runDeviceFlow({ baseUrl, hostname, clientLabel: label, log });
    } else if (flow === "password") {
      log(`Starting password login against ${baseUrl}…`);
      issued = await runPasswordFlow({
        baseUrl,
        email: opts.email,
        passwordStdin: !!opts.passwordStdin,
        hostname,
        clientLabel: label,
        log,
      });
    } else {
      log(`Starting browser login against ${baseUrl}…`);
      issued = await runBrowserFlow({
        baseUrl,
        hostname,
        clientLabel: label,
        log,
        openBrowser: !opts.noBrowser,
      });
    }
  } catch (err) {
    if (err instanceof ApiError && err.status === 0) {
      throw new Error(`Could not reach ${baseUrl}. Is the DeepSQL backend running?`);
    }
    throw err;
  }

  store.setProfile(baseUrl, {
    token: issued.token,
    tokenId: issued.token_id,
    username: issued.username,
    createdAt: new Date().toISOString(),
    expiresAt: issued.expires_at || null,
  });

  stdout.write(`Authorized as ${issued.username} at ${baseUrl}\n`);
  stdout.write(`Token saved to ${store.authFilePath()}\n`);
}

module.exports = { run, resolveLoginBaseUrl };
