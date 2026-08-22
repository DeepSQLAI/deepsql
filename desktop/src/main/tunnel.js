'use strict';

/**
 * SSH tunnel transport.
 *
 * Equivalent to `ssh -i key.pem -L <local>:127.0.0.1:80 ubuntu@vm`, run in
 * process so there is no dependency on an ssh binary being installed (Windows
 * especially). A loopback listener accepts connections from the embedded
 * browser and each one is forwarded over the SSH channel to the DeepSQL nginx
 * on the VM.
 *
 * Why this matters for DeepSQL specifically: the compose stack only publishes
 * the frontend and backend ports to the host, and a hardened VM keeps them
 * behind the firewall. Tunnelling reaches them without exposing anything to the
 * internet, and the app ends up on a `http://127.0.0.1:<port>` origin — which
 * Chromium treats as a secure context, so the backend's `Secure` session
 * cookies are still accepted.
 */

const fs = require('node:fs');
const net = require('node:net');
const crypto = require('node:crypto');
const { EventEmitter } = require('node:events');

const { Client, utils: sshUtils } = require('ssh2');

const log = require('./logger');

const READY_TIMEOUT_MS = 20_000;
const KEEPALIVE_MS = 15_000;
const RECONNECT_DELAYS_MS = [1_000, 2_000, 5_000, 10_000, 20_000];

class TunnelError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

/** `SHA256:base64` — the same format OpenSSH prints. */
function hostKeyFingerprint(key) {
  return `SHA256:${crypto.createHash('sha256').update(key).digest('base64').replace(/=+$/, '')}`;
}

/**
 * Validate the private key up front, translating ssh2's terse parse errors into
 * something a user can act on, and hand back the *raw* key material.
 *
 * Returning the raw buffer rather than the parsed key object is deliberate:
 * `Client.connect` re-parses `privateKey` itself and only offers the publickey
 * method when that succeeds. Given an already-parsed key object it quietly
 * skips publickey entirely and the server answers with a bare authentication
 * failure — a very misleading symptom for what is really a type mismatch.
 *
 * @returns {Buffer} the key file contents, for `Client.connect({ privateKey })`
 */
function loadPrivateKey(privateKeyPath, passphrase) {
  if (!privateKeyPath) {
    throw new TunnelError('key-missing', 'No private key file selected.');
  }
  let raw;
  try {
    raw = fs.readFileSync(privateKeyPath);
  } catch (err) {
    throw new TunnelError(
      'key-unreadable',
      `Could not read the private key at ${privateKeyPath}: ${err.message}`,
    );
  }

  const parsed = sshUtils.parseKey(raw, passphrase || undefined);
  if (parsed instanceof Error) {
    const message = parsed.message || '';
    if (/passphrase/i.test(message) && /no passphrase|missing/i.test(message)) {
      throw new TunnelError(
        'passphrase-required',
        'This private key is encrypted. Enter its passphrase to continue.',
      );
    }
    if (/bad passphrase|decrypt|integrity/i.test(message)) {
      throw new TunnelError(
        'passphrase-invalid',
        'That passphrase did not unlock the private key.',
      );
    }
    if (/Cannot parse privateKey|unsupported/i.test(message)) {
      throw new TunnelError(
        'key-unsupported',
        'Unsupported private key format. Export it as OpenSSH or PEM (ssh-keygen -p -m PEM -f key.pem).',
      );
    }
    throw new TunnelError('key-invalid', message || 'Invalid private key.');
  }
  return raw;
}

class SshTunnel extends EventEmitter {
  /**
   * @param {object} profile     full profile record (main-process side)
   * @param {object} credentials `{ passphrase, password }` resolved from safeStorage
   */
  constructor(profile, credentials) {
    super();
    this.profile = profile;
    this.credentials = credentials || {};
    this.client = null;
    this.server = null;
    this.localPort = null;
    this.status = 'idle';
    this.closedByUs = false;
    // Only a session that once reached `ready` is worth reconnecting. Without
    // this, a connect that fails on authentication would be retried forever
    // behind a caller that has already given up and reported the error.
    this.everReady = false;
    this.reconnectPending = false;
    this.reconnectAttempt = 0;
    this.reconnectTimer = null;
    this.sockets = new Set();
    this.hostKeyFingerprint = '';
  }

