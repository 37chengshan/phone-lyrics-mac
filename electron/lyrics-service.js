const { parseLRC } = require('../shared/lrc');

const DEMO_LRC = `[00:00.00]演示模式：手机歌词镜像
[00:04.00]Mac 不播放声音，只同步桌面歌词
[00:09.00]打开 Android 中继，填入下方 IP
[00:14.00]手机热点同网段即可实时对齐
[00:19.00]支持单行 / 双行、字号、锁定
[00:25.00]— demo line 6 —
[00:30.00]— demo line 7 —
[00:35.00]— demo line 8 —
[00:40.00]循环演示结束，从头开始
`;

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

class LyricsService {
  constructor() {
    this.cache = new Map();
  }

  /**
   * @param {string} title
   * @param {string} artist
   * @returns {Promise<{lines:{timeMs:number,text:string}[], source:string}>}
   */
  async fetchLyrics(title, artist) {
    const key = `${artist}::${title}`.toLowerCase();
    if (this.cache.has(key)) return this.cache.get(key);
    let result = null;
    for (let attempt = 0; attempt < 2 && !result; attempt++) {
      result = await this.tryQQ(title, artist);
      if (!result) await sleep(400 * (attempt + 1));
    }
    if (!result) result = await this.tryLrclib(title, artist);
    if (!result) {
      // Spec: show song name when no lyrics — do not inject demo lines under a real track.
      result = {
        lines: [
          { timeMs: 0, text: `${artist ? artist + ' - ' : ''}${title}` },
        ],
        source: 'title-only',
      };
    }
    this.cache.set(key, result);
    return result;
  }

  async tryQQ(title, artist) {
    try {
      const q = encodeURIComponent(`${artist} ${title}`.trim());
      const searchUrl = `https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${q}&p=1&n=5&format=json`;
      const searchRes = await fetch(searchUrl, {
        headers: { Referer: 'https://y.qq.com', 'User-Agent': 'Mozilla/5.0' },
      });
      const searchJson = await searchRes.text();
      const json = JSON.parse(searchJson.replace(/callback\((.*)\)/s, '$1').replace(/^\s*callback\(/, '').replace(/\);?\s*$/, '') || searchJson);
      const list = json?.data?.song?.list || [];
      if (!list.length) return null;
      const pick = list[0];
      const mid = pick.songmid || pick.media_mid;
      if (!mid) return null;
      const lyricUrl = `https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${mid}&format=json&nobase64=1`;
      const lyricRes = await fetch(lyricUrl, {
        headers: {
          Referer: 'https://y.qq.com',
          'User-Agent': 'Mozilla/5.0',
        },
      });
      const raw = await lyricRes.text();
      let lyricJson;
      try {
        lyricJson = JSON.parse(raw);
      } catch {
        lyricJson = JSON.parse(
          raw.replace(/^\s*MusicJsonCallback\(/, '').replace(/\);?\s*$/, '')
        );
      }
      const lrcText = lyricJson?.lyric || '';
      const lines = parseLRC(decodeURIComponent(lrcText));
      if (!lines.length) return null;
      return { lines, source: 'qq' };
    } catch {
      return null;
    }
  }

  async tryLrclib(title, artist) {
    try {
      const url = `https://lrclib.net/api/get?track_name=${encodeURIComponent(title)}&artist_name=${encodeURIComponent(artist)}`;
      const res = await fetch(url, { headers: { 'User-Agent': 'phone-lyrics-mac/0.1' } });
      if (!res.ok) return null;
      const data = await res.json();
      const lrcText = data.syncedLyrics || data.plainLyrics || '';
      const lines = parseLRC(lrcText);
      if (!lines.length) return null;
      return { lines, source: 'lrclib' };
    } catch {
      return null;
    }
  }

  demoLines() {
    return parseLRC(DEMO_LRC);
  }
}

module.exports = { LyricsService, DEMO_LRC };
