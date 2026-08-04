'use strict';

/**
 * Transport manager — owns the live connection behind each open workspace.
 *
 * Both transports resolve to the same thing: an *origin* the embedded browser
 * can navigate to. `direct` hands back the VM's public origin; `tunnel` hands
 * back a loopback origin backed by an SSH channel. Everything downstream (the
 * workspace window, session partition, health polling) is transport-agnostic
 * because of that.
 */

const { EventEmitter } = require('node:events');

const profilesStore = require('./profiles');
const { SshTunnel, TunnelError } = require('./tunnel');
const { probe } = require('./probe');
const { HEALTH_INTERVAL_MS } = require('./config');
const log = require('./logger');

class Transport extends EventEmitter {
  constructor() {
    super();
    /** @type {Map<string, {profile:object, origin:string, tunnel:SshTunnel|null, health:object, timer:NodeJS.Timeout|null}>} */
    this.active = new Map();
  }

  isConnected(profileId) {
    return this.active.has(profileId);
  }

  get(profileId) {
    return this.active.get(profileId) || null;
  }

  originFor(profileId) {
    return this.active.get(profileId)?.origin || null;
  }

  /**
   * Bring up a connection.
   * @param {string} profileId
   * @param {{passphrase?:string,password?:string}} [transient] credentials typed
   *   at the connect prompt and deliberately not persisted.
   */
  async connect(profileId, transient = {}) {
    const existing = this.active.get(profileId);
    if (existing) return { ok: true, origin: existing.origin, reused: true };

    const profile = profilesStore.get(profileId);
    if (!profile) throw new TunnelError('no-profile', 'That connection no longer exists.');

    const stored = profilesStore.resolveSecrets(profile);
    const credentials = {
      passphrase: transient.passphrase || stored.passphrase,
      password: transient.password || stored.password,
    };

    let tunnel = null;
    let origin;

    if (profile.transport === 'tunnel') {
      tunnel = new SshTunnel(profile, credentials);
      tunnel.on('status', (payload) =>
        this.emit('status', { profileId, phase: 'tunnel', ...payload }),
      );
      tunnel.on('host-key', (payload) => {
        if (payload.firstUse) profilesStore.rememberHostKey(profileId, payload.fingerprint);
        this.emit('host-key', { profileId, ...payload });
      });

      this.emit('status', {
        profileId,
        phase: 'tunnel',
        status: 'connecting',
        detail: 'Establishing SSH tunnel…',
      });

      const { localPort } = await tunnel.start();
      profilesStore.rememberLocalPort(profileId, localPort);
      origin = profilesStore.originFor(profile, localPort);
    } else {
      if (!profile.url) {
        throw new TunnelError('no-url', 'This connection has no server URL configured.');
      }
      origin = profile.url;
    }

    this.emit('status', {
      profileId,
      phase: 'probe',
      status: 'connecting',
      detail: 'Checking DeepSQL is responding…',
    });

    const health = await probe(origin, profile);
    if (!health.ok) {
      if (tunnel) await tunnel.stop();
      throw new TunnelError('unreachable', explainProbeFailure(profile, health));
    }

    // First successful TLS handshake in pinned/insecure mode establishes the pin.
    if (
      profile.transport === 'direct' &&
      health.fingerprint &&
      ['pinned', 'insecure'].includes(profile.tls.mode) &&
      !profile.tls.fingerprint
    ) {
      profilesStore.rememberCertFingerprint(profileId, health.fingerprint);
    }

    const entry = { profile, origin, tunnel, health, timer: null };
    this.active.set(profileId, entry);
    profilesStore.markConnected(profileId);
    this.startHealthPolling(profileId);

    log.info('transport', 'connected', {
      profileId,
      transport: profile.transport,
      origin,
      latencyMs: health.latencyMs,
    });

    this.emit('status', {
      profileId,
      phase: 'ready',
      status: 'ready',
      detail: 'Connected.',
      origin,
      latencyMs: health.latencyMs,
    });

    return {
      ok: true,
      origin,
      latencyMs: health.latencyMs,
      certificateFingerprint: health.fingerprint,
      hostKeyFingerprint: tunnel?.hostKeyFingerprint || '',
    };
  }

  startHealthPolling(profileId) {
    const entry = this.active.get(profileId);
    if (!entry) return;
    if (entry.timer) clearInterval(entry.timer);
    entry.timer = setInterval(async () => {
      const current = this.active.get(profileId);
      if (!current) return;
      const health = await probe(current.origin, current.profile, { timeoutMs: 8_000 });
      current.health = health;
      this.emit('health', { profileId, ...health });
    }, HEALTH_INTERVAL_MS);
    // A pending probe must not hold the process open at quit time.
    if (typeof entry.timer.unref === 'function') entry.timer.unref();
  }

  async disconnect(profileId) {
    const entry = this.active.get(profileId);
    if (!entry) return;
    if (entry.timer) clearInterval(entry.timer);
    if (entry.tunnel) await entry.tunnel.stop();
    this.active.delete(profileId);
    log.info('transport', 'disconnected', { profileId });
    this.emit('status', { profileId, phase: 'closed', status: 'closed', detail: '' });
  }

  async disconnectAll() {
    await Promise.all([...this.active.keys()].map((id) => this.disconnect(id)));
  }

  /**
   * Dry-run used by the "Test connection" button in the launcher. Brings the
   * transport up, probes, and tears it straight back down so testing never
   * leaves a stray tunnel behind.
   */
  async test(profileId, transient = {}) {
    const alreadyOpen = this.active.has(profileId);
    try {
      const result = await this.connect(profileId, transient);
      if (!alreadyOpen) await this.disconnect(profileId);
      return { ok: true, ...result };
    } catch (err) {
      return { ok: false, code: err.code || 'error', detail: err.message };
    }
  }
}

/**
 * An HTTP error *through a working tunnel* almost always means the tunnel
 * reached the wrong web server rather than that DeepSQL is down — typically the
 * host reverse proxy, which does name-based virtual hosting and cannot match
 * `Host: 127.0.0.1:<port>`. Say so, because "HTTP 404" on its own sends people
 * looking at the backend.
 */
function explainProbeFailure(profile, health) {
  if (profile.transport !== 'tunnel' || !health.status) return health.detail;
  const { remoteHost, remotePort } = profile.ssh;
  return (
    `${health.detail} The tunnel reached ${remoteHost}:${remotePort} inside the VM, but that ` +
    'server did not serve DeepSQL. If it is a reverse proxy matching on hostname, it cannot ' +
    'match the tunnel\'s Host header — point the remote port at the DeepSQL frontend container ' +
    'instead (3000 in the default Compose stack).'
  );
}

module.exports = new Transport();
