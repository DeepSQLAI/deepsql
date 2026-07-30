"use strict";

/**
 * `deepsql growth` — table growth analytics.
 *
 * Backend has a full `/growth-monitoring/*` controller with size/row time
 * series, anomaly detection (sudden growth spikes flagged by severity),
 * predictions, and per-table alert configs. Until this command shipped,
 * none of that was reachable from the CLI or the MCP — the agent and
 * terminal users could only get to it by hand-rolling SQL against
 * `table_stats_history`. Now they can ask DeepSQL directly.
 *
 *   trends    [--connection <c>] [--table <t>] [--days N=30]    GET    /growth-monitoring/trends/{cid}
 *   history   [--connection <c>] [--table <t>] [--days N=7]     GET    /growth-monitoring/history/{cid}
 *   anomalies [--connection <c>] [--table <t>] [--unack] [--days N=30]  GET /growth-monitoring/anomalies/{cid}
 *   ack       <anomalyId>                                       POST   /growth-monitoring/anomalies/{id}/acknowledge
 *   capture   [--connection <c>]                                POST   /growth-monitoring/capture/{cid}  (admin)
 *   config    show [--connection <c>] [--table <t>]             GET    /growth-monitoring/config/{cid}
 *   config    set --file <path>                                 POST   /growth-monitoring/config       (admin)
 *
 * `capture` and `config set` are the only mutations; both are admin-level
 * but neither writes user data, so we don't gate them behind --confirm.
 * The other subcommands are pure reads.
 */

const fs = require("node:fs");
const { ApiError, request } = require("../api/client");
const { resolveSession } = require("./_session");
const { resolveConnectionId } = require("./_connections");

const SUBCOMMANDS = {
  trends: cmdTrends,
  history: cmdHistory,
  anomalies: cmdAnomalies,
  ack: cmdAck,
  capture: cmdCapture,
  config: cmdConfig,
};

async function run(opts, io = {}) {
  const sub = opts.positional[0] || "trends";
  const handler = SUBCOMMANDS[sub];
  if (!handler) {
    throw new Error(
      `Unknown growth subcommand: ${sub}. ` +
        `Try one of: trends, history, anomalies, ack <id>, capture, config show|set.`,
    );
  }
  return wrap(handler)({ ...opts, positional: opts.positional.slice(1) }, io);
}

function wrap(handler) {
  return async (opts, io) => {
    try {
      return await handler(opts, io);
    } catch (err) {
      if (err instanceof ApiError && err.status === 403) {
        throw new Error(
          "Access denied — growth-monitoring requires permissions on this connection.",
        );
      }
      if (err instanceof ApiError && err.status === 404) {
        throw new Error(err.message || "Resource not found.");
      }
      throw err;
    }
  };
}

// ─── trends ────────────────────────────────────────────────────────────────
//
// The most agent-friendly subcommand: rolls up the time series into per-
// table headlines ("orders: +18% / 30d, 240MB → 285MB"). Default subcommand
// because "what's growing?" is the most common question.

