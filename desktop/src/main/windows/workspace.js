'use strict';

/**
 * Workspace window — a frameless shell whose top 44px is our own native chrome
 * and whose remaining area is a WebContentsView pointed at the live DeepSQL UI.
 *
 * The DeepSQL web app is loaded, not reimplemented. That keeps the client
 * permanently in step with whatever version the VM runs (no bundle/backend
 * skew, no second copy of 40+ tabs to maintain) while the chrome above it
 * supplies the things a browser tab cannot: which VM you are on, how you are
 * reaching it, and the latency of that path.
 */

const path = require('node:path');
const {
  BaseWindow,
  WebContentsView,
  BrowserWindow,
  session: electronSession,
  shell,
} = require('electron');

const {
  CHROME_HEIGHT,
  CHROME_HTML,
  CHROME_PRELOAD,
  DEVTOOLS_ENABLED,
} = require('../config');

/** Must match `.banner { height }` in renderer/chrome/chrome.css. */
const BANNER_HEIGHT = 36;
const tls = require('../tls');
const log = require('../logger');

/** @type {Map<string, Workspace>} profileId -> workspace */
const workspaces = new Map();

// Required lazily: launcher.js and workspace.js are both reachable from ipc.js,
// and a top-level require here closes a cycle through transport.js.
const launcherModule = () => require('./launcher');

class Workspace {
  constructor(profile, origin) {
    this.profile = profile;
    this.origin = origin;
    this.window = null;
    this.chromeView = null;
    this.contentView = null;
    this.session = null;
    // The chrome view grows by BANNER_HEIGHT when it shows the error banner;
    // it is a fixed-height view, so the banner is invisible unless we make room.
    this.bannerVisible = false;
  }

  /**
   * Each profile gets its own persistent partition, so signing into two
   * different DeepSQL VMs never crosses cookies, tokens or cached RAG state.
   */
  buildSession() {
    const partition = `persist:deepsql-${this.profile.id}`;
    const ses = electronSession.fromPartition(partition);

    if (this.profile.transport === 'direct') {
      tls.applyToSession(ses, this.profile);
    }

    // DeepSQL needs none of the powerful web APIs. Deny by default; the only
    // thing worth allowing is clipboard write for "copy SQL" buttons.
    ses.setPermissionRequestHandler((_contents, permission, callback) => {
      callback(permission === 'clipboard-sanitized-write');
    });
    ses.setPermissionCheckHandler((_contents, permission) =>
      permission === 'clipboard-sanitized-write',
    );

    ses.on('will-download', (_event, item) => {
      log.info('workspace', 'download started', {
        profileId: this.profile.id,
        filename: item.getFilename(),
      });
    });

    this.session = ses;
    return ses;
  }

