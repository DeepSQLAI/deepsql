'use strict';

/** Every renderer→main entry point in the app lives here. */

const { ipcMain, dialog, shell, app } = require('electron');

const profilesStore = require('./profiles');
const secrets = require('./secrets');
const transport = require('./transport');
const launcher = require('./windows/launcher');
const workspaces = require('./windows/workspace');
const log = require('./logger');

function register() {
  // ── App ────────────────────────────────────────────────────────────────
  ipcMain.handle('app:info', () => ({
    // Not app.getVersion(): under `electron <script>` (how scripts/smoke.js and
    // scripts/tunnel-selftest.js run) that returns Electron's own version.
    version: require('../../package.json').version,
    electron: process.versions.electron,
    platform: process.platform,
    arch: process.arch,
    keychainAvailable: secrets.available(),
    logPath: log.logFile(),
    profilesPath: profilesStore.storePath(),
  }));

  ipcMain.handle('app:open-log', () => shell.openPath(log.logFile()));

  ipcMain.handle('app:open-external', (_event, url) => {
    if (/^https?:\/\//i.test(url)) shell.openExternal(url);
  });

  // ── Profiles ───────────────────────────────────────────────────────────
  ipcMain.handle('profiles:list', () => profilesStore.list());

  ipcMain.handle('profiles:save', (_event, input) => profilesStore.upsert(input || {}));

  ipcMain.handle('profiles:delete', async (_event, id) => {
    await transport.disconnect(id);
    workspaces.get(id)?.close();
    return profilesStore.remove(id);
  });

  ipcMain.handle('profiles:clear-host-key', (_event, id) => {
    profilesStore.upsert({ id, ssh: { hostKeyFingerprint: '' } });
    return profilesStore.toSafeProfile(profilesStore.get(id));
  });

  ipcMain.handle('profiles:test', (_event, { id, transient }) =>
    transport.test(id, transient || {}),
  );

  // ── File pickers ───────────────────────────────────────────────────────
  ipcMain.handle('dialog:pick-private-key', async () => {
    const parent = launcher.get();
    const result = await dialog.showOpenDialog(parent, {
      title: 'Select an OpenSSH private key',
      properties: ['openFile', 'showHiddenFiles', 'dontAddToRecent'],
      filters: [
        { name: 'Private keys', extensions: ['pem', 'key', 'ppk', 'openssh'] },
        { name: 'All files', extensions: ['*'] },
      ],
    });
    return result.canceled ? null : result.filePaths[0];
  });

  ipcMain.handle('dialog:pick-ca-bundle', async () => {
    const parent = launcher.get();
    const result = await dialog.showOpenDialog(parent, {
      title: 'Select a CA certificate bundle',
      properties: ['openFile', 'showHiddenFiles', 'dontAddToRecent'],
      filters: [
        { name: 'Certificates', extensions: ['pem', 'crt', 'cer', 'ca-bundle'] },
        { name: 'All files', extensions: ['*'] },
      ],
    });
    return result.canceled ? null : result.filePaths[0];
  });

  // ── Connect / disconnect ───────────────────────────────────────────────
  ipcMain.handle('connect:open', async (_event, { id, transient }) => {
    try {
      const result = await transport.connect(id, transient || {});
      const profile = profilesStore.get(id);
      workspaces.open(profile, result.origin);
      launcher.hide();
      return { ok: true, ...result };
    } catch (err) {
      log.error('ipc', 'connect failed', { id, code: err.code, message: err.message });
      return { ok: false, code: err.code || 'error', detail: err.message };
    }
  });

  ipcMain.handle('connect:disconnect', async (_event, id) => {
    workspaces.get(id)?.close();
    await transport.disconnect(id);
    return true;
  });

  ipcMain.handle('connect:active', () => [...transport.active.keys()]);

  // ── Workspace chrome ───────────────────────────────────────────────────
  ipcMain.on('chrome:ready', (event) => {
    workspaces.fromWebContents(event.sender)?.pushState();
  });

  ipcMain.on('chrome:action', (event, action) => {
    const workspace = workspaces.fromWebContents(event.sender);
    if (!workspace) return;
    switch (action) {
      case 'reload':
        workspace.reload();
        break;
      case 'back':
        workspace.goBack();
        break;
      case 'forward':
        workspace.goForward();
        break;
      case 'home':
        workspace.goHome();
        break;
      case 'open-external':
        workspace.openInBrowser();
        break;
      case 'switch':
        launcher.create();
        break;
      case 'disconnect':
        workspace.close();
        transport.disconnect(workspace.profile.id);
        launcher.create();
        break;
      case 'devtools':
        workspace.contentView.webContents.toggleDevTools();
        break;
      default:
        break;
    }
  });

  ipcMain.on('chrome:window', (event, command) => {
    const workspace = workspaces.fromWebContents(event.sender);
    const win = workspace?.window;
    if (!win) return;
    if (command === 'minimize') win.minimize();
    else if (command === 'maximize') {
      if (win.isMaximized()) win.unmaximize();
      else win.maximize();
      workspace.pushState();
    } else if (command === 'close') win.close();
  });

  // ── Transport events fan out to whichever windows care ─────────────────
  transport.on('status', (payload) => {
    launcher.send('transport:status', payload);
  });

  transport.on('health', (payload) => {
    launcher.send('transport:health', payload);
    workspaces.get(payload.profileId)?.sendToChrome('workspace:health', payload);
  });

  transport.on('host-key', (payload) => {
    launcher.send('transport:status', {
      ...payload,
      phase: 'host-key',
      status: payload.mismatch ? 'error' : 'info',
      detail: payload.mismatch
        ? `Host key mismatch: the VM presented ${payload.fingerprint}.`
        : `Pinned SSH host key ${payload.fingerprint}.`,
    });
  });
}

module.exports = { register };