async function cmdTrends(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  const days = clampDays(opts.days, 30);

  const response = await request(
    session.baseUrl,
    `/growth-monitoring/trends/${encodeURIComponent(connectionId)}`,
    {
      token: session.token,
      query: { tableName: opts.table || null, days },
    },
  );

  if (opts.json) {
    stdout.write(`${JSON.stringify(response, null, 2)}\n`);
    return;
  }

  const trends = response?.trends || {};
  const sizeOverTime = Array.isArray(trends.sizeOverTime) ? trends.sizeOverTime : [];
  if (sizeOverTime.length === 0) {
    stdout.write(
      `No growth data for this connection in the last ${days} day(s). ` +
        `Run \`deepsql growth capture --connection <c>\` to take a fresh ` +
        `snapshot if monitoring is enabled, or wait for the scheduled cycle.\n`,
    );
    return;
  }

  // Group by table and compute first / last snapshot per table.
  const byTable = new Map();
  for (const point of sizeOverTime) {
    const t = point.table || "(unknown)";
    if (!byTable.has(t)) byTable.set(t, []);
    byTable.get(t).push(point);
  }

  // Sort tables by absolute growth (largest first) so the most-changed
  // tables sit at the top of the output — what an agent or operator
  // actually needs to see first.
  const rows = [];
  for (const [table, points] of byTable.entries()) {
    points.sort((a, b) => String(a.timestamp).localeCompare(String(b.timestamp)));
    const first = points[0];
    const last = points[points.length - 1];
    const firstBytes = first?.sizeBytes ?? 0;
    const lastBytes = last?.sizeBytes ?? 0;
    const deltaBytes = lastBytes - firstBytes;
    const deltaPct = firstBytes > 0 ? (deltaBytes / firstBytes) * 100 : null;
    rows.push({
      table,
      firstBytes,
      lastBytes,
      deltaBytes,
      deltaPct,
      snapshots: points.length,
    });
  }
  rows.sort((a, b) => Math.abs(b.deltaBytes) - Math.abs(a.deltaBytes));

  stdout.write(
    `${rows.length} table${rows.length === 1 ? "" : "s"} with growth data ` +
      `over the last ${days} day(s):\n\n`,
  );
  for (const r of rows) {
    const arrow = r.deltaBytes >= 0 ? "↑" : "↓";
    const pct = r.deltaPct != null ? signedPct(r.deltaPct) : "n/a";
    stdout.write(
      `  ${arrow} ${r.table.padEnd(40)} ` +
        `${formatBytes(r.firstBytes)} → ${formatBytes(r.lastBytes)} ` +
        `(${pct}, ${formatBytes(Math.abs(r.deltaBytes))} ${arrow === "↑" ? "added" : "freed"}, ` +
        `${r.snapshots} snapshots)\n`,
    );
  }
  stdout.write(
    `\nUse \`deepsql growth history --table <name>\` for per-snapshot detail, ` +
      `or \`deepsql growth anomalies\` for sudden spikes.\n`,
  );
}

// ─── history ───────────────────────────────────────────────────────────────

async function cmdHistory(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  const days = clampDays(opts.days, 7);

  const response = await request(
    session.baseUrl,
    `/growth-monitoring/history/${encodeURIComponent(connectionId)}`,
    {
      token: session.token,
      query: { tableName: opts.table || null, days },
    },
  );

  if (opts.json) {
    stdout.write(`${JSON.stringify(response, null, 2)}\n`);
    return;
  }

  const history = Array.isArray(response?.history) ? response.history : [];
  if (history.length === 0) {
    stdout.write(`No history rows in the last ${days} day(s).\n`);
    return;
  }

  stdout.write(`${history.length} snapshot${history.length === 1 ? "" : "s"} ` +
    `over ${days} day(s):\n\n`);
  for (const h of history) {
    const ts = (h.snapshotTimestamp || "").substring(0, 19).replace("T", " ");
    const sz = formatBytes(h.sizeBytes);
    const rows = h.rowCount != null ? `${formatNumber(h.rowCount)} rows` : "rows: n/a";
    const growth = h.sizeGrowthPercent != null
      ? ` Δ ${signedPct(h.sizeGrowthPercent)}`
      : "";
    const bloat = h.bloatPercent != null && h.bloatPercent > 0
      ? `, bloat ${h.bloatPercent.toFixed(1)}%`
      : "";
    stdout.write(`  ${ts}  ${h.tableName.padEnd(36)} ${sz.padStart(10)}  ${rows.padStart(16)}${growth}${bloat}\n`);
  }
}

// ─── anomalies ─────────────────────────────────────────────────────────────

