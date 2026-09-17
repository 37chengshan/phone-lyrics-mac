const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');
const {
  app,
  BrowserWindow,
  Tray,
  Menu,
  ipcMain,
  screen,
  nativeImage,
  Notification,
  shell,
} = require('electron');
const { StatusServer } = require('./server');
const { LyricsService } = require('./lyrics-service');
const { parseLRC, locateLine } = require('../shared/lrc');

const DEFAULTS = {
  port: 8765,
  fontSize: 30,
  rows: 2,
  locked: false,
  opacity: 0.95,
  demo: false,
  showOverlay: true,
};

const LOG_PATH = path.join(app.getPath('userData'), 'phone-lyrics.log');
function log(...args) {
  const line = `[${new Date().toISOString()}] ${args.map(String).join(' ')}\n`;
  try {
    fs.appendFileSync(LOG_PATH, line);
  } catch {}
}

/** 16x16 template-ish music note PNG */
const TRAY_PNG =
  'iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAhklEQVQ4y2NgGAWDHjAyMPxnYGD4z8jA8J+RgeE/IwPDf0YGBob/jAwM/xkZGP4zMjD8Z2Rg+M/IwPCfkYHhPyMDw39GBob/jAwM/xkZGP4zMjD8Z2Rg+M/IwPCfkYHhPyMDw39GBob/jAwM/xkZGP4zMjD8Z2Rg+M/IwPCfkYHhPyMDAwMDAwMDAwMDAwMDw38AAH0Q8xU3J7cAAAAASUVORK5CYII=';

let tray = null;
let overlayWin = null;
let settingsWin = null;
let server = null;
const lyricsService = new LyricsService();
const DEMO_LRC = `[00:00.00]演示：手机歌词镜像已就绪
[00:03.50]悬浮窗可拖动 · 可缩放 · 可锁定
[00:07.00]托盘 ♪ 可随时开关与设置
[00:11.00]手机中继推送到本机 :8765
[00:15.50]— demo —
[00:20.00]— 循环播放 —
`;

let settings = { ...DEFAULTS };
let nowPlaying = null;
let lyricLines = [];
let lyricSource = null;
let lyricKey = null;
let lyricError = null;
let anchor = { positionMs: 0, wall: 0 };
let demoStartWall = Date.now();
let ticker = null;
let connected = false;
let lyricsFetchSeq = 0;

function trayImage() {
  return nativeImage
    .createFromBuffer(Buffer.from(TRAY_PNG, 'base64'))
    .resize({ width: 16, height: 16 });
}

function computePositionMs() {
  if (settings.demo) {
    const last = lyricLines[lyricLines.length - 1]?.timeMs || 25000;
    return (Date.now() - demoStartWall) % (last + 3000);
  }
  if (!nowPlaying) return 0;
  if (nowPlaying.state === 'playing') {
    return Math.max(0, anchor.positionMs + (Date.now() - anchor.wall));
  }
  return Math.max(0, anchor.positionMs);
}

function snapshotState() {
  const pos = computePositionMs();
  const loc = locateLine(lyricLines, pos);
  const online = settings.demo || connected;
  let currentText = '等待手机';
  if (settings.demo) currentText = loc.current?.text ?? '演示中';
  else if (online && nowPlaying) {
    currentText = loc.current?.text ?? nowPlaying.title;
  } else if (online && !nowPlaying) {
    currentText = '已连接 · 等待播放';
  } else {
    currentText = '等待手机';
  }

  return {
    settings: { ...settings },
    connected: online,
    demo: settings.demo,
    addresses: server ? server.listLanAddresses() : [],
    port: settings.port,
    nowPlaying: nowPlaying
      ? {
          title: nowPlaying.title,
          artist: nowPlaying.artist,
          durationMs: nowPlaying.durationMs,
          state: nowPlaying.state,
          source: nowPlaying.source,
        }
      : null,
    lyricSource,
    lyricError,
    lyricReady: lyricLines.length > 0,
    positionMs: pos,
    currentText,
    nextText: loc.next?.text ?? '',
    logPath: LOG_PATH,
  };
}

function broadcastState() {
  const state = snapshotState();
  for (const win of [settingsWin, overlayWin]) {
    if (win && !win.isDestroyed()) win.webContents.send('state:push', state);
  }
}

