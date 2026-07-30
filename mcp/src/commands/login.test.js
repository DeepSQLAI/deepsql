"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

// Stub the auth/store module before requiring login so login picks up
// our fake. Each test rebuilds the stub with the profile shape it wants.
function withStubbedStore(profiles, fn) {
  const storeKey = require.resolve("../auth/store");
  const loginKey = require.resolve("./login");
  delete require.cache[storeKey];
  delete require.cache[loginKey];
  require.cache[storeKey] = {
    id: storeKey,
    filename: storeKey,
    loaded: true,
    exports: {
      normalizeBaseUrl: (url) => url.endsWith("/") ? url : `${url}/`,
      listProfiles: () => ({ profiles, default: Object.keys(profiles)[0] || null }),
      // Other fns aren't used by resolveLoginBaseUrl. Stub them just so
      // the require() in login.js doesn't blow up.
      defaultBaseUrl: () => null,
      getProfile: () => null,
      setProfile: () => {},
    },
  };
  const { resolveLoginBaseUrl } = require("./login");
  try {
    return fn(resolveLoginBaseUrl);
  } finally {
    delete require.cache[storeKey];
    delete require.cache[loginKey];
  }
}

function captureStderr() {
  let buf = "";
  return { write: (s) => { buf += s; }, get: () => buf };
}

// ─── --url always wins ────────────────────────────────────────────────────

test("login --url wins regardless of saved profiles", () => {
  withStubbedStore(
    { "https://existing.example.com/": { token: "t" } },
    (resolve) => {
      const stderr = captureStderr();
      const url = resolve({ url: "https://customer-fresh.example.com" }, { stderr });
      assert.equal(url, "https://customer-fresh.example.com/");
      assert.equal(stderr.get(), "", "no chatty hint when user passed --url explicitly");
    },
  );
});

// ─── 0 profiles: clean error with self-host example ───────────────────────

test("login with no --url and no saved profiles errors with a self-host-friendly hint", () => {
  withStubbedStore({}, (resolve) => {
    assert.throws(
      () => resolve({}, { stderr: captureStderr() }),
      /Pass --url <https:\/\/your-deepsql-host>.*deepsql\.acme\.com/s,
    );
  });
});

// ─── exactly 1 profile: use it, but announce which one ───────────────────

test("login with no --url and one saved profile uses it AND announces it on stderr", () => {
  withStubbedStore(
    { "https://customer.example.com/": { token: "t" } },
    (resolve) => {
      const stderr = captureStderr();
      const url = resolve({}, { stderr });
      assert.equal(url, "https://customer.example.com/");
      const hint = stderr.get();
      assert.match(hint, /Using saved profile: https:\/\/customer\.example\.com\//);
      assert.match(hint, /Pass --url to log in against a different host/);
    },
  );
});

// ─── ≥ 2 profiles: refuse to guess (the regression we're fixing) ─────────

test("login with no --url and multiple profiles refuses to guess — lists them and requires --url", () => {
  // This is the scenario that bit the user: they logged into our hosted
  // demo (deepsql.stayflexi.com) AND their self-hosted instance, and bare
  // `deepsql login` kept defaulting to whichever was logged in first.
  // After this fix, the CLI lists both and requires --url.
  withStubbedStore(
    {
      "https://deepsql.stayflexi.com/": { token: "t1" },
      "https://customer.example.com/":  { token: "t2" },
    },
    (resolve) => {
      let thrown;
      try {
        resolve({}, { stderr: captureStderr() });
      } catch (err) {
        thrown = err;
      }
      assert.ok(thrown, "must throw when multiple profiles exist");
      assert.match(thrown.message, /Multiple saved DeepSQL profiles/);
      assert.match(thrown.message, /Pass --url <host> to choose one/);
      // Both URLs must be listed so the user can pick.
      assert.match(thrown.message, /https:\/\/deepsql\.stayflexi\.com\//);
      assert.match(thrown.message, /https:\/\/customer\.example\.com\//);
      // And we point them at config set-default for pinning.
      assert.match(thrown.message, /deepsql config set-default/);
    },
  );
});