  setStatus(status, detail) {
    this.status = status;
    this.emit('status', { status, detail: detail || '' });
  }

  /** Resolves once the loopback listener is bound and SSH is authenticated. */
  async start() {
    this.closedByUs = false;
    try {
      const key =
        this.profile.ssh.authMethod === 'key'
          ? loadPrivateKey(this.profile.ssh.privateKeyPath, this.credentials.passphrase)
          : null;

      await this.connectSsh(key);
      await this.verifyForwarding();
      await this.listenLocally();
    } catch (err) {
      // Leave nothing behind: a half-open SSH client or a bound listener after
      // a failed start would linger with no owner to close it.
      await this.stop();
      throw err;
    }
    this.setStatus('ready');
    return { localPort: this.localPort, hostKeyFingerprint: this.hostKeyFingerprint };
  }

  /**
   * Open and immediately close one forwarding channel before binding the local
   * listener.
   *
   * Authentication succeeding says nothing about whether forwarding is allowed:
   * a hardened sshd (`AllowTcpForwarding no`) accepts the login and then refuses
   * every direct-tcpip channel. Without this check the failure only appears once
   * the browser makes a request, arriving as a bare "socket hang up" that points
   * nowhere near the actual cause.
   */
  verifyForwarding() {
    const { ssh } = this.profile;
    return new Promise((resolve, reject) => {
      this.client.forwardOut('127.0.0.1', 0, ssh.remoteHost, ssh.remotePort, (err, stream) => {
        if (err) {
          reject(this.translateChannelError(err));
          return;
        }
        stream.destroy();
        resolve();
      });
    });
  }

  /** Turn an SSH channel-open failure into the specific thing that is wrong. */
  translateChannelError(err) {
    const { ssh } = this.profile;
    const target = `${ssh.remoteHost}:${ssh.remotePort}`;
    // ssh2 reports `reason` as the constant name, older builds as its number.
    const reason = String(err?.reason ?? '');

    if (reason === 'ADMINISTRATIVELY_PROHIBITED' || reason === '1') {
      return new TunnelError(
        'forwarding-denied',
        `${ssh.host} accepted the login but its SSH server refuses port forwarding, ` +
          'so the tunnel cannot carry any traffic. Check `sudo sshd -T | grep -i ' +
          'allowtcpforwarding` on the VM — if it says "no", either use the Direct over TLS ' +
          'transport instead, or allow forwarding for this user only.',
      );
    }
    if (reason === 'CONNECT_FAILED' || reason === '2') {
      return new TunnelError(
        'remote-port-closed',
        `The SSH server allows forwarding, but nothing accepted a connection on ${target} ` +
          'from inside the VM. Use remote port 80 when a reverse proxy fronts DeepSQL, or ' +
          '3000 if the frontend container is published directly.',
      );
    }
    if (reason === 'RESOURCE_SHORTAGE' || reason === '4') {
      return new TunnelError(
        'forwarding-exhausted',
        `${ssh.host} is out of resources for new forwarding channels. Try again shortly.`,
      );
    }
    return new TunnelError(
      'forwarding-failed',
      `The VM refused to open a forwarding channel to ${target}: ${err?.message || 'unknown error'}`,
    );
  }