  create() {
    const isMac = process.platform === 'darwin';

    this.window = new BaseWindow({
      width: 1440,
      height: 920,
      minWidth: 1024,
      minHeight: 680,
      show: false,
      backgroundColor: '#f5f5f7',
      title: `${this.profile.name} — DeepSQL`,
      frame: isMac,
      titleBarStyle: isMac ? 'hiddenInset' : 'default',
      trafficLightPosition: isMac ? { x: 16, y: 14 } : undefined,
      icon:
        process.platform === 'linux'
          ? path.join(__dirname, '..', '..', '..', 'build', 'icon.png')
          : undefined,
    });

    // On Windows/Linux we draw our own window controls in the chrome, so the
    // native frame comes off entirely.
    if (!isMac) this.window.setMenuBarVisibility(false);

    const ses = this.buildSession();

    this.chromeView = new WebContentsView({
      webPreferences: {
        preload: CHROME_PRELOAD,
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: false,
        devTools: DEVTOOLS_ENABLED,
      },
    });
    this.chromeView.webContents.loadFile(CHROME_HTML);

    this.contentView = new WebContentsView({
      webPreferences: {
        session: ses,
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
        webSecurity: true,
        // The DeepSQL dashboard artifact renders in a sandboxed iframe and
        // relies on the standard same-origin rules; nothing here may relax them.
        // This is the view holding the user's authenticated DeepSQL session, so
        // it is the one DevTools must never attach to in a shipped build.
        devTools: DEVTOOLS_ENABLED,
      },
    });

    this.window.contentView.addChildView(this.chromeView);
    this.window.contentView.addChildView(this.contentView);

    this.layout();
    this.window.on('resize', () => this.layout());

    this.wireContentNavigation();

    this.contentView.webContents.loadURL(this.origin);

    this.window.once('ready-to-show', () => this.window.show());
    // BaseWindow has no ready-to-show for child views; show once chrome paints.
    this.chromeView.webContents.once('did-finish-load', () => {
      this.window.show();
      this.pushState();
    });

    // Closing the window releases the transport. A tunnel that outlived its
    // window would keep an SSH session and a loopback listener open with no UI
    // left to show for it.
    this.window.on('closed', () => {
      workspaces.delete(this.profile.id);
      this.window = null;
      require('../transport')
        .disconnect(this.profile.id)
        .catch((err) => log.error('workspace', 'disconnect on close failed', err));
      launcherModule().send('workspace:closed', { profileId: this.profile.id });
    });

    if (DEVTOOLS_ENABLED) {
      this.contentView.webContents.openDevTools({ mode: 'bottom' });
    }

    workspaces.set(this.profile.id, this);
    return this;
  }

  layout() {
    if (!this.window) return;
    const { width, height } = this.window.getContentBounds();
    const chromeHeight = CHROME_HEIGHT + (this.bannerVisible ? BANNER_HEIGHT : 0);
    this.chromeView.setBounds({ x: 0, y: 0, width, height: chromeHeight });
    this.contentView.setBounds({
      x: 0,
      y: chromeHeight,
      width,
      height: Math.max(0, height - chromeHeight),
    });
  }

  setBanner(visible) {
    if (this.bannerVisible === visible) return;
    this.bannerVisible = visible;
    this.layout();
  }

  wireContentNavigation() {
    const wc = this.contentView.webContents;

    // Resolved per call, not captured once. A settings change rebuilds the
    // transport onto a new origin (a tunnel rebuild lands on a different local
    // port), and a confinement check frozen at the old origin would then treat
    // the app's own pages as external and bounce every click to the OS browser.
    const isInternal = (target) => {
      try {
        return new URL(target).origin === safeOrigin(this.origin);
      } catch {
        return false;
      }
    };

    // Keep the embedded view pinned to the DeepSQL origin. A stray external
    // navigation inside the app shell would put an unrelated site on the same
    // session as the user's DeepSQL cookies.
    wc.on('will-navigate', (event, url) => {
      if (isInternal(url)) return;
      event.preventDefault();
      if (/^https?:/i.test(url)) shell.openExternal(url);
    });

    wc.setWindowOpenHandler(({ url }) => {
      if (isInternal(url)) {
        // Shared dashboards and similar same-origin popups get a real window
        // on the same session so auth carries over.
        const popup = new BrowserWindow({
          width: 1200,
          height: 820,
          backgroundColor: '#f5f5f7',
          webPreferences: {
            session: this.session,
            contextIsolation: true,
            sandbox: true,
            devTools: DEVTOOLS_ENABLED,
          },
        });
        popup.loadURL(url);
        return { action: 'deny' };
      }
      if (/^https?:/i.test(url)) shell.openExternal(url);
      return { action: 'deny' };
    });

    for (const event of [
      'did-start-loading',
      'did-stop-loading',
      'did-navigate',
      'did-navigate-in-page',
    ]) {
      wc.on(event, () => this.pushState());
    }

    wc.on('did-fail-load', (_event, errorCode, errorDescription, validatedURL, isMainFrame) => {
      if (!isMainFrame || errorCode === -3 /* ERR_ABORTED */) return;
      log.error('workspace', 'content failed to load', {
        profileId: this.profile.id,
        errorCode,
        errorDescription,
        validatedURL,
      });
      this.setBanner(true);
      this.sendToChrome('workspace:load-error', { errorCode, errorDescription });
    });

    wc.on('did-finish-load', () => this.setBanner(false));

    wc.on('render-process-gone', (_event, details) => {
      log.error('workspace', 'renderer gone', { profileId: this.profile.id, ...details });
      this.setBanner(true);
      this.sendToChrome('workspace:load-error', {
        errorCode: -1,
        errorDescription: `The DeepSQL view stopped responding (${details.reason}).`,
      });
    });
  }

