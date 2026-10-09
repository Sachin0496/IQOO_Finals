'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const net = require('node:net');
const WebSocket = require('ws');
const { createServer, newRoomId, ALPHABET, ROOM_RE, CLOSE } = require('../server.js');

const quiet = () => {};

const keyOf = (name) => name.padEnd(20, 'x'); // a client's secret: 16+ URL-safe characters

/** A client that records everything it receives and can wait for the next message. `key` is its secret for the call. */
function client(port, room, role, key = keyOf(role), headers = {}) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?room=${room}&role=${role}&k=${key}`, { headers });
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

  const extra = client(s.port, 'FULL22', 'guest', keyOf('stranger'));
  const closed = await within(extra.closed);
  assert.equal(closed.code, CLOSE.FULL);
  const extraMouna = client(s.port, 'FULL22', 'mouna', keyOf('stranger'));
  assert.notEqual((await within(extraMouna.closed)).code, 1000); // refused (it does not hold the room's key)

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

/** Sends raw bytes and returns what the server answers before it closes the connection (or after 1 s). */
function raw(port, bytes) {
  return new Promise((resolve) => {
    const sock = net.connect(port, '127.0.0.1', () => sock.write(bytes));
    let out = '';
    sock.on('data', (d) => { out += d.toString(); });
    sock.on('close', () => resolve(out));
    sock.on('error', () => resolve(out));
    setTimeout(() => { sock.destroy(); resolve(out); }, 1000);
  });
}

test('a request target that is not a URL gets 400 and the server keeps running', async (t) => {
  const s = await createServer({ log: quiet });
  t.after(() => s.close());
  assert.match(await raw(s.port, 'GET //[ HTTP/1.1\r\nHost: x\r\n\r\n'), /^HTTP\/1\.1 400 /);
  // The same target on an upgrade is dropped without a reply.
  const upgrade = await raw(s.port, 'GET //[ HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n');
  assert.equal(upgrade, '');
  const health = await (await fetch(`http://127.0.0.1:${s.port}/healthz`)).json();
  assert.equal(health.ok, true);
});

test('the same client coming back replaces its half-open socket at once', async (t) => {
  const s = await createServer({ log: quiet });
  t.after(() => s.close());
  const mouna = client(s.port, 'HALF22', 'mouna', keyOf('app'));
  const guest = client(s.port, 'HALF22', 'guest', keyOf('phone'));
  await Promise.all([mouna.opened, guest.opened]);
  await within(mouna.next()); await within(mouna.next()); await within(guest.next());

  // The guest's phone goes away without a close frame: the server still has it as open (the ping loop is 20 s away).
  guest.ws._socket.pause();

  const back = client(s.port, 'HALF22', 'guest', keyOf('phone'));
  await back.opened;
  assert.deepEqual((await within(back.next())).data, { t: 'peer', joined: true });
  assert.deepEqual((await within(mouna.next())).data, { t: 'peer', joined: true });
  mouna.ws.send(JSON.stringify({ t: 'say', text: 'hello again' }));
  assert.deepEqual((await within(back.next())).data, { t: 'say', text: 'hello again' });
  assert.equal(s.rooms.size, 1);
  guest.ws.terminate();
});

test('a half-open old socket is replaced even when the server never saw it close', async (t) => {
  const s = await createServer({ log: quiet });
  t.after(() => s.close());
  // A raw WebSocket whose process "freezes": it never answers or closes. Only the same key may replace it.
  const old = client(s.port, 'FROZ22', 'guest', keyOf('phone'));
  await old.opened;
  await within(old.next());
  old.ws._socket.pause(); // no reads, no pongs, no close handshake
  const other = client(s.port, 'FROZ22', 'guest', keyOf('stranger'));
  assert.equal((await within(other.closed)).code, CLOSE.FULL);
  assert.ok(s.rooms.get('FROZ22').guest, 'the live (or merely quiet) occupant was not evicted');
  const same = client(s.port, 'FROZ22', 'guest', keyOf('phone'));
  await same.opened;
  assert.deepEqual((await within(same.next())).data, { t: 'peer', joined: false });
  old.ws.terminate();
});

