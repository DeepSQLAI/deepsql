"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");

const { challengeFor, generateState, generateVerifier, base64UrlEncode } = require("./pkce");

test("generateVerifier returns base64url with no padding", () => {
  const verifier = generateVerifier();
  assert.match(verifier, /^[A-Za-z0-9_-]+$/);
  assert.ok(verifier.length >= 43, "verifier must meet RFC 7636 length minimum");
});

test("challengeFor matches the spec: base64url(sha256(verifier))", () => {
  const verifier = "test-verifier-with-enough-entropy-for-rfc7636";
  const expected = base64UrlEncode(crypto.createHash("sha256").update(verifier).digest());
  assert.equal(challengeFor(verifier), expected);
});

test("two verifiers produce different challenges", () => {
  const a = generateVerifier();
  const b = generateVerifier();
  assert.notEqual(a, b);
  assert.notEqual(challengeFor(a), challengeFor(b));
});

test("generateState returns base64url state of meaningful length", () => {
  const state = generateState();
  assert.match(state, /^[A-Za-z0-9_-]+$/);
  assert.ok(state.length >= 16);
});
