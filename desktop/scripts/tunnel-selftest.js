'use strict';

/**
 * End-to-end self-test for the SSH tunnel transport.
 *
 * Stands up a throwaway SSH server and a fake DeepSQL health endpoint on
 * loopback, then drives the real SshTunnel + probe code against them. It
 * exercises the parts that are otherwise only reachable with a live VM and a
 * private key: key parsing, publickey auth, host-key pinning, direct-tcpip
 * forwarding, and the sticky local port.
 *
 *   npm run selftest:tunnel
 */

const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const { app } = require('electron');

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'deepsql-tunnel-test-'));
app.setPath('userData', tmpDir);

const results = [];
function check(name, condition, detail = '') {
  results.push({ name, ok: Boolean(condition), detail });
  process.stdout.write(`  ${condition ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}\n`);
}

app.whenReady().then(async () => {
  const { Server } = require('ssh2');
  const { SshTunnel, hostKeyFingerprint } = require('../src/main/tunnel');
  const { probe } = require('../src/main/probe');

  // ── Fixtures ───────────────────────────────────────────────────────────
  const { privateKey, publicKeyPem } = generateRsaPem();
  const keyPath = path.join(tmpDir, 'test-key.pem');
  fs.writeFileSync(keyPath, privateKey, { mode: 0o600 });

  // Stands in for the DeepSQL nginx inside the VM.
  //
  // It also emulates Spring's CORS check, because that is a real failure mode
  // of the tunnel transport: the origin is http://127.0.0.1:<sticky port>, and
  // a VM whose CORS_ALLOWED_ORIGINS lists only its hostname answers 403 with a
  // plain-text body. Spring rejects on the presence of `Origin` alone — there
  // is no same-origin exemption — so this mirrors that rule exactly.
  let lastProbeOrigin = null;
  let corsAllowedOrigins = null; // null = allow everything
  const upstream = http.createServer((req, res) => {
    if (req.url === '/api/actuator/health') {
      const origin = req.headers.origin || null;
      lastProbeOrigin = origin;
      if (origin && corsAllowedOrigins && !corsAllowedOrigins.includes(origin)) {
        res.writeHead(403, { 'Content-Type': 'text/plain' });
        res.end('Invalid CORS request');
        return;
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{"status":"UP"}');
      return;
    }
    res.writeHead(404).end();
  });
  await listen(upstream, 0, '127.0.0.1');
  const upstreamPort = upstream.address().port;

  let forwardedTo = null;
  // Flipped on to emulate a VM with `AllowTcpForwarding no`: login succeeds,
  // every direct-tcpip channel is refused.
  let refuseForwarding = false;
  const sshServer = new Server({ hostKeys: [privateKey] }, (client) => {
    client.on('authentication', (ctx) => {
      // Accepting any publickey is fine here: the point is to exercise our
      // client, not to be a real authenticator.
      if (ctx.method === 'publickey') ctx.accept();
      else ctx.reject(['publickey']);
    });
    client.on('ready', () => {
      client.on('tcpip', (accept, reject, info) => {
        if (refuseForwarding) {
          // 1 = ADMINISTRATIVELY_PROHIBITED. Verified against a real OpenSSH
          // server with `AllowTcpForwarding no`, which sends exactly this with
          // the description "open failed". A bare reject() would default to
          // CONNECT_FAILED and test the wrong branch.
          reject(1, 'open failed');
          return;
        }
        forwardedTo = `${info.destIP}:${info.destPort}`;
        const channel = accept();
        const socket = net.connect(info.destPort, info.destIP, () => {
          channel.pipe(socket).pipe(channel);
        });
        socket.on('error', () => channel.end());
      });
    });
    client.on('error', () => {});
  });
  await listen(sshServer, 0, '127.0.0.1');
  const sshPort = sshServer.address().port;

  const expectedHostKey = hostKeyFingerprint(publicKeyPem.sshWireFormat);

  // ── 1. First connection pins the host key and forwards traffic ─────────
  const profile = makeProfile({ keyPath, sshPort, upstreamPort });
  const tunnel = new SshTunnel(profile, {});
  const { localPort, hostKeyFingerprint: seenHostKey } = await tunnel.start();

  check('tunnel binds a loopback port', localPort > 0, `port ${localPort}`);
  check(
    'host key fingerprint matches the server key',
    seenHostKey === expectedHostKey,
    seenHostKey,
  );

  const health = await probe(`http://127.0.0.1:${localPort}`, profile);
  check('health probe succeeds through the tunnel', health.ok, health.detail);
  check(
    'traffic is forwarded to the configured remote endpoint',
    forwardedTo === `127.0.0.1:${upstreamPort}`,
    forwardedTo,
  );

  // The probe must announce the origin the browser is about to use, or a CORS
  // allowlist that omits it stays invisible until the user's first login POST.
  check(
    'probe sends the browser origin so CORS is checked at connect time',
    lastProbeOrigin === `http://127.0.0.1:${localPort}`,
    lastProbeOrigin === null ? 'no Origin header sent' : lastProbeOrigin,
  );

  // Same tunnel, same healthy backend — only the allowlist changes.
  corsAllowedOrigins = ['https://deepsql.example.com'];
  const corsHealth = await probe(`http://127.0.0.1:${localPort}`, profile);
  check(
    'an origin missing from CORS_ALLOWED_ORIGINS fails the probe',
    !corsHealth.ok && corsHealth.status === 403,
    `ok=${corsHealth.ok} status=${corsHealth.status}`,
  );
  corsAllowedOrigins = null;

  // The listener must never be reachable from anything but loopback.
  check(
    'listener is bound to loopback only',
    tunnel.server.address().address === '127.0.0.1',
    tunnel.server.address().address,
  );

  await tunnel.stop();
  check('stop() releases the local port', await portIsFree(localPort));

  // ── 2. A changed host key is refused ───────────────────────────────────
  const tamperedProfile = makeProfile({ keyPath, sshPort, upstreamPort });
  tamperedProfile.ssh.hostKeyFingerprint = 'SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
  const strictTunnel = new SshTunnel(tamperedProfile, {});
  let rejected = null;
  try {
    await strictTunnel.start();
  } catch (err) {
    rejected = err;
  }
  await strictTunnel.stop();
  check(
    'mismatched host key aborts the connection',
    rejected !== null,
    rejected ? rejected.code : 'connection was allowed',
  );

  // ── 3. A refused forwarding channel fails before the listener binds ────
  // Regression guard: this used to surface as "socket hang up" only once the
  // browser made a request, with nothing pointing at the SSH layer at all.
  //
  // ssh2's server-side reject() takes no arguments and always answers
  // CONNECT_FAILED, so the live half of this test can only cover that branch.
  // The ADMINISTRATIVELY_PROHIBITED branch — what a real `AllowTcpForwarding no`
  // server sends — is checked directly below against the error shape observed
  // from OpenSSH: `{ reason: 1, message: '(SSH) Channel open failure: open failed' }`.
  refuseForwarding = true;
  const deniedProfile = makeProfile({ keyPath, sshPort, upstreamPort });
  const deniedTunnel = new SshTunnel(deniedProfile, {});
  let denied = null;
  try {
    await deniedTunnel.start();
  } catch (err) {
    denied = err;
  }
  await deniedTunnel.stop();
  check(
    'a refused channel fails start() instead of the later HTTP request',
    denied?.code === 'remote-port-closed',
    denied ? denied.code : 'start() succeeded',
  );
  check(
    'the failure never leaves a listener bound',
    deniedTunnel.server === null || deniedTunnel.localPort === null,
    `server=${deniedTunnel.server === null ? 'null' : 'bound'}`,
  );
  refuseForwarding = false;

  const classifier = new SshTunnel(makeProfile({ keyPath, sshPort, upstreamPort }), {});
  const prohibited = classifier.translateChannelError({
    reason: 1,
    message: '(SSH) Channel open failure: open failed',
  });
  check(
    'AllowTcpForwarding=no is named as the cause',
    prohibited.code === 'forwarding-denied' && /allowtcpforwarding/i.test(prohibited.message),
    prohibited.code,
  );
  check(
    'a closed remote port is distinguished from a forwarding ban',
    classifier.translateChannelError({ reason: 2 }).code === 'remote-port-closed',
  );

  // ── 4. Missing key file fails with an actionable message ───────────────
  const noKeyProfile = makeProfile({ keyPath: path.join(tmpDir, 'nope.pem'), sshPort, upstreamPort });
  let keyError = null;
  try {
    await new SshTunnel(noKeyProfile, {}).start();
  } catch (err) {
    keyError = err;
  }
  check('missing key file is reported clearly', keyError?.code === 'key-unreadable', keyError?.code);

  // ── 5. Teardown ────────────────────────────────────────────────────────
  sshServer.close();
  upstream.close();
  fs.rmSync(tmpDir, { recursive: true, force: true });

  const failed = results.filter((r) => !r.ok).length;
  process.stdout.write(`\n${results.length - failed}/${results.length} checks passed\n`);
  app.exit(failed === 0 ? 0 : 1);
});

function makeProfile({ keyPath, sshPort, upstreamPort }) {
  return {
    id: 'selftest',
    name: 'selftest',
    transport: 'tunnel',
    tls: { mode: 'system' },
    ssh: {
      host: '127.0.0.1',
      port: sshPort,
      username: 'tester',
      authMethod: 'key',
      privateKeyPath: keyPath,
      remoteHost: '127.0.0.1',
      remotePort: upstreamPort,
      remoteScheme: 'http',
      localPort: 0,
      stickyLocalPort: 0,
      hostKeyFingerprint: '',
    },
  };
}

function generateRsaPem() {
  const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', {
    modulusLength: 2048,
    privateKeyEncoding: { type: 'pkcs1', format: 'pem' },
    publicKeyEncoding: { type: 'pkcs1', format: 'der' },
  });
  // ssh2 fingerprints the SSH wire format of the public key, so rebuild it from
  // the RSA modulus/exponent rather than trusting the DER encoding.
  const jwk = crypto.createPublicKey(privateKey).export({ format: 'jwk' });
  const e = Buffer.from(jwk.e, 'base64url');
  const n = Buffer.from(jwk.n, 'base64url');
  const sshWireFormat = Buffer.concat([
    sshString(Buffer.from('ssh-rsa')),
    sshString(prefixZeroIfSigned(e)),
    sshString(prefixZeroIfSigned(n)),
  ]);
  return { privateKey, publicKeyPem: { der: publicKey, sshWireFormat } };
}

function sshString(buffer) {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(buffer.length);
  return Buffer.concat([length, buffer]);
}

function prefixZeroIfSigned(buffer) {
  return buffer[0] & 0x80 ? Buffer.concat([Buffer.from([0]), buffer]) : buffer;
}

function listen(server, port, host) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => resolve());
  });
}

function portIsFree(port) {
  return new Promise((resolve) => {
    const socket = net.connect(port, '127.0.0.1');
    socket.on('connect', () => {
      socket.destroy();
      resolve(false);
    });
    socket.on('error', () => resolve(true));
  });
}