  pushState() {
    if (!this.window || !this.contentView) return;
    const wc = this.contentView.webContents;
    this.sendToChrome('workspace:state', {
      // The health poll only fires every HEALTH_INTERVAL_MS, so without the
      // reading taken during connect the chrome would sit on "checking…" for
      // the first 20 seconds of every session.
      health: require('../transport').get(this.profile.id)?.health || null,
      profileId: this.profile.id,
      name: this.profile.name,
      transport: this.profile.transport,
      origin: this.origin,
      url: wc.getURL(),
      loading: wc.isLoading(),
      canGoBack: wc.navigationHistory.canGoBack(),
      canGoForward: wc.navigationHistory.canGoForward(),
      maximized: this.window.isMaximized(),
      platform: process.platform,
      tlsMode: this.profile.tls?.mode || 'system',
    });
  }

  sendToChrome(channel, payload) {
    if (this.chromeView && !this.chromeView.webContents.isDestroyed()) {
      this.chromeView.webContents.send(channel, payload);
    }
  }

  /**
   * Adopt an edited profile, and move to a new origin if the transport was
   * rebuilt onto one.
   *
   * Without this the window keeps serving the origin it opened with: a rebuilt
   * tunnel binds a different local port, so the old origin is a port nothing is
   * listening on any more, and the window sits there looking connected while
   * every request fails.
   */
  updateProfile(profile, origin) {
    this.profile = profile;
    if (this.window) this.window.setTitle(`${profile.name} — DeepSQL`);

    const nextOrigin = origin || this.origin;
    if (nextOrigin !== this.origin) {
      this.origin = nextOrigin;
      // loadURL, not reload: the old URL's port is gone with the old tunnel.
      this.contentView.webContents.loadURL(nextOrigin);
    }
    this.pushState();
  }

  // ── actions invoked from the chrome ──────────────────────────────────────
  reload() {
    this.contentView.webContents.reloadIgnoringCache();
  }

  goBack() {
    const nav = this.contentView.webContents.navigationHistory;
    if (nav.canGoBack()) nav.goBack();
  }

  goForward() {
    const nav = this.contentView.webContents.navigationHistory;
    if (nav.canGoForward()) nav.goForward();
  }

  goHome() {
    this.contentView.webContents.loadURL(this.origin);
  }

  openInBrowser() {
    const url = this.contentView.webContents.getURL() || this.origin;
    // A tunnel origin is meaningless outside this app — send the VM's real URL.
    shell.openExternal(this.profile.transport === 'tunnel' ? this.origin : url);
  }

  focus() {
    if (this.window) {
      if (this.window.isMinimized()) this.window.restore();
      this.window.focus();
    }
  }

  close() {
    if (this.window) this.window.close();
  }
}

function safeOrigin(url) {
  try {
    return new URL(url).origin;
  } catch {
    return '';
  }
}

function open(profile, origin) {
  const existing = workspaces.get(profile.id);
  if (existing && existing.window) {
    existing.focus();
    return existing;
  }
  return new Workspace(profile, origin).create();
}

function get(profileId) {
  return workspaces.get(profileId) || null;
}

/** Resolve the workspace that owns a given chrome/content webContents. */
function fromWebContents(contents) {
  for (const workspace of workspaces.values()) {
    if (
      workspace.chromeView?.webContents === contents ||
      workspace.contentView?.webContents === contents
    ) {
      return workspace;
    }
  }
  return null;
}

function all() {
  return [...workspaces.values()];
}

module.exports = { open, get, all, fromWebContents };
