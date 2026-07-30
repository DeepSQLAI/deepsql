#!/usr/bin/env node

/**
 * MCP Comparison Test Suite
 *
 * Runs 15 prompts across 3 product dimensions through both:
 *   - Brain (get_brain_context) — DeepSQL retrieval context
 *   - Direct DB (execute_sql) — raw SQL against target database
 *
 * Usage:
 *   DEEPSQL_AUTH_TOKEN=<jwt> node mcp/test-mcp-comparison.js [--suite <path>] [--dimension <1|2|3|building|monitoring|bi|all>] [--mode <brain|direct|both>]
 *
 * Environment:
 *   DEEPSQL_API_BASE_URL  (default: http://localhost:8080/api/)
 *   DEEPSQL_AUTH_TOKEN     (required)
 *   DEEPSQL_MCP_TIMEOUT_MS (default: 120000)
 *   TEST_CONNECTION_ID     (recommended; otherwise falls back to suite/default ID)
 *   TEST_CONNECTION_NAME   (optional display name override)
 */

const { spawn } = require("child_process");
const fs = require("fs");
const path = require("path");

const DEFAULT_SUITE_PATH = path.join(
  __dirname,
  "..",
  "tests",
  "suites",
  "mcp",
  "aws_sf_prod-product-suite.json",
);
const LEGACY_CONNECTION_ID = "0b8c8fc2-aba6-4a5a-886d-5f5e08ee9cbe";

function getArgValue(args, name, fallback) {
  const index = args.indexOf(name);
  return index >= 0 && index + 1 < args.length ? args[index + 1] : fallback;
}

function resolveSuitePath(suiteArg) {
  if (!suiteArg) {
    return DEFAULT_SUITE_PATH;
  }
  return path.isAbsolute(suiteArg)
    ? suiteArg
    : path.resolve(process.cwd(), suiteArg);
}

function loadSuite(suitePath) {
  const raw = JSON.parse(fs.readFileSync(suitePath, "utf8"));
  if (!Array.isArray(raw.testCases) || raw.testCases.length === 0) {
    throw new Error(`Suite ${suitePath} does not contain any test cases.`);
  }

  const seenIds = new Set();
  for (const testCase of raw.testCases) {
    if (!testCase.id || !testCase.dimension || !testCase.brain?.question || !testCase.direct?.sql) {
      throw new Error(`Suite ${suitePath} contains an invalid test case: ${JSON.stringify(testCase)}`);
    }
    if (seenIds.has(testCase.id)) {
      throw new Error(`Suite ${suitePath} contains duplicate test case id '${testCase.id}'.`);
    }
    seenIds.add(testCase.id);
  }

  return raw;
}

function dimensionAliases(testCases) {
  const dimensions = [...new Set(testCases.map((testCase) => testCase.dimension))];
  const aliases = new Map();

  dimensions.forEach((dimension, index) => {
    aliases.set(String(index + 1), dimension);
    aliases.set(dimension.toLowerCase(), dimension);

    const normalized = dimension.toLowerCase().replace(/[^a-z0-9]+/g, "-");
    aliases.set(normalized, dimension);

    if (normalized.includes("building")) {
      aliases.set("building", dimension);
      aliases.set("product-building", dimension);
      aliases.set("development", dimension);
      aliases.set("product-development", dimension);
    } else if (normalized.includes("monitoring")) {
      aliases.set("monitoring", dimension);
      aliases.set("product-monitoring", dimension);
    } else if (normalized.includes("business-intelligence")) {
      aliases.set("bi", dimension);
      aliases.set("business-intelligence", dimension);
      aliases.set("business", dimension);
    }
  });

  return aliases;
}

// ── MCP stdio transport ─────────────────────────────────────────────────────

class McpTestClient {
  constructor(connectionId) {
    this.connectionId = connectionId;
    this.reqId = 0;
    this.buf = Buffer.alloc(0);
    this.pending = new Map();
    this.server = null;
  }

