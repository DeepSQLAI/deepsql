'use strict';

/**
 * Reachability probe used by both transports.
 *
 * Hits the Spring actuator health endpoint that the frontend nginx already
 * proxies (docker/nginx/default.conf routes /api/ to the backend), so a 200
 * here proves the whole chain — transport, nginx, backend — not just that a
 * TCP port answered.
 *
 * The probe deliberately sends an `Origin` header naming the origin the
 * embedded browser is about to load. Spring treats *any* request carrying
 * `Origin` as a CORS request (the same-origin short-circuit was dropped in
 * Spring 5.3), so an origin missing from the backend's `CORS_ALLOWED_ORIGINS`
 * is rejected with `403 Invalid CORS request` — and this probe sees it.
 *
 * Without the header the probe passes and the failure surfaces much later and
 * much further away: Chromium omits `Origin` on same-origin GETs, so the SPA
 * loads fine and only the first POST — the login itself — fails, as an opaque
 * "Request failed with status code 403" (the body is plain text, so the web
 * app's axios interceptor finds no `.message` to show). Over a tunnel this is
 * near-guaranteed, because the origin is `http://127.0.0.1:<sticky port>` and
 * no deployment lists that by hand. Same reasoning as verifyForwarding():
 * check the thing up front, where it can still be explained.
 */

const http = require('node:http');
const https = require('node:https');

const { HEALTH_PATH } = require('./config');
const tls = require('./tls');

const DEFAULT_TIMEOUT_MS = 10_000;

/**
 * @param {string} origin  e.g. https://dba-agent.example.com or http://127.0.0.1:52341
 * @param {object} [profile]  supplies the TLS policy for https origins
 * @returns {Promise<{ok:boolean,status:number|null,latencyMs:number,fingerprint:string,detail:string}>}
 */
function probe(origin, profile, { timeoutMs = DEFAULT_TIMEOUT_MS, path = HEALTH_PATH } = {}) {
  return new Promise((resolve) => {
    let url;
    try {
      url = new URL(path, origin);
    } catch {
      resolve(fail('Invalid server URL.'));
      return;
    }

    const isHttps = url.protocol === 'https:';
    const startedAt = Date.now();
    let settled = false;
    let fingerprint = '';

    const finish = (result) => {
      if (settled) return;
      settled = true;
      resolve({ latencyMs: Date.now() - startedAt, fingerprint, ...result });
    };

    let options;
    try {
      options = {
        method: 'GET',
        headers: {
          Accept: 'application/json',
          'User-Agent': 'DeepSQL-Desktop',
          // `url.origin` rather than the caller's string: it is normalised
          // (default ports dropped, no trailing slash) exactly as a browser
          // would send it, so the check matches what Chromium does next.
          Origin: url.origin,
        },
        ...(isHttps ? tls.nodeTlsOptions(profile) : {}),
      };
    } catch (err) {
      finish({ ok: false, status: null, detail: err.message });
      return;
    }

    const request = (isHttps ? https : http).request(url, options, (response) => {
      // Drain — an undrained response keeps the socket (and the agent) alive.
      response.resume();
      const status = response.statusCode;
      finish({
        ok: status >= 200 && status < 400,
        status,
        detail:
          status >= 200 && status < 400
            ? 'DeepSQL responded.'
            : `DeepSQL responded with HTTP ${status}.`,
      });
    });

    if (isHttps) {
      request.on('socket', (socket) => {
        socket.on('secureConnect', () => {
          const verdict = tls.checkPeerCertificate(profile, socket);
          fingerprint = verdict.fingerprint;
          if (!verdict.ok) {
            request.destroy(new Error(verdict.reason));
          }
        });
      });
    }

    request.setTimeout(timeoutMs, () => {
      request.destroy(new Error(`No response within ${Math.round(timeoutMs / 1000)}s.`));
    });

    request.on('error', (err) => {
      finish({ ok: false, status: null, detail: explain(err) });
    });

    request.end();
  });

  function fail(detail) {
    return { ok: false, status: null, latencyMs: 0, fingerprint: '', detail };
  }
}

function explain(err) {
  switch (err.code) {
    case 'ENOTFOUND':
      return 'Hostname could not be resolved. Check the server URL or your DNS/VPN.';
    case 'ECONNREFUSED':
      return 'Connection refused. DeepSQL may not be running, or the port is closed.';
    case 'ETIMEDOUT':
      return 'Connection timed out. A firewall or security group may be blocking the port.';
    case 'EHOSTUNREACH':
    case 'ENETUNREACH':
      return 'Host unreachable from this network.';
    case 'ECONNRESET':
      // Over a tunnel this is what a refused forwarding channel looks like from
      // the HTTP client's side. SshTunnel.verifyForwarding should have caught it
      // first with a precise reason; this is the fallback wording.
      return 'The connection was closed before a response arrived. Over an SSH tunnel this usually means the forwarding channel was refused or the remote port is wrong.';
    case 'DEPTH_ZERO_SELF_SIGNED_CERT':
    case 'SELF_SIGNED_CERT_IN_CHAIN':
      return 'The server uses a self-signed certificate. Switch TLS mode to Pinned certificate or Custom CA.';
    case 'UNABLE_TO_VERIFY_LEAF_SIGNATURE':
      return 'The certificate chain is incomplete or issued by an unknown CA.';
    case 'ERR_TLS_CERT_ALTNAME_INVALID':
      return 'The certificate does not cover this hostname.';
    case 'CERT_HAS_EXPIRED':
      return 'The server certificate has expired.';
    default:
      return err.message || 'Could not reach DeepSQL.';
  }
}

module.exports = { probe };
