/**
 * Audio Intercom - signaling relay (internet mode)
 * -------------------------------------------------
 * Free/open-source (uses only the `ws` package). This server never sees or
 * touches audio media - it only relays small JSON signaling messages
 * (SDP offer/answer, ICE candidates) between exactly two sockets that
 * share the same pairing "room" code, then gets out of the way.
 *
 * Protocol (all messages are JSON):
 *   Client -> Server:
 *     { "type": "create_room" }
 *         -> Server replies { "type": "room_created", "code": "123456" }
 *     { "type": "join_room", "code": "123456" }
 *         -> Server replies { "type": "joined", "code": "123456" }
 *            or { "type": "error", "message": "..." }
 *     { "type": "offer" | "answer" | "candidate" | "bye", "code": "123456", "payload": {...} }
 *         -> relayed verbatim to the other peer in the room
 *
 *   Server -> Client (relay):
 *     { "type": "offer" | "answer" | "candidate" | "bye", "payload": {...} }
 *     { "type": "peer_left" }
 *
 * Run:
 *   npm install
 *   node server.js            # ws://0.0.0.0:8080
 *
 * For production, put this behind a TLS-terminating reverse proxy
 * (nginx / Caddy with a free Let's Encrypt cert) so clients connect to
 * wss://yourdomain/ws. Never expose plain ws:// on the public internet.
 */

const http = require('http');
const crypto = require('crypto');
const WebSocket = require('ws');

const PORT = process.env.PORT || 8080;
const CODE_TTL_MS = 5 * 60 * 1000; // pairing codes expire after 5 minutes

/** roomCode -> { sockets: Set<ws>, createdAt: number } */
const rooms = new Map();

function generateCode() {
  let code;
  do {
    code = String(crypto.randomInt(0, 1_000_000)).padStart(6, '0');
  } while (rooms.has(code));
  return code;
}

function cleanupExpiredRooms() {
  const now = Date.now();
  for (const [code, room] of rooms) {
    if (now - room.createdAt > CODE_TTL_MS && room.sockets.size === 0) {
      rooms.delete(code);
    }
  }
}
setInterval(cleanupExpiredRooms, 30_000);

const server = http.createServer((_req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end('Audio Intercom signaling relay is running.\n');
});

const wss = new WebSocket.Server({ server, path: '/ws' });

wss.on('connection', (ws) => {
  ws.roomCode = null;

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return ws.send(JSON.stringify({ type: 'error', message: 'invalid_json' }));
    }

    switch (msg.type) {
      case 'create_room': {
        const code = generateCode();
        rooms.set(code, { sockets: new Set([ws]), createdAt: Date.now() });
        ws.roomCode = code;
        ws.send(JSON.stringify({ type: 'room_created', code }));
        break;
      }

      case 'join_room': {
        const room = rooms.get(msg.code);
        if (!room) {
          return ws.send(JSON.stringify({ type: 'error', message: 'room_not_found_or_expired' }));
        }
        if (room.sockets.size >= 2) {
          return ws.send(JSON.stringify({ type: 'error', message: 'room_full' }));
        }
        room.sockets.add(ws);
        ws.roomCode = msg.code;
        ws.send(JSON.stringify({ type: 'joined', code: msg.code }));
        break;
      }

      case 'offer':
      case 'answer':
      case 'candidate':
      case 'bye': {
        const room = rooms.get(ws.roomCode);
        if (!room) return;
        for (const peer of room.sockets) {
          if (peer !== ws && peer.readyState === WebSocket.OPEN) {
            peer.send(JSON.stringify({ type: msg.type, payload: msg.payload }));
          }
        }
        break;
      }

      default:
        ws.send(JSON.stringify({ type: 'error', message: 'unknown_type' }));
    }
  });

  ws.on('close', () => {
    const room = rooms.get(ws.roomCode);
    if (!room) return;
    room.sockets.delete(ws);
    for (const peer of room.sockets) {
      if (peer.readyState === WebSocket.OPEN) {
        peer.send(JSON.stringify({ type: 'peer_left' }));
      }
    }
    if (room.sockets.size === 0) {
      // keep the (now-empty) room around until TTL cleanup, in case of
      // a quick reconnect, but a fresh create_room will never reuse the code.
    }
  });
});

server.listen(PORT, () => {
  console.log(`Signaling relay listening on ws://0.0.0.0:${PORT}/ws`);
});
