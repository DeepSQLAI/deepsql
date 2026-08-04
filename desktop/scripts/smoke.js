'use strict';

/**
 * Headless connection check — the same transport code the app uses, without a
 * window. Handy for diagnosing a VM from a terminal or CI before shipping a
 * profile to users.
 *
 *   npm run smoke -- --url https://deepsql.example.com
 *   npm run smoke -- --url https://10.0.0.5 --tls pinned
 *   npm run smoke -- --ssh-host 20.29.48.144 --ssh-user ubuntu \
 *                    --key ~/keys/vm.pem [--passphrase secret] [--remote-port 80]
 *
 * It runs against a throwaway userData directory so it never touches the
 * profiles or keychain entries of the installed app.
 */

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { app } = require('electron');

const tmpUserData = fs.mkdtempSync(path.join(os.tmpdir(), 'deepsql-smoke-'));
app.setPath('userData', tmpUserData);

const args = parseArgs(process.argv.slice(2));

app.whenReady().then(async () => {
  const profiles = require('../src/main/profiles');
  const transport = require('../src/main/transport');

  const useTunnel = Boolean(args['ssh-host']);
  const profile = profiles.upsert({
    name: 'smoke',
    transport: useTunnel ? 'tunnel' : 'direct',
    url: args.url || '',
    tls: { mode: args.tls || 'system', fingerprint: args.fingerprint || '', caPath: args.ca || '' },
    ssh: {
      host: args['ssh-host'] || '',
      port: args['ssh-port'] || 22,
      username: args['ssh-user'] || 'ubuntu',
      authMethod: args.key ? 'key' : args.password ? 'password' : 'agent',
      privateKeyPath: args.key ? expand(args.key) : '',
      passphrase: args.passphrase,
      password: args.password,
      remoteHost: args['remote-host'] || '127.0.0.1',
      remotePort: args['remote-port'] || 80,
      remoteScheme: args['remote-scheme'] || 'http',
      localPort: 0,
    },
  });

  transport.on('status', (event) => {
    if (event.detail) log(`  ${event.detail}`);
  });

  log(`transport : ${profile.transport}`);
  log(useTunnel ? `ssh       : ${profile.ssh.username}@${profile.ssh.host}` : `url       : ${profile.url}`);

  const result = await transport.test(profile.id);

  if (result.ok) {
    log('');
    log('  OK');
    log(`  origin    : ${result.origin}`);
    log(`  latency   : ${result.latencyMs} ms`);
    if (result.certificateFingerprint) log(`  cert      : ${result.certificateFingerprint}`);
    if (result.hostKeyFingerprint) log(`  host key  : ${result.hostKeyFingerprint}`);
  } else {
    log('');
    log(`  FAILED (${result.code})`);
    log(`  ${result.detail}`);
  }

  fs.rmSync(tmpUserData, { recursive: true, force: true });
  app.exit(result.ok ? 0 : 1);
});

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    if (!argv[i].startsWith('--')) continue;
    const key = argv[i].slice(2);
    const next = argv[i + 1];
    if (next === undefined || next.startsWith('--')) {
      out[key] = true;
    } else {
      out[key] = next;
      i += 1;
    }
  }
  return out;
}

function expand(p) {
  return p.startsWith('~') ? path.join(os.homedir(), p.slice(1)) : p;
}

function log(line) {
  process.stdout.write(`${line}\n`);
}
