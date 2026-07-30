"use strict";

/**
 * OAuth 2.0 device-code flow (RFC 8628 shape, adapted for self-hosted DeepSQL).
 *
 * 1. POST /auth/cli/device/code → {device_code, user_code, verification_uri, interval, expires_in}
 * 2. Print the user_code + verification_uri.
 * 3. Poll POST /auth/cli/device/token with {deviceCode}.
 *    - 200 → token issued
 *    - 428 (PRECONDITION_REQUIRED) → keep polling
 *    - 429 → bump interval (`slow_down`)
 *    - 410 → expired
 *    - 403 → user denied
 */

const { ApiError, request } = require("../api/client");

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function runDeviceFlow({ baseUrl, hostname, clientLabel, log = () => {} }) {
  const started = await request(baseUrl, "/auth/cli/device/code", {
    method: "POST",
    json: { hostname, clientLabel },
  });

  log(
    `Visit ${started.verification_uri} and enter code:\n\n  ${started.user_code}\n\n` +
      `Waiting for approval (expires in ${Math.round((started.expires_in || 900) / 60)} min)…`,
  );

  let interval = (started.interval || 5) * 1000;
  const deadline = Date.now() + (started.expires_in || 900) * 1000;

  while (Date.now() < deadline) {
    await sleep(interval);
    try {
      const issued = await request(baseUrl, "/auth/cli/device/token", {
        method: "POST",
        json: { deviceCode: started.device_code },
      });
      return issued;
    } catch (err) {
      if (!(err instanceof ApiError)) throw err;
      if (err.status === 428) continue; // authorization_pending
      if (err.status === 429) {
        interval = Math.min(interval * 2, 30000);
        continue;
      }
      if (err.status === 410) throw new Error("Device code expired before approval. Run `deepsql login` again.");
      if (err.status === 403) throw new Error("Authorization was denied.");
      throw err;
    }
  }
  throw new Error("Device authorization timed out.");
}

module.exports = { runDeviceFlow };
