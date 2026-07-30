"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

const { parseArgs, buildOpts } = require("../cli");

function opts(argv) {
  return buildOpts(parseArgs(argv));
}

function captureStdout() {
  let out = "";
  let err = "";
  return {
    stream: { write: (s) => { out += s; } },
    errStream: { write: (s) => { err += s; } },
    out: () => out,
    err: () => err,
  };
}

function loadWithStubs({ onRequest }) {
  for (const k of [
    require.resolve("../api/client"),
    require.resolve("./_session"),
    require.resolve("./_connections"),
    require.resolve("./connections"),
  ]) {
    delete require.cache[k];
  }

  const apiKey = require.resolve("../api/client");
  require.cache[apiKey] = {
    id: apiKey, filename: apiKey, loaded: true,
    exports: {
      ApiError: class ApiError extends Error {},
      async request(baseUrl, path, body) {
        return onRequest(baseUrl, path, body);
      },
      setClientContext() {},
      getClientContext() { return null; },
    },
  };

  const sessKey = require.resolve("./_session");
  require.cache[sessKey] = {
    id: sessKey, filename: sessKey, loaded: true,
    exports: {
      resolveSession: () => ({
        baseUrl: "http://test",
        token: "t",
        defaultConnection: null,
      }),
    },
  };

  return require("./connections");
}

test("connections test <saved-name> posts only the connection id", async () => {
  const seen = [];
  const connections = loadWithStubs({
    onRequest: (_baseUrl, path, body) => {
      seen.push({ path, body });
      if (path === "/connections") {
        return [{ id: "cid-1", connectionName: "prod", sshPrivateKey: "(masked)" }];
      }
      if (path === "/connections/test") {
        return {
          success: false,
          connectionSuccessful: false,
          message: "Connection failed",
          privileges: [],
        };
      }
      throw new Error(`unexpected path ${path}`);
    },
  });
  const stdout = captureStdout();
  const previousExitCode = process.exitCode;
  process.exitCode = undefined;
  try {
    const code = await connections.run(opts(["test", "prod"]), { stdout: stdout.stream });

    assert.equal(code, 1);
    assert.equal(process.exitCode, 1);
    assert.deepEqual(seen.at(-1).body.json, { id: "cid-1" });
    assert.match(stdout.out(), /Connection failed/);
  } finally {
    process.exitCode = previousExitCode;
  }
});
