const path = require('node:path');
const {
  app,
  BrowserWindow,
  Tray,
  Menu,
  ipcMain,
  screen,
  nativeImage,
  Notification,
} = require('electron');
const { StatusServer } = require('./server');
const { LyricsService, DEMO_LRC } = require('./lyrics-service');
const { parseLRC, locateLine } = require('../shared/lrc');

const DEFAULTS = {
  port: 8765,
  fontSize: 28,
  rows: 1,
  locked: false,
  opacity: 0.92,
  demo: false,
  showOverlay: true,
};

let tray = null;
let overlayWin = null;
let settingsWin = null;
let server = null;
let lyricsService = new LyricsService();

let settings = { ...DEFAULTS };
let nowPlaying = null;
let lyricLines = [];
let lyricSource = null;
let lyricKey = null;
let anchor = { positionMs: 0, wall: 0 };
let demoStartWall = Date.now();
let ticker = null;
let connected = false;

function iconImage() {
  // 16x16 black lyric note-ish square; macOS template
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
    <text x="8" y="12" text-anchor="middle" font-size="12">♪</text>
  </svg>`;
  return nativeImage.createFromBuffer(Buffer.from(svg)).resize({ width: 16, height: 16 });
}

function computePositionMs() {
  if (settings.demo) {
    const elapsed = Date.now() - demoStartWall;
    const last = lyricLines[lyricLines.length - 1]?.timeMs || 40000;
    return elapsed % (last + 4000);
  }
  if (!nowPlaying) return 0;
  if (nowPlaying.state === 'playing') {
    return anchor.positionMs + (Date.now() - anchor.wall);
  }
  return anchor.positionMs;
}

function snapshotState() {
  const pos = computePositionMs();
  const loc = locateLine(lyricLines, pos);
  return {
    settings,
    connected: settings.demo ? true : connected,
    demo: settings.demo,
    addresses: server ? server.listLanAddresses() : [],
    port: settings.port,
    nowPlaying: nowPlaying
      ? {
          title: nowPlaying.title,
          artist: nowPlaying.artist,
          durationMs: nowPlaying.durationMs,
          state: nowPlaying.state,
        }
      : null,
    lyricSource,
    lyricReady: lyricLines.length > 0,
    positionMs: pos,
    currentText: settings.demo
      ? (loc.current?.text ?? '演示中')
      : !connected
        ? '等待手机'
        : (loc.current?.text ?? (nowPlaying ? nowPlaying.title : '等待手机')),
    nextText: loc.next?.text ?? '',
  };
}

function pushState() {
  const state = snapshotState();
  if (settingsWin && !settingsWin.isDestroyed()) {
    settingsWin.webContents.send('state:push', state);
  }
  if (overlayWin && !overlayWin.isDestroyed()) {
    overlayWin.webContents.send('state:push', state);
  }
}

let lyricsFetchSeq = 0;
async function ensureLyrics(np) {
  const key = `${np.artist}::${np.title}`.toLowerCase();
  if (lyricKey === key && lyricLines.length) return;
  const seq = ++lyricsFetchSeq;
  lyricKey = key;
  lyricLines = [];
  lyricSource = 'loading';
  pushState();
  if (settings.demo) {
    lyricLines = parseLRC(DEMO_LRC);
    lyricSource = 'demo';
    pushState();
    return;
  }
  const result = await lyricsService.fetchLyrics(np.title, np.artist);
  if (seq !== lyricsFetchSeq) return; // stale fetch
  lyricLines = result.lines;
  lyricSource = result.source;
  pushState();
}

function onNowPlaying(np) {
  nowPlaying = np;
  connected = true;
  anchor = { positionMs: np.positionMs, wall: Date.now() };
  ensureLyrics(np).catch(() => {});
  pushState();
}

function createOverlay() {
  if (overlayWin) return overlayWin;
  const display = screen.getPrimaryDisplay();
  const { width } = display.workAreaSize;
  overlayWin = new BrowserWindow({
    width: Math.min(900, width - 80),
    height: settings.rows === 2 ? 120 : 72,
    x: display.workArea.x + 40,
    y: display.workArea.y + display.workArea.height - 140,
    frame: false,
    transparent: true,
    resizable: true,
    alwaysOnTop: true,
    skipTaskbar: true,
    focusable: false,
    hasShadow: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  overlayWin.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });
  overlayWin.setAlwaysOnTop(true, 'screen-saver');
  overlayWin.loadFile(path.join(__dirname, '../src/overlay/index.html'));
  overlayWin.on('closed', () => {
    overlayWin = null;
  });
  applyOverlayInteraction();
  return overlayWin;
}

function applyOverlayInteraction() {
  if (!overlayWin || overlayWin.isDestroyed()) return;
  if (settings.locked) {
    overlayWin.setIgnoreMouseEvents(true, { forward: true });
  } else {
    overlayWin.setIgnoreMouseEvents(false);
  }
  overlayWin.setOpacity(settings.opacity);
  const bounds = overlayWin.getBounds();
  overlayWin.setBounds({
    ...bounds,
    height: settings.rows === 2 ? 120 : 72,
  });
}

function createSettings() {
  if (settingsWin) {
    settingsWin.show();
    settingsWin.focus();
    return settingsWin;
  }
  settingsWin = new BrowserWindow({
    width: 420,
    height: 560,
    resizable: false,
    maximizable: false,
    title: '手机歌词镜像',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  settingsWin.setMenuBarVisibility(false);
  settingsWin.loadFile(path.join(__dirname, '../src/settings/index.html'));
  settingsWin.on('closed', () => {
    settingsWin = null;
  });
  return settingsWin;
}

function createTray() {
  tray = new Tray(iconImage());
  tray.setToolTip('手机歌词镜像');
  const rebuild = () => {
    const menu = Menu.buildFromTemplate([
      {
        label: connected || settings.demo ? '已连接手机 / 演示中' : '等待手机连接',
        enabled: false,
      },
      { type: 'separator' },
      {
        label: settings.showOverlay ? '隐藏歌词窗' : '显示歌词窗',
        click: () => {
          settings.showOverlay = !settings.showOverlay;
          if (settings.showOverlay) {
            createOverlay().show();
          } else if (overlayWin) {
            overlayWin.hide();
          }
          pushState();
          rebuild();
        },
      },
      {
        label: settings.locked ? '解锁移动' : '锁定歌词窗',
        click: async () => {
          settings.locked = !settings.locked;
          applyOverlayInteraction();
          pushState();
          rebuild();
        },
      },
      {
        label: settings.demo ? '关闭演示模式' : '开启演示模式',
        click: async () => {
          settings.demo = !settings.demo;
          if (settings.demo) {
            demoStartWall = Date.now();
            lyricKey = 'demo';
            lyricLines = parseLRC(DEMO_LRC);
            lyricSource = 'demo';
          } else {
            lyricKey = null;
            lyricLines = [];
            lyricSource = null;
            if (nowPlaying) await ensureLyrics(nowPlaying);
          }
          pushState();
          rebuild();
        },
      },
      { type: 'separator' },
      { label: '设置…', click: () => createSettings() },
      {
        label: '退出',
        click: () => {
          app.isQuiting = true;
          app.quit();
        },
      },
    ]);
    tray.setContextMenu(menu);
  };
  rebuild();
  tray.on('click', () => {
    if (settingsWin) settingsWin.show();
    else createSettings();
  });
}

async function startServer(port) {
  if (server) await server.stop();
  server = new StatusServer({
    port,
    onNowPlaying,
  });
  try {
    await server.start();
    return { ok: true, port, addresses: server.listLanAddresses() };
  } catch (err) {
    if (Notification.isSupported()) {
      new Notification({
        title: '端口占用',
        body: `无法监听 ${port}：${err.message}`,
      }).show();
    }
    return { ok: false, error: err.message };
  }
}

function startTicker() {
  if (ticker) clearInterval(ticker);
  ticker = setInterval(() => {
    const wasConnected = connected;
    if (!settings.demo && server) {
      connected = server.isConnected();
    }
    if (wasConnected !== connected) pushState();
    else if (overlayWin && !overlayWin.isDestroyed()) {
      // keep lyrics smooth even without settings open
      overlayWin.webContents.send('state:push', snapshotState());
    }
  }, 200);
}

function registerIpc() {
  ipcMain.handle('state:get', () => snapshotState());
  ipcMain.handle('settings:set', async (_e, partial) => {
    const prevPort = settings.port;
    settings = { ...settings, ...partial };
    if (partial.port && partial.port !== prevPort) {
      await startServer(settings.port);
    }
    if (partial.demo !== undefined) {
      if (settings.demo) {
        demoStartWall = Date.now();
        lyricKey = 'demo';
        lyricLines = parseLRC(DEMO_LRC);
        lyricSource = 'demo';
      } else {
        lyricKey = null;
        if (nowPlaying) await ensureLyrics(nowPlaying);
      }
    }
    if (partial.showOverlay !== undefined) {
      if (settings.showOverlay) createOverlay().show();
      else if (overlayWin) overlayWin.hide();
    }
    applyOverlayInteraction();
    pushState();
    return snapshotState();
  });
  ipcMain.handle('server:restart', async (_e, port) => {
    const result = await startServer(port || settings.port);
    if (result.ok) settings.port = result.port;
    pushState();
    return result;
  });
  ipcMain.handle('demo:toggle', async (_e, on) => {
    settings.demo = !!on;
    if (settings.demo) {
      demoStartWall = Date.now();
      lyricKey = 'demo';
      lyricLines = parseLRC(DEMO_LRC);
      lyricSource = 'demo';
    }
    pushState();
    return snapshotState();
  });
  ipcMain.handle('overlay:hide', () => {
    settings.showOverlay = false;
    if (overlayWin) overlayWin.hide();
    pushState();
    return true;
  });
}

app.whenReady().then(async () => {
  registerIpc();
  createTray();
  createOverlay();
  await startServer(settings.port);
  startTicker();
  // Open settings once on first launch for discoverability
  createSettings();
});

app.on('window-all-closed', (e) => {
  // keep tray alive
});

app.on('before-quit', async () => {
  if (ticker) clearInterval(ticker);
  if (server) await server.stop();
});