  connectSsh(key) {
    const { ssh } = this.profile;
    return new Promise((resolve, reject) => {
      const client = new Client();
      this.client = client;
      this.setStatus('connecting', `Opening SSH session to ${ssh.username}@${ssh.host}…`);

      let settled = false;
      const settleOk = () => {
        if (settled) return;
        settled = true;
        resolve();
      };
      const settleErr = (err) => {
        if (settled) {
          // Post-ready failure: this is a drop, not a start-up failure.
          if (this.everReady) this.handleDrop(err);
          return;
        }
        settled = true;
        reject(err);
      };

      client.on('ready', () => {
        this.everReady = true;
        this.reconnectAttempt = 0;
        log.info('tunnel', 'ssh session ready', { host: ssh.host, user: ssh.username });
        settleOk();
      });

      client.on('error', (err) => settleErr(this.translateSshError(err)));

      client.on('close', () => {
        if (!settled) {
          settleErr(
            new TunnelError('ssh-closed', 'The SSH connection closed before it was ready.'),
          );
          return;
        }
        this.handleDrop(new TunnelError('ssh-closed', 'The SSH connection closed.'));
      });

      const config = {
        host: ssh.host,
        port: ssh.port,
        username: ssh.username,
        readyTimeout: READY_TIMEOUT_MS,
        keepaliveInterval: KEEPALIVE_MS,
        keepaliveCountMax: 4,
        hostVerifier: (hostKey) => this.verifyHostKey(hostKey),
      };

      if (ssh.authMethod === 'key') {
        config.privateKey = key;
        if (this.credentials.passphrase) config.passphrase = this.credentials.passphrase;
      } else if (ssh.authMethod === 'password') {
        if (!this.credentials.password) {
          settleErr(
            new TunnelError('password-required', 'Enter the SSH password for this host.'),
          );
          return;
        }
        config.password = this.credentials.password;
      } else {
        const agentSock =
          process.platform === 'win32'
            ? process.env.SSH_AUTH_SOCK || 'pageant'
            : process.env.SSH_AUTH_SOCK;
        if (!agentSock) {
          settleErr(
            new TunnelError(
              'agent-unavailable',
              'No SSH agent found (SSH_AUTH_SOCK is not set). Start ssh-agent or switch to key file authentication.',
            ),
          );
          return;
        }
        config.agent = agentSock;
      }

      client.connect(config);
    });
  }

  /**
   * Trust on first use, then strict. A changed host key is exactly the signal
   * of a man-in-the-middle, so a mismatch is a hard failure with the two
   * fingerprints spelled out rather than a dialog the user can click through.
   */
  verifyHostKey(hostKey) {
    const fingerprint = hostKeyFingerprint(hostKey);
    this.hostKeyFingerprint = fingerprint;
    const pinned = this.profile.ssh.hostKeyFingerprint;
    if (!pinned) {
      this.emit('host-key', { fingerprint, firstUse: true });
      return true;
    }
    if (pinned === fingerprint) return true;
    this.emit('host-key', { fingerprint, expected: pinned, firstUse: false, mismatch: true });
    return false;
  }

  /**
   * Bind the loopback listener.
   *
   * Port choice is sticky on purpose. The tunnel origin is
   * `http://127.0.0.1:<port>`, and localStorage/IndexedDB are keyed by origin
   * *including the port* — a fresh random port every launch would silently
   * reset the user's DeepSQL UI state. So we reuse the port we bound last time
   * when the user has not pinned one, and only fall back to a random port if
   * something else has taken it.
   */
  listenLocally() {
    const { ssh } = this.profile;
    const pinned = ssh.localPort || 0;
    const preferred = pinned || ssh.stickyLocalPort || 0;

    const bind = (port, allowFallback) =>
      new Promise((resolve, reject) => {
        const server = net.createServer((socket) => this.handleLocalSocket(socket));
        this.server = server;

        server.on('error', (err) => {
          server.removeAllListeners();
          this.server = null;
          if (err.code === 'EADDRINUSE' && allowFallback) {
            log.warn('tunnel', 'preferred local port busy, falling back', { port });
            resolve(bind(0, false));
            return;
          }
          if (err.code === 'EADDRINUSE') {
            reject(
              new TunnelError(
                'port-in-use',
                `Local port ${port} is already in use. Set the local port to 0 to pick one automatically.`,
              ),
            );
            return;
          }
          reject(new TunnelError('listen-failed', err.message));
        });

        // Bind loopback only: the tunnel must never be reachable from the LAN.
        server.listen(port, '127.0.0.1', () => {
          this.localPort = server.address().port;
          log.info('tunnel', 'loopback listener bound', {
            localPort: this.localPort,
            remote: `${ssh.remoteHost}:${ssh.remotePort}`,
          });
          resolve();
        });
      });

    return bind(preferred, preferred !== 0 && pinned === 0);
  }

