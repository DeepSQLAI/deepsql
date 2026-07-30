"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

function withTempStore(fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "deepsql-store-"));
  const previous = process.env.DEEPSQL_CONFIG_DIR;
  process.env.DEEPSQL_CONFIG_DIR = dir;
  // Force re-resolution of the path each call.
  delete require.cache[require.resolve("./store")];
  const store = require("./store");
  try {
    return fn(store, dir);
  } finally {
    process.env.DEEPSQL_CONFIG_DIR = previous;
    delete require.cache[require.resolve("./store")];
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

test("setProfile then getProfile round-trips", () => {
  withTempStore((store) => {
    store.setProfile("http://localhost:8080", {
      token: "dsql_mcp_x.y",
      username: "alice",
      tokenId: 7,
    });
    const profile = store.getProfile("http://localhost:8080");
    assert.equal(profile.token, "dsql_mcp_x.y");
    assert.equal(profile.username, "alice");
    assert.equal(store.defaultBaseUrl(), "http://localhost:8080");
  });
});

test("normalizes trailing slash so http://x and http://x/ share a profile", () => {
  withTempStore((store) => {
    store.setProfile("http://localhost:8080/", { token: "t", username: "a" });
    const profile = store.getProfile("http://localhost:8080");
    assert.ok(profile);
    assert.equal(profile.token, "t");
  });
});

test("removeProfile clears the profile and reassigns default", () => {
  withTempStore((store) => {
    store.setProfile("http://a", { token: "ta", username: "alice" });
    store.setProfile("http://b", { token: "tb", username: "bob" });
    store.removeProfile("http://a");
    assert.equal(store.getProfile("http://a"), null);
    assert.equal(store.defaultBaseUrl(), "http://b");
  });
});

test("auth file is written with mode 0600", { skip: process.platform === "win32" }, () => {
  withTempStore((store, dir) => {
    store.setProfile("http://localhost:8080", { token: "t", username: "a" });
    const stat = fs.statSync(path.join(dir, "auth.json"));
    assert.equal(stat.mode & 0o777, 0o600);
  });
});

test("setDefaultConnection round-trips and clearing it removes the field", () => {
  withTempStore((store) => {
    store.setProfile("http://x", { token: "t", username: "a" });
    assert.equal(store.getDefaultConnection("http://x"), null);

    store.setDefaultConnection("http://x", "prod-replica");
    assert.equal(store.getDefaultConnection("http://x"), "prod-replica");

    store.setDefaultConnection("http://x", null);
    assert.equal(store.getDefaultConnection("http://x"), null);
  });
});

test("setDefaultConnection refuses to write when the profile doesn't exist", () => {
  withTempStore((store) => {
    assert.throws(
      () => store.setDefaultConnection("http://no-such-profile", "x"),
      /No profile saved/,
    );
  });
});

test("rejects loose perms unless DEEPSQL_INSECURE_AUTH=1", { skip: process.platform === "win32" }, () => {
  withTempStore((store, dir) => {
    store.setProfile("http://localhost:8080", { token: "t", username: "a" });
    fs.chmodSync(path.join(dir, "auth.json"), 0o644);
    assert.throws(() => store.load(), /insecure permissions/);
    process.env.DEEPSQL_INSECURE_AUTH = "1";
    try {
      assert.doesNotThrow(() => store.load());
    } finally {
      delete process.env.DEEPSQL_INSECURE_AUTH;
    }
  });
});
