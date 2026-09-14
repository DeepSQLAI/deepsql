"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

const { parseArgs, buildOpts } = require("../cli");

function opts(argv) {
  return buildOpts(parseArgs(argv));
}

function loadWithStubs({ responses = [], error } = {}) {
  for (const k of [
    require.resolve("../api/client"),
    require.resolve("./_session"),
    require.resolve("./_connections"),
    require.resolve("./migration"),
  ]) {
    delete require.cache[k];
  }

  class ApiError extends Error {
    constructor(message, { status, body } = {}) { super(message); this.status = status; this.body = body; }
  }

  const apiKey = require.resolve("../api/client");
  let i = 0;
  require.cache[apiKey] = {
    id: apiKey, filename: apiKey, loaded: true,
    exports: {
      ApiError,
      async request() {
        if (error) throw error;
        const r = responses[i] ?? {};
        i++;
        return r;
      },
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

  return { migration: require("./migration"), ApiError };
}

function captureStdout() {
  let out = ""; let err = "";
  return {
    stream: { write: (s) => { out += s; } },
    errStream: { write: (s) => { err += s; } },
    out: () => out,
    err: () => err,
  };
}

// ─── normal DANGER payload with multiple locks (FK case) ──────────────────

test("migration analyze renders a DANGER verdict with every lock entry, marked", async () => {
  const { migration } = loadWithStubs({
    responses: [{
      dialect: "postgres", verdict: "DANGER", safeToRun: false, dialectSupported: true,
      operation: "ADD FOREIGN KEY", table: "orders",
      locks: [
        { table: "orders", mode: "SHARE ROW EXCLUSIVE", blocks: ["writes"] },
        { table: "customers", mode: "ROW SHARE", blocks: [] },
      ],
      rewritesTable: false, tableRows: 1200000, tableSizeBytes: 500000000,
      estimatedDuration: "~2s", reason: "Validates existing rows against customers.",
      saferAlternative: "Add NOT VALID then VALIDATE CONSTRAINT separately.",
      docsUrl: "https://example.com/docs",
    }],
  });
  const io = captureStdout();
  await migration.run(
    opts(["analyze", "--connection", "c1", "--sql", "ALTER TABLE orders ADD FOREIGN KEY (customer_id) REFERENCES customers(id)"]),
    { stdout: io.stream, stderr: io.errStream },
  );
  const text = io.out();
  assert.match(text, /^✗ DANGER {2}ADD FOREIGN KEY on orders/);
  // Both lock entries must render — this is a two-table lock (FK also locks
  // the referenced table).
  assert.match(text, /lock {11}: orders SHARE ROW EXCLUSIVE \(blocks writes\)/);
  assert.match(text, /lock {11}: customers ROW SHARE/);
  assert.match(text, /table rows {5}: 1,200,000/);
  assert.match(text, /duration {7}: ~2s/);
  assert.match(text, /why {12}: Validates existing rows against customers\./);
  assert.match(text, /safer {10}: Add NOT VALID then VALIDATE CONSTRAINT separately\./);
  assert.match(text, /docs {11}: https:\/\/example\.com\/docs/);
  assert.doesNotMatch(text, /undefined|NaN|\bnull\b/);
});

// ─── UNKNOWN payload with a null table ─────────────────────────────────────

test("migration analyze on UNKNOWN prints the reason, never 'null'", async () => {
  const { migration } = loadWithStubs({
    responses: [{
      verdict: "UNKNOWN", table: null, operation: null,
      reason: "Statement could not be parsed as a supported DDL form.",
    }],
  });
  const io = captureStdout();
  await migration.run(
    opts(["analyze", "--connection", "c1", "--sql", "SOME NONSENSE DDL"]),
    { stdout: io.stream, stderr: io.errStream },
  );
  const text = io.out();
  assert.match(text, /^UNKNOWN — Statement could not be parsed as a supported DDL form\./);
  assert.match(text, /No analysis was possible/);
  assert.doesNotMatch(text, /undefined|NaN|\bnull\b/);
});

// ─── unknown-table-size sentinel (Long.MAX_VALUE) ──────────────────────────

test("migration analyze renders the Long.MAX_VALUE row-count sentinel as unknown, not a huge number", async () => {
  const { migration } = loadWithStubs({
    responses: [{
      verdict: "CAUTION", operation: "ADD COLUMN", table: "huge_table",
      locks: [{ table: "huge_table", mode: "ACCESS EXCLUSIVE", blocks: ["reads", "writes"] }],
      rewritesTable: true, tableRows: 9223372036854775807,
      estimatedDuration: "unknown", reason: "Could not measure table size.",
    }],
  });
  const io = captureStdout();
  await migration.run(
    opts(["analyze", "--connection", "c1", "--sql", "ALTER TABLE huge_table ADD COLUMN x int"]),
    { stdout: io.stream, stderr: io.errStream },
  );
  const text = io.out();
  assert.match(text, /table rows {5}: unknown — treated as large/);
  assert.doesNotMatch(text, /9,223,372,036,854/);
  assert.doesNotMatch(text, /undefined|NaN/);
});

test("migration analyze treats a JS-float-imprecise near-sentinel value the same way", async () => {
  // 9223372036854775807 does not round-trip through a JS double; a backend
  // that serializes Long.MAX_VALUE can come back as 9223372036854775808 or
  // similar depending on the JSON library. Any implausibly large value must
  // still read as "unknown", not print a fake precise-looking number.
  const { migration } = loadWithStubs({
    responses: [{
      verdict: "CAUTION", operation: "ADD COLUMN", table: "huge_table",
      locks: [],
      rewritesTable: true, tableRows: 9223372036854775808,
      reason: "Could not measure table size.",
    }],
  });
  const io = captureStdout();
  await migration.run(
    opts(["analyze", "--connection", "c1", "--sql", "ALTER TABLE huge_table ADD COLUMN x int"]),
    { stdout: io.stream },
  );
  assert.match(io.out(), /table rows {5}: unknown — treated as large/);
});

// ─── verdict markers ────────────────────────────────────────────────────────

test("migration analyze marks SAFE with no warning glyph", async () => {
  const { migration } = loadWithStubs({
    responses: [{ verdict: "SAFE", operation: "ADD COLUMN", table: "t", locks: [] }],
  });
  const io = captureStdout();
  await migration.run(opts(["analyze", "--connection", "c1", "--sql", "ALTER TABLE t ADD COLUMN y text"]), { stdout: io.stream });
  assert.match(io.out(), /^SAFE {2}ADD COLUMN on t/);
});

test("migration analyze marks FAILS the same as DANGER", async () => {
  const { migration } = loadWithStubs({
    responses: [{ verdict: "FAILS", operation: "ADD COLUMN", table: "t", locks: [], reason: "Column already exists." }],
  });
  const io = captureStdout();
  await migration.run(opts(["analyze", "--connection", "c1", "--sql", "ALTER TABLE t ADD COLUMN y text"]), { stdout: io.stream });
  assert.match(io.out(), /^✗ FAILS {2}ADD COLUMN on t/);
});

// ─── argv plumbing ──────────────────────────────────────────────────────────

test("migration analyze requires --sql", async () => {
  const { migration } = loadWithStubs({ responses: [{}] });
  await assert.rejects(
    migration.run(opts(["analyze", "--connection", "c1"]), { stdout: captureStdout().stream }),
    /Usage: deepsql migration analyze/,
  );
});

test("migration --json emits the raw backend body without prose", async () => {
  const { migration } = loadWithStubs({
    responses: [{ verdict: "SAFE", operation: "ADD COLUMN", table: "t" }],
  });
  const io = captureStdout();
  await migration.run(opts(["analyze", "--connection", "c1", "--sql", "ALTER TABLE t ADD COLUMN y text", "--json"]), { stdout: io.stream });
  const parsed = JSON.parse(io.out());
  assert.equal(parsed.verdict, "SAFE");
});

// ─── 403 / 404 wrapping ─────────────────────────────────────────────────────

test("migration wraps 403 with a permissions-shaped error", async () => {
  for (const k of [
    require.resolve("../api/client"),
    require.resolve("./_session"),
    require.resolve("./_connections"),
    require.resolve("./migration"),
  ]) {
    delete require.cache[k];
  }
  class RealApiError extends Error {
    constructor(message, { status, body } = {}) { super(message); this.status = status; this.body = body; }
  }
  const apiKey = require.resolve("../api/client");
  require.cache[apiKey] = {
    id: apiKey, filename: apiKey, loaded: true,
    exports: {
      ApiError: RealApiError,
      async request() { throw new RealApiError("forbidden", { status: 403 }); },
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
    exports: { resolveConnectionId: async () => "conn-x" },
  };
  const fresh = require("./migration");

  await assert.rejects(
    fresh.run(opts(["analyze", "--connection", "c1", "--sql", "ALTER TABLE t ADD COLUMN y text"]), { stdout: captureStdout().stream }),
    /Access denied — migration analysis requires permissions/,
  );
});

test("migration wraps 404 with the backend message", async () => {
  for (const k of [
    require.resolve("../api/client"),
    require.resolve("./_session"),
    require.resolve("./_connections"),
    require.resolve("./migration"),
  ]) {
    delete require.cache[k];
  }
  class RealApiError extends Error {
    constructor(message, { status, body } = {}) { super(message); this.status = status; this.body = body; }
  }
  const apiKey = require.resolve("../api/client");
  require.cache[apiKey] = {
    id: apiKey, filename: apiKey, loaded: true,
    exports: {
      ApiError: RealApiError,
      async request() { throw new RealApiError("Connection not found.", { status: 404 }); },
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
    exports: { resolveConnectionId: async () => "conn-x" },
  };
  const fresh = require("./migration");

  await assert.rejects(
    fresh.run(opts(["analyze", "--connection", "c1", "--sql", "ALTER TABLE t ADD COLUMN y text"]), { stdout: captureStdout().stream }),
    /Connection not found\./,
  );
});
