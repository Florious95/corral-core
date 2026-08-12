/*
 * E2E harness: drive the real daemon through the web client's own protocol
 * stack (js/protocol.js + js/binary.js + js/client.js) over a Node WebSocket.
 * This verifies the deliverable connects, authenticates, lists real tmux
 * sessions, mirrors a live terminal, and answers input/scrollback — the same
 * code a browser loads.
 */
import { WebSocket } from 'ws';

const URL = process.env.WS_URL || 'ws://127.0.0.1:9910/ws';
const TOKEN = process.env.WS_TOKEN || 'e2e-test-token';

// Node has no browser WebSocket; shim one from the 'ws' package.
class NodeWebSocket {
  constructor(url) { this.ws = new WebSocket(url); this._binaryType = 'blob'; }
  // 'arraybuffer' is the browser API; the ws lib delivers Buffers directly, so
  // we accept the assignment but do not round-trip through the property.
  set binaryType(v) { this._binaryType = v; }
  get binaryType() { return this._binaryType; }
  get readyState() { return this.ws.readyState; }
  send(data) { this.ws.send(data); }
  close(code, reason) { this.ws.close(code, reason); }
  set onopen(f) { this.ws.on('open', f); }
  set onmessage(f) { this.ws.on('message', (data, isBinary) => f({ data: isBinary ? data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) : data.toString() })); }
  set onclose(f) { this.ws.on('close', (code, reason) => f({ code, reason: reason.toString() })); }
  set onerror(f) { this.ws.on('error', f); }
}

import { Client } from '../js/client.js';
import { BINARY_KIND } from '../js/binary.js';

const results = [];
function check(name, cond, extra = '') {
  results.push([name, !!cond, extra]);
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${extra ? '  ' + extra : ''}`);
}

let connected = false;
let gotListing = false;
let gotSnapshot = false;
let gotDelta = false;
let gotInputAck = false;
let gotScrollback = false;
let mirrorRef = null;
let failed = false;

const client = new Client({
  url: URL,
  token: TOKEN,
  wsFactory: (u) => new NodeWebSocket(u),
  inputTimeoutMs: 5000,
  onStateChange: (s) => { if (s === 'ready') connected = true; },
  onFrame: (type) => {
    if (type === 'listing') gotListing = true;
    if (type === 'input_ack') gotInputAck = true;
  },
  onBinary: (f) => {
    if (f.kind === BINARY_KIND.SNAPSHOT) { gotSnapshot = true; mirrorRef = f.ref; }
    if (f.kind === BINARY_KIND.DELTA) gotDelta = true;
    if (f.kind === BINARY_KIND.SCROLLBACK) gotScrollback = true;
  },
  onLocalError: (c, m) => { console.log(`LOCAL ERROR ${c}: ${m}`); failed = true; },
  onInputResult: () => {},
});

client.connect();

// Find a session to subscribe once the listing arrives. We watch the internal
// model: client.onFrame('listing') above sets gotListing; poll the model for a
// candidate session, then subscribe.
function maybeSubscribe() {
  const ws = client.workspaces;
  for (const w of ws) {
    for (const s of w.sessions) {
      if (!mirrorRef) {
        mirrorRef = s.ref;
        client.subscribe(s.ref, 24, 80);
        // send an input to verify the whole-line inject + ack path
        client.input(s.ref, 'echo from-e2e');
        // fetch one history page
        client.scrollback(s.ref, -200, 100);
        return;
      }
    }
  }
}

const timer = setInterval(() => {
  if (!mirrorRef) maybeSubscribe();
  const done = connected && gotListing && gotSnapshot && gotDelta && gotInputAck && gotScrollback;
  if (done || failed) {
    clearInterval(timer);
    finish();
  }
}, 200);

// hard deadline
setTimeout(() => { if (!mirrorRef) { console.log('TIMEOUT waiting for sessions'); } finish(); }, 12000);

function finish() {
  try {
    const ws = client.workspaces;
    check('connected (state=ready)', connected);
    check('listing received', gotListing);
    check('listing has >=1 workspace', ws.length >= 1);
    check('listing has >=1 session', ws.some((w) => w.sessions.length >= 1));
    check('subscribed to a session', !!mirrorRef);
    check('snapshot binary frame received', gotSnapshot);
    check('delta binary frame received', gotDelta);
    check('input_ack received (input path worked)', gotInputAck);
    check('scrollback binary frame received', gotScrollback);
    const anySessions = ws.some((w) => w.sessions.length >= 1);
    const anyStates = anySessions ? ws.flatMap((w) => w.sessions.map((s) => s.state)).every((st) => ['working', 'idle', 'blocked', 'done', 'unknown'].includes(st)) : false;
    check('session states are closed-set', anyStates);

    const failedCount = results.filter(([n, ok]) => !ok).length;
    console.log(`\n=== ${results.length - failedCount}/${results.length} e2e checks passed ===`);
    client.disconnect();
    process.exit(failedCount === 0 ? 0 : 1);
  } catch (e) {
    console.log('finish error', e);
    process.exit(1);
  }
}
