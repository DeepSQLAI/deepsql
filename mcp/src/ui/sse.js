"use strict";

/**
 * Minimal Server-Sent Events consumer using node:fetch's ReadableStream.
 *
 * Yields { event, data } objects. `data` is the raw string — caller decides
 * whether to JSON.parse(). Honours SIGINT by aborting the underlying request
 * and resolving the iterator cleanly.
 *
 * SSE wire format we parse (RFC):
 *     event: <name>\n
 *     data: <line1>\n
 *     data: <line2>\n
 *     \n      ← message boundary
 *
 * We don't implement reconnection, last-event-id, or comment lines (`:` prefix)
 * because the optimize stream is short-lived and stateless.
 */

const { resolveUrl, normalizeBaseUrl } = require("../api/client");

async function* streamSse(baseUrl, path, { token, query, signal } = {}) {
  let url;
  if (typeof path === "string" && /^https?:\/\//i.test(path)) {
    url = path;
  } else {
    url = resolveUrl(baseUrl, path);
  }
  if (query && typeof query === "object") {
    const u = new URL(url);
    for (const [k, v] of Object.entries(query)) {
      if (v == null) continue;
      u.searchParams.set(k, String(v));
    }
    url = u.toString();
  }

  const headers = { Accept: "text/event-stream" };
  if (token) headers.Authorization = `Bearer ${token}`;

  const response = await fetch(url, { headers, signal });
  if (!response.ok) {
    const text = await response.text().catch(() => "");
    const err = new Error(`SSE ${response.status}: ${text || response.statusText}`);
    err.status = response.status;
    throw err;
  }
  if (!response.body) {
    throw new Error("SSE response has no body");
  }

  const decoder = new TextDecoder();
  let buffer = "";

  for await (const chunk of response.body) {
    buffer += decoder.decode(chunk, { stream: true });
    let idx;
    // Each SSE message ends with a blank line (\n\n or \r\n\r\n).
    while ((idx = nextMessageEnd(buffer)) !== -1) {
      const raw = buffer.slice(0, idx);
      buffer = buffer.slice(idx).replace(/^(\r?\n){2}/, "");
      const message = parseMessage(raw);
      if (message) yield message;
    }
  }

  // Drain any remaining message that didn't end with a blank line (server
  // close after final event).
  const final = parseMessage(buffer);
  if (final) yield final;
}

function nextMessageEnd(buffer) {
  const lf = buffer.indexOf("\n\n");
  const crlf = buffer.indexOf("\r\n\r\n");
  if (lf === -1) return crlf;
  if (crlf === -1) return lf;
  return Math.min(lf, crlf);
}

function parseMessage(raw) {
  if (!raw || !raw.trim()) return null;
  let event = "message";
  const dataLines = [];
  for (const rawLine of raw.split(/\r?\n/)) {
    if (!rawLine || rawLine.startsWith(":")) continue;
    const colon = rawLine.indexOf(":");
    const field = colon === -1 ? rawLine : rawLine.slice(0, colon);
    const value = colon === -1 ? "" : rawLine.slice(colon + 1).replace(/^\s/, "");
    if (field === "event") event = value;
    else if (field === "data") dataLines.push(value);
  }
  if (dataLines.length === 0) return null;
  return { event, data: dataLines.join("\n") };
}

module.exports = { streamSse };
