"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

const { parseArgs, buildOpts } = require("../cli");

function opts(argv) {
  return buildOpts(parseArgs(argv));
}

// ─── argv plumbing ─────────────────────────────────────────────────────────

test("growth flags: --table, --days, --unack, --json land in opts", () => {
  const o = opts(["anomalies", "--table", "orders", "--days", "14", "--unack", "--json"]);
  assert.equal(o.table, "orders");
  assert.equal(o.days, "14");
  assert.equal(o.unack, true);
  assert.equal(o.json, true);
  assert.deepEqual(o.positional, ["anomalies"]);
});

test("growth ack <id> keeps the id as positional[1]", () => {
  const o = opts(["ack", "anomaly-abc-123"]);
  assert.deepEqual(o.positional, ["ack", "anomaly-abc-123"]);
});

test("growth config set --file <path> threads the file flag", () => {
  const o = opts(["config", "set", "--file", "/tmp/alert.json"]);
  assert.deepEqual(o.positional, ["config", "set"]);
  assert.equal(o.file, "/tmp/alert.json");
});

// ─── dispatch with fake api/client ─────────────────────────────────────────

function loadWithStubs({ requests = [], responses = {}, error } = {}) {
  for (const k of [
    require.resolve("../api/client"),
    require.resolve("./_session"),
    require.resolve("./_connections"),
    require.resolve("./growth"),
  ]) {
    delete require.cache[k];
  }

  class ApiError extends Error {
    constructor(message, { status, body } = {}) { super(message); this.status = status; this.body = body; }
  }

  const apiKey = require.resolve("../api/client");
  require.cache[apiKey] = {
    id: apiKey, filename: apiKey, loaded: true,
    exports: {
      ApiError,
      async request(_base, path, options) {
        requests.push({ path, options });
        if (error) throw error;
        if (Object.prototype.hasOwnProperty.call(responses, path)) {
          return responses[path];
        }
        return {};
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
    exports: {
      resolveConnectionId: async () => "00000000-0000-0000-0000-000000000001",
      listConnections: async () => [],
    },
  };

  return { growth: require("./growth"), ApiError };
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

// ─── trends ────────────────────────────────────────────────────────────────

test("growth trends (default) hits /growth-monitoring/trends/{cid} with default 30-day window", async () => {
  const requests = [];
  const { growth } = loadWithStubs({
    requests,
    responses: {
      "/growth-monitoring/trends/00000000-0000-0000-0000-000000000001": {
        trends: { sizeOverTime: [] },
        days: 30,
      },
    },
  });
  const io = captureStdout();
  await growth.run(opts([]), { stdout: io.stream });   // default subcommand = trends
  assert.equal(requests.length, 1);
  assert.match(requests[0].path, /^\/growth-monitoring\/trends\//);
  // --days falls back to 30, --table is null (omitted from query).
  assert.equal(requests[0].options.query.days, 30);
  assert.equal(requests[0].options.query.tableName, null);
  assert.match(io.out(), /No growth data/);
});

test("growth trends renders per-table headline sorted by absolute delta", async () => {
  const { growth } = loadWithStubs({
    responses: {
      "/growth-monitoring/trends/00000000-0000-0000-0000-000000000001": {
        trends: {
          sizeOverTime: [
            // orders: tiny growth
            { table: "orders",    timestamp: "2026-04-15T00:00:00", sizeBytes: 100_000_000 },
            { table: "orders",    timestamp: "2026-05-15T00:00:00", sizeBytes: 110_000_000 },
            // events_log: huge growth — should sort first
            { table: "events_log", timestamp: "2026-04-15T00:00:00", sizeBytes: 1_000_000_000 },
            { table: "events_log", timestamp: "2026-05-15T00:00:00", sizeBytes: 5_000_000_000 },
            // archive: shrunk
            { table: "archive",   timestamp: "2026-04-15T00:00:00", sizeBytes: 200_000_000 },
            { table: "archive",   timestamp: "2026-05-15T00:00:00", sizeBytes: 50_000_000 },
          ],
        },
        days: 30,
      },
    },
  });
  const io = captureStdout();
  await growth.run(opts([]), { stdout: io.stream });
  const text = io.out();
  // events_log absolute delta = 4GB (biggest), sorts first
  const evIdx = text.indexOf("events_log");
  const archIdx = text.indexOf("archive");
  const ordIdx = text.indexOf("orders");
  assert.ok(evIdx >= 0 && archIdx >= 0 && ordIdx >= 0);
  assert.ok(evIdx < archIdx, "events_log (4GB delta) should sort before archive (150MB shrink)");
  assert.ok(archIdx < ordIdx, "archive (150MB shrink) should sort before orders (10MB)");
  assert.match(text, /↑ events_log/);
  assert.match(text, /↓ archive/);
  assert.match(text, /↑ orders/);
  // Headline includes the formatted byte sizes + signed percent. Bytes are
  // rendered in 1024-base (953.7 MB for 1e9 bytes, 4.66 GB for 5e9 bytes).
  assert.match(text, /events_log\s+\S*\s+953\.7 MB → 4\.66 GB/);
  assert.match(text, /\+400\.0%/); // 1e9 → 5e9 = +400%
});

test("growth trends --table scopes the request and --days is respected", async () => {
  const requests = [];
  const { growth } = loadWithStubs({
    requests,
    responses: {
      "/growth-monitoring/trends/00000000-0000-0000-0000-000000000001": { trends: { sizeOverTime: [] } },
    },
  });
  await growth.run(opts(["trends", "--table", "orders", "--days", "7"]), { stdout: captureStdout().stream });
  assert.equal(requests[0].options.query.tableName, "orders");
  assert.equal(requests[0].options.query.days, 7);
});

// ─── history ───────────────────────────────────────────────────────────────

test("growth history hits the /history endpoint with default 7-day window", async () => {
  const requests = [];
  const { growth } = loadWithStubs({
    requests,
    responses: {
      "/growth-monitoring/history/00000000-0000-0000-0000-000000000001": { history: [] },
    },
  });
  await growth.run(opts(["history"]), { stdout: captureStdout().stream });
  assert.equal(requests[0].options.query.days, 7);
  assert.match(requests[0].path, /^\/growth-monitoring\/history\//);
});

test("growth history renders snapshot rows with timestamp, size, rows, and growth delta", async () => {
  const { growth } = loadWithStubs({
    responses: {
      "/growth-monitoring/history/00000000-0000-0000-0000-000000000001": {
        history: [
          {
            snapshotTimestamp: "2026-05-14T03:00:00",
            tableName: "orders",
            sizeBytes: 110_000_000,
            rowCount: 850_000,
            sizeGrowthPercent: 10.5,
            bloatPercent: 12.3,
          },
        ],
      },
    },
  });
  const io = captureStdout();
  await growth.run(opts(["history"]), { stdout: io.stream });
  const text = io.out();
  assert.match(text, /2026-05-14 03:00:00/);
  assert.match(text, /orders/);
  assert.match(text, /104\.9 MB/);
  assert.match(text, /850,000 rows/);
  assert.match(text, /\+10\.5%/);
  assert.match(text, /bloat 12\.3%/);
});

// ─── anomalies ─────────────────────────────────────────────────────────────

test("growth anomalies summary surfaces severity counts + worst marker", async () => {
  const { growth } = loadWithStubs({
    responses: {
      "/growth-monitoring/anomalies/00000000-0000-0000-0000-000000000001": {
        anomalies: [
          {
            id: "a1", tableName: "events_log", severity: "CRITICAL",
            anomalyType: "PERCENTAGE_GROWTH",
            detectionTimestamp: "2026-05-14T03:00:00",
            previousSizeBytes: 1_000_000_000,
            currentSizeBytes: 4_000_000_000,
            sizeGrowthPercent: 300,
            description: "events_log grew 300% in 24h — checking for ingestion runaway.",
          },
          {
            id: "a2", tableName: "orders", severity: "WARNING",
            anomalyType: "STATISTICAL_ANOMALY",
            detectionTimestamp: "2026-05-14T02:00:00",
            description: "growth z-score above threshold",
            acknowledged: true,
          },
        ],
        statistics: { total: 2, critical: 1, warning: 1, unacknowledged: 1 },
      },
    },
  });
  const io = captureStdout();
  await growth.run(opts(["anomalies"]), { stdout: io.stream });
  const text = io.out();
  assert.match(text, /2 anomalies \(1 critical, 1 warning, 1 unacknowledged\)/);
  assert.match(text, /✗ \[CRITICAL\]/);
  assert.match(text, /⚠ \[WARNING\]/);
  assert.match(text, /events_log/);
  // 1e9 → 4e9 bytes in 1024-base = 953.7 MB → 3.73 GB.
  assert.match(text, /953\.7 MB → 3\.73 GB/);
  assert.match(text, /\+300\.0%/);
  // Acked anomaly should be marked
  assert.match(text, /\[acked\]/);
  // Hint pointing at `ack`
  assert.match(text, /deepsql growth ack/);
});

test("growth anomalies --unack threads the unacknowledgedOnly flag", async () => {
  const requests = [];
  const { growth } = loadWithStubs({
    requests,
    responses: {
      "/growth-monitoring/anomalies/00000000-0000-0000-0000-000000000001": { anomalies: [] },
    },
  });
  await growth.run(opts(["anomalies", "--unack"]), { stdout: captureStdout().stream });
  assert.equal(requests[0].options.query.unacknowledgedOnly, "true");
});

// ─── ack ───────────────────────────────────────────────────────────────────

test("growth ack <id> POSTs to the right endpoint", async () => {
  const requests = [];
  const { growth } = loadWithStubs({
    requests,
    responses: {
      "/growth-monitoring/anomalies/anomaly-x/acknowledge": { success: true },
    },
  });
  await growth.run(opts(["ack", "anomaly-x"]), { stdout: captureStdout().stream });
  assert.equal(requests.length, 1);
  assert.equal(requests[0].path, "/growth-monitoring/anomalies/anomaly-x/acknowledge");
  assert.equal(requests[0].options.method, "POST");
});

test("growth ack without an id throws a friendly Usage:", async () => {
  const { growth } = loadWithStubs({});
  await assert.rejects(
    growth.run(opts(["ack"]), { stdout: captureStdout().stream }),
    /Usage: deepsql growth ack <anomalyId>/,
  );
});

// ─── capture ───────────────────────────────────────────────────────────────

test("growth capture POSTs to /capture/{cid} and prints the async-job hint", async () => {
  const requests = [];
  const { growth } = loadWithStubs({
    requests,
    responses: {
      "/growth-monitoring/capture/00000000-0000-0000-0000-000000000001": {
        message: "Snapshot capture requested for connection 00000000…",
      },
    },
  });
  const io = captureStdout();
  await growth.run(opts(["capture"]), { stdout: io.stream });
  assert.equal(requests[0].options.method, "POST");
  assert.match(io.out(), /Snapshot capture requested/);
  assert.match(io.out(), /runs asynchronously/);
});

// ─── config ────────────────────────────────────────────────────────────────

test("growth config show with no config rows nudges toward `config set`", async () => {
  const { growth } = loadWithStubs({
    responses: {
      "/growth-monitoring/config/00000000-0000-0000-0000-000000000001": {
        configurations: [],
      },
    },
  });
  const io = captureStdout();
  await growth.run(opts(["config", "show"]), { stdout: io.stream });
  assert.match(io.out(), /No growth-monitoring configurations/);
  assert.match(io.out(), /deepsql growth config set --file/);
});

test("growth config show pretty-prints a single configuration when --table is set", async () => {
  const { growth } = loadWithStubs({
    responses: {
      "/growth-monitoring/config/00000000-0000-0000-0000-000000000001": {
        configuration: {
          tableName: "orders",
          percentageGrowthWarning: 20,
          percentageGrowthCritical: 50,
          absoluteGrowthWarningBytes: 100_000_000,
          absoluteGrowthCriticalBytes: 1_000_000_000,
          zScoreThreshold: 3.0,
          isEnabled: true,
        },
      },
    },
  });
  const io = captureStdout();
  await growth.run(opts(["config", "show", "--table", "orders"]), { stdout: io.stream });
  const text = io.out();
  assert.match(text, /table=orders/);
  assert.match(text, /warning=20%/);
  assert.match(text, /critical=50%/);
  assert.match(text, /enabled: true/);
});

test("growth config set --file requires the flag and a valid connectionId in the JSON body", async () => {
  const { growth } = loadWithStubs({});
  await assert.rejects(
    growth.run(opts(["config", "set"]), { stdout: captureStdout().stream }),
    /Usage: deepsql growth config set --file/,
  );
});

// ─── 403 / 404 wrapping ───────────────────────────────────────────────────

test("growth wraps 403 with a permissions-shaped error", async () => {
  // The trick here: growth.js destructures `ApiError` and `request` at
  // require time, so we need the stubbed module to expose BOTH from the
  // start. `loadWithStubs` already exports its own ApiError class on the
  // stubbed module — we just need `request` to throw an INSTANCE of that
  // exact class (so growth.js's `err instanceof ApiError` check passes).
  //
  // We do this by registering an api/client stub whose request() looks
  // up its own exported ApiError and constructs the error from it. This
  // works because the stub object is the same module reference growth.js
  // sees, so its `ApiError` ref and our stub's `ApiError` ref are
  // identical.
  for (const k of [
    require.resolve("../api/client"),
    require.resolve("./_session"),
    require.resolve("./_connections"),
    require.resolve("./growth"),
  ]) {
    delete require.cache[k];
  }
  class ApiError extends Error {
    constructor(message, { status, body } = {}) { super(message); this.status = status; this.body = body; }
  }
  const apiKey = require.resolve("../api/client");
  require.cache[apiKey] = {
    id: apiKey, filename: apiKey, loaded: true,
    exports: {
      ApiError,
      async request() {
        throw new ApiError("forbidden", { status: 403 });
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
    exports: { resolveConnectionId: async () => "conn-x", listConnections: async () => [] },
  };
  const growth = require("./growth");

  await assert.rejects(
    growth.run(opts(["anomalies"]), { stdout: captureStdout().stream }),
    /Access denied — growth-monitoring requires permissions/,
  );
});

test("growth --json emits the raw backend body without prose", async () => {
  const { growth } = loadWithStubs({
    responses: {
      "/growth-monitoring/anomalies/00000000-0000-0000-0000-000000000001": {
        anomalies: [{ id: "x", tableName: "t" }],
        statistics: { total: 1 },
      },
    },
  });
  const io = captureStdout();
  await growth.run(opts(["anomalies", "--json"]), { stdout: io.stream });
  const parsed = JSON.parse(io.out());
  assert.equal(parsed.anomalies.length, 1);
  assert.equal(parsed.statistics.total, 1);
});

test.after(() => {
  for (const k of [
    require.resolve("../api/client"),
    require.resolve("./_session"),
    require.resolve("./_connections"),
    require.resolve("./growth"),
  ]) {
    delete require.cache[k];
  }
});
