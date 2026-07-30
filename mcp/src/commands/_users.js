"use strict";

/**
 * Resolve a user reference (numeric id, email, or username) to a {id, email,
 * username, role, ...} record.
 *
 * Backend `GET /admin/users` returns the full list, so we fetch once per
 * invocation and match locally. Cheap for typical org sizes.
 */

const { request } = require("../api/client");

let cachedUsers = null;

async function listUsers(session) {
  if (cachedUsers) return cachedUsers;
  cachedUsers = await request(session.baseUrl, "/admin/users", { token: session.token });
  if (!Array.isArray(cachedUsers)) cachedUsers = [];
  return cachedUsers;
}

function clearUserCache() {
  cachedUsers = null;
}

async function resolveUser(session, ref) {
  if (ref == null || String(ref).trim() === "") {
    throw new Error("Pass a user email, username, or numeric id.");
  }
  const trimmed = String(ref).trim();

  // Numeric id — short-circuit if list isn't already cached.
  if (/^\d+$/.test(trimmed)) {
    const users = await listUsers(session);
    const hit = users.find((u) => String(u.id) === trimmed);
    if (hit) return hit;
    throw new Error(`User id ${trimmed} not found.`);
  }

  const users = await listUsers(session);
  const lower = trimmed.toLowerCase();
  const exactEmail = users.find((u) => (u.email || "").toLowerCase() === lower);
  if (exactEmail) return exactEmail;
  const exactUsername = users.find((u) => (u.username || "").toLowerCase() === lower);
  if (exactUsername) return exactUsername;

  const available = users
    .map((u) => u.email || u.username)
    .filter(Boolean)
    .slice(0, 20);
  const hint = available.length ? ` Available: ${available.join(", ")}.` : "";
  throw new Error(`User "${trimmed}" not found.${hint}`);
}

module.exports = { listUsers, resolveUser, clearUserCache };
