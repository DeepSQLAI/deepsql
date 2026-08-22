'use strict';

/**
 * End-to-end self-test for "an edited setting takes effect".
 *
 * Stands up two fake DeepSQL servers on different loopback ports and drives the
 * real profiles + transport code against them. Editing a profile to point at the
 * second server must move the live connection to it; that is the whole bug this
 * covers, and it is invisible to any check that only inspects what was saved,
 * because saving was never the broken half.
 *
 *   npm run selftest:settings
 */

const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { app } = require('electron');

// Before anything reads it: the real store holds the user's live connections.
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'deepsql-settings-test-'));
app.setPath('userData', tmpDir);

const results = [];
function check(name, condition, detail = '') {
  results.push({ name, ok: Boolean(condition), detail });
  process.stdout.write(`  ${condition ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}\n`);
}

/** A stand-in for the DeepSQL nginx, tagged so we can tell the two apart. */
function fakeDeepSql(label) {
  return http.createServer((req, res) => {
    if (req.url === '/api/actuator/health') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ status: 'UP', server: label }));
      return;
    }
    res.writeHead(404);
    res.end();
  });
}

function listen(server) {
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server.address().port)));
}

app.whenReady().then(async () => {
  const profiles = require('../src/main/profiles');
  const transport = require('../src/main/transport');

  const serverA = fakeDeepSql('A');
  const serverB = fakeDeepSql('B');
  const portA = await listen(serverA);
  const portB = await listen(serverB);
  const originA = `http://127.0.0.1:${portA}`;
  const originB = `http://127.0.0.1:${portB}`;

  process.stdout.write(`\nFake DeepSQL A=${originA}  B=${originB}\n\n`);

  try {
    // ── The reported bug: change a setting, and the behaviour must follow ──
    process.stdout.write('Edited settings reach the live connection\n');

    const saved = profiles.upsert({ name: 'Test', transport: 'direct', url: originA });
    const id = saved.id;

    const first = await transport.connect(id);
    check('connects to the configured server', first.origin === originA, first.origin);

    const second = await transport.connect(id);
    check(
      'an unchanged reconnect reuses the live connection',
      second.reused === true && second.origin === originA,
      `reused=${second.reused} origin=${second.origin}`,
    );

    // Exactly what the launcher does on Connect: persist the form, then connect.
    profiles.upsert({ id, name: 'Test', transport: 'direct', url: originB });
    const third = await transport.connect(id);
    check(
      'connecting after an edit moves to the new server',
      third.origin === originB,
      `origin=${third.origin}${third.reused ? ' (REUSED STALE CONNECTION)' : ''}`,
    );
    check(
      'the stale connection was not reused',
      third.reused !== true,
      third.reused ? 'connect() returned the pre-edit origin' : '',
    );

    // ── reconcile(): what makes a save take effect immediately ────────────
    process.stdout.write('\nreconcile() applies a save without waiting for Connect\n');

    profiles.upsert({ id, name: 'Test', transport: 'direct', url: originA });
    const reconciled = await transport.reconcile(id);
    check(
      'reconcile rebuilds onto the saved settings',
      reconciled.changed === true && reconciled.ok === true && reconciled.origin === originA,
      `changed=${reconciled.changed} ok=${reconciled.ok} origin=${reconciled.origin}`,
    );
    check(
      'the live origin now matches the saved profile',
      transport.originFor(id) === originA,
      transport.originFor(id),
    );

    // A rename must not cost the user their session.
    const before = transport.get(id);
    profiles.upsert({ id, name: 'Renamed', transport: 'direct', url: originA });
    const renamed = await transport.reconcile(id);
    check(
      'renaming does not rebuild the connection',
      renamed.changed === false && transport.get(id) === before,
      `changed=${renamed.changed} sameEntry=${transport.get(id) === before}`,
    );

    // ── A failed rebuild must not leave the old connection running ────────
    process.stdout.write('\nA rebuild that fails leaves no half-applied state\n');

    await new Promise((resolve) => serverB.close(resolve));
    profiles.upsert({ id, name: 'Renamed', transport: 'direct', url: originB });
    const failed = await transport.reconcile(id);
    check(
      'reconcile reports the failure',
      failed.changed === true && failed.ok === false,
      `ok=${failed.ok} detail=${failed.detail}`,
    );
    check(
      'no connection is left serving the replaced settings',
      transport.isConnected(id) === false,
      transport.isConnected(id) ? `still connected to ${transport.originFor(id)}` : '',
    );

    // ── test() must describe the settings on screen ───────────────────────
    process.stdout.write('\nTest reports on current settings, not the live ones\n');

    profiles.upsert({ id, name: 'Renamed', transport: 'direct', url: originA });
    await transport.connect(id);
    const serverC = fakeDeepSql('C');
    const portC = await listen(serverC);
    const originC = `http://127.0.0.1:${portC}`;
    profiles.upsert({ id, name: 'Renamed', transport: 'direct', url: originC });
    const tested = await transport.test(id);
    check(
      'testing after an edit exercises the edited settings',
      tested.ok === true && tested.rebuilt === true && transport.originFor(id) === originC,
      `ok=${tested.ok} rebuilt=${tested.rebuilt} live=${transport.originFor(id)}`,
    );
    await new Promise((resolve) => serverC.close(resolve));

    // ── The fingerprint itself ────────────────────────────────────────────
    process.stdout.write('\nTransport fingerprint\n');

    const base = profiles.get(id);
    const sameName = { ...base, name: 'Something else' };
    check(
      'name is not part of the transport identity',
      profiles.transportFingerprint(base) === profiles.transportFingerprint(sameName),
    );
    const otherPort = { ...base, ssh: { ...base.ssh, remotePort: base.ssh.remotePort + 1 } };
    check(
      'the SSH remote port is part of it',
      profiles.transportFingerprint(base) !== profiles.transportFingerprint(otherPort),
    );
    const otherLocal = { ...base, ssh: { ...base.ssh, localPort: 44444 } };
    check(
      'the pinned local port is part of it',
      profiles.transportFingerprint(base) !== profiles.transportFingerprint(otherLocal),
    );
    const otherSticky = { ...base, ssh: { ...base.ssh, stickyLocalPort: 44444 } };
    check(
      'the sticky local port is NOT (we choose it, not the user)',
      profiles.transportFingerprint(base) === profiles.transportFingerprint(otherSticky),
    );

    await transport.disconnectAll();
  } catch (err) {
    check('self-test ran to completion', false, err.stack || err.message);
  }

  await new Promise((resolve) => serverA.close(resolve));

  const failed = results.filter((r) => !r.ok);
  process.stdout.write(
    `\n${results.length - failed.length}/${results.length} checks passed\n`,
  );
  fs.rmSync(tmpDir, { recursive: true, force: true });
  app.exit(failed.length === 0 ? 0 : 1);
});
