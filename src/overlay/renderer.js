const root = document.getElementById('root');
const currentEl = document.getElementById('current');
const nextEl = document.getElementById('next');
const statusEl = document.getElementById('status');
const chrome = document.getElementById('chrome');

const btnDrag = document.getElementById('btn-drag');
const btnResize = document.getElementById('btn-resize');
const btnLock = document.getElementById('btn-lock');
const btnSettings = document.getElementById('btn-settings');
const btnClose = document.getElementById('btn-close');

let state = null;
let mode = null; // 'move' | 'resize'
let lastPt = null;

function applyState(s) {
  if (!s) return;
  state = s;
  const cfg = s.settings;
  root.classList.toggle('rows-1', cfg.rows === 1);
  root.classList.toggle('rows-2', cfg.rows === 2);
  root.classList.toggle('locked', !!cfg.locked);
  root.classList.toggle('offline', !s.connected && !s.demo);

  currentEl.style.fontSize = `${cfg.fontSize}px`;
  nextEl.style.fontSize = `${Math.max(12, Math.round(cfg.fontSize * 0.68))}px`;
  currentEl.textContent = s.currentText || '…';
  nextEl.textContent = s.nextText || '';

  btnLock.textContent = cfg.locked ? '解锁' : '锁定';
  btnLock.classList.toggle('active', !!cfg.locked);

  const online = s.connected || s.demo;
  statusEl.className = `status ${online ? 'on' : ''}`;
  const song = s.nowPlaying
    ? `${s.nowPlaying.artist ? s.nowPlaying.artist + ' · ' : ''}${s.nowPlaying.title}`
    : s.demo
      ? '演示曲目'
      : '未连接';
  statusEl.innerHTML = `<span class="dot"></span>${song}${s.lyricSource ? ' · ' + s.lyricSource : ''}`;
}

// Prevent chrome buttons from starting drag region issues
chrome.addEventListener('mousedown', (e) => e.stopPropagation());

function pt(e) {
  return { x: e.screenX, y: e.screenY };
}

function onDown(kind) {
  return (e) => {
    if (e.button !== 0 || !state || state.settings.locked) return;
    mode = kind;
    lastPt = pt(e);
    e.preventDefault();
  };
}

function onMove(e) {
  if (!mode || !lastPt || !window.phoneLyrics) return;
  const p = pt(e);
  const dx = p.x - lastPt.x;
  const dy = p.y - lastPt.y;
  lastPt = p;
  if (mode === 'move') window.phoneLyrics.moveOverlay(dx, dy);
  else window.phoneLyrics.resizeOverlay(dx, dy);
}

function onUp() {
  mode = null;
  lastPt = null;
}

btnDrag.addEventListener('mousedown', onDown('move'));
btnResize.addEventListener('mousedown', onDown('resize'));
window.addEventListener('mousemove', onMove);
window.addEventListener('mouseup', onUp);

btnLock.addEventListener('click', async () => {
  if (!window.phoneLyrics || !state) return;
  const next = await window.phoneLyrics.setSettings({ locked: !state.settings.locked });
  applyState(next);
});

btnSettings.addEventListener('click', () => window.phoneLyrics?.openSettings());
btnClose.addEventListener('click', () => window.phoneLyrics?.hideOverlay());

if (window.phoneLyrics) {
  window.phoneLyrics.onState(applyState);
  window.phoneLyrics.getState().then(applyState);
} else {
  currentEl.textContent = '等待手机';
}
