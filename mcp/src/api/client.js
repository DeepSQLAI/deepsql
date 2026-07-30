"use strict";

/**
 * HTTP client used by the CLI. Wraps fetch with sane defaults, base-URL
 * normalization, and consistent error shapes.
 *
 * Distinct from `deepsql-phase1-lib.js`'s `callDeepSqlApi` because the CLI
 * needs:
 *   - profile-based base URL resolution (not env-only)
 *   - to talk to the unauthenticated /auth/cli endpoints
 *   - per-call query string composition for brain endpoints
 */

class ApiError extends Error {
  constructor(message, { status, body } = {}) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

/**
 * Module-level "who is talking to the backend" record. The CLI's entry
 * point calls setClientContext() once at startup, then every request()
 * call below stamps X-DeepSQL-Client-{Type,Agent,Version} headers without
 * each command needing to thread that information through.
 *
 *   type    "cli" by default for everything that goes through this module
 *   agent   "terminal", or the value of --caller-agent / DEEPSQL_CALLER_AGENT
 *           (set by agents like claude-code when they shell out to `deepsql`)
 *   version the npm package version, so backend audit shows which CLI build
 */
let currentClient = null;

function setClientContext(client) {
  currentClient = client ? { ...client } : null;
}

function getClientContext() {
  return currentClient;
}

function normalizeBaseUrl(url) {
  if (!url) throw new ApiError("No DeepSQL URL configured. Run `deepsql login --url <url>` first.");
  return url.endsWith("/") ? url : `${url}/`;
}

function resolveUrl(baseUrl, path) {
  const normalized = String(path || "").replace(/^\/+/, "");
  // The backend mounts everything under /api. Accept paths with or without
  // the prefix.
  const withApi = normalized.startsWith("api/") ? normalized : `api/${normalized}`;
  return new URL(withApi, normalizeBaseUrl(baseUrl)).toString();
}

async function request(baseUrl, pathOrUrl, { method = "GET", json, headers, token, timeoutMs = 120000, query, returnHeaders = false } = {}) {
  let url;
  if (typeof pathOrUrl === "string" && /^https?:\/\//i.test(pathOrUrl)) {
    url = pathOrUrl;
  } else {
    url = resolveUrl(baseUrl, pathOrUrl);
  }
  if (query && typeof query === "object") {
    const u = new URL(url);
    for (const [key, value] of Object.entries(query)) {
      if (value == null) continue;
      u.searchParams.set(key, String(value));
    }
    url = u.toString();
  }

  const requestHeaders = {
    Accept: "application/json",
    ...(headers || {}),
  };
  if (token) requestHeaders.Authorization = `Bearer ${token}`;
  if (json != null) requestHeaders["Content-Type"] = "application/json";
  if (currentClient) {
    if (currentClient.type) requestHeaders["X-DeepSQL-Client-Type"] = currentClient.type;
    if (currentClient.agent) requestHeaders["X-DeepSQL-Client-Agent"] = currentClient.agent;
    if (currentClient.version) requestHeaders["X-DeepSQL-Client-Version"] = currentClient.version;
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  let response;
  try {
    response = await fetch(url, {
      method,
      headers: requestHeaders,
      body: json == null ? undefined : JSON.stringify(json),
      signal: controller.signal,
    });
  } catch (err) {
    if (err.name === "AbortError") {
      throw new ApiError(`Request to ${url} timed out after ${timeoutMs}ms`);
    }
    throw new ApiError(`Network error contacting ${url}: ${err.message}`);
  } finally {
    clearTimeout(timer);
  }

  const text = await response.text();
  let body = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
  }
  if (!response.ok) {
    const message = (body && typeof body === "object" && (body.message || body.error)) || `HTTP ${response.status}`;
    throw new ApiError(message, { status: response.status, body });
  }
  if (returnHeaders) {
    // Node's fetch exposes Set-Cookie via getSetCookie() (Node 20+); the
    // password-login flow needs the auth_token cookie value to mint a long-
    // lived MCP token afterwards.
    const setCookies = typeof response.headers.getSetCookie === "function"
      ? response.headers.getSetCookie()
      : [];
    return { body, status: response.status, setCookies };
  }
  return body;
}

/**
 * Extract a single cookie value from an array of Set-Cookie header values.
 *
 *   parseCookieValue(["auth_token=abc.def; Path=/; HttpOnly", "refresh_token=…"], "auth_token")
 *     => "abc.def"
 */
function parseCookieValue(setCookies, name) {
  if (!Array.isArray(setCookies)) return null;
  for (const raw of setCookies) {
    const match = raw && raw.match(new RegExp(`(?:^|;\\s*)${name}=([^;]+)`));
    if (match) return decodeURIComponent(match[1]);
  }
  return null;
}

module.exports = {
  ApiError,
  request,
  resolveUrl,
  normalizeBaseUrl,
  parseCookieValue,
  setClientContext,
  getClientContext,
};
