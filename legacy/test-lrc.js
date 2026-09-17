const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');
// 本文件 2026-09-17 随 Electron 原型一起搬进 legacy/,shared/ 的深度少了一层。
const { parseLRC, locateLine } = require('./shared/lrc');

test('parseLRC multi timestamps and fractions', () => {
  const lines = parseLRC(
    '[00:12.00]第一句\n[00:15.50]第二句\n[00:15.50][01:02.083]重复时间戳\n'
  );
  assert.equal(lines.length, 4);
  assert.equal(lines[0].timeMs, 12000);
  assert.equal(lines[0].text, '第一句');
  assert.equal(lines[1].timeMs, 15500);
  assert.equal(lines[3].timeMs, 62083);
});

test('locateLine current and next', () => {
  const lines = parseLRC('[00:00.00]A\n[00:10.00]B\n[00:20.00]C\n');
  const a = locateLine(lines, 5000);
  assert.equal(a.current.text, 'A');
  assert.equal(a.next.text, 'B');
  const b = locateLine(lines, 15000);
  assert.equal(b.current.text, 'B');
  assert.equal(b.next.text, 'C');
  const before = locateLine(lines, -1);
  assert.equal(before.current, null);
  assert.equal(before.next.text, 'A');
});

console.log('lrc tests ok →', path.basename(__filename));
