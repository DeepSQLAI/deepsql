'use strict';

const { Menu, app, shell } = require('electron');

const launcher = require('./windows/launcher');
const workspaces = require('./windows/workspace');
const transport = require('./transport');
const log = require('./logger');
const { DEVTOOLS_ENABLED } = require('./config');

function focusedWorkspace() {
  return workspaces.all().find((w) => w.window?.isFocused()) || workspaces.all()[0] || null;
}

function build() {
  const isMac = process.platform === 'darwin';

  const template = [
    ...(isMac
      ? [
          {
            label: app.name,
            submenu: [
              { role: 'about' },
              { type: 'separator' },
              { role: 'services' },
              { type: 'separator' },
              { role: 'hide' },
              { role: 'hideOthers' },
              { role: 'unhide' },
              { type: 'separator' },
              { role: 'quit' },
            ],
          },
        ]
      : []),
    {
      label: 'Connection',
      submenu: [
        {
          label: 'Manage Connections…',
          accelerator: 'CmdOrCtrl+Shift+O',
          click: () => launcher.create(),
        },
        {
          label: 'Reload DeepSQL',
          accelerator: 'CmdOrCtrl+R',
          click: () => focusedWorkspace()?.reload(),
        },
        {
          label: 'Open in Browser',
          accelerator: 'CmdOrCtrl+Shift+B',
          click: () => focusedWorkspace()?.openInBrowser(),
        },
        { type: 'separator' },
        {
          label: 'Disconnect',
          click: () => {
            const workspace = focusedWorkspace();
            if (!workspace) return;
            workspace.close();
            transport.disconnect(workspace.profile.id);
            launcher.create();
          },
        },
        ...(isMac ? [] : [{ type: 'separator' }, { role: 'quit' }]),
      ],
    },
    {
      label: 'Edit',
      submenu: [
        { role: 'undo' },
        { role: 'redo' },
        { type: 'separator' },
        { role: 'cut' },
        { role: 'copy' },
        { role: 'paste' },
        ...(isMac ? [{ role: 'pasteAndMatchStyle' }] : []),
        { role: 'selectAll' },
      ],
    },
    {
      label: 'View',
      submenu: [
        {
          label: 'Back',
          accelerator: 'CmdOrCtrl+[',
          click: () => focusedWorkspace()?.goBack(),
        },
        {
          label: 'Forward',
          accelerator: 'CmdOrCtrl+]',
          click: () => focusedWorkspace()?.goForward(),
        },
        {
          label: 'Home',
          accelerator: 'CmdOrCtrl+Shift+H',
          click: () => focusedWorkspace()?.goHome(),
        },
        { type: 'separator' },
        {
          label: 'Actual Size',
          accelerator: 'CmdOrCtrl+0',
          click: () => setZoom(0),
        },
        {
          label: 'Zoom In',
          accelerator: 'CmdOrCtrl+Plus',
          click: () => bumpZoom(0.5),
        },
        {
          label: 'Zoom Out',
          accelerator: 'CmdOrCtrl+-',
          click: () => bumpZoom(-0.5),
        },
        { type: 'separator' },
        // The DevTools item exists only in a dev build. It also owns the
        // Alt+Cmd+I / Ctrl+Shift+I binding: this app installs its own menu, so
        // Electron adds no toggleDevTools role of its own, and dropping the item
        // drops the shortcut with it. The webPreferences `devTools: false` in
        // workspace.js is what makes that stick — this only hides the door.
        ...(DEVTOOLS_ENABLED
          ? [
              {
                label: 'Toggle Developer Tools',
                accelerator: isMac ? 'Alt+Cmd+I' : 'Ctrl+Shift+I',
                click: () => focusedWorkspace()?.contentView.webContents.toggleDevTools(),
              },
            ]
          : []),
        { role: 'togglefullscreen' },
      ],
    },
    { role: 'windowMenu' },
    {
      role: 'help',
      submenu: [
        {
          label: 'Open Log File',
          click: () => shell.openPath(log.logFile()),
        },
        {
          label: 'DeepSQL Documentation',
          click: () => shell.openExternal('https://github.com/deepsql/deepsql'),
        },
      ],
    },
  ];

  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

function setZoom(level) {
  const wc = focusedWorkspace()?.contentView.webContents;
  if (wc) wc.setZoomLevel(level);
}

function bumpZoom(delta) {
  const wc = focusedWorkspace()?.contentView.webContents;
  if (wc) wc.setZoomLevel(wc.getZoomLevel() + delta);
}

module.exports = { build };
