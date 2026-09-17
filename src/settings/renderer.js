const $ = (id) => document.getElementById(id);

function applyState(state) {
  if (!state) return;
  const online = state.connected || state.demo;
  const pill = $('conn');
  pill.textContent = online ? (state.demo ? '演示' : '在线') : '离线';
  pill.className = `pill ${online ? 'on' : 'off'}`;

  $('song').textContent = state.nowPlaying
    ? `${state.nowPlaying.artist || ''} ${state.nowPlaying.artist ? '· ' : ''}${state.nowPlaying.title}`
    : state.demo
      ? '演示模式'
      : '等待手机…';
  $('pkg').textContent = state.nowPlaying?.source || '—';

  const addr =
    (state.addresses && state.addresses[0]) || '127.0.0.1';
  $('endpoint').textContent = `http://${addr}:${state.port}/api/now-playing`;

  const s = state.settings;
  if (document.activeElement !== $('port')) $('port').value = s.port;
  $('font').value = s.fontSize;
  $('font-val').textContent = s.fontSize;
  $('rows1').classList.toggle('active', s.rows === 1);
  $('rows2').classList.toggle('active', s.rows === 2);
  $('lock').checked = !!s.locked;
  $('opacity').value = Math.round(s.opacity * 100);
  $('op-val').textContent = Math.round(s.opacity * 100);
  $('demo').checked = !!s.demo;
  $('lyric-src').textContent = [
    state.lyricSource ? `歌词源 ${state.lyricSource}` : '',
    state.lyricError ? `· ${state.lyricError}` : '',
  ]
    .filter(Boolean)
    .join(' ');
}

async function push(partial) {
  if (!window.phoneLyrics) return;
  applyState(await window.phoneLyrics.setSettings(partial));
}

$('font').addEventListener('input', (e) => {
  $('font-val').textContent = e.target.value;
  push({ fontSize: Number(e.target.value) });
});
$('opacity').addEventListener('input', (e) => {
  $('op-val').textContent = e.target.value;
  push({ opacity: Number(e.target.value) / 100 });
});
$('lock').addEventListener('change', (e) => push({ locked: e.target.checked }));
$('demo').addEventListener('change', (e) => push({ demo: e.target.checked }));
$('rows1').addEventListener('click', () => push({ rows: 1 }));
$('rows2').addEventListener('click', () => push({ rows: 2 }));
$('restart').addEventListener('click', async () => {
  const result = await window.phoneLyrics.restartServer(Number($('port').value));
  if (!result.ok) $('lyric-src').textContent = result.error || '端口启动失败';
});
$('show-overlay').addEventListener('click', () => window.phoneLyrics.showOverlay());
$('hide-overlay').addEventListener('click', () => window.phoneLyrics.hideOverlay());
$('open-log').addEventListener('click', () => window.phoneLyrics.openLog());
$('copy-ip').addEventListener('click', async () => {
  const text = $('endpoint').textContent;
  try {
    await navigator.clipboard.writeText(text);
    $('lyric-src').textContent = '已复制推送地址';
  } catch {
    $('lyric-src').textContent = text;
  }
});

if (window.phoneLyrics) {
  window.phoneLyrics.onState(applyState);
  window.phoneLyrics.getState().then(applyState);
}
