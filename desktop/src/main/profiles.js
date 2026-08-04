'use strict';

/**
 * Connection profiles: one per DeepSQL VM a user can reach.
 *
 * Stored at userData/profiles.json. Everything in that file is non-secret
 * except the two `*Cipher` fields, which hold safeStorage ciphertext (see
 * secrets.js). Nothing here ever holds a plaintext credential on disk.
 */

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { app } = require('electron');

const secrets = require('./secrets');
const log = require('./logger');

const FILE_VERSION = 1;

let cache = null;

function storePath() {
  return path.join(app.getPath('userData'), 'profiles.json');
}

function emptyStore() {
  return { version: FILE_VERSION, profiles: [], lastUsedProfileId: null };
}

function load() {
  if (cache) return cache;
  try {
    const raw = fs.readFileSync(storePath(), 'utf8');
    const parsed = JSON.parse(raw);
    cache = {
      version: FILE_VERSION,
      profiles: Array.isArray(parsed.profiles) ? parsed.profiles : [],
      lastUsedProfileId: parsed.lastUsedProfileId ?? null,
    };
  } catch {
    cache = emptyStore();
  }
  return cache;
}

function persist() {
  const file = storePath();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  // Write-then-rename so a crash mid-write cannot leave a truncated store.
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(load(), null, 2), { mode: 0o600 });
  fs.renameSync(tmp, file);
}

function normalizeUrl(value) {
  const trimmed = String(value || '').trim();
  if (!trimmed) return '';
  const withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
  try {
    const url = new URL(withScheme);
    // Only the origin matters — the SPA owns every path below it.
    return url.origin;
  } catch {
    return '';
  }
}

function defaults() {
  return {
    id: crypto.randomUUID(),
    name: 'DeepSQL',
    transport: 'direct',
    url: '',
    tls: { mode: 'system', fingerprint: '', caPath: '' },
    ssh: {
      host: '',
      port: 22,
      username: 'ubuntu',
      authMethod: 'key',
      privateKeyPath: '',
      passphraseCipher: null,
      passwordCipher: null,
      remoteHost: '127.0.0.1',
      // The frontend container, not the host reverse proxy on :80. A host nginx
      // almost always does name-based virtual hosting, and a tunnel arrives with
      // `Host: 127.0.0.1:<port>`, which matches no server_name and falls through
      // to the default vhost — a 404 that looks like DeepSQL is broken. The
      // container's own nginx uses `server_name _` and answers whatever Host it
      // is given, so it is the right target for a tunnel.
      remotePort: 3000,
      remoteScheme: 'http',
      localPort: 0,
      // Last port we actually bound, so the tunnel origin (and therefore the
      // web app's localStorage) stays stable across launches. Not user-facing.
      stickyLocalPort: 0,
      hostKeyFingerprint: '',
    },
    createdAt: new Date().toISOString(),
    lastConnectedAt: null,
  };
}

/** Merge user input over an existing profile without letting it inject fields. */
function applyInput(base, input = {}) {
  const next = {
    ...base,
    name: String(input.name ?? base.name).trim() || 'DeepSQL',
    transport: input.transport === 'tunnel' ? 'tunnel' : 'direct',
    url: input.url === undefined ? base.url : normalizeUrl(input.url),
  };

  const tlsIn = input.tls || {};
  next.tls = {
    mode: ['system', 'pinned', 'custom-ca', 'insecure'].includes(tlsIn.mode)
      ? tlsIn.mode
      : base.tls.mode,
    fingerprint: String(tlsIn.fingerprint ?? base.tls.fingerprint ?? '')
      .trim()
      .toUpperCase(),
    caPath: String(tlsIn.caPath ?? base.tls.caPath ?? '').trim(),
  };

  const sshIn = input.ssh || {};
  const b = base.ssh;
  next.ssh = {
    host: String(sshIn.host ?? b.host).trim(),
    port: clampPort(sshIn.port ?? b.port, 22),
    username: String(sshIn.username ?? b.username).trim(),
    authMethod: ['key', 'agent', 'password'].includes(sshIn.authMethod)
      ? sshIn.authMethod
      : b.authMethod,
    privateKeyPath: String(sshIn.privateKeyPath ?? b.privateKeyPath).trim(),
    passphraseCipher: b.passphraseCipher ?? null,
    passwordCipher: b.passwordCipher ?? null,
    remoteHost: String(sshIn.remoteHost ?? b.remoteHost).trim() || '127.0.0.1',
    remotePort: clampPort(sshIn.remotePort ?? b.remotePort, 3000),
    remoteScheme: sshIn.remoteScheme === 'https' ? 'https' : 'http',
    localPort: clampPort(sshIn.localPort ?? b.localPort, 0, true),
    stickyLocalPort: clampPort(b.stickyLocalPort, 0, true),
    hostKeyFingerprint: String(
      sshIn.hostKeyFingerprint ?? b.hostKeyFingerprint ?? '',
    ).trim(),
  };

  // Secret fields arrive as plaintext from the launcher and are sealed here.
  // `undefined` means "leave as-is"; an empty string means "clear it".
  if (sshIn.passphrase !== undefined) {
    const sealed = secrets.seal(next.id, 'passphrase', sshIn.passphrase);
    next.ssh.passphraseCipher = sealed.cipher;
  }
  if (sshIn.password !== undefined) {
    const sealed = secrets.seal(next.id, 'password', sshIn.password);
    next.ssh.passwordCipher = sealed.cipher;
  }

  return next;
}

