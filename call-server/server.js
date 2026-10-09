'use strict';
/*
 * Mouna call relay. Two clients per room, `mouna` (the app) and `guest` (a browser): everything one sends, text or
 * binary, is passed to the other untouched. The server never reads, stores or logs audio or text; it logs only
 * how many rooms and clients there are. See README.md for the message format the two sides speak.
 */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { WebSocketServer } = require('ws');

const PAGE = path.join(__dirname, 'public', 'call.html');
const LANDING = path.join(__dirname, 'public', 'index.html');

// No I, L, O, 0 or 1: a room id read aloud or typed from a screenshot can't be mistaken.
const ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
const ROOM_RE = /^[A-Z2-9]{4,16}$/;
const ROLES = new Set(['mouna', 'guest']);
const PING_MS = 20_000;
const MAX_PAYLOAD = 4 * 1024 * 1024; // a long Sarvam sentence is a few hundred KB
const MAX_BUFFERED = 8 * 1024 * 1024; // a peer this far behind gets nothing more until it catches up
const MAX_ROOMS = 2000;

// Close codes the clients can act on (4000-4999 is the application range).
const CLOSE = { BAD_ROOM: 4400, FULL: 4409, BUSY: 4429 };

function newRoomId(len = 6, rand = require('node:crypto').randomInt) {
  let s = '';
  for (let i = 0; i < len; i++) s += ALPHABET[rand(ALPHABET.length)];
  return s;
}

function createServer({ port = 0, host = '0.0.0.0', log = console.log } = {}) {
  const rooms = new Map(); // id -> { mouna?: ws, guest?: ws }
  let clients = 0;
  const count = (what) => log(`${what} rooms=${rooms.size} clients=${clients}`);

  const html = (res, file) => {
    fs.readFile(file, (err, body) => {
      if (err) { res.writeHead(500).end('page missing'); return; }
      res.writeHead(200, {
        'content-type': 'text/html; charset=utf-8',
        'cache-control': 'no-store',
        // The room id is in the URL: keep it out of Referer headers and search engines.
        'referrer-policy': 'no-referrer',
        'x-robots-tag': 'noindex',
        'x-content-type-options': 'nosniff',
        'content-security-policy': "default-src 'none'; script-src 'unsafe-inline' blob:; style-src 'unsafe-inline'; connect-src 'self' wss: ws:; media-src blob:; worker-src blob:; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
        'permissions-policy': 'microphone=(self), camera=(), geolocation=()',
      });
      res.end(body);
    });
  };

  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://x');
    if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405).end(); return; }
    if (url.pathname === '/healthz') {
      res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      res.end(JSON.stringify({ ok: true, rooms: rooms.size }));
    } else if (url.pathname === '/') {
      html(res, LANDING);
    } else if (/^\/c\/[A-Za-z0-9]{1,32}\/?$/.test(url.pathname)) {
      html(res, PAGE);
    } else if (url.pathname === '/favicon.ico') {
      res.writeHead(204).end();
    } else {
      res.writeHead(404, { 'content-type': 'text/plain' }).end('not found');
    }
  });

  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_PAYLOAD });
  server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url, 'http://x');
    if (url.pathname !== '/ws') { socket.destroy(); return; }
    wss.handleUpgrade(req, socket, head, (ws) => join(ws, url));
  });

  function join(ws, url) {
    const id = (url.searchParams.get('room') || '').toUpperCase();
    const role = url.searchParams.get('role') || '';
    // Rejected after the upgrade rather than with an HTTP error, so a browser can read the reason.
    const reject = (code, reason) => ws.close(code, reason);
    if (!ROOM_RE.test(id) || !ROLES.has(role)) return reject(CLOSE.BAD_ROOM, 'bad room or role');

    let room = rooms.get(id);
    if (!room) {
      if (rooms.size >= MAX_ROOMS) return reject(CLOSE.BUSY, 'server busy');
      room = {};
      rooms.set(id, room);
    }
    const old = room[role];
    // The same role again is a reconnect only if the old socket has stopped answering pings (a dropped phone
    // looks open for up to 40 s). Otherwise the room is taken.
    if (old) {
      if (old.isAlive) { if (!room.mouna && !room.guest) rooms.delete(id); return reject(CLOSE.FULL, 'room full'); }
      old.terminate();
      clients--;
    }
    room[role] = ws;
    clients++;
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });
    count(`join ${role}`);

    const peerOf = () => room[role === 'mouna' ? 'guest' : 'mouna'];
    const send = (to, msg) => { if (to && to.readyState === 1) to.send(msg); };
    const peer = peerOf();
    send(ws, JSON.stringify({ t: 'peer', joined: !!peer }));
    send(peer, JSON.stringify({ t: 'peer', joined: true }));

    ws.on('message', (data, isBinary) => {
      const to = peerOf();
      if (!to || to.readyState !== 1 || to.bufferedAmount > MAX_BUFFERED) return;
      to.send(data, { binary: isBinary });
    });
    ws.on('error', () => {});
    ws.on('close', () => {
      if (room[role] !== ws) return; // replaced by a reconnect
      delete room[role];
      clients--;
      send(peerOf(), JSON.stringify({ t: 'peer', joined: false }));
      if (!room.mouna && !room.guest) rooms.delete(id);
      count(`leave ${role}`);
    });
  }

  const ping = setInterval(() => {
    for (const ws of wss.clients) {
      if (ws.isAlive === false) { ws.terminate(); continue; }
      ws.isAlive = false;
      ws.ping();
    }
  }, PING_MS);
  ping.unref();

  return new Promise((resolve) => {
    server.listen(port, host, () => {
      resolve({
        server, rooms, port: server.address().port,
        close: () => new Promise((done) => {
          clearInterval(ping);
          for (const ws of wss.clients) ws.terminate();
          server.close(() => done());
        }),
      });
    });
  });
}

module.exports = { createServer, newRoomId, ALPHABET, ROOM_RE, CLOSE, PING_MS };

if (require.main === module) {
  const port = Number(process.env.PORT) || 8787;
  createServer({ port }).then((s) => console.log(`mouna call server on :${s.port}`));
}
