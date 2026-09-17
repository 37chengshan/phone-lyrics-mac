/** LRC 解析与进度定位（Mac / 浏览器共用） */

/**
 * @param {string} text
 * @returns {{timeMs:number, text:string}[]}
 */
function parseLRC(text) {
  if (!text || typeof text !== 'string') return [];
  const lines = [];
  const re = /\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]/g;
  for (const raw of text.split(/\r?\n/)) {
    re.lastIndex = 0;
    const stamps = [];
    let m;
    while ((m = re.exec(raw)) !== null) {
      const min = Number(m[1]);
      const sec = Number(m[2]);
      const frac = m[3] ? Number(String(m[3]).padEnd(3, '0').slice(0, 3)) : 0;
      stamps.push(min * 60000 + sec * 1000 + frac);
    }
    const content = raw.replace(/\[[^\]]*\]/g, '').trim();
    if (!stamps.length) continue;
    for (const t of stamps) lines.push({ timeMs: t, text: content });
  }
  lines.sort((a, b) => a.timeMs - b.timeMs);
  return lines;
}

/**
 * @param {{timeMs:number, text:string}[]} lines
 * @param {number} positionMs
 * @returns {{index:number, current:object|null, next:object|null}}
 */
function locateLine(lines, positionMs) {
  if (!lines.length) return { index: -1, current: null, next: null };
  let lo = 0;
  let hi = lines.length - 1;
  let idx = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (lines[mid].timeMs <= positionMs) {
      idx = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  if (idx < 0) {
    return { index: -1, current: null, next: lines[0] || null };
  }
  return {
    index: idx,
    current: lines[idx],
    next: lines[idx + 1] || null,
  };
}

module.exports = { parseLRC, locateLine };
