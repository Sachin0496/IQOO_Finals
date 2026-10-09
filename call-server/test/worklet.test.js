'use strict';
// The guest page's AudioWorklet downsampler, run outside a browser: 48 kHz and 44.1 kHz in, 16 kHz Int16 frames out.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

function workletSource() {
  const html = fs.readFileSync(path.join(__dirname, '..', 'public', 'call.html'), 'utf8');
  const m = html.match(/const WORKLET = `([\s\S]*?)`;/);
  assert.ok(m, 'WORKLET not found in call.html');
  return m[1].replace(/\$\{FRAME\}/g, '640');
}

function run(rate, hz, seconds) {
  const frames = [];
  let Proc;
  const AudioWorkletProcessor = class { constructor() { this.port = { postMessage: (b) => frames.push(new Int16Array(b)) }; } };
  new Function('AudioWorkletProcessor', 'registerProcessor', 'sampleRate', workletSource())(AudioWorkletProcessor, (n, c) => { Proc = c; }, rate);
  const p = new Proc();
  const total = Math.floor(rate * seconds);
  for (let off = 0; off < total; off += 128) {
    const block = new Float32Array(128);
    for (let i = 0; i < 128; i++) block[i] = 0.5 * Math.sin(2 * Math.PI * hz * (off + i) / rate);
    p.process([[block]]);
  }
  return frames;
}

for (const rate of [48000, 44100]) {
  test(`${rate} Hz in becomes 40 ms frames of 16 kHz PCM at the same pitch`, () => {
    const frames = run(rate, 440, 1);
    assert.ok(frames.length >= 24 && frames.length <= 25, `got ${frames.length} frames`);
    assert.ok(frames.every((f) => f.length === 640));
    const all = Int16Array.from(frames.flatMap((f) => [...f]));
    let crossings = 0;
    for (let i = 1; i < all.length; i++) if (all[i - 1] < 0 && all[i] >= 0) crossings++;
    const hz = crossings / (all.length / 16000);
    assert.ok(Math.abs(hz - 440) < 8, `pitch ${hz}`);
    const peak = Math.max(...all.map(Math.abs));
    assert.ok(peak > 14000 && peak < 17000, `peak ${peak}`); // 0.5 full scale = 16384
  });
}
