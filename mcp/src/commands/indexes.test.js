"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const Module = require("node:module");

const { parseArgs, buildOpts } = require("../cli");

function opts(argv) {
  return buildOpts(parseArgs(argv));
}

// ─── argv plumbing ─────────────────────────────────────────────────────────

test("indexes flags: --all, --status, --json land in opts", () => {
  const o = opts(["list", "--all", "--status", "APPLIED", "--json"]);
  assert.equal(o.all, true);
  assert.equal(o.status, "APPLIED");
  assert.equal(o.json, true);
  assert.deepEqual(o.positional, ["list"]);
});

test("indexes usage <table> keeps the table name as positional[1]", () => {
  const o = opts(["usage", "users", "--connection", "c1"]);
  assert.deepEqual(o.positional, ["usage", "users"]);
  assert.equal(o.connection, "c1");
});

// ─── dispatch ──────────────────────────────────────────────────────────────
//
// Build a fake api/client + _session + _connections so the `run` handlers
// don't try to hit the network. We stub them via Node's `require.cache` so
// they intercept the next require() inside indexes.js.

function loadWithStubs({ requests = [], responses = {} }) {
  // Reset the modules we're about to stub + the indexes module itself.
  for (const k of [
    require.resolve("../api/client"),
    require.resolve("./_session"),
    require.resolve("./_connections"),
    require.resolve("./indexes"),
  ]) {
    delete require.cache[k];
  }

  const apiKey = require.resolve("../api/client");
  require.cache[apiKey] = {
    id: apiKey,
    filename: apiKey,
    loaded: true,
    exports: {
      ApiError: class ApiError extends Error {},
      async request(_base, path) {
        requests.push(path);
        if (Object.prototype.hasOwnProperty.call(responses, path)) {
          return responses[path];
        }
        return [];
      },
    },
  };

  const sessKey = require.resolve("./_session");
  require.cache[sessKey] = {
    id: sessKey,
    filename: sessKey,
    loaded: true,
    exports: {
      resolveSession: () => ({ baseUrl: "http://test", token: "t" }),
    },
  };

  const connKey = require.resolve("./_connections");
  require.cache[connKey] = {
    id: connKey,
    filename: connKey,
    loaded: true,
    exports: {
      resolveConnectionId: async () => "00000000-0000-0000-0000-000000000001",
      listConnections: async () => [],
    },
  };

  return require("./indexes");
}

function captureStdout() {
  let out = "";
  return {
    stream: { write: (s) => { out += s; } },
    out: () => out,
  };
}

