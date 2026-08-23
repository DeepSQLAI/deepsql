'use strict';

/**
 * Auto-update, off unless a feed is configured.
 *
 * electron-builder writes app-update.yml into the package only when a `publish`
 * target is set; with `"publish": null` (the default in package.json) there is
 * no feed and calling checkForUpdates would throw on every launch. So the whole
 * thing is opt-in: set a publish target before release, or point
 * DEEPSQL_UPDATE_FEED at a generic feed for an internal rollout.
 */

const fs = require('node:fs');
const path = require('node:path');
const { app } = require('electron');
const log = require('./logger');

function init() {
  if (!app.isPackaged) return;

  const feedUrl = process.env.DEEPSQL_UPDATE_FEED;
  // With `"publish": null` there is no app-update.yml in the bundle. Calling
  // checkForUpdates anyway throws ENOENT on every launch — an expected
  // configuration, not an error worth logging as one.
  const bundledFeed = path.join(process.resourcesPath, 'app-update.yml');
  if (!feedUrl && !fs.existsSync(bundledFeed)) {
    log.info('updater', 'no update feed configured; auto-update disabled');
    return;
  }

  let autoUpdater;
  try {
    ({ autoUpdater } = require('electron-updater'));
  } catch {
    return; // dependency absent in a trimmed build
  }

  autoUpdater.logger = {
    info: (m) => log.info('updater', String(m)),
    warn: (m) => log.warn('updater', String(m)),
    error: (m) => log.error('updater', String(m)),
    debug: () => {},
  };
  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;

  if (feedUrl) {
    autoUpdater.setFeedURL({ provider: 'generic', url: feedUrl });
  }

  autoUpdater.on('update-downloaded', (info) => {
    log.info('updater', 'update downloaded; will install on quit', { version: info.version });
  });

  autoUpdater.checkForUpdates().catch((err) => {
    log.warn('updater', 'update check failed', { message: err.message });
  });
}

module.exports = { init };
