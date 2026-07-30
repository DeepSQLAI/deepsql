"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const { resolveSecrets, maskSecrets, SECRET_FIELDS } = require("./secrets");

function tempfile(content, mode = 0o600) {
  const file = path.join(os.tmpdir(), `deepsql-secrets-${Date.now()}-${Math.random().toString(36).slice(2)}.tmp`);
  fs.writeFileSync(file, content, { mode });
  return file;
}

test("plaintext non-secret fields pass through unchanged", () => {
  const out = resolveSecrets({ host: "db.x", port: 5432, dbType: "postgres" });
  assert.deepEqual(out, { host: "db.x", port: 5432, dbType: "postgres" });
});

test("$VAR substitution pulls from process.env", () => {
  process.env.DEEPSQL_TEST_PWD = "s3cret";
  try {
    const out = resolveSecrets({ password: "$DEEPSQL_TEST_PWD", host: "x" });
    assert.equal(out.password, "s3cret");
    assert.equal(out.host, "x");
  } finally {
    delete process.env.DEEPSQL_TEST_PWD;
  }
});

test("$VAR throws when the env var is missing", () => {
  delete process.env.DEEPSQL_TEST_MISSING_PWD;
  assert.throws(
    () => resolveSecrets({ password: "$DEEPSQL_TEST_MISSING_PWD" }),
    /references env var DEEPSQL_TEST_MISSING_PWD, but it is not set/,
  );
});

test("@file: reads file contents at runtime", () => {
  const file = tempfile("-----BEGIN CERT-----\nblob\n-----END CERT-----\n");
  try {
    const out = resolveSecrets({ sslCaCertificate: `@file:${file}` });
    assert.match(out.sslCaCertificate, /BEGIN CERT/);
  } finally {
    fs.unlinkSync(file);
  }
});

test("@file: rejects insecure permissions unless DEEPSQL_INSECURE_AUTH=1", { skip: process.platform === "win32" }, () => {
  const file = tempfile("body", 0o644);
  try {
    assert.throws(
      () => resolveSecrets({ sshPrivateKey: `@file:${file}` }),
      /insecure permissions/,
    );
    process.env.DEEPSQL_INSECURE_AUTH = "1";
    try {
      const out = resolveSecrets({ sshPrivateKey: `@file:${file}` });
      assert.equal(out.sshPrivateKey, "body");
    } finally {
      delete process.env.DEEPSQL_INSECURE_AUTH;
    }
  } finally {
    fs.unlinkSync(file);
  }
});

test("@file: expands ~/ to homedir", () => {
  // Write a real file under homedir to confirm expansion works end-to-end.
  const dir = fs.mkdtempSync(path.join(os.homedir(), ".deepsql-test-"));
  const filename = path.join(dir, "secret.pem");
  fs.writeFileSync(filename, "homedir-secret", { mode: 0o600 });
  const relative = `~/${path.relative(os.homedir(), filename)}`;
  try {
    const out = resolveSecrets({ sshPrivateKey: `@file:${relative}` });
    assert.equal(out.sshPrivateKey, "homedir-secret");
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("plaintext secrets warn only when sourcePath is inside a git tree (best-effort)", () => {
  const warnings = [];
  resolveSecrets(
    { password: "plain-text-password" },
    {
      sourcePath: __filename, // this file is in our git tree
      log: (msg) => warnings.push(msg),
    },
  );
  assert.ok(
    warnings.some((m) => m.includes("plaintext secret")),
    `expected a plaintext-secret warning, got: ${warnings.join(" / ") || "(none)"}`,
  );
});

test("plaintext secrets do NOT warn when --allow-plaintext-secrets is set", () => {
  const warnings = [];
  resolveSecrets(
    { password: "plain-text-password" },
    {
      sourcePath: __filename,
      allowPlaintextSecrets: true,
      log: (msg) => warnings.push(msg),
    },
  );
  assert.equal(warnings.length, 0);
});

test("plaintext non-secret fields don't trigger warnings even in a git tree", () => {
  const warnings = [];
  resolveSecrets(
    { host: "plain-host" },
    {
      sourcePath: __filename,
      log: (msg) => warnings.push(msg),
    },
  );
  assert.equal(warnings.length, 0);
});

test("maskSecrets replaces non-empty secret fields with '(set)'", () => {
  const cfg = {
    host: "db.x",
    password: "abc",
    sshPrivateKey: "",
    sshPassphrase: "phrase",
  };
  const masked = maskSecrets(cfg);
  assert.equal(masked.host, "db.x");
  assert.equal(masked.password, "(set)");
  assert.equal(masked.sshPrivateKey, "");
  assert.equal(masked.sshPassphrase, "(set)");
});

test("SECRET_FIELDS export covers the canonical list", () => {
  for (const f of [
    "password",
    "sshPassword",
    "sshPrivateKey",
    "sshPassphrase",
    "sslCaCertificate",
    "sslClientCertificate",
    "sslClientKey",
    "sslClientKeyPassphrase",
  ]) {
    assert.ok(SECRET_FIELDS.has(f), `${f} should be in SECRET_FIELDS`);
  }
});
