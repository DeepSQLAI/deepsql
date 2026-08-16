'use strict';

/**
 * DeepSQL desktop client — main process entry point.
 *
 * Lifecycle: launcher (pick a VM) → transport comes up (TLS or SSH tunnel) →
 * workspace window opens on the resulting origin. Quitting tears every tunnel
 * down; a leaked loopback listener would be a live hole into the VM.
 */

const { app, BrowserWindow, shell } = require('electron');

const { PROTOCOL, IS_DEV, DEVTOOLS_ENABLED } = require('./config');
const ipc = require('./ipc');
const menu = require('./menu');
const updater = require('./updater');
const launcher = require('./windows/launcher');
const workspaces = require('./windows/workspace');
const transport = require('./transport');
const log = require('./logger');

/**
 * Chromium's debugging switches open a DevTools *protocol* endpoint, which is a
 * separate door from the DevTools UI that `devTools: false` closes: a packaged
 * app started with --remote-debugging-port will happily accept an external
 * inspector on the workspace view and its authenticated session. Chromium parses
 * these before any of our code runs, so the port may already be listening by the
 * time we get here — refusing to start is what closes it again, immediately and
 * before any window opens or any tunnel comes up.
 *
 * ELECTRON_RUN_AS_NODE is out of reach by construction: it turns the binary into
 * a plain Node process that never loads this file.
 */
const DEBUG_SWITCH =
  /^--(remote-debugging-port|remote-debugging-pipe|remote-allow-origins|inspect|inspect-brk|inspect-port)(=|$)/;

const debugSwitches = process.argv.slice(1).filter((arg) => DEBUG_SWITCH.test(arg));

if (debugSwitches.length > 0 && !DEVTOOLS_ENABLED) {
  log.error('app', 'refusing to start with remote debugging enabled', {
    switches: debugSwitches,
  });
  app.exit(1);
}
// Two instances would fight over the same profiles.json and could bind the same
// sticky tunnel port; the second one just focuses the first.
else if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', (_event, argv) => {
    const existing = launcher.get() || workspaces.all()[0]?.window;
    if (existing) {
      if (existing.isMinimized?.()) existing.restore();
      existing.focus();
    } else {
      launcher.create();
    }
    handleDeepLink(argv.find((arg) => arg.startsWith(`${PROTOCOL}://`)));
  });

  bootstrap();
}

function bootstrap() {
  app.setName('DeepSQL');

  if (process.defaultApp) {
    // Dev: register the scheme with the electron binary + script path.
    if (process.argv.length >= 2) {
      app.setAsDefaultProtocolClient(PROTOCOL, process.execPath, [process.argv[1]]);
    }
  } else {
    app.setAsDefaultProtocolClient(PROTOCOL);
  }

  app.on('open-url', (event, url) => {
    event.preventDefault();
    handleDeepLink(url);
  });

  app.whenReady().then(() => {
    log.info('app', 'starting', {
      version: app.getVersion(),
      electron: process.versions.electron,
      platform: `${process.platform}-${process.arch}`,
      dev: IS_DEV,
    });

    ipc.register();
    menu.build();
    launcher.create();
    updater.init();
    handleDeepLinkFromArgv();

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0 && workspaces.all().length === 0) {
        launcher.create();
      }
    });
  });

  app.on('window-all-closed', async () => {
    await transport.disconnectAll();
    if (process.platform !== 'darwin') app.quit();
  });

  app.on('before-quit', async (event) => {
    if (transport.active.size === 0) return;
    event.preventDefault();
    await transport.disconnectAll();
    app.quit();
  });

  // Defence in depth: even if a view slipped past its own navigation guard,
  // nothing may open a Node-enabled window or reach a non-http scheme.
  app.on('web-contents-created', (_event, contents) => {
    contents.on('will-attach-webview', (event) => event.preventDefault());
    contents.setWindowOpenHandler(({ url }) => {
      if (/^https?:/i.test(url)) shell.openExternal(url);
      return { action: 'deny' };
    });
  });
}

/**
 * `deepsql://connect?url=https://vm.example.com&name=Prod` opens the launcher
 * with a new profile pre-filled — the one-click "connect to our VM" link an
 * admin can paste into onboarding docs.
 */
function handleDeepLink(rawUrl) {
  if (!rawUrl) return;
  let parsed;
  try {
    parsed = new URL(rawUrl);
  } catch {
    return;
  }
  if (parsed.protocol !== `${PROTOCOL}:`) return;

  const window = launcher.create();
  const payload = {
    action: parsed.hostname || 'connect',
    url: parsed.searchParams.get('url') || '',
    name: parsed.searchParams.get('name') || '',
    transport: parsed.searchParams.get('transport') === 'tunnel' ? 'tunnel' : 'direct',
    sshHost: parsed.searchParams.get('sshHost') || '',
    sshUser: parsed.searchParams.get('sshUser') || '',
  };
  const send = () => window.webContents.send('deeplink', payload);
  if (window.webContents.isLoading()) {
    window.webContents.once('did-finish-load', send);
  } else {
    send();
  }
  log.info('app', 'handled deep link', payload);
}

/** Windows/Linux cold start with a protocol argument. */
function handleDeepLinkFromArgv() {
  const arg = process.argv.find((value) => value.startsWith(`${PROTOCOL}://`));
  if (arg) handleDeepLink(arg);
}
