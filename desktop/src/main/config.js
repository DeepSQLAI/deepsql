'use strict';

/**
 * Shared constants for the DeepSQL desktop client.
 *
 * The client is a *thin* client: it never bundles a copy of the DeepSQL web UI.
 * It navigates a WebContentsView at the real DeepSQL origin (reached either
 * directly over TLS or through an SSH tunnel), so the UI a user sees is always
 * exactly the version their VM is running. There is no bundle/backend skew to
 * manage, and cookies behave the same as they do in a browser because the app
 * content is served from a single origin (the frontend nginx in
 * docker/nginx/default.conf already fronts /api and /agent-api).
 *
 * CORS is the one thing that does *not* come for free: over a tunnel that single
 * origin is http://127.0.0.1:<sticky port>, which the VM's CORS_ALLOWED_ORIGINS
 * has to allow. See the note in probe.js — the probe sends an Origin header so a
 * missing entry is caught here rather than at the user's first login attempt.
 */

const path = require('node:path');
const { app } = require('electron');

const IS_DEV =
  process.env.DEEPSQL_DESKTOP_DEV === '1' || !app.isPackaged;

// Health probe served by the Spring backend through the frontend nginx.
const HEALTH_PATH = '/api/actuator/health';

// How often the workspace re-probes the connection while a window is open.
const HEALTH_INTERVAL_MS = 20_000;

// Height of the native top chrome above the embedded DeepSQL UI.
const CHROME_HEIGHT = 44;

const RENDERER_DIR = path.join(__dirname, '..', 'renderer');
const PRELOAD_DIR = path.join(__dirname, '..', 'preload');

const LAUNCHER_HTML = path.join(RENDERER_DIR, 'launcher', 'index.html');
const CHROME_HTML = path.join(RENDERER_DIR, 'chrome', 'index.html');
const LAUNCHER_PRELOAD = path.join(PRELOAD_DIR, 'launcher.js');
const CHROME_PRELOAD = path.join(PRELOAD_DIR, 'chrome.js');

const PROTOCOL = 'deepsql';

module.exports = {
  IS_DEV,
  HEALTH_PATH,
  HEALTH_INTERVAL_MS,
  CHROME_HEIGHT,
  LAUNCHER_HTML,
  CHROME_HTML,
  LAUNCHER_PRELOAD,
  CHROME_PRELOAD,
  PROTOCOL,
};
