'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const WebSocket = require('ws');
const { createServer, newRoomId, ALPHABET, ROOM_RE, CLOSE } = require('../server.js');

const quiet = () => {};

/** A client that records everything it receives and can wait for the next message. */
function client(port, room, role) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?room=${room}&role=${role}`);
  const inbox = [];
  const waiting = [];
  ws.on('message', (data, isBinary) => {
    const m = { isBinary, data: isBinary ? Buffer.from(data) : JSON.parse(data.toString()) };
    const w = waiting.shift();
    if (w) w(m); else inbox.push(m);
  });
  const closed = new Promise((res) => ws.on('close', (code, reason) => res({ code, reason: reason.toString() })));
  const opened = new Promise((res, rej) => { ws.on('open', res); ws.on('error', rej); });
  const next = () => (inbox.length ? Promise.resolve(inbox.shift()) : new Promise((res) => waiting.push(res)));
  return { ws, opened, closed, next };
}

const within = (p, ms = 2000) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), ms))]);

test('room ids are 6 characters from the unambiguous alphabet', () => {
  for (let i = 0; i < 200; i++) {
    const id = newRoomId();
    assert.equal(id.length, 6);
    assert.match(id, ROOM_RE);
    for (const c of id) assert.ok(ALPHABET.includes(c));
  }
  for (const bad of ['I', 'L', 'O', '0', '1']) assert.ok(!ALPHABET.includes(bad));
  assert.notEqual(newRoomId(), newRoomId());
});

test('relays binary and text both ways, with peer events', async (t) => {
  const s = await createServer({ log: quiet });
  t.after(() => s.close());

  const mouna = client(s.port, 'ROOM42', 'mouna');
  await mouna.opened;
  assert.deepEqual((await within(mouna.next())).data, { t: 'peer', joined: false });

  const guest = client(s.port, 'room42', 'guest'); // room ids are case-insensitive
  await guest.opened;
  assert.deepEqual((await within(guest.next())).data, { t: 'peer', joined: true });
  assert.deepEqual((await within(mouna.next())).data, { t: 'peer', joined: true });

  // Mouna -> guest: a caption and an audio frame (kind 1 = WAV).
  mouna.ws.send(JSON.stringify({ t: 'say', text: 'Hello' }));
  const audio = Buffer.from([1, 0x52, 0x49, 0x46, 0x46, 9, 9, 9]);
  mouna.ws.send(audio);
  assert.deepEqual((await within(guest.next())).data, { t: 'say', text: 'Hello' });
  const gotAudio = await within(guest.next());
  assert.ok(gotAudio.isBinary);
  assert.deepEqual(gotAudio.data, audio);

  // Guest -> Mouna: PCM (kind 16) and a text message.
  const pcm = Buffer.concat([Buffer.from([16]), Buffer.alloc(1280, 7)]);
  guest.ws.send(pcm);
  guest.ws.send(JSON.stringify({ t: 'answered' }));
  const gotPcm = await within(mouna.next());
  assert.ok(gotPcm.isBinary);
  assert.deepEqual(gotPcm.data, pcm);
  assert.deepEqual((await within(mouna.next())).data, { t: 'answered' });

  // Leaving is announced, and the room is cleaned up when both are gone.
  guest.ws.close();
  await guest.closed;
  assert.deepEqual((await within(mouna.next())).data, { t: 'peer', joined: false });
  assert.equal(s.rooms.size, 1);
  mouna.ws.close();
  await mouna.closed;
  await within(new Promise((res) => { const i = setInterval(() => { if (s.rooms.size === 0) { clearInterval(i); res(); } }, 10); }));
});

test('a third client is rejected and the pair is left alone', async (t) => {
  const s = await createServer({ log: quiet });
  t.after(() => s.close());
  const mouna = client(s.port, 'FULL22', 'mouna');
  const guest = client(s.port, 'FULL22', 'guest');
  await Promise.all([mouna.opened, guest.opened]);
  await within(mouna.next()); await within(mouna.next()); await within(guest.next());

  const extra = client(s.port, 'FULL22', 'guest');
  const closed = await within(extra.closed);
  assert.equal(closed.code, CLOSE.FULL);
  const extraMouna = client(s.port, 'FULL22', 'mouna');
  assert.equal((await within(extraMouna.closed)).code, CLOSE.FULL);

  // The pair still talks.
  mouna.ws.send(JSON.stringify({ t: 'say', text: 'still here' }));
  assert.deepEqual((await within(guest.next())).data, { t: 'say', text: 'still here' });
});

test('bad room ids and roles are refused', async (t) => {
  const s = await createServer({ log: quiet });
  t.after(() => s.close());
  assert.equal((await within(client(s.port, 'x', 'guest').closed)).code, CLOSE.BAD_ROOM);
  assert.equal((await within(client(s.port, 'GOODID', 'admin').closed)).code, CLOSE.BAD_ROOM);
  assert.equal(s.rooms.size, 0);
});

test('serves the landing page, the callee page and a health check', async (t) => {
  const s = await createServer({ log: quiet });
  t.after(() => s.close());
  const base = `http://127.0.0.1:${s.port}`;
  const health = await (await fetch(`${base}/healthz`)).json();
  assert.equal(health.ok, true);
  const landing = await fetch(`${base}/`);
  assert.equal(landing.status, 200);
  assert.match(await landing.text(), /Mouna/);
  const page = await fetch(`${base}/c/ABC234`);
  assert.equal(page.status, 200);
  assert.match(page.headers.get('content-security-policy'), /default-src 'none'/);
  assert.match(await page.text(), /Answer/);
  assert.equal((await fetch(`${base}/nope`)).status, 404);
});

test('logs counts only, never room ids or content', async (t) => {
  const lines = [];
  const s = await createServer({ log: (l) => lines.push(l) });
  t.after(() => s.close());
  const mouna = client(s.port, 'SECRET', 'mouna');
  await mouna.opened;
  await within(mouna.next());
  mouna.ws.send(JSON.stringify({ t: 'say', text: 'private words' }));
  mouna.ws.close();
  await mouna.closed;
  await new Promise((r) => setTimeout(r, 50));
  assert.ok(lines.length >= 1);
  for (const l of lines) assert.doesNotMatch(l, /SECRET|private/);
});
