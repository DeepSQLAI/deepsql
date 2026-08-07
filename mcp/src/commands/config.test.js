"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

// Stub auth/store so these tests never read or write the developer's real
// ~/.config/deepsql/auth.json. The stub keeps state in memory and reproduces the
// default-reassignment rule from store.removeProfile.
function withStubbedStore({ profiles = {}, defaultUrl = null }, fn) {
  const state = { default: defaultUrl, profiles: { ...profiles } };
  const storeKey = require.resolve("../auth/store");
  const configKey = require.resolve("./config");
  delete require.cache[storeKey];
  delete require.cache[configKey];
  require.cache[storeKey] = {
    id: storeKey,
    filename: storeKey,
    loaded: true,
    exports: {
      normalizeBaseUrl: (url) => String(url).replace(/\/+$/, ""),
      listProfiles: () => ({ default: state.default, profiles: { ...state.profiles } }),
      authFilePath: () => "/tmp/does-not-exist/auth.json",
      setDefault: (url) => { state.default = url; },
      removeProfile: (url) => {
        delete state.profiles[url];
        if (state.default === url) {
          const remaining = Object.keys(state.profiles);
          state.default = remaining.length === 1 ? remaining[0] : null;
        }
      },
    },
  };
  const config = require("./config");
  try {
    return fn(config, state);
  } finally {
    delete require.cache[storeKey];
    delete require.cache[configKey];
  }
}

function capture() {
  let buf = "";
  return { write: (s) => { buf += s; }, get: () => buf };
}

const A = "http://localhost:9085";
const B = "http://localhost:8082";
const C = "http://localhost:8080";

test("config remove forgets the named profile", async () => {
  await withStubbedStore(
    { profiles: { [A]: { username: "a" }, [B]: { username: "b" } }, defaultUrl: B },
    async (config, state) => {
      const out = capture();
      await config.run({ positional: ["remove", A] }, { stdout: out });
      assert.match(out.get(), /Removed profile http:\/\/localhost:9085/);
      assert.deepEqual(Object.keys(state.profiles), [B]);
      assert.equal(state.default, B, "removing a non-default must not move the default");
    },
  );
});

test("removing the default with one profile left adopts it AND says so", async () => {
  await withStubbedStore(
    { profiles: { [A]: { username: "a" }, [B]: { username: "b" } }, defaultUrl: B },
    async (config, state) => {
      const out = capture();
      await config.run({ positional: ["remove", B] }, { stdout: out });
      assert.equal(state.default, A, "one survivor is unambiguous");
      // Never move the default silently — for a DBA tool that means a different DB.
      assert.match(out.get(), /Default is now http:\/\/localhost:9085/);
    },
  );
});

test("removing the default with several left clears it instead of guessing", async () => {
  await withStubbedStore(
    {
      profiles: { [A]: { username: "a" }, [B]: { username: "b" }, [C]: { username: "c" } },
      defaultUrl: B,
    },
    async (config, state) => {
      const out = capture();
      await config.run({ positional: ["remove", B] }, { stdout: out });
      assert.equal(
        state.default, null,
        "picking remaining[0] would be insertion order — the same implicit guess login refuses",
      );
      assert.match(out.get(), /No default profile is set/);
    },
  );
});

test("config remove on an unknown url errors and lists what is saved", async () => {
  await withStubbedStore(
    { profiles: { [A]: { username: "a" } }, defaultUrl: A },
    async (config, state) => {
      await assert.rejects(
        () => config.run({ positional: ["remove", "http://localhost:1234"] }, { stdout: capture() }),
        /No saved profile for http:\/\/localhost:1234[\s\S]*localhost:9085/,
      );
      assert.deepEqual(Object.keys(state.profiles), [A], "nothing removed on error");
    },
  );
});

test("config remove without a url explains the usage", async () => {
  await withStubbedStore({ profiles: { [A]: {} }, defaultUrl: A }, async (config) => {
    await assert.rejects(
      () => config.run({ positional: ["remove"] }, { stdout: capture() }),
      /deepsql config remove <url>/,
    );
  });
});

test("unknown subcommand lists the real ones", async () => {
  await withStubbedStore({ profiles: {}, defaultUrl: null }, async (config) => {
    await assert.rejects(
      () => config.run({ positional: ["nope"] }, { stdout: capture() }),
      /Unknown config subcommand: nope[\s\S]*remove/,
    );
  });
});

test("config defaults to show", async () => {
  await withStubbedStore(
    { profiles: { [A]: { username: "a", tokenId: 1 } }, defaultUrl: A },
    async (config) => {
      const out = capture();
      await config.run({ positional: [] }, { stdout: out });
      assert.match(out.get(), /Default: http:\/\/localhost:9085/);
      assert.match(out.get(), /user=a/);
    },
  );
});
