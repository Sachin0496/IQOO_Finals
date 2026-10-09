'use strict';
/*
 * Mouna call relay. Two clients per room, `mouna` (the app) and `guest` (a browser): everything one sends, text or
 * binary, is passed to the other untouched. The server never reads, stores or logs audio or text; it logs only
 * how many rooms and clients there are. See README.md for the message format the two sides speak.
 */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { timingSafeEqual } = require('node:crypto');
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
const MAX_ROOMS_PER_IP = 20; // live rooms one address may have open, so one client can't use up MAX_ROOMS
// A client's secret for the call (`k`), made once per call: 16-64 URL-safe characters.
const TOKEN_RE = /^[A-Za-z0-9_-]{16,64}$/;

// Close codes the clients can act on (4000-4999 is the application range).
const CLOSE = { BAD_ROOM: 4400, NOT_YOURS: 4403, FULL: 4409, BUSY: 4429 };

function newRoomId(len = 6, rand = require('node:crypto').randomInt) {
  let s = '';
  for (let i = 0; i < len; i++) s += ALPHABET[rand(ALPHABET.length)];
  return s;
}

function sameToken(a, b) {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b));
  return x.length === y.length && timingSafeEqual(x, y);
}

/** The request target as a URL, or null if it isn't one (`//[` throws, and a throw here would end the process). */
function parseTarget(req) {
  try { return new URL(req.url, 'http://x'); } catch { return null; }
}

/** Who is asking: the proxy's header when there is one (Cloudflare, Render), else the socket's address. */
function clientIp(req) {
  const h = req.headers;
  const fwd = String(h['x-forwarded-for'] || '').split(',')[0].trim();
  return String(h['cf-connecting-ip'] || '').trim() || fwd || req.socket.remoteAddress || '';
}

function createServer({ port = 0, host = '0.0.0.0', log = console.log, maxRoomsPerIp = MAX_ROOMS_PER_IP } = {}) {
  // id -> { mouna?: ws, guest?: ws, owner?: token of the first mouna, ip: who opened it }
  const rooms = new Map();
  const perIp = new Map(); // ip -> live rooms opened from it
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

  const dropRoom = (id) => {
    const room = rooms.get(id);
    if (!room) return;
    rooms.delete(id);
    const n = (perIp.get(room.ip) || 1) - 1;
    if (n > 0) perIp.set(room.ip, n); else perIp.delete(room.ip);
  };

  const server = http.createServer((req, res) => {
    const url = parseTarget(req);
    if (!url) { res.writeHead(400, { 'content-type': 'text/plain', connection: 'close' }).end('bad request'); return; }
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
    const url = parseTarget(req);
    if (!url || url.pathname !== '/ws') { socket.destroy(); return; }
    wss.handleUpgrade(req, socket, head, (ws) => join(ws, url, clientIp(req)));
  });

  function join(ws, url, ip) {
    const id = (url.searchParams.get('room') || '').toUpperCase();
    const role = url.searchParams.get('role') || '';
    // Rejected after the upgrade rather than with an HTTP error, so a browser can read the reason.
    const reject = (code, reason) => ws.close(code, reason);
    const token = url.searchParams.get('k') || '';
    if (!ROOM_RE.test(id) || !ROLES.has(role) || !TOKEN_RE.test(token)) return reject(CLOSE.BAD_ROOM, 'bad room, role or key');

    let room = rooms.get(id);
    if (!room) {
      if (rooms.size >= MAX_ROOMS || (perIp.get(ip) || 0) >= maxRoomsPerIp) return reject(CLOSE.BUSY, 'server busy');
      room = { ip };
      rooms.set(id, room);
      perIp.set(ip, (perIp.get(ip) || 0) + 1);
    }
    // The first Mouna in a room owns it for as long as the room exists; the app keeps the same key for a favourite's
    // fixed room, so only it can come back. Anyone else claiming `mouna` is refused, whether or not the seat is free.
    if (role === 'mouna' && room.owner && !sameToken(room.owner, token)) return reject(CLOSE.NOT_YOURS, 'not your room');
    const old = room[role];
    // A seat that is taken stays taken, even if its holder looks dead: the ping loop clears dead sockets. Only the same
    // client (same key) coming back replaces its own old socket at once, which is a reconnect after a half-open drop.
    if (old) {
      if (!sameToken(old.token, token)) return reject(CLOSE.FULL, 'room full');
      old.terminate(); // its close handler sees it was replaced and leaves the room alone
      clients--;
    }
    if (role === 'mouna') room.owner = token;
    ws.token = token;
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
      if (!room.mouna && !room.guest) dropRoom(id);
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

module.exports = { createServer, newRoomId, ALPHABET, ROOM_RE, TOKEN_RE, CLOSE, PING_MS, MAX_ROOMS_PER_IP };

if (require.main === module) {
  const port = Number(process.env.PORT) || 8787;
  createServer({ port }).then((s) => console.log(`mouna call server on :${s.port}`));
}