async function ensureLyrics(np) {
  const key = `${np.artist || ''}::${np.title}`.toLowerCase();
  if (lyricKey === key && lyricLines.length) return;
  const seq = ++lyricsFetchSeq;
  lyricKey = key;
  lyricLines = [];
  lyricSource = 'loading';
  lyricError = null;
  broadcastState();

  if (settings.demo) {
    lyricLines = parseLRC(DEMO_LRC);
    lyricSource = 'demo';
    broadcastState();
    return;
  }

  log('fetch lyrics', np.title, np.artist);
  const result = await lyricsService.fetchLyrics(np.title, np.artist);
  if (seq !== lyricsFetchSeq) return;
  lyricLines = result.lines;
  lyricSource = result.source;
  lyricError = result.error || null;
  log('lyrics', result.source, 'lines', result.lines.length, result.error || '');
  broadcastState();
}

function onNowPlaying(np) {
  nowPlaying = np;
  connected = true;
  anchor = { positionMs: Number(np.positionMs) || 0, wall: Date.now() };
  ensureLyrics(np).catch((e) => log('ensureLyrics fail', e.message));
  broadcastState();
}

function createOverlay() {
  if (overlayWin && !overlayWin.isDestroyed()) return overlayWin;
  const display = screen.getPrimaryDisplay();
  const wa = display.workArea;
  const w = Math.min(960, wa.width - 80);
  const h = settings.rows === 2 ? 128 : 84;
  overlayWin = new BrowserWindow({
    width: w,
    height: h,
    x: wa.x + Math.round((wa.width - w) / 2),
    y: wa.y + wa.height - h - 28,
    frame: false,
    transparent: true,
    resizable: true,
    maximizable: false,
    fullscreenable: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    hasShadow: false,
    show: settings.showOverlay,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  overlayWin.setAlwaysOnTop(true, 'screen-saver');
  overlayWin.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true });
  overlayWin.loadFile(path.join(__dirname, '../src/overlay/index.html'));
  overlayWin.on('closed', () => {
    overlayWin = null;
  });
  overlayWin.on('resize', () => {
    const b = overlayWin.getBounds();
    settings.fontSize = Math.max(
      16,
      Math.min(48, Math.round((b.height - (settings.rows === 2 ? 48 : 24)) / (settings.rows === 2 ? 1.6 : 1.2)))
    );
    broadcastState();
  });
  applyOverlayInteraction();
  return overlayWin;
}

function applyOverlayInteraction() {
  if (!overlayWin || overlayWin.isDestroyed()) return;
  // locked → click-through; unlocked → interactive
  overlayWin.setIgnoreMouseEvents(!!settings.locked, { forward: !!settings.locked });
  overlayWin.setOpacity(Math.max(0.2, Math.min(1, settings.opacity)));
  const b = overlayWin.getBounds();
  const targetH = settings.rows === 2 ? 128 : 84;
  if (Math.abs(b.height - targetH) > 2) {
    overlayWin.setBounds({ ...b, height: targetH });
  }
  if (settings.showOverlay) overlayWin.showInactive();
  else overlayWin.hide();
}

