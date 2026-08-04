'use strict';

/**
 * TLS policy for the `direct` transport.
 *
 * A self-hosted DeepSQL VM lands in one of four situations, and each gets an
 * explicit mode rather than a "just turn off verification" escape hatch:
 *
 *   system     — a publicly-trusted certificate (Let's Encrypt et al.). Chromium
 *                and Node verify normally. This is the default and the one every
 *                VM with a real hostname should use.
 *   pinned     — any certificate whose SHA-256 matches `tls.fingerprint`. Covers
 *                self-signed certs and IP-only deployments without weakening
 *                anything: an attacker needs the exact key, not just any cert.
 *   custom-ca  — verified against a private CA bundle the user points at, the
 *                normal shape for a corporate internal PKI.
 *   insecure   — accept whatever the host presents, then immediately pin it, so
 *                the window of trust is the first connection only. Surfaced in
 *                the UI in red; it exists because the alternative is users
 *                setting NODE_TLS_REJECT_UNAUTHORIZED=0 globally.
 */

const fs = require('node:fs');
const crypto = require('node:crypto');
const log = require('./logger');

/** SHA-256 of the DER body, formatted like `openssl x509 -fingerprint -sha256`. */
function fingerprintFromPem(pem) {
  const body = String(pem || '')
    .replace(/-----BEGIN CERTIFICATE-----/g, '')
    .replace(/-----END CERTIFICATE-----/g, '')
    .replace(/\s+/g, '');
  if (!body) return '';
  return fingerprintFromDer(Buffer.from(body, 'base64'));
}

function fingerprintFromDer(der) {
  const hex = crypto.createHash('sha256').update(der).digest('hex').toUpperCase();
  return hex.match(/.{2}/g).join(':');
}

function normalizeFingerprint(value) {
  const cleaned = String(value || '')
    .trim()
    .toUpperCase()
    .replace(/^SHA-?256[:=\s]*/i, '')
    .replace(/[^0-9A-F]/g, '');
  if (cleaned.length !== 64) return '';
  return cleaned.match(/.{2}/g).join(':');
}

function readCaBundle(caPath) {
  if (!caPath) return null;
  try {
    return fs.readFileSync(caPath);
  } catch (err) {
    throw new Error(`Could not read CA bundle at ${caPath}: ${err.message}`);
  }
}

/**
 * Node `https.request` options implementing the profile's policy.
 * In `pinned`/`insecure` mode Node's own chain check is disabled and replaced by
 * the fingerprint check in `checkPeerCertificate` — never skip that follow-up.
 */
function nodeTlsOptions(profile) {
  const mode = profile?.tls?.mode || 'system';
  if (mode === 'custom-ca') {
    return { ca: readCaBundle(profile.tls.caPath), rejectUnauthorized: true };
  }
  if (mode === 'pinned' || mode === 'insecure') {
    return { rejectUnauthorized: false };
  }
  return { rejectUnauthorized: true };
}

/**
 * @returns {{ ok: boolean, fingerprint: string, reason?: string }}
 */
function checkPeerCertificate(profile, socket) {
  const mode = profile?.tls?.mode || 'system';
  const peer =
    typeof socket?.getPeerCertificate === 'function'
      ? socket.getPeerCertificate(false)
      : null;
  const fingerprint = peer?.raw ? fingerprintFromDer(peer.raw) : '';

  if (mode === 'pinned') {
    const expected = normalizeFingerprint(profile.tls.fingerprint);
    if (!expected) {
      // Nothing pinned yet — first connection establishes the pin.
      return { ok: true, fingerprint, pinNow: true };
    }
    if (expected !== fingerprint) {
      return {
        ok: false,
        fingerprint,
        reason:
          'The server presented a different TLS certificate than the one pinned for this connection.',
      };
    }
  }

  if (mode === 'insecure') {
    return { ok: true, fingerprint, pinNow: !profile.tls.fingerprint };
  }

  return { ok: true, fingerprint };
}

/**
 * Install the policy on the profile's Electron session so the embedded UI is
 * held to exactly the same rules as our health probe. Without this the
 * WebContentsView would fall back to Chromium's root store and reject the
 * self-signed certs that `pinned` mode is there to support.
 */
function applyToSession(session, profile) {
  const mode = profile?.tls?.mode || 'system';
  const host = safeHost(profile.url);

  if (mode === 'system') {
    session.setCertificateVerifyProc((_request, callback) => callback(-3));
    return;
  }

  let caCerts = null;
  if (mode === 'custom-ca') {
    const bundle = readCaBundle(profile.tls.caPath);
    caCerts = String(bundle || '')
      .split(/(?=-----BEGIN CERTIFICATE-----)/g)
      .map((pem) => fingerprintFromPem(pem))
      .filter(Boolean);
  }

  const expected = normalizeFingerprint(profile.tls.fingerprint);

  session.setCertificateVerifyProc((request, callback) => {
    // Anything that is not this profile's host goes through Chromium untouched.
    if (host && request.hostname !== host) return callback(-3);
    if (request.verificationResult === 'net::OK') return callback(0);

    const leaf = fingerprintFromPem(request.certificate?.data);
    const chain = [leaf, ...collectChainFingerprints(request.certificate)];

    if (mode === 'insecure') {
      log.warn('tls', 'accepting unverified certificate (insecure mode)', {
        hostname: request.hostname,
        fingerprint: leaf,
      });
      return callback(0);
    }
    if (mode === 'pinned') {
      return callback(expected && expected === leaf ? 0 : -2);
    }
    if (mode === 'custom-ca') {
      return callback(chain.some((fp) => caCerts.includes(fp)) ? 0 : -2);
    }
    return callback(-3);
  });
}

function collectChainFingerprints(certificate) {
  const out = [];
  let node = certificate?.issuerCert;
  // Chains are short; the guard is only against a malformed self-referential one.
  for (let depth = 0; node && depth < 10; depth += 1) {
    const fp = fingerprintFromPem(node.data);
    if (fp) out.push(fp);
    node = node.issuerCert === node ? null : node.issuerCert;
  }
  return out;
}

function safeHost(url) {
  try {
    return new URL(url).hostname;
  } catch {
    return '';
  }
}

module.exports = {
  fingerprintFromPem,
  fingerprintFromDer,
  normalizeFingerprint,
  nodeTlsOptions,
  checkPeerCertificate,
  applyToSession,
};
