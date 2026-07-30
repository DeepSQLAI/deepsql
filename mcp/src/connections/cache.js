"use strict";

// Small on-disk cache for the connections list, keyed per DeepSQL instance.
// Each `deepsql` invocation is a fresh process, so without this every command
// re-fetches (and re-decrypts, server-side) the whole list just to resolve
// `--connection`. The list changes rarely, so a short TTL makes the common path
// (resolve a connection name → id) effectively free while staying fresh.
//
// Holds only the same non-secret connection summary the user already sees
// (name/host/port/etc.; secrets are masked server-side). File is 0600.

const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { configDir } = require("../auth/store");

const DEFAULT_TTL_MS = 60000; // 60s

function cacheFile(baseUrl) {
  const key = crypto.createHash("sha1").update(String(baseUrl || "")).digest("hex").slice(0, 16);
  return path.join(configDir(), "cache", `connections-${key}.json`);
}

function read(baseUrl, ttlMs = DEFAULT_TTL_MS) {
  try {
    const obj = JSON.parse(fs.readFileSync(cacheFile(baseUrl), "utf8"));
    if (!obj || !Array.isArray(obj.data)) return null;
    if (Date.now() - (obj.ts || 0) > ttlMs) return null;
    return obj.data;
  } catch {
    return null;
  }
}

function write(baseUrl, data) {
  if (!Array.isArray(data)) return;
  try {
    const dir = path.join(configDir(), "cache");
    fs.mkdirSync(dir, { recursive: true });
    const file = cacheFile(baseUrl);
    fs.writeFileSync(file, JSON.stringify({ ts: Date.now(), data }), { mode: 0o600 });
    try { fs.chmodSync(file, 0o600); } catch { /* best-effort */ }
  } catch {
    /* cache is best-effort — never fail a command over it */
  }
}

function invalidate(baseUrl) {
  try { fs.rmSync(cacheFile(baseUrl), { force: true }); } catch { /* ignore */ }
}

module.exports = { read, write, invalidate, DEFAULT_TTL_MS };
