const root = document.getElementById('root');
const currentEl = document.getElementById('current');
const nextEl = document.getElementById('next');

let dragging = false;
let start = null;

function applyState(state) {
  if (!state) return;
  const s = state.settings;
  root.classList.toggle('rows-1', s.rows === 1);
  root.classList.toggle('rows-2', s.rows === 2);
  root.classList.toggle('locked', !!s.locked);
  root.classList.toggle('offline', !state.connected && !state.demo);
  currentEl.style.fontSize = `${s.fontSize}px`;
  nextEl.style.fontSize = `${Math.max(12, Math.round(s.fontSize * 0.72))}px`;
  currentEl.textContent = state.currentText || '…';
  nextEl.textContent = state.nextText || '';
}

window.addEventListener('mousedown', (e) => {
  if (e.button !== 0) return;
  dragging = true;
  start = { x: e.screenX, y: e.screenY };
});

window.addEventListener('mouseup', () => {
  dragging = false;
  start = null;
});

window.addEventListener('mousemove', (e) => {
  if (!dragging || !start) return;
  const dx = e.screenX - start.x;
  const dy = e.screenY - start.y;
  start = { x: e.screenX, y: e.screenY };
  // Electron drag via mouse position deltas is handled natively for frameless
  // windows only with CSS -webkit-app-region; for locked we skip.
  // When unlocked, body uses app-region drag region for the strip.
});

if (window.phoneLyrics) {
  window.phoneLyrics.onState(applyState);
  window.phoneLyrics.getState().then(applyState);
} else {
  // Browser demo fallback
  window.__applyState = applyState;
  currentEl.textContent = '等待手机';
}
