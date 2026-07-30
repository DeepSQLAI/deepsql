"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

const { parseArgs, buildOpts } = require("../cli");

function opts(argv) {
  return buildOpts(parseArgs(argv));
}

// users -----------------------------------------------------------------------

test("users add positional email + role/name flags", () => {
  const o = opts(["add", "x@y.com", "--role", "DEVELOPER", "--name", "X"]);
  assert.deepEqual(o.positional, ["add", "x@y.com"]);
  assert.equal(o.role, "DEVELOPER");
  assert.equal(o.name, "X");
});

test("users delete --yes flag", () => {
  const o = opts(["delete", "alice@x.com", "--yes"]);
  assert.equal(o.yes, true);
  assert.deepEqual(o.positional, ["delete", "alice@x.com"]);
});

test("users reset-password --password-stdin flag", () => {
  const o = opts(["reset-password", "alice@x.com", "--password-stdin"]);
  assert.equal(o.passwordStdin, true);
  assert.equal(o.password, null); // stays null since it's a separate flag
});

// access ----------------------------------------------------------------------

test("access grant requires user/connection/level", () => {
  const o = opts([
    "grant",
    "--user",
    "alice@x.com",
    "--connection",
    "mylocalpg",
    "--level",
    "read",
  ]);
  assert.equal(o.user, "alice@x.com");
  assert.equal(o.connection, "mylocalpg");
  assert.equal(o.level, "read");
});

test("access policy passes positional user + connection", () => {
  const o = opts(["policy", "alice@x.com", "mylocalpg"]);
  assert.deepEqual(o.positional, ["policy", "alice@x.com", "mylocalpg"]);
});

// permissions -----------------------------------------------------------------

test("permissions override --grant + --reason", () => {
  const o = opts([
    "override",
    "--role",
    "DEVELOPER",
    "--permission",
    "USE_CHAT",
    "--grant",
    "--reason",
    "Beta access",
  ]);
  assert.equal(o.role, "DEVELOPER");
  assert.equal(o.permission, "USE_CHAT");
  assert.equal(o.grant, true);
  assert.equal(o.revoke, false);
  assert.equal(o.reason, "Beta access");
});

test("permissions override --revoke flips to revoke", () => {
  const o = opts(["override", "--role", "DEVELOPER", "--permission", "USE_CHAT", "--revoke"]);
  assert.equal(o.grant, false);
  assert.equal(o.revoke, true);
});

// slow-queries ----------------------------------------------------------------

test("slow-queries history N positional", () => {
  const o = opts(["history", "--connection", "mylocalpg", "5"]);
  assert.deepEqual(o.positional, ["history", "5"]);
  assert.equal(o.connection, "mylocalpg");
});

test("slow-queries analyze flags", () => {
  const o = opts([
    "analyze",
    "--connection",
    "mylocalpg",
    "--time-range",
    "LAST_HOUR",
    "--threshold-ms",
    "200",
    "--limit",
    "20",
  ]);
  assert.equal(o.timeRange, "LAST_HOUR");
  assert.equal(o.thresholdMs, "200");
  assert.equal(o.limit, "20");
});

test("slow-queries optimize uses --query-id", () => {
  const o = opts(["optimize", "--connection", "mylocalpg", "--query-id", "q-123"]);
  assert.equal(o.queryId, "q-123");
});

test("slow-queries delete --history-id with --yes", () => {
  const o = opts(["delete", "--history-id", "42", "--yes"]);
  assert.equal(o.historyId, "42");
  assert.equal(o.yes, true);
});

// setup -----------------------------------------------------------------------

test("setup --force --skip-email", () => {
  const o = opts(["--force", "--skip-email"]);
  assert.equal(o.force, true);
  assert.equal(o.skipEmail, true);
});

// password flag stays a flag for login + value for users -----------------------

test("--password alone (login flow) leaves password truthy", () => {
  const o = opts(["--password"]);
  assert.equal(!!o.password, true);
});

test("--password=secret keeps the string value (still truthy)", () => {
  const o = opts(["--password=secret"]);
  assert.equal(o.password, "secret");
});

// connections add/test flags ----------------------------------------------

test("connections add --from-file with --upsert/--no-test/--wait/--delete-after", () => {
  const o = opts([
    "add",
    "--from-file",
    "/tmp/conn.json",
    "--upsert",
    "--no-test",
    "--wait",
    "--delete-after",
  ]);
  assert.equal(o.fromFile, "/tmp/conn.json");
  assert.equal(o.upsert, true);
  assert.equal(o.noTest, true);
  assert.equal(o.wait, true);
  assert.equal(o.deleteAfter, true);
});

test("connections add --from-stdin + --allow-plaintext-secrets", () => {
  const o = opts(["add", "--from-stdin", "--allow-plaintext-secrets"]);
  assert.equal(o.fromStdin, true);
  assert.equal(o.allowPlaintextSecrets, true);
});

test("connections add --cloud triggers cloud-prompt path", () => {
  const o = opts(["add", "--cloud"]);
  assert.equal(o.cloud, true);
});

test("connections init <name> --force --wait", () => {
  const o = opts(["init", "prod", "--force", "--wait"]);
  assert.deepEqual(o.positional, ["init", "prod"]);
  assert.equal(o.force, true);
  assert.equal(o.wait, true);
});