function createSettings() {
  if (settingsWin && !settingsWin.isDestroyed()) {
    settingsWin.show();
    settingsWin.focus();
    return settingsWin;
  }
  settingsWin = new BrowserWindow({
    width: 440,
    height: 620,
    resizable: true,
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

function rebuildTray() {
  if (!tray) return;
  const menu = Menu.buildFromTemplate([
    {
      label:
        settings.demo
          ? '演示模式运行中'
          : connected
            ? `已连接 · ${nowPlaying?.title || ''}`
            : '等待手机连接',
      enabled: false,
    },
    { type: 'separator' },
    {
      label: settings.showOverlay ? '隐藏歌词窗' : '显示歌词窗',
      click: () => {
        settings.showOverlay = !settings.showOverlay;
        applyOverlayInteraction();
        broadcastState();
        rebuildTray();
      },
    },
    {
      label: settings.locked ? '解锁（可拖动）' : '锁定（点击穿透）',
      click: () => {
        settings.locked = !settings.locked;
        applyOverlayInteraction();
        broadcastState();
        rebuildTray();
      },
    },
    {
      label: settings.demo ? '关闭演示' : '开启演示',
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
        broadcastState();
        rebuildTray();
      },
    },
    { type: 'separator' },
    { label: '打开设置…', click: () => createSettings() },
    {
      label: '查看日志',
      click: () => shell.openPath(LOG_PATH),
    },
    { type: 'separator' },
    {
      label: '退出',
      click: () => {
        app.isQuiting = true;
        app.quit();
      },
    },
  ]);
  tray.setContextMenu(menu);
}

function createTray() {
  tray = new Tray(trayImage());
  tray.setToolTip('手机歌词镜像');
  rebuildTray();
  tray.on('click', () => {
    if (settingsWin) {
      if (settingsWin.isVisible()) settingsWin.hide();
      else settingsWin.show();
    } else {
      createSettings();
    }
  });
}

async function startServer(port) {
  if (server) await server.stop();
  server = new StatusServer({ port, onNowPlaying });
  try {
    await server.start();
    log('server listen', port, server.listLanAddresses().join(','));
    return { ok: true, port, addresses: server.listLanAddresses() };
  } catch (err) {
    log('server fail', err.message);
    if (Notification.isSupported()) {
      new Notification({ title: '端口占用', body: `无法监听 ${port}：${err.message}` }).show();
    }
    return { ok: false, error: err.message };
  }
}

function startTicker() {
  if (ticker) clearInterval(ticker);
  ticker = setInterval(() => {
    if (!settings.demo && server) {
      const next = server.isConnected();
      if (next !== connected) {
        connected = next;
        if (!connected) {
          // never keep another song's lyrics when phone is gone
          nowPlaying = null;
          lyricLines = [];
          lyricKey = null;
          lyricSource = null;
          lyricError = null;
          log('phone disconnected — cleared lyrics');
        }
        broadcastState();
        rebuildTray();
        return;
      }
    }
    if (overlayWin && !overlayWin.isDestroyed()) {
      overlayWin.webContents.send('state:push', snapshotState());
    }
  }, 200);
}

function registerIpc() {
  ipcMain.handle('state:get', () => snapshotState());
  ipcMain.handle('settings:set', async (_e, partial) => {
    const prevPort = settings.port;
    settings = { ...settings, ...partial };
    if (partial.port && Number(partial.port) !== prevPort) {
      await startServer(Number(settings.port));
    }
    if (partial.demo !== undefined) {
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
    }
    applyOverlayInteraction();
    broadcastState();
    rebuildTray();
    return snapshotState();
  });
  ipcMain.handle('server:restart', async (_e, port) => {
    const result = await startServer(Number(port) || settings.port);
    if (result.ok) settings.port = result.port;
    broadcastState();
    return result;
  });
  ipcMain.handle('overlay:hide', () => {
    settings.showOverlay = false;
    applyOverlayInteraction();
    broadcastState();
    rebuildTray();
    return true;
  });
  ipcMain.handle('overlay:show', () => {
    settings.showOverlay = true;
    if (!overlayWin) createOverlay();
    applyOverlayInteraction();
    broadcastState();
    rebuildTray();
    return true;
  });
  ipcMain.handle('overlay:open-settings', () => {
    createSettings();
    return true;
  });
  ipcMain.handle('overlay:move', (_e, dx, dy) => {
    if (!overlayWin || settings.locked) return;
    const b = overlayWin.getBounds();
    overlayWin.setPosition(b.x + Math.round(dx), b.y + Math.round(dy));
  });
  ipcMain.handle('overlay:resize', (_e, dw, dh) => {
    if (!overlayWin || settings.locked) return;
    const b = overlayWin.getBounds();
    const w = Math.max(280, b.width + Math.round(dw));
    const h = Math.max(settings.rows === 2 ? 96 : 64, b.height + Math.round(dh));
    overlayWin.setBounds({ x: b.x, y: b.y, width: w, height: h });
  });
  ipcMain.handle('open-log', () => shell.openPath(LOG_PATH));
}

app.whenReady().then(async () => {
  log('app ready');
  registerIpc();
  createTray();
  createOverlay();
  await startServer(settings.port);
  startTicker();
  createSettings();
  broadcastState();
});

app.on('window-all-closed', () => {
  // keep tray
});

app.on('before-quit', async () => {
  if (ticker) clearInterval(ticker);
  if (server) await server.stop();
});