  start() {
    return new Promise((resolve, reject) => {
      this.server = spawn(
        process.execPath,
        [path.join(__dirname, "deepsql-phase1-server.js")],
        { env: process.env, stdio: ["pipe", "pipe", "pipe"] },
      );
      this.server.stdout.on("data", (c) => {
        this.buf = Buffer.concat([this.buf, c]);
        this.drain();
      });
      this.server.stderr.on("data", () => {});
      this.server.on("error", reject);

      // Initialize
      this.call("initialize", {
        protocolVersion: "2025-03-26",
        capabilities: {},
        clientInfo: { name: "mcp-test-suite", version: "1.0" },
      }).then((res) => {
        // Send initialized notification
        const n = JSON.stringify({
          jsonrpc: "2.0",
          method: "notifications/initialized",
        });
        this.server.stdin.write(
          `Content-Length: ${Buffer.byteLength(n)}\r\n\r\n${n}`,
        );
        resolve(res);
      });
    });
  }

  call(method, params = {}) {
    return new Promise((resolve) => {
      const id = ++this.reqId;
      this.pending.set(id, resolve);
      const body = JSON.stringify({ jsonrpc: "2.0", id, method, params });
      this.server.stdin.write(
        `Content-Length: ${Buffer.byteLength(body, "utf8")}\r\n\r\n${body}`,
      );
    });
  }

  drain() {
    while (true) {
      const i = this.buf.indexOf("\r\n\r\n");
      if (i < 0) return;
      const m = this.buf.slice(0, i).toString().match(/Content-Length:\s*(\d+)/i);
      if (!m) {
        this.buf = this.buf.slice(i + 4);
        continue;
      }
      const len = parseInt(m[1]);
      if (this.buf.length < i + 4 + len) return;
      const msg = JSON.parse(this.buf.slice(i + 4, i + 4 + len).toString());
      this.buf = this.buf.slice(i + 4 + len);
      const cb = this.pending.get(msg.id);
      if (cb) {
        this.pending.delete(msg.id);
        cb(msg);
      }
    }
  }

  async getBrainContext(question) {
    const res = await this.call("tools/call", {
      name: "get_brain_context",
      arguments: { connectionId: this.connectionId, question },
    });
    return res.result;
  }

  async executeReadonlySql(sql) {
    const res = await this.call("tools/call", {
      name: "execute_sql",
      arguments: { connectionId: this.connectionId, query: sql, limit: 20 },
    });
    return res.result;
  }

  stop() {
    if (this.server) {
      this.server.stdin.end();
      this.server.kill();
    }
  }
}

// ── Result formatting ───────────────────────────────────────────────────────

function truncate(str, max = 200) {
  if (!str) return "(empty)";
  return str.length > max ? str.substring(0, max) + "..." : str;
}

function formatBrainResult(result) {
  if (result?.isError) {
    return { status: "ERROR", answer: result.content?.[0]?.text || "unknown" };
  }
  const sc = result?.structuredContent || {};
  const resultCount =
    sc.resultCount ??
    sc.totalResults ??
    (Array.isArray(sc.results) ? sc.results.length : null);
  const tableCount = Array.isArray(sc.ragTableNames)
    ? sc.ragTableNames.length
    : sc.ragTableNames && typeof sc.ragTableNames === "object"
      ? Object.keys(sc.ragTableNames).length
      : Array.isArray(sc.tablesCovered)
        ? sc.tablesCovered.length
        : null;
  const typeCount =
    sc.typeCounts && typeof sc.typeCounts === "object"
      ? Object.values(sc.typeCounts).reduce((sum, value) => sum + Number(value || 0), 0)
      : 0;
  const hasBrainData =
    !sc.skipped &&
    ((resultCount != null && resultCount > 0) ||
      (tableCount != null && tableCount > 0) ||
      typeCount > 0);
  return {
    status: "OK",
    chatId: sc.chatId || null,
    success: sc.success,
    answer: sc.message || result?.content?.[0]?.text || "(empty)",
    sql: sc.sql || null,
    resultCount,
    tableCount,
    hasBrainData,
  };
}