async function cmdAnomalies(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  const days = clampDays(opts.days, 30);

  const response = await request(
    session.baseUrl,
    `/growth-monitoring/anomalies/${encodeURIComponent(connectionId)}`,
    {
      token: session.token,
      query: {
        tableName: opts.table || null,
        unacknowledgedOnly: opts.unack ? "true" : null,
        days,
      },
    },
  );

  if (opts.json) {
    stdout.write(`${JSON.stringify(response, null, 2)}\n`);
    return;
  }

  const anomalies = Array.isArray(response?.anomalies) ? response.anomalies : [];
  const stats = response?.statistics || {};

  if (anomalies.length === 0) {
    const scope = opts.unack ? "unacknowledged " : "";
    stdout.write(`No ${scope}anomalies in the last ${days} day(s).\n`);
    return;
  }

  const total = stats.total ?? anomalies.length;
  const crit = stats.critical ?? 0;
  const warn = stats.warning ?? 0;
  const unack = stats.unacknowledged ?? 0;
  stdout.write(`${total} anomal${total === 1 ? "y" : "ies"} ` +
    `(${crit} critical, ${warn} warning, ${unack} unacknowledged) ` +
    `over ${days} day(s):\n\n`);

  for (const a of anomalies) {
    const sev = (a.severity || "INFO").padEnd(8);
    const marker = sev.startsWith("CRITICAL") ? "✗" : sev.startsWith("WARNING") ? "⚠" : "ℹ";
    const ts = (a.detectionTimestamp || "").substring(0, 19).replace("T", " ");
    const type = a.anomalyType || "?";
    const sizeStr = a.previousSizeBytes != null && a.currentSizeBytes != null
      ? `${formatBytes(a.previousSizeBytes)} → ${formatBytes(a.currentSizeBytes)}`
      : "";
    const pct = a.sizeGrowthPercent != null ? ` (${signedPct(a.sizeGrowthPercent)})` : "";
    const ackMark = a.acknowledged ? " [acked]" : "";
    stdout.write(`  ${marker} [${sev.trim()}] ${ts}  ${a.tableName}  ${type}${ackMark}\n`);
    if (sizeStr) stdout.write(`      size: ${sizeStr}${pct}\n`);
    if (a.description) stdout.write(`      ${a.description}\n`);
    stdout.write(`      id: ${a.id}\n\n`);
  }

  if (unack > 0) {
    stdout.write(`Use \`deepsql growth ack <id>\` to acknowledge an anomaly.\n`);
  }
}

// ─── ack ───────────────────────────────────────────────────────────────────

async function cmdAck(opts, { stdout = process.stdout } = {}) {
  const anomalyId = opts.positional[0];
  if (!anomalyId) {
    throw new Error("Usage: deepsql growth ack <anomalyId>");
  }
  const session = resolveSession(opts);

  const response = await request(
    session.baseUrl,
    `/growth-monitoring/anomalies/${encodeURIComponent(anomalyId)}/acknowledge`,
    { method: "POST", token: session.token },
  );

  if (opts.json) {
    stdout.write(`${JSON.stringify(response, null, 2)}\n`);
    return;
  }
  if (response?.success) {
    stdout.write(`Acknowledged anomaly ${anomalyId}.\n`);
  } else {
    stdout.write(`${response?.message || "Acknowledge attempt did not return success."}\n`);
    process.exitCode = 1;
  }
}

// ─── capture ───────────────────────────────────────────────────────────────

async function cmdCapture(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);

  const response = await request(
    session.baseUrl,
    `/growth-monitoring/capture/${encodeURIComponent(connectionId)}`,
    { method: "POST", token: session.token },
  );

  if (opts.json) {
    stdout.write(`${JSON.stringify(response, null, 2)}\n`);
    return;
  }
  stdout.write(
    `${response?.message || "Snapshot capture requested."}\n` +
      `The job runs asynchronously on the backend; re-run ` +
      `\`deepsql growth trends\` in a minute or two to see the new data.\n`,
  );
}

// ─── config ────────────────────────────────────────────────────────────────
//
// Two flavors: `config show` (read) and `config set --file <p>` (admin
// write). The set form takes the full GrowthAlertConfiguration JSON
// body so we don't have to enumerate every threshold flag — the schema
// is wider than most CLI inputs warrant and JSON keeps us honest.

async function cmdConfig(opts, io = {}) {
  const action = opts.positional[0] || "show";
  if (action === "show") return cmdConfigShow(opts, io);
  if (action === "set") return cmdConfigSet(opts, io);
  throw new Error(`Unknown config action: ${action}. Try \`show\` or \`set --file <path>\`.`);
}

async function cmdConfigShow(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  const response = await request(
    session.baseUrl,
    `/growth-monitoring/config/${encodeURIComponent(connectionId)}`,
    { token: session.token, query: { tableName: opts.table || null } },
  );

  if (opts.json) {
    stdout.write(`${JSON.stringify(response, null, 2)}\n`);
    return;
  }

  const single = response?.configuration;
  const list = Array.isArray(response?.configurations) ? response.configurations : null;

  if (single) {
    renderConfig(stdout, single);
    return;
  }
  if (!list || list.length === 0) {
    stdout.write(
      "No growth-monitoring configurations for this connection. " +
        "Defaults are applied. To customize thresholds, " +
        "POST a GrowthAlertConfiguration JSON via " +
        "`deepsql growth config set --file <path>`.\n",
    );
    return;
  }
  stdout.write(`${list.length} alert configuration${list.length === 1 ? "" : "s"}:\n`);
  for (const c of list) {
    stdout.write("\n");
    renderConfig(stdout, c);
  }
}

