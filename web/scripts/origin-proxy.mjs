/* TEST HARNESS ONLY — not part of the served app.
 * WS bridge: accepts a browser WebSocket, strips the browser Origin header
 * (the daemon's coder/websocket.Accept rejects cross-origin by default, and
 * browsers always send Origin), forwards messages to the daemon and relays
 * replies back. Lets the browser smoke test reach a real daemon without a
 * server-side origin-policy change (which is out of web/ write scope). */
import { createServer } from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';

const UPSTREAM = process.env.UPSTREAM || 'ws://127.0.0.1:9910/ws';
const PORT = Number(process.env.PORT || 9920);

const server = createServer((req, res) => {
  res.writeHead(426, { 'Content-Type': 'text/plain' });
  res.end('use WebSocket');
});
const wss = new WebSocketServer({ server });

wss.on('connection', (client) => {
  console.log('[proxy] browser connected');
  const up = new WebSocket(UPSTREAM);
  up.on('open', () => console.log('[proxy] upstream open'));
  up.on('message', (data, isBinary) => {
    if (client.readyState === WebSocket.OPEN) client.send(data, { binary: isBinary });
  });
  up.on('error', (e) => console.log('[proxy] upstream error', e.message));
  up.on('close', (c, reason) => console.log('[proxy] upstream close', c, reason.toString()));
  client.on('message', (data, isBinary) => {
    console.log('[proxy] client msg', data.toString().slice(0, 40));
    if (up.readyState === WebSocket.OPEN) up.send(data, { binary: isBinary });
  });
  client.on('close', () => { try { up.close(); } catch {} });
  client.on('error', () => {});
});

server.listen(PORT, () => console.log(`[origin-proxy] ${PORT} -> ${UPSTREAM}`));
