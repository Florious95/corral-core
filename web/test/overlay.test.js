import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import {
  OverlayEmulator,
  dropScratchLines,
  renderOverlayFrame,
  sessionSocketFromRef,
} from '../js/overlay.js';
import { encodeControl, decodeControl } from '../js/protocol.js';
import { Client } from '../js/client.js';

test('sessionSocketFromRef uses structural prefix only', () => {
  assert.equal(sessionSocketFromRef('/tmp/tmux-1000/default\u001f%3'), '/tmp/tmux-1000/default');
  assert.equal(sessionSocketFromRef('bare'), 'bare');
  assert.equal(sessionSocketFromRef(''), '');
});

test('overlay_subscribe encodes socket and overlay_frame decodes', () => {
  const sent = JSON.parse(encodeControl('overlay_subscribe', { socket: '/tmp/ov-a/sock' }));
  assert.deepEqual(sent, {
    v: 1,
    type: 'overlay_subscribe',
    payload: { socket: '/tmp/ov-a/sock' },
  });
  const frame = decodeControl(JSON.stringify({
    v: 1,
    type: 'overlay_frame',
    payload: { seq: 1, text: 'hello', rows: 24, cols: 80 },
  }));
  assert.equal(frame.type, 'overlay_frame');
  assert.equal(frame.payload.seq, 1);
  assert.equal(frame.payload.text, 'hello');
});

test('rendered text has no bare control sequences', () => {
  const raw = '\x1b[?1049h\x1b[22;0;0t\x1b(B\x1b[m\x1b[H\x1b[2J' +
    '\x1b[30m\x1b[43m├─ 0:claude\x1b[K';
  const shown = renderOverlayFrame(raw, 80, 24);
  assert.match(shown, /claude/);
  for (const bad of ['[?1049', '[?1049h', '[K', '(B[m', '[30m', '[43m', '\x1b[', '[H', '[2J']) {
    assert.equal(shown.includes(bad), false, `must not contain ${bad}: ${JSON.stringify(shown)}`);
  }
});

test('repeated full redraws stay bounded and do not stack trees', () => {
  const emu = new OverlayEmulator(80, 24);
  const frame = '\x1b[?1049h\x1b[H\x1b[2J' +
    '(0) - 1 windows\n' +
    '├─ 0:claude\n' +
    '│  ✳ idle\n';
  let shown = '';
  for (let i = 0; i < 12; i += 1) {
    emu.resize(80, 24);
    shown = renderOverlayFrame(frame, 80, 24, emu);
  }
  const lines = shown.split('\n');
  assert.ok(lines.length <= 24, `lines=${lines.length} text=${shown}`);
  assert.equal([...shown.matchAll(/├─ 0:claude/g)].length, 1, shown);
});

test('CJK mixed tree stays readable and wide cells do not overlap', () => {
  const shown = renderOverlayFrame('├─ 0:中文目录-claude\n│  工作中 docs/说明.md', 80, 8);
  assert.match(shown, /中文目录-claude/);
  assert.match(shown, /说明\.md/);
  assert.equal(shown.includes('\uFFFD'), false);
});

test('tree stays fully expanded (no collapsed markers introduced)', () => {
  const shown = renderOverlayFrame('(0) +\n├─ 0:a\n│  ├─ 1:b\n│  └─ 2:c\n', 80, 12);
  assert.match(shown, /├─ 0:a/);
  assert.match(shown, /├─ 1:b/);
  assert.match(shown, /└─ 2:c/);
});

test('scratch observer lines are dropped', () => {
  const shown = dropScratchLines('sess-user: 1 windows\nam-overlay: 1 windows (attached)\n├─ tree*\n├─ sleep*\n│  ov-spin');
  assert.match(shown, /sess-user/);
  assert.equal(shown.includes('am-overlay'), false);
  assert.equal(shown.includes('ov-spin'), false);
  assert.equal(shown.includes('tree*'), false);
  assert.equal(shown.includes('sleep*'), false);
});

test('client subscribeOverlay sends current socket and replays', () => {
  const sockets = [];
  class FakeWS {
    constructor() { this.readyState = 1; this.sent = []; this.onopen = null; this.onmessage = null; this.onclose = null; }
    send(d) { this.sent.push(d); }
    close() { this.readyState = 3; }
  }
  const client = new Client({
    url: 'ws://127.0.0.1:9/ws',
    token: 'tok',
    wsFactory: () => { const ws = new FakeWS(); sockets.push(ws); return ws; },
  });
  client.connect();
  const ws = sockets[0];
  ws.readyState = 1;
  ws.onopen({});
  ws.onmessage({ data: JSON.stringify({ v: 1, type: 'auth_ack', payload: { ok: true } }) });
  const sock = '/tmp/ov-web/sock';
  const ref = `${sock}\u001f%2`;
  assert.equal(sessionSocketFromRef(ref), sock);
  assert.equal(client.subscribeOverlay(sessionSocketFromRef(ref)), true);
  const types = ws.sent.map((s) => JSON.parse(s).type);
  assert.ok(types.includes('overlay_subscribe'));
  const sub = ws.sent.map((s) => JSON.parse(s)).filter((f) => f.type === 'overlay_subscribe').pop();
  assert.equal(sub.payload.socket, sock);
});

test('session chrome includes overlay_subscribe path and 查看 button', () => {
  const app = readFileSync(new URL('../js/app.js', import.meta.url), 'utf8');
  const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
  assert.match(app, /overlay_subscribe|subscribeOverlay/);
  assert.match(app, /overlay_frame/);
  assert.match(html, />查看</);
});