async function cmdConfigSet(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  if (!opts.file) {
    throw new Error(
      "Usage: deepsql growth config set --file <path-to-config.json>\n" +
        "The JSON body must match GrowthAlertConfiguration (connectionId required).",
    );
  }
  let body;
  try {
    body = JSON.parse(fs.readFileSync(opts.file, "utf8"));
  } catch (err) {
    throw new Error(`Could not read/parse ${opts.file}: ${err.message}`);
  }
  if (!body.connectionId) {
    throw new Error("Config JSON must include a `connectionId` field.");
  }
  const session = resolveSession(opts);
  const response = await request(
    session.baseUrl,
    "/growth-monitoring/config",
    { method: "POST", token: session.token, json: body },
  );

  if (opts.json) {
    stdout.write(`${JSON.stringify(response, null, 2)}\n`);
    return;
  }
  if (response?.success) {
    stdout.write(`Saved growth-monitoring configuration.\n`);
    if (response.configuration) renderConfig(stdout, response.configuration);
  } else {
    stderr.write(`${response?.message || "Save did not return success."}\n`);
    process.exitCode = 1;
  }
}

// ─── helpers ───────────────────────────────────────────────────────────────

function clampDays(value, fallback) {
  if (value == null) return fallback;
  const n = Number.parseInt(value, 10);
  if (!Number.isFinite(n) || n < 1) return fallback;
  return Math.min(365, n);
}

function formatBytes(bytes) {
  if (bytes == null) return "?";
  const abs = Math.abs(bytes);
  if (abs < 1024) return `${bytes} B`;
  if (abs < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (abs < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  if (abs < 1024 * 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  return `${(bytes / (1024 * 1024 * 1024 * 1024)).toFixed(2)} TB`;
}

function formatNumber(n) {
  if (n == null) return "?";
  return Number(n).toLocaleString("en-US");
}

function signedPct(n) {
  if (n == null) return "n/a";
  const sign = n > 0 ? "+" : "";
  return `${sign}${Number(n).toFixed(1)}%`;
}

function renderConfig(stdout, c) {
  const scope = c.tableName ? `table=${c.tableName}` : "connection-wide";
  stdout.write(`Configuration (${scope}):\n`);
  if (c.percentageGrowthWarning != null) {
    stdout.write(`  growth %  warning=${c.percentageGrowthWarning}%  critical=${c.percentageGrowthCritical}%\n`);
  }
  if (c.absoluteGrowthWarningBytes != null) {
    stdout.write(`  growth bytes  warning=${formatBytes(c.absoluteGrowthWarningBytes)}  critical=${formatBytes(c.absoluteGrowthCriticalBytes)}\n`);
  }
  if (c.rowSpikeWarning != null) {
    stdout.write(`  row spike  warning=${formatNumber(c.rowSpikeWarning)}  critical=${formatNumber(c.rowSpikeCritical)}` +
      (c.rowSpikePercentage != null ? `  pct=${c.rowSpikePercentage}%` : "") + "\n");
  }
  if (c.zScoreThreshold != null) {
    stdout.write(`  z-score threshold: ${c.zScoreThreshold}\n`);
  }
  if (c.historicalWindowHours != null) {
    stdout.write(`  historical window: ${c.historicalWindowHours} hours\n`);
  }
  if (c.minHoursBetweenAlerts != null) {
    stdout.write(`  min hours between alerts: ${c.minHoursBetweenAlerts}\n`);
  }
  if (c.notificationChannels) stdout.write(`  channels: ${c.notificationChannels}\n`);
  if (c.emailRecipients) stdout.write(`  email: ${c.emailRecipients}\n`);
  if (c.webhookUrl) stdout.write(`  webhook: ${c.webhookUrl}\n`);
  stdout.write(`  enabled: ${c.isEnabled !== false}\n`);
}

module.exports = { run };
