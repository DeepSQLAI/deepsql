"use strict";

/**
 * Direct username/password login — for headless boxes where neither a browser
 * nor copy-paste of a device code is convenient (e.g. provisioning a fresh
 * self-host VM via Ansible/cloud-init, or a CI runner that needs to mint a
 * service-account token from a stored secret).
 *
 * Flow:
 *   1. Prompt for email + password (or accept --email and --password-stdin).
 *   2. POST /auth/login. The backend either:
 *        a) succeeds and sets the auth_token cookie containing a session JWT, or
 *        b) returns { challengeId, message } indicating an email-OTP step.
 *   3. If a challenge is required, kick off /auth/email/start, prompt for the
 *      OTP, then POST /auth/email/verify. Same auth_token cookie is set on
 *      success.
 *   4. With the JWT in hand, POST /auth/mcp-tokens to mint a long-lived MCP
 *      token. The session cookie is short-lived (minutes); the MCP token lives
 *      until revoked, which is what we want to persist to ~/.config/deepsql.
 *
 * Security notes:
 *   - We never persist the raw password. It's read from a TTY (echo off) or
 *     from stdin and then dropped after the login POST.
 *   - The session JWT is held only in memory between login and the
 *     mcp-tokens POST.
 *   - For CI-style use, callers should pipe the password via stdin
 *     (`--password-stdin`) rather than passing it as an argv flag, since argv
 *     shows up in `ps`.
 */

const os = require("node:os");

const { ApiError, parseCookieValue, request } = require("../api/client");
const { prompt, promptPassword, readSingleLineFromStdin } = require("./prompt");

async function runPasswordFlow({ baseUrl, email, passwordStdin, hostname, clientLabel, log = () => {} }) {
  const resolvedEmail = email && email.trim() ? email.trim() : await prompt("Email: ");
  if (!resolvedEmail) throw new Error("Email is required.");

  const password = passwordStdin
    ? await readSingleLineFromStdin()
    : await promptPassword("Password: ");
  if (!password) throw new Error("Password is required.");

  log(`Authenticating ${resolvedEmail} against ${baseUrl}…`);
  let jwt = await postLoginAndExtractJwt(baseUrl, resolvedEmail, password);

  if (!jwt) {
    // Server returned a challenge — currently the only kind we handle is
    // email OTP. Other challenges (e.g. authenticator-app MFA) should fall
    // back to the browser flow with a clear error.
    log("Server requires email verification. Sending OTP…");
    jwt = await runEmailOtpChallenge(baseUrl, resolvedEmail);
  }

  log("Minting long-lived CLI token…");
  const tokenName = buildTokenName(clientLabel, hostname);
  const created = await request(baseUrl, "/auth/mcp-tokens", {
    method: "POST",
    token: jwt,
    json: { name: tokenName },
  });

  return {
    token: created.token,
    token_id: created.id,
    username: extractUsername(created, resolvedEmail),
    expires_at: created.expiresAt || null,
  };
}

async function postLoginAndExtractJwt(baseUrl, email, password) {
  let result;
  try {
    result = await request(baseUrl, "/auth/login", {
      method: "POST",
      json: { email, password },
      returnHeaders: true,
    });
  } catch (err) {
    if (err instanceof ApiError && err.status >= 400 && err.status < 500) {
      const detail = err.body && typeof err.body === "object" ? err.body.message : null;
      throw new Error(detail || "Invalid email or password.");
    }
    throw err;
  }

  const jwt = parseCookieValue(result.setCookies, "auth_token");
  if (jwt) return jwt;

  // No cookie on the response — must be a challenge handoff. Body shape is
  // { challengeId, message }.
  if (result.body && result.body.challengeId) return null;

  throw new Error(
    "Login succeeded but no session token was issued — your DeepSQL instance may use SSO that the CLI can't drive. Use `deepsql login` (browser flow) instead.",
  );
}

async function runEmailOtpChallenge(baseUrl, email) {
  // Fresh challenge so /email/start has something to act on. The login call
  // above already created a challenge but didn't expose its id; calling
  // /email/start without a prior challenge id is supported and starts a new
  // one for the same user.
  let started;
  try {
    started = await request(baseUrl, "/auth/email/start", {
      method: "POST",
      json: { email },
    });
  } catch (err) {
    throw new Error(
      `Could not start email verification: ${err.message}. Use \`deepsql login\` (browser flow) if your account requires SSO or authenticator MFA.`,
    );
  }

  const otp = await prompt(`Email OTP (sent to ${email}): `);
  if (!otp) throw new Error("OTP is required.");

  const result = await request(baseUrl, "/auth/email/verify", {
    method: "POST",
    json: { challengeId: started.challengeId, otp },
    returnHeaders: true,
  });
  const jwt = parseCookieValue(result.setCookies, "auth_token");
  if (!jwt) {
    throw new Error("Email verification did not return a session token. Aborting.");
  }
  return jwt;
}

function extractUsername(createdTokenResponse, fallbackEmail) {
  if (createdTokenResponse && typeof createdTokenResponse === "object") {
    if (createdTokenResponse.username) return createdTokenResponse.username;
    if (createdTokenResponse.userId) return String(createdTokenResponse.userId);
  }
  return fallbackEmail;
}

function buildTokenName(clientLabel, hostname) {
  const parts = ["CLI"];
  parts.push(clientLabel && clientLabel.trim() ? clientLabel.trim() : "deepsql");
  if (hostname && hostname.trim()) parts.push(`@ ${hostname.trim()}`);
  parts.push("(password)");
  const name = parts.join(" ");
  return name.length > 120 ? name.slice(0, 120) : name;
}

module.exports = { runPasswordFlow };
