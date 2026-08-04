'use strict';

/** Preload for the workspace's native top chrome. */

const { contextBridge, ipcRenderer } = require('electron');

const on = (channel, handler) => {
  const listener = (_event, payload) => handler(payload);
  ipcRenderer.on(channel, listener);
  return () => ipcRenderer.removeListener(channel, listener);
};

contextBridge.exposeInMainWorld('deepsqlChrome', {
  ready: () => ipcRenderer.send('chrome:ready'),
  action: (action) => ipcRenderer.send('chrome:action', action),
  window: (command) => ipcRenderer.send('chrome:window', command),
  onState: (handler) => on('workspace:state', handler),
  onHealth: (handler) => on('workspace:health', handler),
  onLoadError: (handler) => on('workspace:load-error', handler),
});