function clampPort(value, fallback, allowZero = false) {
  const n = Number.parseInt(value, 10);
  if (!Number.isFinite(n)) return fallback;
  if (n === 0 && allowZero) return 0;
  if (n < 1 || n > 65535) return fallback;
  return n;
}

/** Strip ciphertext before anything crosses the IPC boundary. */
function toSafeProfile(profile) {
  if (!profile) return null;
  const { ssh, ...rest } = profile;
  const { passphraseCipher, passwordCipher, ...sshRest } = ssh;
  return {
    ...rest,
    ssh: {
      ...sshRest,
      hasStoredPassphrase: Boolean(
        passphraseCipher || secrets.open(profile.id, 'passphrase', null),
      ),
      hasStoredPassword: Boolean(
        passwordCipher || secrets.open(profile.id, 'password', null),
      ),
    },
  };
}

function list() {
  return load().profiles.map(toSafeProfile);
}

function get(id) {
  return load().profiles.find((p) => p.id === id) || null;
}

function upsert(input = {}) {
  const store = load();
  const existing = input.id ? get(input.id) : null;
  const merged = applyInput(existing || defaults(), input);
  if (existing) {
    store.profiles = store.profiles.map((p) => (p.id === merged.id ? merged : p));
  } else {
    store.profiles.push(merged);
  }
  persist();
  log.info('profiles', existing ? 'updated profile' : 'created profile', {
    id: merged.id,
    transport: merged.transport,
  });
  return toSafeProfile(merged);
}

function remove(id) {
  const store = load();
  store.profiles = store.profiles.filter((p) => p.id !== id);
  if (store.lastUsedProfileId === id) store.lastUsedProfileId = null;
  persist();
  secrets.forget(id);
  return true;
}

function markConnected(id) {
  const profile = get(id);
  if (!profile) return;
  profile.lastConnectedAt = new Date().toISOString();
  load().lastUsedProfileId = id;
  persist();
}

/** Persist a host key fingerprint the first time we see it (trust on first use). */
function rememberHostKey(id, fingerprint) {
  const profile = get(id);
  if (!profile) return;
  profile.ssh.hostKeyFingerprint = fingerprint;
  persist();
  log.info('profiles', 'pinned SSH host key', { id, fingerprint });
}

/** Remember the loopback port a tunnel bound, keeping the origin stable. */
function rememberLocalPort(id, port) {
  const profile = get(id);
  if (!profile || !port) return;
  if (profile.ssh.stickyLocalPort === port) return;
  profile.ssh.stickyLocalPort = port;
  persist();
}

function rememberCertFingerprint(id, fingerprint) {
  const profile = get(id);
  if (!profile) return;
  profile.tls.fingerprint = fingerprint;
  persist();
  log.info('profiles', 'pinned TLS certificate', { id, fingerprint });
}

function lastUsedProfileId() {
  return load().lastUsedProfileId;
}

/** Resolved secrets for the transport layer — main process only. */
function resolveSecrets(profile) {
  return {
    passphrase: secrets.open(profile.id, 'passphrase', profile.ssh.passphraseCipher),
    password: secrets.open(profile.id, 'password', profile.ssh.passwordCipher),
  };
}

/**
 * The origin the workspace window will navigate to for this profile.
 * For a tunnel the port is only known once the local listener is bound, so the
 * transport layer passes it in.
 */
function originFor(profile, boundLocalPort) {
  if (profile.transport === 'tunnel') {
    return `${profile.ssh.remoteScheme}://127.0.0.1:${boundLocalPort}`;
  }
  return profile.url;
}

module.exports = {
  list,
  get,
  upsert,
  remove,
  markConnected,
  rememberHostKey,
  rememberLocalPort,
  rememberCertFingerprint,
  lastUsedProfileId,
  resolveSecrets,
  toSafeProfile,
  normalizeUrl,
  originFor,
  storePath,
};
