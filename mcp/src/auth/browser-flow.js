"use strict";

/**
 * PKCE + loopback redirect flow.
 *
 * 1. Generate verifier/challenge/state.
 * 2. Bind a one-shot HTTP server on 127.0.0.1:<random-port>.
 * 3. POST /auth/cli/authorize → {authorization_id, authorize_url}.
 * 4. Open the authorize URL in the user's browser.
 * 5. Wait for the loopback callback (code + state). Verify state matches.
 * 6. POST /auth/cli/exchange → MCP token.
 *
 * The loopback server only responds to the single expected callback path; any
 * other request gets a 404 to avoid being a useful open-redirect target.
 */

const http = require("node:http");
const { spawn } = require("node:child_process");

const { challengeFor, generateState, generateVerifier } = require("./pkce");
const { request } = require("../api/client");

const CALLBACK_PATH = "/cb";
const SUCCESS_HTML = `<!doctype html>
<html><head><meta charset="utf-8"><title>DeepSQL CLI authorized</title>
<style>body{font:16px/1.5 -apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;max-width:480px;margin:80px auto;padding:0 24px;color:#111}h1{font-size:24px;margin:0 0 12px}p{color:#555}</style>
</head><body><h1>You're all set.</h1><p>You can close this tab and return to your terminal.</p></body></html>`;

const FAILURE_HTML = `<!doctype html>
<html><head><meta charset="utf-8"><title>DeepSQL CLI authorization failed</title>
<style>body{font:16px/1.5 -apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;max-width:480px;margin:80px auto;padding:0 24px;color:#111}h1{font-size:24px;margin:0 0 12px;color:#b91c1c}p{color:#555}</style>
</head><body><h1>Authorization failed.</h1><p>Return to your terminal for details.</p></body></html>`;

function startLoopbackServer() {
  return new Promise((resolve, reject) => {
    const callbackPromise = new Promise((resolveCallback, rejectCallback) => {
      const server = http.createServer((req, res) => {
        const url = new URL(req.url, `http://127.0.0.1`);
        if (url.pathname !== CALLBACK_PATH) {
          res.writeHead(404, { "Content-Type": "text/plain" });
          res.end("Not found");
          return;
        }
        const code = url.searchParams.get("code");
        const state = url.searchParams.get("state");
        const error = url.searchParams.get("error");
        if (error || !code) {
          res.writeHead(400, { "Content-Type": "text/html; charset=utf-8" });
          res.end(FAILURE_HTML);
          rejectCallback(new Error(error || "Loopback callback missing `code` parameter"));
        } else {
          res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
          res.end(SUCCESS_HTML);
          resolveCallback({ code, state });
        }
        // Server can shut down once we've handled the one expected request.
        setImmediate(() => server.close());
      });

      server.on("error", reject);
      server.listen(0, "127.0.0.1", () => {
        const { port } = server.address();
        resolve({
          port,
          redirectUri: `http://127.0.0.1:${port}${CALLBACK_PATH}`,
          callback: callbackPromise,
          close: () => server.close(),
        });
      });
    });
  });
}

function openInBrowser(url) {
  // The URL arrives in a server response, so it is not ours to trust. Args are
  // passed as an array (no shell), but the win32 branch goes through cmd, where
  // a non-http scheme could still be read as something other than a URL.
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    return;
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return;

  const safeUrl = parsed.toString();
  const opener =
    process.platform === "darwin"
      ? ["open", [safeUrl]]
      : process.platform === "win32"
        ? ["cmd", ["/c", "start", '""', safeUrl]]
        : ["xdg-open", [safeUrl]];
  try {
    const child = spawn(opener[0], opener[1], { stdio: "ignore", detached: true });
    child.on("error", () => {});
    child.unref();
  } catch {
    // Fall through silently — caller already prints the URL.
  }
}

async function runBrowserFlow({
  baseUrl,
  hostname,
  clientLabel,
  openBrowser = true,
  log = () => {},
  timeoutMs = 5 * 60 * 1000,
}) {
  const verifier = generateVerifier();
  const challenge = challengeFor(verifier);
  const state = generateState();

  const server = await startLoopbackServer();
  log(`listening for callback on ${server.redirectUri}`);

  let started;
  try {
    started = await request(baseUrl, "/auth/cli/authorize", {
      method: "POST",
      json: {
        redirectUri: server.redirectUri,
        codeChallenge: challenge,
        state,
        hostname,
        clientLabel,
      },
    });
  } catch (err) {
    server.close();
    throw err;
  }

  log(`open ${started.authorize_url}`);
  if (openBrowser) openInBrowser(started.authorize_url);

  const callbackTimer = setTimeout(() => {
    server.close();
  }, timeoutMs);

  let callback;
  try {
    callback = await server.callback;
  } finally {
    clearTimeout(callbackTimer);
  }

  if (callback.state && callback.state !== state) {
    throw new Error("State mismatch — possible CSRF; aborting.");
  }

  const issued = await request(baseUrl, "/auth/cli/exchange", {
    method: "POST",
    json: {
      authorizationId: started.authorization_id,
      code: callback.code,
      codeVerifier: verifier,
    },
  });

  return issued;
}

module.exports = { runBrowserFlow, startLoopbackServer };
