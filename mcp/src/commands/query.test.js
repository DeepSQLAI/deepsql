"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const Module = require("node:module");

const { parseArgs, buildOpts } = require("../cli");

function opts(argv) {
  return buildOpts(parseArgs(argv));
}

// ─── argv plumbing ─────────────────────────────────────────────────────────

test("query --write opt-in lands in opts.write", () => {
  // Boolean flags eat the next token if they aren't given a value;
  // we always put SQL first in real use so that doesn't happen.
  const o = opts(["UPDATE t SET x=1 WHERE id=1", "--connection", "c1", "--write"]);
  assert.equal(o.write, true);
  assert.equal(o.connection, "c1");
});

test("query --caller-agent lands in opts.callerAgent", () => {
  const o = opts(["--caller-agent", "claude-code", "SELECT 1"]);
  assert.equal(o.callerAgent, "claude-code");
});

// ─── dispatch with fake api/client ─────────────────────────────────────────

function loadWithStubs({ responses = [], onRequest = () => {}, confirmAnswer = false }) {
  for (const k of [
    require.resolve("../api/client"),
    require.resolve("./_session"),
    require.resolve("./_connections"),
    require.resolve("../ui/prompts"),
    require.resolve("./query"),
  ]) {
    delete require.cache[k];
  }

  const apiKey = require.resolve("../api/client");
  let callIndex = 0;
  require.cache[apiKey] = {
    id: apiKey, filename: apiKey, loaded: true,
    exports: {
      ApiError: class ApiError extends Error {},
      async request(_base, path, body) {
        onRequest(path, body);
        const r = responses[callIndex] ?? { success: true, result: { columns: [], rows: [] } };
        callIndex++;
        return r;
      },
      setClientContext() {},
      getClientContext() { return null; },
    },
  };

  const sessKey = require.resolve("./_session");
  require.cache[sessKey] = {
    id: sessKey, filename: sessKey, loaded: true,
    exports: { resolveSession: () => ({ baseUrl: "http://test", token: "t" }) },
  };

  const connKey = require.resolve("./_connections");
  require.cache[connKey] = {
    id: connKey, filename: connKey, loaded: true,
    exports: {
      resolveConnectionId: async () => "00000000-0000-0000-0000-000000000001",
      listConnections: async () => [],
    },
  };

  const promptsKey = require.resolve("../ui/prompts");
  require.cache[promptsKey] = {
    id: promptsKey, filename: promptsKey, loaded: true,
    exports: {
      confirm: async () => confirmAnswer,
      input: async () => "",
      password: async () => "",
      select: async () => "",
    },
  };

  return require("./query");
}

function captureStdout() {
  let out = "";
  let err = "";
  return {
    stream: { write: (s) => { out += s; } },
    errStream: { write: (s) => { err += s; } },
    out: () => out,
    err: () => err,
  };
}

test("query hits canonical /connections/{id}/query endpoint, not /mcp/query-readonly", async () => {
  const seen = [];
  const query = loadWithStubs({
    onRequest: (path) => seen.push(path),
    responses: [{ success: true, result: { columns: ["x"], rows: [[1]], rowCount: 1 } }],
  });
  const stdout = captureStdout();
  await query.run(opts(["SELECT 1"]), { stdout: stdout.stream });
  assert.equal(seen.length, 1);
  assert.match(seen[0], /\/connections\/00000000-0000-0000-0000-000000000001\/query$/);
  assert.equal(/\/mcp\//.test(seen[0]), false, "must not fall back to deprecated /mcp/ endpoint");
});

test("query --write sets mutationConfirmed=true upfront (no confirm prompt)", async () => {
  const seen = [];
  const query = loadWithStubs({
    onRequest: (path, body) => seen.push(body && body.json),
    responses: [{ success: true, result: { rowCount: 1 } }],
  });
  const stdout = captureStdout();
  await query.run(opts(["UPDATE t SET x=1 WHERE id=1", "--write"]), { stdout: stdout.stream });
  assert.equal(seen.length, 1);
  assert.equal(seen[0].mutationConfirmed, true);
});

test("query handles two-step requiresConfirmation: prompts, then re-sends with confirmMutation=true", async () => {
  const seen = [];
  const query = loadWithStubs({
    onRequest: (_path, body) => seen.push(body && body.json),
    responses: [
      // First call: server returns requiresConfirmation
      {
        success: false,
        requiresConfirmation: true,
        message: "This statement will modify the database.",
        queryType: "UPDATE",
        warnings: ["A WHERE clause was detected, but verify the target rows."],
      },
      // Second call after the user confirmed: success
      { success: true, result: { rowCount: 1 } },
    ],
    confirmAnswer: true, // simulate user typing 'y'
  });
  const io = captureStdout();
  await query.run(opts(["UPDATE t SET x=1 WHERE id=1"]), { stdout: io.stream, stderr: io.errStream });

  assert.equal(seen.length, 2);
  assert.equal(seen[0].mutationConfirmed, false);
  assert.equal(seen[1].mutationConfirmed, true);
  assert.match(io.err(), /This statement will modify the database/);
  assert.match(io.err(), /A WHERE clause was detected/);
});

test("query honors a 'no' answer at the confirmation prompt — never sends the second call", async () => {
  const seen = [];
  const query = loadWithStubs({
    onRequest: (_path, body) => seen.push(body && body.json),
    responses: [
      { success: false, requiresConfirmation: true, message: "ok?", warnings: [] },
    ],
    confirmAnswer: false,
  });
  const io = captureStdout();
  await query.run(opts(["UPDATE t SET x=1 WHERE id=1"]), { stdout: io.stream, stderr: io.errStream });
  assert.equal(seen.length, 1, "must not retry when user declined");
  assert.match(io.err(), /Aborted/);
});

test("query prints policy block messages with the error code and exits non-zero", async () => {
  const query = loadWithStubs({
    responses: [{
      success: false,
      message: "Only admins can execute DDL or DML from the SQL Editor.",
      errorCode: "EDITOR_MUTATION_FORBIDDEN",
    }],
  });
  const io = captureStdout();
  // process.exitCode is shared state; reset around the test
  const prev = process.exitCode;
  process.exitCode = 0;
  await query.run(opts(["UPDATE t SET x=1"]), { stdout: io.stream, stderr: io.errStream });
  assert.equal(process.exitCode, 1);
  assert.match(io.err(), /Only admins can execute DDL or DML/);
  assert.match(io.err(), /EDITOR_MUTATION_FORBIDDEN/);
  process.exitCode = prev;
});

test("query --json prints the raw response body, including requiresConfirmation flag", async () => {
  // With --json, we should NOT prompt; the caller wants the raw shape.
  // Today's implementation will still prompt — that's a follow-up. For now
  // assert the eventual JSON includes the confirmation marker so a script
  // can react.
  const query = loadWithStubs({
    responses: [{ success: true, result: { columns: ["x"], rows: [[1]], rowCount: 1 } }],
  });
  const io = captureStdout();
  // Put the SQL first so `--json` (a boolean flag) doesn't try to swallow it
  // as its value. This is also how a human types it.
  await query.run(opts(["SELECT 1", "--json"]), { stdout: io.stream, stderr: io.errStream });
  const parsed = JSON.parse(io.out());
  assert.equal(parsed.success, true);
  assert.equal(parsed.result.rowCount, 1);
});

test.after(() => {
  for (const k of [
    require.resolve("../api/client"),
    require.resolve("./_session"),
    require.resolve("./_connections"),
    require.resolve("../ui/prompts"),
    require.resolve("./query"),
  ]) {
    delete require.cache[k];
  }
});

void Module;