  handleLocalSocket(socket) {
    const { ssh } = this.profile;
    this.sockets.add(socket);
    socket.on('close', () => this.sockets.delete(socket));
    socket.on('error', () => socket.destroy());

    if (!this.client || this.status !== 'ready') {
      // Mid-reconnect: fail fast so the browser retries instead of hanging.
      socket.destroy();
      return;
    }

    this.client.forwardOut(
      '127.0.0.1',
      socket.remotePort || 0,
      ssh.remoteHost,
      ssh.remotePort,
      (err, stream) => {
        if (err) {
          log.warn('tunnel', 'forwardOut failed', { message: err.message });
          socket.destroy();
          return;
        }
        stream.on('error', () => socket.destroy());
        socket.pipe(stream).pipe(socket);
      },
    );
  }

  handleDrop(err) {
    if (this.closedByUs || this.reconnectPending) return;
    log.warn('tunnel', 'ssh session dropped', { message: err.message });

    // Drop in-flight sockets; they are bound to a channel that no longer exists.
    for (const socket of this.sockets) socket.destroy();
    this.sockets.clear();

    const delay =
      RECONNECT_DELAYS_MS[Math.min(this.reconnectAttempt, RECONNECT_DELAYS_MS.length - 1)];
    this.reconnectAttempt += 1;
    this.reconnectPending = true;
    this.setStatus(
      'reconnecting',
      `Connection lost. Reconnecting in ${Math.round(delay / 1000)}s…`,
    );

    this.reconnectTimer = setTimeout(async () => {
      this.reconnectPending = false;
      if (this.closedByUs) return;
      try {
        const key =
          this.profile.ssh.authMethod === 'key'
            ? loadPrivateKey(this.profile.ssh.privateKeyPath, this.credentials.passphrase)
            : null;
        await this.connectSsh(key);
        this.setStatus('ready', 'Reconnected.');
      } catch (retryErr) {
        this.handleDrop(retryErr);
      }
    }, delay);
  }

  translateSshError(err) {
    const message = err?.message || 'SSH connection failed.';
    if (err?.level === 'client-authentication' || /All configured auth/i.test(message)) {
      return new TunnelError(
        'auth-failed',
        'SSH authentication failed. Check the username, key file, and that the key is authorised on the VM.',
      );
    }
    if (/host key/i.test(message) || /verification/i.test(message)) {
      const pinned = this.profile.ssh.hostKeyFingerprint;
      return new TunnelError(
        'host-key-mismatch',
        `The VM presented host key ${this.hostKeyFingerprint}, but ${pinned} is pinned for this connection. ` +
          'If the VM was genuinely rebuilt, clear the pinned host key in the connection settings; otherwise stop and investigate.',
      );
    }
    if (err?.code === 'ENOTFOUND') {
      return new TunnelError('dns', `Could not resolve SSH host ${this.profile.ssh.host}.`);
    }
    if (err?.code === 'ECONNREFUSED') {
      return new TunnelError(
        'ssh-refused',
        `Nothing is listening on ${this.profile.ssh.host}:${this.profile.ssh.port}.`,
      );
    }
    if (err?.code === 'ETIMEDOUT' || /Timed out/i.test(message)) {
      return new TunnelError(
        'ssh-timeout',
        `Timed out reaching ${this.profile.ssh.host}:${this.profile.ssh.port}. Check the security group / firewall allows SSH from your IP.`,
      );
    }
    return err instanceof TunnelError ? err : new TunnelError('ssh-error', message);
  }

  async stop() {
    this.closedByUs = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    for (const socket of this.sockets) socket.destroy();
    this.sockets.clear();
    if (this.server) {
      await new Promise((resolve) => this.server.close(resolve));
      this.server = null;
    }
    if (this.client) {
      this.client.end();
      this.client = null;
    }
    this.setStatus('closed');
    log.info('tunnel', 'tunnel stopped', { profileId: this.profile.id });
  }
}

module.exports = { SshTunnel, TunnelError, loadPrivateKey, hostKeyFingerprint };