function formatDirectResult(result) {
  if (result?.isError) {
    return {
      status: "ERROR",
      error: result.content?.[0]?.text || "unknown",
      rowCount: 0,
    };
  }
  const sc = result?.structuredContent || {};
  const data = sc?.result || sc?.data || sc;
  const rows = data?.rows || data?.results || [];
  return {
    status: "OK",
    rowCount: data?.rowCount ?? rows.length ?? 0,
    columns: data?.columns || [],
    sampleRows: rows.slice(0, 3),
    summary: result?.content?.[0]?.text || "",
  };
}

// ── Main ────────────────────────────────────────────────────────────────────

async function main() {
  const args = process.argv.slice(2);
  const suitePath = resolveSuitePath(getArgValue(args, "--suite", null));
  const suite = loadSuite(suitePath);
  const dimArg = getArgValue(args, "--dimension", "all").toLowerCase();
  const modeArg = getArgValue(args, "--mode", "both");
  const aliases = dimensionAliases(suite.testCases);
  if (dimArg !== "all" && !aliases.has(dimArg)) {
    console.error("Unknown dimension. Use --dimension 1|2|3|building|monitoring|bi|all");
    process.exit(1);
  }
  if (!["brain", "direct", "both"].includes(modeArg)) {
    console.error("Unknown mode. Use --mode brain|direct|both");
    process.exit(1);
  }
  const selectedDimension = dimArg === "all" ? null : aliases.get(dimArg);
  const selectedTests = selectedDimension
    ? suite.testCases.filter((testCase) => testCase.dimension === selectedDimension)
    : suite.testCases;

  if (selectedTests.length === 0) {
    console.error("No tests matched. Use --dimension 1|2|3|building|monitoring|bi|all");
    process.exit(1);
  }

  const runBrain = modeArg === "brain" || modeArg === "both";
  const runDirect = modeArg === "direct" || modeArg === "both";
  const connectionId =
    process.env.TEST_CONNECTION_ID || suite.connectionId || LEGACY_CONNECTION_ID;
  const connectionDisplayName =
    process.env.TEST_CONNECTION_NAME ||
    suite.connectionDisplayName ||
    suite.connectionProfile ||
    "configured test connection";

  console.log("╔═══════════════════════════════════════════════════════════════╗");
  console.log("║   DeepSQL MCP Comparison Test Suite                          ║");
  console.log(
    `║   Suite: ${truncate(suite.suiteName || path.basename(suitePath), 48)}`.padEnd(
      64,
    ) + "║",
  );
  console.log(
    `║   Connection: ${truncate(connectionDisplayName, 43)}`.padEnd(64) + "║",
  );
  console.log(
    `║   Tests: ${selectedTests.length} prompts | Mode: ${modeArg}`.padEnd(64) + "║",
  );
  console.log("╚═══════════════════════════════════════════════════════════════╝\n");

  const client = new McpTestClient(connectionId);
  await client.start();
  console.log("MCP server initialized.\n");

  const results = [];
  let passed = 0;
  let failed = 0;
  let currentDimension = "";

  for (const tc of selectedTests) {
    if (tc.dimension !== currentDimension) {
      currentDimension = tc.dimension;
      console.log(
        `\n${"═".repeat(65)}\n  ${currentDimension.toUpperCase()}\n${"═".repeat(65)}`,
      );
    }

    const entry = {
      id: tc.id,
      dimension: tc.dimension,
      brainQuestion: tc.brain.question,
      directLabel: tc.direct.label,
      directSql: tc.direct.sql,
      brain: null,
      direct: null,
      expectBrainRicher: tc.expectBrainRicher,
      reason: tc.reason,
      verdict: null,
    };

    console.log(`\n┌─ ${tc.id}: ${truncate(tc.brain.question, 55)}`);

    // Brain test
    if (runBrain) {
      try {
        const brainRaw = await client.getBrainContext(tc.brain.question);
        entry.brain = formatBrainResult(brainRaw);
        const indicator = entry.brain.hasBrainData ? "✅ Brain data" : "⚠️  No brain data";
        console.log(`│  [BRAIN]  ${indicator}`);
        console.log(`│           ${truncate(entry.brain.answer, 80)}`);
      } catch (err) {
        entry.brain = { status: "ERROR", answer: err.message };
        console.log(`│  [BRAIN]  ❌ ${err.message}`);
      }
    }

    // Direct DB test
    if (runDirect) {
      try {
        const directRaw = await client.executeReadonlySql(tc.direct.sql);
        entry.direct = formatDirectResult(directRaw);
        console.log(
          `│  [DIRECT] ${entry.direct.status === "OK" ? "📊" : "❌"} ${entry.direct.rowCount} rows | ${entry.direct.columns.slice(0, 4).join(", ")}`,
        );
      } catch (err) {
        entry.direct = { status: "ERROR", error: err.message, rowCount: 0 };
        console.log(`│  [DIRECT] ❌ ${err.message}`);
      }
    }

    // Verdict
    if (runBrain && runDirect) {
      const brainOk = entry.brain?.status === "OK" && entry.brain?.hasBrainData;
      const directOk = entry.direct?.status === "OK" && entry.direct?.rowCount > 0;
      if (brainOk && !directOk) entry.verdict = "BRAIN_WINS";
      else if (!brainOk && directOk) entry.verdict = "DIRECT_WINS";
      else if (brainOk && directOk) entry.verdict = "BRAIN_RICHER";
      else entry.verdict = "BOTH_LIMITED";
      console.log(`│  [VERDICT] ${entry.verdict} — ${tc.reason}`);
    }

    const testPassed =
      (runBrain ? entry.brain?.status === "OK" : true) &&
      (runDirect ? entry.direct?.status === "OK" : true);
    if (testPassed) passed++;
    else failed++;

    console.log(`└─ ${testPassed ? "PASS" : "FAIL"}`);
    results.push(entry);
  }

  client.stop();

  // ── Summary ─────────────────────────────────────────────────────────────
  console.log(`\n${"═".repeat(65)}`);
  console.log("  SUMMARY");
  console.log(`${"═".repeat(65)}`);
  console.log(`  Total: ${results.length} | Passed: ${passed} | Failed: ${failed}`);

  if (runBrain && runDirect) {
    const verdicts = {};
    for (const r of results) {
      verdicts[r.verdict] = (verdicts[r.verdict] || 0) + 1;
    }
    console.log("\n  Verdict breakdown:");
    for (const [v, count] of Object.entries(verdicts)) {
      console.log(`    ${v}: ${count}`);
    }
  }

  console.log(`\n  Brain data available: ${results.filter((r) => r.brain?.hasBrainData).length}/${results.length}`);
  console.log(`  Direct DB returned rows: ${results.filter((r) => r.direct?.rowCount > 0).length}/${results.length}`);
  console.log(`${"═".repeat(65)}\n`);

  // ── Write JSON results ──────────────────────────────────────────────────
  const outputPath = path.resolve(
    __dirname,
    "..",
    process.env.MCP_COMPARISON_REPORT_PATH ||
      suite.resultsOutputPath ||
      "output/test-reports/mcp-comparison-test-results.json",
  );
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(
    outputPath,
    JSON.stringify(
      {
        testDate: new Date().toISOString(),
        suiteName: suite.suiteName || null,
        suitePath,
        connection: connectionId,
        connectionName: connectionDisplayName,
        mode: modeArg,
        totalTests: results.length,
        passed,
        failed,
        results,
      },
      null,
      2,
    ),
  );
  console.log(`Results written to ${outputPath}`);

  process.exit(failed > 0 ? 1 : 0);
}

main().catch((err) => {
  console.error("Fatal:", err);
  process.exit(1);
});
