const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('phoneLyrics', {
  getState: () => ipcRenderer.invoke('state:get'),
  setSettings: (partial) => ipcRenderer.invoke('settings:set', partial),
  restartServer: (port) => ipcRenderer.invoke('server:restart', port),
  toggleDemo: (on) => ipcRenderer.invoke('demo:toggle', on),
  hideOverlay: () => ipcRenderer.invoke('overlay:hide'),
  onState: (cb) => {
    const handler = (_e, state) => cb(state);
    ipcRenderer.on('state:push', handler);
    return () => ipcRenderer.removeListener('state:push', handler);
  },
});
