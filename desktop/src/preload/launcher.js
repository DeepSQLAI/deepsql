'use strict';

/**
 * Preload for the launcher window. The renderer gets a narrow, explicit API —
 * no ipcRenderer, no Node — so a compromised page cannot reach the filesystem
 * or the SSH layer beyond these calls.
 */

const { contextBridge, ipcRenderer } = require('electron');

const on = (channel, handler) => {
  const listener = (_event, payload) => handler(payload);
  ipcRenderer.on(channel, listener);
  return () => ipcRenderer.removeListener(channel, listener);
};

contextBridge.exposeInMainWorld('deepsql', {
  app: {
    info: () => ipcRenderer.invoke('app:info'),
    openLog: () => ipcRenderer.invoke('app:open-log'),
    openExternal: (url) => ipcRenderer.invoke('app:open-external', url),
  },
  profiles: {
    list: () => ipcRenderer.invoke('profiles:list'),
    save: (input) => ipcRenderer.invoke('profiles:save', input),
    remove: (id) => ipcRenderer.invoke('profiles:delete', id),
    test: (id, transient) => ipcRenderer.invoke('profiles:test', { id, transient }),
    clearHostKey: (id) => ipcRenderer.invoke('profiles:clear-host-key', id),
  },
  dialog: {
    pickPrivateKey: () => ipcRenderer.invoke('dialog:pick-private-key'),
    pickCaBundle: () => ipcRenderer.invoke('dialog:pick-ca-bundle'),
  },
  connection: {
    open: (id, transient) => ipcRenderer.invoke('connect:open', { id, transient }),
    disconnect: (id) => ipcRenderer.invoke('connect:disconnect', id),
    activeIds: () => ipcRenderer.invoke('connect:active'),
  },
  onDeepLink: (handler) => on('deeplink', handler),
  onStatus: (handler) => on('transport:status', handler),
  onHealth: (handler) => on('transport:health', handler),
  onWorkspaceClosed: (handler) => on('workspace:closed', handler),
});
