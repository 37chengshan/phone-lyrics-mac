const { parseLRC } = require('../shared/lrc');

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function ua() {
  return {
    'User-Agent':
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
    Accept: 'application/json, text/plain, */*',
  };
}

/** score how well a candidate matches title/artist */
function scoreHit(title, artist, candTitle, candArtist) {
  const t1 = String(title || '').trim().toLowerCase();
  const a1 = String(artist || '').trim().toLowerCase();
  const t2 = String(candTitle || '').trim().toLowerCase();
  const a2 = String(candArtist || '').trim().toLowerCase();
  let s = 0;
  if (!t1 || !t2) return 0;
  if (t1 === t2) s += 4;
  else if (t2.includes(t1) || t1.includes(t2)) s += 2;
  if (a1 && a2) {
    if (a1 === a2) s += 3;
    else if (a1.includes(a2) || a2.includes(a1)) s += 2;
  }
  return s;
}

class LyricsService {
  constructor() {
    this.cache = new Map();
    this.lastError = null;
  }

  /**
   * @param {string} title
   * @param {string} artist
   * @returns {Promise<{lines:{timeMs:number,text:string}[], source:string}>}
   */
  async fetchLyrics(title, artist) {
    const key = `${artist || ''}::${title}`.toLowerCase();
    if (this.cache.has(key)) return this.cache.get(key);

    const attempts = [
      ['lrclib', () => this.fromLrclib(title, artist)],
      ['netease', () => this.fromNetease(title, artist)],
      ['qq', () => this.fromQQ(title, artist)],
    ];

    for (const [name, fn] of attempts) {
      for (let i = 0; i < 2; i++) {
        try {
          const result = await fn();
          if (result && result.lines && result.lines.length) {
            this.lastError = null;
            this.cache.set(key, result);
            return result;
          }
        } catch (err) {
          this.lastError = `${name}: ${err.message}`;
        }
        await sleep(300 * (i + 1));
      }
    }

    const titleOnly = {
      lines: [{ timeMs: 0, text: `${artist ? artist + ' - ' : ''}${title}` }],
      source: 'title-only',
      error: this.lastError,
    };
    this.cache.set(key, titleOnly);
    return titleOnly;
  }

  async fromLrclib(title, artist) {
    const q = new URLSearchParams({
      track_name: title,
      artist_name: artist || '',
    });
    const listRes = await fetch(`https://lrclib.net/api/search?${q}`, {
      headers: ua(),
    });
    if (!listRes.ok) throw new Error(`lrclib ${listRes.status}`);
    const list = await listRes.json();
    const arr = Array.isArray(list) ? list : [];
    arr.sort(
      (a, b) =>
        scoreHit(title, artist, b.trackName, b.artistName) -
        scoreHit(title, artist, a.trackName, a.artistName)
    );
    for (const item of arr.slice(0, 4)) {
      const lrc = item.syncedLyrics || item.plainLyrics || '';
      const lines = parseLRC(lrc);
      if (lines.length >= 3) {
        return { lines, source: `lrclib:${item.id || 'hit'}` };
      }
    }
    // exact get fallback
    const exact = await fetch(
      `https://lrclib.net/api/get?${new URLSearchParams({
        track_name: title,
        artist_name: artist || '',
      })}`,
      { headers: ua() }
    );
    if (exact.ok) {
      const data = await exact.json();
      const lines = parseLRC(data.syncedLyrics || data.plainLyrics || '');
      if (lines.length >= 3) return { lines, source: 'lrclib:exact' };
    }
    return null;
  }

  async fromNetease(title, artist) {
    const keyword = `${artist || ''} ${title}`.trim();
    const searchUrl = `https://music.163.com/api/search/get/web?s=${encodeURIComponent(
      keyword
    )}&type=1&limit=8`;
    const res = await fetch(searchUrl, {
      headers: {
        ...ua(),
        Referer: 'https://music.163.com',
        Cookie: 'appver=2.0.2;',
      },
    });
    if (!res.ok) throw new Error(`netease search ${res.status}`);
    const data = await res.json();
    const songs = data?.result?.songs || [];
    if (!songs.length) return null;
    songs.sort((a, b) => {
      const an = (a.artists || a.ar || []).map((x) => x.name).join(',');
      const bn = (b.artists || b.ar || []).map((x) => x.name).join(',');
      return (
        scoreHit(title, artist, b.name, bn) - scoreHit(title, artist, a.name, an)
      );
    });
    const song = songs[0];
    const lyricRes = await fetch(
      `https://music.163.com/api/song/lyric?id=${song.id}&lv=1&kv=1&tv=-1`,
      {
        headers: {
          ...ua(),
          Referer: 'https://music.163.com',
        },
      }
    );
    if (!lyricRes.ok) throw new Error(`netease lyric ${lyricRes.status}`);
    const lyricJson = await lyricRes.json();
    const lrcText =
      lyricJson?.lrc?.lyric || lyricJson?.klyric?.lyric || lyricJson?.tlyric?.lyric || '';
    const lines = parseLRC(lrcText);
    if (lines.length < 3) return null;
    return { lines, source: `netease:${song.id}` };
  }

  async fromQQ(title, artist) {
    const q = encodeURIComponent(`${artist || ''} ${title}`.trim());
    const searchUrl = `https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${q}&p=1&n=5&format=json`;
    const res = await fetch(searchUrl, {
      headers: {
        ...ua(),
        Referer: 'https://y.qq.com',
      },
    });
    const raw = await res.text();
    let json;
    try {
      json = JSON.parse(raw);
    } catch {
      const m = raw.match(/\{[\s\S]*\}/);
      if (!m) return null;
      json = JSON.parse(m[0]);
    }
    const list = json?.data?.song?.list || [];
    if (!list.length) return null;
    const pick = list[0];
    const mid = pick.songmid;
    if (!mid) return null;
    const lyricUrl = `https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${mid}&format=json&nobase64=1`;
    const lyricRes = await fetch(lyricUrl, {
      headers: {
        ...ua(),
        Referer: 'https://y.qq.com',
      },
    });
    const lyricRaw = await lyricRes.text();
    let lyricJson;
    try {
      lyricJson = JSON.parse(lyricRaw);
    } catch {
      const cleaned = lyricRaw
        .replace(/^[^(]*\(/, '')
        .replace(/\);?\s*$/, '');
      lyricJson = JSON.parse(cleaned);
    }
    const lrcText = lyricJson?.lyric || '';
    const lines = parseLRC(decodeURIComponent(lrcText));
    if (lines.length < 3) return null;
    return { lines, source: `qq:${mid}` };
  }
}

module.exports = { LyricsService };
