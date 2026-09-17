const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('phoneLyrics', {
  getState: () => ipcRenderer.invoke('state:get'),
  setSettings: (partial) => ipcRenderer.invoke('settings:set', partial),
  restartServer: (port) => ipcRenderer.invoke('server:restart', port),
  hideOverlay: () => ipcRenderer.invoke('overlay:hide'),
  showOverlay: () => ipcRenderer.invoke('overlay:show'),
  openSettings: () => ipcRenderer.invoke('overlay:open-settings'),
  moveOverlay: (dx, dy) => ipcRenderer.invoke('overlay:move', dx, dy),
  resizeOverlay: (dw, dh) => ipcRenderer.invoke('overlay:resize', dw, dh),
  openLog: () => ipcRenderer.invoke('open-log'),
  onState: (cb) => {
    const handler = (_e, state) => cb(state);
    ipcRenderer.on('state:push', handler);
    return () => ipcRenderer.removeListener('state:push', handler);
  },
});
