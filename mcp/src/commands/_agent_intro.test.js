"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

const { renderIntro } = require("./_agent_intro");
const { loadIntroData } = require("./_agent_status");

function capture() {
  let buf = "";
  return { write: (s) => { buf += s; }, get: () => buf };
}

function intro(opts) {
  const out = capture();
  renderIntro(out, { useColor: false, ...opts });
  return out.get();
}

// ─── the reported bug ─────────────────────────────────────────────────────
//
// After logging in to a second host while the saved default still pointed at a
// dead one, the agent intro said "No databases connected yet → deepsql
// connections add" — advising the user to create a connection they already had,
// and hiding the fact that the CLI was pointed at a server that wasn't running.
// `deepsql connections list` reported the network error correctly the whole
// time; only the intro swallowed it.

test("an unreachable server is NOT rendered as an empty account", () => {
  const text = intro({
    connections: [],
    unreachable: "Network error contacting http://localhost:8082/api/connections: fetch failed",
    baseUrl: "http://localhost:8082",
  });
  assert.doesNotMatch(
    text,
    /No databases connected yet/,
    "must not tell the user to add a connection when the server was never reached",
  );
  assert.doesNotMatch(text, /deepsql connections add/);
  assert.match(text, /Could not reach your DeepSQL server/);
  // Name the host — the usual cause is the default pointing somewhere else.
  assert.match(text, /http:\/\/localhost:8082/);
  // And carry the underlying reason rather than inventing a friendlier one.
  assert.match(text, /fetch failed/);
});

test("a genuinely empty account still gets the onboarding hint", () => {
  const text = intro({ connections: [], unreachable: null });
  assert.match(text, /No databases connected yet/);
  assert.match(text, /deepsql connections add/);
  assert.doesNotMatch(text, /Could not reach/);
});

test("connections are listed when the server is reachable", () => {
  const text = intro({
    connections: [
      { name: "Self-Host Vault Postgres", dbType: "postgres", canManage: true },
    ],
    unreachable: null,
  });
  assert.match(text, /Connections \(1\)/);
  assert.match(text, /Self-Host Vault Postgres/);
  assert.doesNotMatch(text, /Could not reach/);
  assert.doesNotMatch(text, /No databases connected yet/);
});

test("renderIntro without the new fields behaves as before", () => {
  // Callers that predate `unreachable` must be unaffected.
  const text = intro({ connections: [] });
  assert.match(text, /No databases connected yet/);
});

// ─── loadIntroData: report the failure instead of swallowing it ───────────

test("loadIntroData reports an unreachable server rather than empty connections", async () => {
  // A session pointed at a port nothing is listening on. No stubbing: this is
  // the real client path, which is what silently returned [] before.
  const session = { baseUrl: "http://127.0.0.1:1", token: "t" };
  const data = await loadIntroData(session, { timeoutMs: 1500 });
  assert.deepEqual(data.connections, []);
  assert.ok(
    data.unreachable,
    "a connection-list failure must be reported, not swallowed into an empty list",
  );
  assert.equal(typeof data.unreachable, "string");
});
