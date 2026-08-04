'use strict';

/**
 * Launcher window — the connection manager.
 *
 * This is the only part of the client with its own UI. It is deliberately
 * styled from DeepSQL's own design tokens (src/index.css) so the app does not
 * feel like a browser bolted onto a web page.
 */

const { BrowserWindow, shell } = require('electron');
const path = require('node:path');

const { LAUNCHER_HTML, LAUNCHER_PRELOAD, IS_DEV } = require('../config');

let window = null;

function create() {
  if (window && !window.isDestroyed()) {
    window.show();
    window.focus();
    return window;
  }

  window = new BrowserWindow({
    width: 1020,
    height: 720,
    minWidth: 880,
    minHeight: 600,
    show: false,
    backgroundColor: '#f5f5f7',
    title: 'DeepSQL',
    titleBarStyle: process.platform === 'darwin' ? 'hiddenInset' : 'default',
    trafficLightPosition: process.platform === 'darwin' ? { x: 18, y: 18 } : undefined,
    icon:
      process.platform === 'linux'
        ? path.join(__dirname, '..', '..', '..', 'build', 'icon.png')
        : undefined,
    webPreferences: {
      preload: LAUNCHER_PRELOAD,
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false, // the preload needs ipcRenderer only; keep Node off in the page
      spellcheck: false,
    },
  });

  window.loadFile(LAUNCHER_HTML);

  window.once('ready-to-show', () => {
    window.show();
    if (IS_DEV) window.webContents.openDevTools({ mode: 'detach' });
  });

  // The launcher is a fixed local page. Anything that tries to navigate it
  // elsewhere is either a bug or hostile — send links to the real browser.
  window.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/i.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  window.webContents.on('will-navigate', (event, url) => {
    if (!url.startsWith('file://')) {
      event.preventDefault();
      if (/^https?:/i.test(url)) shell.openExternal(url);
    }
  });

  window.on('closed', () => {
    window = null;
  });

  return window;
}

function get() {
  return window && !window.isDestroyed() ? window : null;
}

function send(channel, payload) {
  const win = get();
  if (win) win.webContents.send(channel, payload);
}

function hide() {
  const win = get();
  if (win) win.hide();
}

module.exports = { create, get, send, hide };
