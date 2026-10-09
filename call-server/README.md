# Mouna call relay

The server behind **Web link** calls in the Mouna app. A person who cannot speak starts a call in the app; the other
person opens a link or scans a QR code in any phone browser (nothing to install) and answers. Mouna's spoken voice
goes to their browser; their voice goes to Mouna's speaker.

Both sides connect **outbound over WSS** to this server, which passes messages between the two. It is a relay, not
WebRTC: no NAT or TURN problems on venue Wi-Fi or 4G. It stores nothing and logs only how many rooms and clients
there are.

## Run it

```sh
cd call-server
npm i && npm start          # http://localhost:8787  (PORT=... to change)
npm test                    # node --test: relay, peer events, reconnect, room ownership, limits
```

Phone browsers only allow the microphone on **HTTPS**. To host it from your laptop, put a quick tunnel in front of it
(`cloudflared` is a Homebrew install; no account needed):

```sh
cloudflared tunnel --url http://localhost:8787
# prints  https://<random-words>.trycloudflare.com   <- this is the call server URL
```

Give that URL to the app: `call.server=https://...` in `android/local.properties` (build time), Settings > Phone
calls > Call server, or for QA
`adb shell am broadcast -a app.mouna.CALLSERVER --es url https://<random-words>.trycloudflare.com`.
A quick tunnel gets a new address every run. For a fixed address, deploy with `render.yaml` (Render builds from
`call-server/`, gives HTTPS) or a named Cloudflare tunnel.

## Endpoints

| | |
|---|---|
| `GET /` | a small landing page |
| `GET /c/<room>` | the guest's page (HTML, no build step, `public/call.html`) |
| `GET /healthz` | `{"ok":true,"rooms":n}` |
| `WS /ws?room=<room>&role=mouna\|guest&k=<key>` | the relay |

A **room** is 6 characters from `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (no `I L O 0 1`), case-insensitive. Anyone with the
room id can join it, so treat the link like a key. A room holds one `mouna` and one `guest`. Empty rooms are removed.
The server pings every 20 s to keep tunnels and proxies from dropping idle sockets, and clears sockets that stop
answering.

**The key (`k`).** Each client makes a random secret (16-64 characters of `A-Za-z0-9_-`) once per call and sends it on
every connection of that call; the guest page keeps its own in `sessionStorage`, so a reload keeps it. The key is what
tells a reconnect from a stranger:

- A seat that is taken stays taken. A second client for it, with a different key, is closed with **4409** `room full`
  (even if the holder looks dead; the ping loop clears dead sockets, a new join never evicts anyone).
- The **same key** for a taken seat is the same client coming back (a phone that changed network leaves a half-open
  socket for up to 40 s): the old socket is closed and the new one takes the seat at once.
- **Mouna's seat has an owner.** The first `mouna` in a room sets the owner key, which the server remembers in memory
  for as long as the room exists, even while the seat is empty. Any other `mouna` key is closed with **4403** `not your
  room`, so nobody can pose as Mouna in a room while it exists. The app keeps one key per favourite room so it can
  always reclaim it. Once the room is empty and removed, the next `mouna` to arrive owns it again (the owner is not
  stored anywhere), so a room id is only as private as its link.
- A missing or malformed key, room or role gets **4400**.

Limits: at most 2000 rooms, and 20 live rooms opened from one address (**4429** `server busy` beyond that; the address is
`cf-connecting-ip`, else the first hop of `x-forwarded-for`, else the socket's, so run it behind a proxy that sets
them, as Cloudflare and Render do).

## What the two sides say

The server does not read any of this; it passes text frames as text and binary frames as binary.

Text frames are JSON with a `t` field:

| `t` | from | meaning |
|---|---|---|
| `peer` `{joined: bool}` | server | the other side arrived or left (also sent to a newcomer when the other is already there) |
| `hello` `{name}` | Mouna | who is calling; sent whenever the guest arrives |
| `answered` | guest | the guest tapped Answer (sent again after either side reconnects). Mouna starts speaking only after this |
| `say` `{text}` | Mouna | the sentence about to be spoken, for the caption bubble; the audio follows |
| `bye` | either | hang up |

Binary frames start with one **kind** byte, then the payload:

| kind | direction | payload |
|---|---|---|
| `0x01` | Mouna to guest | a WAV file (Sarvam voice, or the phone's TTS) |
| `0x02` | Mouna to guest | an MP4/AAC file (the pre-rendered phrase pack) |
| `0x10` | guest to Mouna | PCM, 16-bit signed little-endian, 16 kHz mono; the page sends ~40 ms (640 samples) per frame |

The guest page plays the audio files in order with WebAudio (`decodeAudioData` reads WAV and AAC in Chrome and
Safari). The Android side is `engine/CallWire.kt` and `engine/WebLink.kt`; its tests pin this format.

## Limits worth knowing

- Anyone who learns a room id can take the free `guest` seat in it, and the `mouna` seat of a room nobody has opened
  yet. Favourite rooms in the app are fixed, so share them with people you trust only; a family member's bookmark is
  the point.
- One relay process, rooms in memory: a restart ends calls in progress (both sides reconnect on their own).
- Messages are capped at 4 MiB, rooms at 2000 (20 per address).
