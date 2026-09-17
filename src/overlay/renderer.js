const root = document.getElementById('root');
const currentEl = document.getElementById('current');
const nextEl = document.getElementById('next');

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

if (window.phoneLyrics) {
  window.phoneLyrics.onState(applyState);
  window.phoneLyrics.getState().then(applyState);
} else {
  window.__applyState = applyState;
  currentEl.textContent = '等待手机';
}