test("indexes list (default) hits the /pending endpoint", async () => {
  const requests = [];
  const indexes = loadWithStubs({
    requests,
    responses: {
      "/index-recommendations/pending/00000000-0000-0000-0000-000000000001": [],
    },
  });
  const stdout = captureStdout();
  await indexes.run(opts(["list"]), { stdout: stdout.stream });
  assert.equal(requests.length, 1);
  assert.match(requests[0], /\/index-recommendations\/pending\//);
  assert.match(stdout.out(), /No pending index recommendations/);
});

test("indexes list --all hits the all-recs endpoint", async () => {
  const requests = [];
  const indexes = loadWithStubs({
    requests,
    responses: {
      "/index-recommendations/00000000-0000-0000-0000-000000000001": [],
    },
  });
  const stdout = captureStdout();
  await indexes.run(opts(["list", "--all"]), { stdout: stdout.stream });
  assert.equal(requests.length, 1);
  assert.equal(requests[0], "/index-recommendations/00000000-0000-0000-0000-000000000001");
});

test("indexes list renders priority, name, ddl per recommendation", async () => {
  const indexes = loadWithStubs({
    responses: {
      "/index-recommendations/pending/00000000-0000-0000-0000-000000000001": [
        {
          id: "r1",
          tableName: "orders",
          columnNames: "customer_id, created_at",
          indexName: "idx_orders_customer_created",
          createStatement: "CREATE INDEX idx_orders_customer_created ON orders (customer_id, created_at)",
          priority: "HIGH",
          estimatedImpact: 42,
          affectedQueries: 7,
          reason: "Hot lookup pattern from chat history",
          status: "PENDING",
        },
      ],
    },
  });
  const stdout = captureStdout();
  await indexes.run(opts(["list"]), { stdout: stdout.stream });
  const text = stdout.out();
  assert.match(text, /\[HIGH\] orders\(customer_id, created_at\)/);
  assert.match(text, /idx_orders_customer_created/);
  assert.match(text, /CREATE INDEX idx_orders_customer_created/);
  assert.match(text, /impact≈42%/);
  assert.match(text, /7 queries/);
});

test("indexes list --status filters client-side and switches to the all endpoint", async () => {
  const requests = [];
  const indexes = loadWithStubs({
    requests,
    responses: {
      "/index-recommendations/00000000-0000-0000-0000-000000000001": [
        { id: "a", tableName: "t1", columnNames: "x", priority: "LOW", status: "APPLIED" },
        { id: "b", tableName: "t2", columnNames: "y", priority: "HIGH", status: "DISMISSED" },
      ],
    },
  });
  const stdout = captureStdout();
  await indexes.run(opts(["list", "--status", "DISMISSED"]), { stdout: stdout.stream });
  assert.match(requests[0], /\/index-recommendations\/00000000/);
  const text = stdout.out();
  assert.match(text, /1 DISMISSED recommendation/);
  assert.match(text, /\[HIGH\] t2/);
  assert.equal(/t1/.test(text), false, "applied row should be filtered out");
});

test("indexes list rejects bogus --status values", async () => {
  const indexes = loadWithStubs({});
  await assert.rejects(
    indexes.run(opts(["list", "--status", "REJECTED"]), { stdout: captureStdout().stream }),
    /Invalid --status/,
  );
});

test("indexes usage <table> wires the table into the URL", async () => {
  const requests = [];
  const indexes = loadWithStubs({
    requests,
    responses: {
      "/index-advisor/00000000-0000-0000-0000-000000000001/usage/users": [],
    },
  });
  const stdout = captureStdout();
  await indexes.run(opts(["usage", "users"]), { stdout: stdout.stream });
  assert.equal(requests[0], "/index-advisor/00000000-0000-0000-0000-000000000001/usage/users");
});

test("indexes usage without a table name throws a friendly Usage:", async () => {
  const indexes = loadWithStubs({});
  await assert.rejects(
    indexes.run(opts(["usage"]), { stdout: captureStdout().stream }),
    /Usage: deepsql indexes usage <tableName>/,
  );
});

test("indexes unused prints size and scan counts in the text view", async () => {
  const indexes = loadWithStubs({
    responses: {
      "/index-advisor/00000000-0000-0000-0000-000000000001/unused": [
        { tableName: "orders", indexName: "idx_old", sizeMb: 120, scans: 0 },
      ],
    },
  });
  const stdout = captureStdout();
  await indexes.run(opts(["unused"]), { stdout: stdout.stream });
  assert.match(stdout.out(), /orders\.idx_old, 120 MB, scans=0/);
});

test("indexes duplicates handles the empty case", async () => {
  const indexes = loadWithStubs({
    responses: {
      "/index-advisor/00000000-0000-0000-0000-000000000001/duplicates": [],
    },
  });
  const stdout = captureStdout();
  await indexes.run(opts(["duplicates"]), { stdout: stdout.stream });
  assert.match(stdout.out(), /No duplicate or redundant indexes detected\./);
});

test("indexes health flattens the report into key: value lines", async () => {
  const indexes = loadWithStubs({
    responses: {
      "/index-advisor/00000000-0000-0000-0000-000000000001/health-report": {
        totalIndexes: 47,
        unusedCount: 3,
        duplicateGroups: 1,
        items: [{ a: 1 }, { a: 2 }],
      },
    },
  });
  const stdout = captureStdout();
  await indexes.run(opts(["health"]), { stdout: stdout.stream });
  const text = stdout.out();
  assert.match(text, /Index health report/);
  assert.match(text, /totalIndexes:.*47/);
  assert.match(text, /items:.*2 entries/);
});

test("indexes missing renders advisor-style suggestions", async () => {
  const indexes = loadWithStubs({
    responses: {
      "/advisor/indexes/00000000-0000-0000-0000-000000000001": [
        { tableName: "users", columns: ["email"], priority: "CRITICAL", reason: "Unindexed equality lookup", suggestedIndex: "CREATE INDEX idx_users_email ON users(email)" },
      ],
    },
  });
  const stdout = captureStdout();
  await indexes.run(opts(["missing"]), { stdout: stdout.stream });
  const text = stdout.out();
  assert.match(text, /\[CRITICAL\] users\(email\)/);
  assert.match(text, /idx_users_email/);
});

test("indexes <unknown-sub> throws a hint that lists the real ones", async () => {
  const indexes = loadWithStubs({});
  await assert.rejects(
    indexes.run(opts(["nope"]), { stdout: captureStdout().stream }),
    /Unknown indexes subcommand: nope/,
  );
});

test("indexes list --json emits raw JSON and no human prose", async () => {
  const indexes = loadWithStubs({
    responses: {
      "/index-recommendations/pending/00000000-0000-0000-0000-000000000001": [
        { id: "r1", tableName: "t", columnNames: "x" },
      ],
    },
  });
  const stdout = captureStdout();
  await indexes.run(opts(["list", "--json"]), { stdout: stdout.stream });
  const text = stdout.out().trim();
  const parsed = JSON.parse(text);
  assert.equal(parsed.length, 1);
  assert.equal(parsed[0].id, "r1");
});

// ═════════════════════════════════════════════════════════════════════════════
// Advisor-surface tests (top / show / refresh / apply / dismiss)
// — absorbed from the deprecated `index-recommendations` namespace in 0.15.0.
// ═════════════════════════════════════════════════════════════════════════════

// Richer stub that captures the full request signature (path, method, query)
// so the advisor tests can assert on POST vs GET, query strings, etc. Same
// shape as `loadWithStubs` above but with explicit request-object capture.
function loadWithRichStubs({ requests = [], responder = () => [] }) {
  for (const k of [
    require.resolve("../api/client"),
    require.resolve("./_session"),
    require.resolve("./_connections"),
    require.resolve("./indexes"),
  ]) {
    delete require.cache[k];
  }

  const apiKey = require.resolve("../api/client");
  require.cache[apiKey] = {
    id: apiKey,
    filename: apiKey,
    loaded: true,
    exports: {
      ApiError: class ApiError extends Error { constructor(m, s) { super(m); this.status = s; } },
      async request(baseUrl, path, opts = {}) {
        const captured = { path, method: opts.method || "GET", query: opts.query || null };
        requests.push(captured);
        return responder(captured);
      },
    },
  };

  const sessKey = require.resolve("./_session");
  require.cache[sessKey] = {
    id: sessKey, filename: sessKey, loaded: true,
    exports: { resolveSession: () => ({ baseUrl: "http://test", token: "t", defaultConnection: null }) },
  };

  const connKey = require.resolve("./_connections");
  require.cache[connKey] = {
    id: connKey, filename: connKey, loaded: true,
    exports: {
      resolveConnectionId: async () => "conn-abc",
      listConnections: async () => [],
    },
  };

  return require("./indexes");
}

function captureStreams() {
  let out = "";
  let err = "";
  return {
    stdout: { write: (s) => { out += s; } },
    stderr: { write: (s) => { err += s; } },
    out: () => out,
    err: () => err,
  };
}

// ─── top ───────────────────────────────────────────────────────────────────

test("indexes top renders workload-weighted summary with net-benefit + evidence", async () => {
  const indexes = loadWithRichStubs({
    responder: ({ path }) => {
      assert.match(path, /\/index-recommendations\/conn-abc\/top$/);
      return [{
        id: "rec-1",
        tableName: "orders",
        columnNames: "customer_id,status",
        kind: "CREATE_INDEX",
        priority: "HIGH",
        occurrenceCount: 4,
        netBenefitMs: 4823000,
        evidenceCount: 3,
        reason: "Workload-weighted composite.",
        hypopgBeforeCost: 1000,
        hypopgAfterCost: 250,
        hypopgReductionPct: 75,
        topEvidence: [
          { calls: 4500, meanExecTimeMs: 850, totalExecTimeMs: 3825000, role: "WHERE_EQ" },
        ],
      }];
    },
  });
  const s = captureStreams();
  await indexes.run(opts(["top", "--connection", "mylocalpg"]), s);
  assert.match(s.out(), /\[CREATE\] orders\(customer_id,status\)/);
  assert.match(s.out(), /seen 4×/);
  assert.match(s.out(), /net=1\.3h saved/);
  assert.match(s.out(), /HypoPG cost: 1000 → 250 \(−75\.0%\)/);
  assert.match(s.out(), /id: rec-1/);
  assert.match(s.out(), /top evidence: 4500 calls/);
});

test("indexes top renders an empty-state message", async () => {
  const indexes = loadWithRichStubs({ responder: () => [] });
  const s = captureStreams();
  await indexes.run(opts(["top", "--connection", "c1"]), s);
  assert.match(s.out(), /No pending index recommendations/);
  assert.match(s.out(), /indexes refresh/); // points at the consolidated namespace, not the deprecated one
});

test("indexes top --limit clamps to [1, 50]", async () => {
  const requests = [];
  const indexes = loadWithRichStubs({ requests, responder: () => [] });
  const s = captureStreams();
  await indexes.run(opts(["top", "--connection", "c1", "--limit", "999"]), s);
  await indexes.run(opts(["top", "--connection", "c1", "--limit", "0"]), s);
  await indexes.run(opts(["top", "--connection", "c1", "--limit", "10"]), s);
  assert.equal(requests[0].query.limit, 50);
  assert.equal(requests[1].query.limit, 1);
  assert.equal(requests[2].query.limit, 10);
});

test("indexes top --json passes the raw payload through", async () => {
  const indexes = loadWithRichStubs({
    responder: () => [{ id: "rec-1", tableName: "orders" }],
  });
  const s = captureStreams();
  await indexes.run(opts(["top", "--connection", "c1", "--json"]), s);
  const parsed = JSON.parse(s.out());
  assert.equal(parsed[0].id, "rec-1");
});

// ─── apply (the one mutation) ──────────────────────────────────────────────

test("indexes apply --mode apply without --confirm refuses up-front (no API call)", async () => {
  const requests = [];
  const indexes = loadWithRichStubs({ requests, responder: () => ({}) });
  const s = captureStreams();
  await assert.rejects(
    () => indexes.run(opts(["apply", "rec-1", "--mode", "apply"]), s),
    /Re-run with --confirm/,
  );
  assert.equal(requests.length, 0, "API should not be hit without --confirm");
});

test("indexes apply --mode dry-run defaults concurrent=true and surfaces planner-cost delta", async () => {
  const requests = [];
  const indexes = loadWithRichStubs({
    requests,
    responder: ({ path, method, query }) => {
      assert.equal(path, "/index-recommendations/rec-1/apply");
      assert.equal(method, "POST");
      assert.deepEqual(query, { mode: "DRY_RUN", confirm: false, concurrent: true });
      return {
        recommendationId: "rec-1",
        executedDdl: "CREATE INDEX idx_o_status ON orders (status);",
        mode: "DRY_RUN",
        status: "OK",
        beforeCost: 1000,
        afterCost: 250,
        costReductionPct: 75,
        samples: [{ fingerprint: "abc123def456", beforeCost: 1000, afterCost: 250 }],
        message: "DRY_RUN complete — planner cost −75.0% (1000 → 250)",
      };
    },
  });
  const s = captureStreams();
  await indexes.run(opts(["apply", "rec-1", "--mode", "dry-run"]), s);
  assert.match(s.out(), /\[DRY_RUN\] OK/);
  assert.match(s.out(), /planner cost: 1000 → 250 \(−75\.0%\)/);
  assert.match(s.out(), /fp=abc123def456 cost 1000 → 250/);
});

test("indexes apply --no-concurrent passes concurrent=false to the backend", async () => {
  const requests = [];
  const indexes = loadWithRichStubs({
    requests,
    responder: () => ({ mode: "APPLY", status: "OK", executedDdl: "CREATE INDEX …" }),
  });
  const s = captureStreams();
  await indexes.run(
    opts(["apply", "rec-1", "--mode", "apply", "--confirm", "--no-concurrent"]),
    s,
  );
  assert.deepEqual(requests[0].query, {
    mode: "APPLY",
    confirm: true,
    concurrent: false,
  });
});

test("indexes apply rejects unknown modes early (no API call)", async () => {
  const requests = [];
  const indexes = loadWithRichStubs({ requests, responder: () => ({}) });
  const s = captureStreams();
  await assert.rejects(
    () => indexes.run(opts(["apply", "rec-1", "--mode", "bogus"]), s),
    /Unknown mode/,
  );
  assert.equal(requests.length, 0);
});

// ─── refresh / dismiss / show ──────────────────────────────────────────────

test("indexes refresh POSTs to /generate and surfaces the count", async () => {
  const indexes = loadWithRichStubs({
    responder: ({ path, method }) => {
      assert.equal(path, "/index-recommendations/generate/conn-abc");
      assert.equal(method, "POST");
      return { success: true, count: 12, message: "Generated 12 index recommendations" };
    },
  });
  const s = captureStreams();
  await indexes.run(opts(["refresh", "--connection", "mylocalpg"]), s);
  assert.match(s.out(), /Refresh complete: 12 candidate/);
});

test("indexes dismiss issues PUT /{id}/dismiss", async () => {
  const requests = [];
  const indexes = loadWithRichStubs({ requests, responder: () => ({}) });
  const s = captureStreams();
  await indexes.run(opts(["dismiss", "rec-9"]), s);
  assert.equal(requests[0].path, "/index-recommendations/rec-9/dismiss");
  assert.equal(requests[0].method, "PUT");
  assert.match(s.out(), /Dismissed recommendation rec-9/);
});

test("indexes show <id> requires --connection", async () => {
  const indexes = loadWithRichStubs({});
  const s = captureStreams();
  await assert.rejects(
    () => indexes.run(opts(["show", "rec-1"]), s),
    /needs --connection/,
  );
});

// Keep the suite hermetic — flush the stubs back so later test files that
// require these modules don't pick up our fakes.
test.after(() => {
  for (const k of [
    require.resolve("../api/client"),
    require.resolve("./_session"),
    require.resolve("./_connections"),
    require.resolve("./indexes"),
  ]) {
    delete require.cache[k];
  }
});

// Quieten the unused-Module warning — we may use it later.
void Module;
