const el = (id) => document.getElementById(id);

const badge = el('conn-badge');
const connMeta = el('conn-meta');
const ips = el('ips');
const port = el('port');
const font = el('font');
const fontVal = el('font-val');
const rows1 = el('rows1');
const rows2 = el('rows2');
const lock = el('lock');
const opacity = el('opacity');
const opVal = el('op-val');
const demo = el('demo');
const lyricSrc = el('lyric-src');

function applyState(state) {
  if (!state) return;
  const on = state.connected || state.demo;
  badge.textContent = on ? (state.demo ? '演示中' : '在线') : '离线';
  badge.className = `badge ${on ? 'on' : 'off'}`;

  if (state.nowPlaying) {
    connMeta.textContent = `${state.nowPlaying.title} — ${state.nowPlaying.artist || '未知歌手'} · ${state.nowPlaying.state}`;
  } else if (state.demo) {
    connMeta.textContent = '演示模式：使用内置示例歌词';
  } else {
    connMeta.textContent = '等待手机 POST /api/now-playing';
  }

  ips.innerHTML = (state.addresses || [])
    .map((ip) => `http://${ip}:${state.port}/api/now-playing`)
    .join('<br/>') || '未检测到非环回 IPv4';

  const s = state.settings;
  if (document.activeElement !== port) port.value = s.port;
  font.value = s.fontSize;
  fontVal.textContent = s.fontSize;
  rows1.classList.toggle('active', s.rows === 1);
  rows2.classList.toggle('active', s.rows === 2);
  lock.checked = !!s.locked;
  opacity.value = Math.round(s.opacity * 100);
  opVal.textContent = Math.round(s.opacity * 100);
  demo.checked = !!s.demo;
  lyricSrc.textContent = state.lyricSource ? `歌词源: ${state.lyricSource}` : '';
}

async function push(partial) {
  if (!window.phoneLyrics) return;
  const next = await window.phoneLyrics.setSettings(partial);
  applyState(next);
}

font.addEventListener('input', () => {
  fontVal.textContent = font.value;
  push({ fontSize: Number(font.value) });
});
opacity.addEventListener('input', () => {
  opVal.textContent = opacity.value;
  push({ opacity: Number(opacity.value) / 100 });
});
lock.addEventListener('change', () => push({ locked: lock.checked }));
demo.addEventListener('change', () => push({ demo: demo.checked }));
rows1.addEventListener('click', () => push({ rows: 1 }));
rows2.addEventListener('click', () => push({ rows: 2 }));
el('btn-restart').addEventListener('click', async () => {
  const result = await window.phoneLyrics.restartServer(Number(port.value));
  if (!result.ok) connMeta.textContent = `端口失败: ${result.error}`;
});
el('btn-hide').addEventListener('click', () => window.phoneLyrics.hideOverlay());

if (window.phoneLyrics) {
  window.phoneLyrics.onState(applyState);
  window.phoneLyrics.getState().then(applyState);
} else {
  connMeta.textContent = '浏览器预览模式（请打开 index.html 使用演示）';
}
