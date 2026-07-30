"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

const { parseArgs, buildOpts } = require("../cli");

function opts(argv) {
  return buildOpts(parseArgs(argv));
}

function loadWithStubs({ responses = [], onRequest = () => {}, errors = [], confirmAnswer = false }) {
  for (const k of [
    require.resolve("../api/client"),
    require.resolve("./_session"),
    require.resolve("./_connections"),
    require.resolve("../ui/prompts"),
    require.resolve("./analyze"),
  ]) {
    delete require.cache[k];
  }

  const apiKey = require.resolve("../api/client");
  let i = 0;
  class FakeApiError extends Error {
    constructor(message, { status, body } = {}) { super(message); this.status = status; this.body = body; }
  }
  require.cache[apiKey] = {
    id: apiKey, filename: apiKey, loaded: true,
    exports: {
      ApiError: FakeApiError,
      async request(_base, path, body) {
        onRequest(path, body);
        if (errors[i]) {
          const e = errors[i];
          i++;
          throw new FakeApiError(e.message || "fail", { status: e.status, body: e.body });
        }
        const r = responses[i] ?? {};
        i++;
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
    exports: { resolveConnectionId: async () => "00000000-0000-0000-0000-000000000001" },
  };

  const promptsKey = require.resolve("../ui/prompts");
  require.cache[promptsKey] = {
    id: promptsKey, filename: promptsKey, loaded: true,
    exports: { confirm: async () => confirmAnswer, input: async () => "", password: async () => "", select: async () => "" },
  };

  return require("./analyze");
}

function captureStdout() {
  let out = ""; let err = "";
  return {
    stream: { write: (s) => { out += s; } },
    errStream: { write: (s) => { err += s; } },
    out: () => out, err: () => err,
  };
}

test("analyze hits /explain/analyze with useAnalyze=false by default", async () => {
  const seen = [];
  const analyze = loadWithStubs({
    onRequest: (path, body) => seen.push({ path, body: body && body.json }),
    responses: [{ planText: "Seq Scan on orders" }],
  });
  const io = captureStdout();
  await analyze.run(opts(["SELECT * FROM orders"]), { stdout: io.stream, stderr: io.errStream });
  assert.equal(seen.length, 1);
  assert.match(seen[0].path, /\/explain\/analyze$/);
  assert.equal(seen[0].body.useAnalyze, false);
  assert.match(io.out(), /Plan \(EXPLAIN, not executed\)/);
  assert.match(io.out(), /Seq Scan on orders/);
});

test("analyze --analyze flips useAnalyze=true on the request", async () => {
  const seen = [];
  const analyze = loadWithStubs({
    onRequest: (path, body) => seen.push(body && body.json),
    responses: [{ wasExecuted: true, planText: "executed" }],
  });
  const io = captureStdout();
  await analyze.run(opts(["SELECT * FROM orders", "--analyze"]), { stdout: io.stream });
  assert.equal(seen[0].useAnalyze, true);
  assert.match(io.out(), /Plan \(executed via EXPLAIN ANALYZE\)/);
});

test("analyze on a mutation triggers two-step confirmation flow on 4xx requiresConfirmation", async () => {
  const seen = [];
  const analyze = loadWithStubs({
    onRequest: (_path, body) => seen.push(body && body.json),
    errors: [{
      status: 200,
      body: { requiresConfirmation: true, message: "ANALYZE will execute this mutation.", queryType: "DELETE", warnings: ["no WHERE"] },
      message: "confirmation required",
    }, null],
    responses: [null, { wasExecuted: true, planText: "deleted 1" }],
    confirmAnswer: true,
  });
  const io = captureStdout();
  await analyze.run(opts(["DELETE FROM users WHERE id=1", "--analyze"]), { stdout: io.stream, stderr: io.errStream });
  assert.equal(seen.length, 2);
  assert.equal(seen[0].mutationConfirmed, false);
  assert.equal(seen[1].mutationConfirmed, true);
  assert.match(io.err(), /ANALYZE will execute/);
});

test("analyze --write skips the prompt and bakes confirmMutation into the first call", async () => {
  const seen = [];
  const analyze = loadWithStubs({
    onRequest: (_path, body) => seen.push(body && body.json),
    responses: [{ wasExecuted: true, planText: "ok" }],
  });
  const io = captureStdout();
  await analyze.run(opts(["DELETE FROM users WHERE id=1", "--analyze", "--write"]), { stdout: io.stream });
  assert.equal(seen.length, 1);
  assert.equal(seen[0].mutationConfirmed, true);
});

test("analyze --json emits the raw analysis payload", async () => {
  const analyze = loadWithStubs({
    responses: [{ aiSummary: "fast", nodeCount: 3, issues: [], indexRecommendations: [] }],
  });
  const io = captureStdout();
  // SQL first so `--json` doesn't eat it as a value.
  await analyze.run(opts(["SELECT 1", "--json"]), { stdout: io.stream });
  const parsed = JSON.parse(io.out());
  assert.equal(parsed.nodeCount, 3);
  assert.equal(parsed.aiSummary, "fast");
});

test("analyze renders AI summary, issues, and index recommendations from the response", async () => {
  const analyze = loadWithStubs({
    responses: [{
      planText: "Seq Scan",
      aiSummary: "Sequential scan on a large table; consider an index on customer_id.",
      issues: [{ severity: "HIGH", title: "Sequential scan", message: "10M rows scanned" }],
      indexRecommendations: [{ tableName: "orders", columns: ["customer_id"], estimatedImpact: 80 }],
      optimizationSuggestions: ["Add WHERE customer_id = ?"],
    }],
  });
  const io = captureStdout();
  await analyze.run(opts(["SELECT * FROM orders"]), { stdout: io.stream });
  const out = io.out();
  assert.match(out, /Summary:/);
  assert.match(out, /Sequential scan on a large table/);
  assert.match(out, /\[HIGH\] Sequential scan/);
  assert.match(out, /orders\(customer_id\)/);
  assert.match(out, /impact≈80/);
  assert.match(out, /Add WHERE customer_id/);
});

test.after(() => {
  for (const k of [
    require.resolve("../api/client"),
    require.resolve("./_session"),
    require.resolve("./_connections"),
    require.resolve("../ui/prompts"),
    require.resolve("./analyze"),
  ]) {
    delete require.cache[k];
  }
});
