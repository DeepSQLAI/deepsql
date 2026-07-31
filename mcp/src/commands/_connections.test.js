"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

// We mock api/client.request before requiring the resolver so its cached
// module reference points at our stub.
const apiClientPath = require.resolve("../api/client");
const realApiClient = require("../api/client");

function withMockedRequest(connections, fn) {
  delete require.cache[require.resolve("./_connections")];
  require.cache[apiClientPath] = {
    ...require.cache[apiClientPath],
    exports: {
      ...realApiClient,
      request: async () => connections,
    },
  };
  try {
    const mod = require("./_connections");
    return fn(mod);
  } finally {
    require.cache[apiClientPath].exports = realApiClient;
    delete require.cache[require.resolve("./_connections")];
  }
}

const session = { baseUrl: "http://x", token: "t" };

test("resolves UUID input as-is without a backend roundtrip", async () => {
  await withMockedRequest([], async ({ resolveConnectionId }) => {
    const id = await resolveConnectionId(session, "11111111-1111-1111-1111-111111111111");
    assert.equal(id, "11111111-1111-1111-1111-111111111111");
  });
});

test("matches by exact connection name", async () => {
  const connections = [
    { id: "id-prod", connectionName: "prod" },
    { id: "id-staging", connectionName: "staging" },
  ];
  await withMockedRequest(connections, async ({ resolveConnectionId }) => {
    assert.equal(await resolveConnectionId(session, "prod"), "id-prod");
    assert.equal(await resolveConnectionId(session, "staging"), "id-staging");
  });
});

test("matches case-insensitively when no exact match exists", async () => {
  const connections = [{ id: "id-prod", connectionName: "MyLocalPG" }];
  await withMockedRequest(connections, async ({ resolveConnectionId }) => {
    assert.equal(await resolveConnectionId(session, "mylocalpg"), "id-prod");
  });
});

test("throws a useful error listing available names when no match", async () => {
  const connections = [
    { id: "1", connectionName: "alpha" },
    { id: "2", connectionName: "beta" },
  ];
  await withMockedRequest(connections, async ({ resolveConnectionId }) => {
    await assert.rejects(
      () => resolveConnectionId(session, "missing"),
      (err) => err.message.includes("alpha") && err.message.includes("beta"),
    );
  });
});

test("falls back through env and saved default before erroring", async () => {
  const connections = [{ id: "id-default", connectionName: "saved" }];
  await withMockedRequest(connections, async ({ resolveConnectionId }) => {
    // No flag, no env, no saved default → error mentioning all three escape hatches.
    delete process.env.DEEPSQL_CONNECTION;
    await assert.rejects(
      () => resolveConnectionId({ baseUrl: "http://x", token: "t" }, ""),
      /No connection specified.*--connection.*DEEPSQL_CONNECTION.*connections use/s,
    );

    // DEEPSQL_CONNECTION is consulted next.
    process.env.DEEPSQL_CONNECTION = "saved";
    try {
      assert.equal(await resolveConnectionId({ baseUrl: "http://x", token: "t" }, ""), "id-default");
    } finally {
      delete process.env.DEEPSQL_CONNECTION;
    }

    // Otherwise, the session's defaultConnection is the final fallback.
    const session = { baseUrl: "http://x", token: "t", defaultConnection: "saved" };
    assert.equal(await resolveConnectionId(session, null), "id-default");
  });
});
