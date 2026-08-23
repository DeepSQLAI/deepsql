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
    const profile = profilesStore.get(profileId);
    if (!profile) throw new TunnelError('no-profile', 'That connection no longer exists.');

    // Reuse a live connection only when it is still the connection the user is
    // asking for. Reusing unconditionally is what made edits look ignored: the
    // launcher persists the form before every Connect, so the stored profile was
    // already correct and this early return handed back the *old* origin and
    // left the old tunnel forwarding to the old port.
    const existing = this.active.get(profileId);
    if (existing) {
      if (existing.fingerprint === profilesStore.transportFingerprint(profile)) {
        return { ok: true, origin: existing.origin, reused: true };
      }
      log.info('transport', 'settings changed under a live connection, rebuilding', {
        profileId,
      });
      await this.disconnect(profileId);
    }

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
      throw new TunnelError(
        health.status === 403 ? 'cors-rejected' : 'unreachable',
        explainProbeFailure(profile, origin, health),
      );
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

    // Re-read rather than reusing the `profile` fetched above: the connect path
    // writes back through the store (trust-on-first-use pins the host key and
    // the TLS certificate, and the bound local port is remembered), so the
    // fingerprint has to be taken from the state that now exists on disk or the
    // very next connect would see a mismatch it caused itself.
    const settled = profilesStore.get(profileId) || profile;
    const entry = {
      profile: settled,
      origin,
      tunnel,
      health,
      timer: null,
      fingerprint: profilesStore.transportFingerprint(settled),
    };
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
    const wasOpen = this.active.has(profileId);
    try {
      // connect() reuses only a connection matching the stored settings, so a
      // pass here always describes the settings on screen. Previously a live
      // connection was reused whatever it was built from, and Test reported a
      // confident success for settings the user had just replaced.
      const result = await this.connect(profileId, transient);
      // Only tear down a connection this call created. If connect() had to
      // rebuild a live one, the rebuild *is* the connection the user wants —
      // leave it up and tell the caller, whose workspace window is still
      // pointed at the old origin.
      if (!wasOpen) await this.disconnect(profileId);
      return { ok: true, rebuilt: wasOpen && !result.reused, ...result };
    } catch (err) {
      return {
        ok: false,
        code: err.code || 'error',
        detail: err.message,
        // A failed rebuild has already closed the old connection: it was built
        // from settings that no longer exist, so keeping it would be the stale
        // state this whole change exists to remove. Say so, rather than leaving
        // the user to discover the session went away.
        closedStaleConnection: wasOpen && !this.active.has(profileId),
      };
    }
  }

  /**
   * Bring a live connection back in line with its stored profile.
   *
   * Called after a profile is saved, so an edit takes effect at once instead of
   * at some later reconnect. Returns what happened so the caller can re-point
   * the workspace window: a tunnel rebuild almost always lands on a different
   * local port, and therefore a different origin.
   *
   * @returns {{changed:boolean, ok:boolean, origin?:string, code?:string, detail?:string}}
   */
  async reconcile(profileId) {
    const entry = this.active.get(profileId);
    if (!entry) return { changed: false, ok: true };

    const profile = profilesStore.get(profileId);
    if (!profile) return { changed: false, ok: true };

    if (entry.fingerprint === profilesStore.transportFingerprint(profile)) {
      return { changed: false, ok: true, origin: entry.origin };
    }

    log.info('transport', 'reconciling live connection with saved settings', { profileId });
    try {
      // connect() sees the mismatch and rebuilds; going through it keeps one
      // code path for building a connection.
      const result = await this.connect(profileId);
      return { changed: true, ok: true, origin: result.origin };
    } catch (err) {
      // The old connection is already down (connect() tears a mismatched one
      // down before rebuilding). Report rather than resurrect it: it served
      // settings the user has replaced.
      log.error('transport', 'reconcile failed', {
        profileId,
        code: err.code,
        message: err.message,
      });
      return { changed: true, ok: false, code: err.code || 'error', detail: err.message };
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
function explainProbeFailure(profile, origin, health) {
  // 403 is its own diagnosis and applies to both transports: the probe sends an
  // Origin header, so the one thing that rejects a *reachable, healthy* DeepSQL
  // with 403 is its CORS allowlist. Checked before the tunnel branch below,
  // which would otherwise blame the remote port for a backend that answered
  // perfectly well.
  if (health.status === 403) {
    return (
      `DeepSQL is running and reachable, but its backend refused the origin ${origin} ` +
      '(HTTP 403, "Invalid CORS request"). Add that origin to CORS_ALLOWED_ORIGINS on the ' +
      'VM and restart the backend. A port wildcard is the durable form, because the local ' +
      'port changes: `CORS_ALLOWED_ORIGINS=https://your-host,http://127.0.0.1:*,' +
      'http://localhost:*`. Left unfixed, DeepSQL would load but every login would fail.'
    );
  }
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