test('a join with another key never evicts an occupant, even one that missed a ping', async (t) => {
  const s = await createServer({ log: quiet });
  t.after(() => s.close());
  const guest = client(s.port, 'LIVE22', 'guest', keyOf('phone'));
  await guest.opened;
  await within(guest.next());
  s.rooms.get('LIVE22').guest.isAlive = false; // inside the ping loop's window
  const intruder = client(s.port, 'LIVE22', 'guest', keyOf('stranger'));
  assert.equal((await within(intruder.closed)).code, CLOSE.FULL);
  assert.equal(guest.ws.readyState, WebSocket.OPEN);
});

test('only the first Mouna owns a room: an impostor cannot join or evict', async (t) => {
  const s = await createServer({ log: quiet });
  t.after(() => s.close());
  const mouna = client(s.port, 'OWNR22', 'mouna', keyOf('owner'));
  const guest = client(s.port, 'OWNR22', 'guest', keyOf('phone'));
  await Promise.all([mouna.opened, guest.opened]);
  await within(mouna.next()); await within(mouna.next()); await within(guest.next());

  const impostor = client(s.port, 'OWNR22', 'mouna', keyOf('impostor'));
  assert.equal((await within(impostor.closed)).code, CLOSE.NOT_YOURS);
  assert.equal(mouna.ws.readyState, WebSocket.OPEN);
  mouna.ws.send(JSON.stringify({ t: 'say', text: 'still me' }));
  assert.deepEqual((await within(guest.next())).data, { t: 'say', text: 'still me' });

  // The owner is remembered while the room exists, even with the seat empty (a guest waiting at a favourite's link).
  mouna.ws.close();
  await mouna.closed;
  assert.deepEqual((await within(guest.next())).data, { t: 'peer', joined: false });
  assert.equal((await within(client(s.port, 'OWNR22', 'mouna', keyOf('impostor')).closed)).code, CLOSE.NOT_YOURS);
  const owner = client(s.port, 'OWNR22', 'mouna', keyOf('owner'));
  await owner.opened;
  assert.deepEqual((await within(owner.next())).data, { t: 'peer', joined: true });
});

test('a key is required, and it must look like one', async (t) => {
  const s = await createServer({ log: quiet });
  t.after(() => s.close());
  assert.equal((await within(client(s.port, 'KEYS22', 'guest', '').closed)).code, CLOSE.BAD_ROOM);
  assert.equal((await within(client(s.port, 'KEYS22', 'guest', 'short').closed)).code, CLOSE.BAD_ROOM);
  assert.equal(s.rooms.size, 0);
});

test('one address can only keep so many rooms open', async (t) => {
  const s = await createServer({ log: quiet, maxRoomsPerIp: 3 });
  t.after(() => s.close());
  const mine = [];
  for (const id of ['AAAA22', 'BBBB22', 'CCCC22']) {
    const c = client(s.port, id, 'mouna', keyOf('app'), { 'cf-connecting-ip': '203.0.113.7' });
    await c.opened; await within(c.next());
    mine.push(c);
  }
  const over = client(s.port, 'DDDD22', 'mouna', keyOf('app'), { 'cf-connecting-ip': '203.0.113.7' });
  assert.equal((await within(over.closed)).code, CLOSE.BUSY);
  assert.equal(s.rooms.has('DDDD22'), false);

  // The first hop of x-forwarded-for counts as the address; another address is unaffected.
  const forwarded = client(s.port, 'EEEE22', 'mouna', keyOf('app'), { 'x-forwarded-for': '203.0.113.7, 10.0.0.1' });
  assert.equal((await within(forwarded.closed)).code, CLOSE.BUSY);
  const other = client(s.port, 'FFFF22', 'mouna', keyOf('app'), { 'cf-connecting-ip': '198.51.100.9' });
  await other.opened;
  assert.deepEqual((await within(other.next())).data, { t: 'peer', joined: false });

  // Joining a room that already exists costs nothing, and closing a room frees its slot.
  const joinsOwn = client(s.port, 'AAAA22', 'guest', keyOf('phone'), { 'cf-connecting-ip': '203.0.113.7' });
  await joinsOwn.opened;
  joinsOwn.ws.close();
  mine[0].ws.close();
  await mine[0].closed;
  await within(new Promise((res) => { const i = setInterval(() => { if (!s.rooms.has('AAAA22')) { clearInterval(i); res(); } }, 10); }));
  const again = client(s.port, 'GGGG22', 'mouna', keyOf('app'), { 'cf-connecting-ip': '203.0.113.7' });
  await again.opened;
  assert.deepEqual((await within(again.next())).data, { t: 'peer', joined: false });
});
