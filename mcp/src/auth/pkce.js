"use strict";

/**
 * PKCE helpers (RFC 7636).
 *
 *   verifier  = high-entropy random string (43–128 chars from the unreserved set)
 *   challenge = base64url(sha256(verifier))   (S256 method)
 *
 * Splitting these out so the CLI tests can exercise the math without spinning
 * up an HTTP server.
 */

const crypto = require("node:crypto");

function base64UrlEncode(buffer) {
  return buffer
    .toString("base64")
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "");
}

function generateVerifier(bytes = 48) {
  return base64UrlEncode(crypto.randomBytes(bytes));
}

function challengeFor(verifier) {
  const hash = crypto.createHash("sha256").update(verifier).digest();
  return base64UrlEncode(hash);
}

function generateState() {
  return base64UrlEncode(crypto.randomBytes(16));
}

module.exports = {
  base64UrlEncode,
  challengeFor,
  generateState,
  generateVerifier,
};
