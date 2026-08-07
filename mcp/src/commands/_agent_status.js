"use strict";

// Best-effort onboarding data for the agent intro: the connections this token
// can see, plus per-connection "needs attention" suggestions for connections the
// user can configure (admin-level). Uses EXISTING backend endpoints only:
//   GET /connections                       (RBAC-scoped list, with canManageConfig)
//   GET /connections/{id}/init-status       (brain readiness: currentStage)
//   GET /slow-log-source/{id}               (slow-query log: enabled)
//
// Everything is wrapped in tight timeouts and swallows errors, so a slow or old
// backend (missing an endpoint) degrades to "show whatever we have" and never
// blocks the REPL.

const { request } = require("../api/client");
const { listConnections } = require("./_connections");

function withTimeout(p, ms) {
  return Promise.race([
    Promise.resolve(p),
    new Promise((_, reject) => setTimeout(() => reject(new Error("timeout")), ms)),
  ]);
}

// Derive config suggestions + a brain-recommendation count for one connection
// from its status endpoints. Returns { items: [...attention], recCount }.
async function checkConnection(session, c) {
  const out = [];
  const [init, slow, recs] = await Promise.all([
    request(session.baseUrl, `/connections/${encodeURIComponent(c.id)}/init-status`, {
      token: session.token,
    }).catch(() => null),
    request(session.baseUrl, `/slow-log-source/${encodeURIComponent(c.id)}`, {
      token: session.token,
    }).catch(() => null),
    // totalCount tracks the limit, so request enough to give an accurate-ish
    // "N to review" nudge (the brain command shows the full list).
    request(session.baseUrl, `/brain/notes/suggestions/${encodeURIComponent(c.id)}?limit=25`, {
      token: session.token,
    }).catch(() => null),
  ]);

  const stage = init && init.currentStage;
  const pct = (init && init.progressPercent) || 0;
  if (stage === "FAILED") {
    out.push({ conn: c.name, text: "brain initialization failed", fix: `deepsql connections init ${c.name}` });
  } else if (stage && stage !== "READY" && pct < 100) {
    // Skip near-done (100% but not yet flipped to READY) — don't nag.
    out.push({ conn: c.name, text: `brain still initializing (${pct}%)`, fix: `deepsql connections init ${c.name}` });
  }
  if (slow && slow.enabled !== true) {
    out.push({
      conn: c.name,
      text: "slow-query log not connected",
      fix: "set it up in the web UI → Slow Query Log",
    });
  }
  // Brain recommendations to review (only meaningful once the brain is trained).
  const recCount = stage !== "FAILED" && recs && typeof recs.totalCount === "number" ? recs.totalCount : 0;
  return { items: out, recCount };
}

// Returns { connections: [{name,dbType,canManage}], suggestions: [{conn,text,fix}] }.
async function loadIntroData(session, { timeoutMs = 2500 } = {}) {
  const data = { connections: [], suggestions: [], recommendationCount: 0, unreachable: null };

  // The connection list is the one fetch whose failure must NOT be swallowed.
  // It used to share a single catch with the suggestions below, so a server that
  // could not be reached at all left connections empty and the intro rendered
  // "No databases connected yet → deepsql connections add" — telling the user to
  // add a connection they already had. An unreachable host was indistinguishable
  // from an empty account. Reported after a login to a second host while the
  // saved default still pointed at a dead one: `deepsql connections list` said
  // "Network error contacting http://…:8082/api/connections" while the agent
  // intro cheerfully reported no databases.
  let list;
  try {
    list = await withTimeout(listConnections(session), timeoutMs);
  } catch (err) {
    data.unreachable = (err && err.message) ? err.message : String(err);
    return data;
  }

  data.connections = (list || []).map((c) => ({
    id: c.id,
    name: c.connectionName || c.name || c.id,
    dbType: c.dbType || "",
    canManage: !!c.canManageConfig,
  }));

  // Suggestions really are decoration — a slow or older backend missing these
  // endpoints should degrade quietly rather than block the REPL.
  try {
    // Status/suggestions only for connections this user can configure
    // (admin-level), capped so a workspace with many connections doesn't fan
    // out at startup.
    const manageable = data.connections.filter((c) => c.canManage).slice(0, 6);
    if (manageable.length) {
      const checks = await withTimeout(
        Promise.all(
          manageable.map((c) => checkConnection(session, c).catch(() => ({ items: [], recCount: 0 })))
        ),
        timeoutMs
      );
      data.suggestions = checks.flatMap((r) => r.items);
      data.recommendationCount = checks.reduce((n, r) => n + (r.recCount || 0), 0);
    }
  } catch {
    /* decoration only — render whatever we have */
  }
  return data;
}

module.exports = { loadIntroData };
