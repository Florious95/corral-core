/*
 * Browser smoke test via CDP: load the served page in headless Chrome, drive the
 * three pages (pair → workspaces → session) against a real daemon, confirm the
 * xterm terminal mounts and renders a snapshot. Verifies the browser path the
 * node e2e cannot: DOM wiring, xterm.js global load, ES module scripts, CSS.
 *
 * Requires: daemon on WS_URL, `npx serve` on APP_URL, headless Chrome (CHROME_BIN).
 */
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { join, dirname } from 'node:path';
import http from 'node:http';
import { WebSocket } from 'ws';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = process.env.CHROME_BIN || '/Users/alauda/Library/Caches/ms-playwright/chromium_headless_shell-1228/chrome-headless-shell-mac-arm64/chrome-headless-shell';
const APP_URL = process.env.APP_URL || 'http://127.0.0.1:8124/';
const WS_URL = process.env.WS_URL || 'ws://127.0.0.1:9910/ws';
const TOKEN = process.env.WS_TOKEN || 'e2e-test-token';
const PORT = 9223;

const results = [];
const check = (n, c, e = '') => { results.push([n, !!c]); console.log(`${c ? 'PASS' : 'FAIL'}  ${n}${e ? '  ' + e : ''}`); };

// Global bail-out so a hung CDP connection can never wedge the run.
const bail = setTimeout(() => {
  console.error('TIMEOUT: smoke run exceeded 45s — killing chrome');
  try { chrome.kill('SIGKILL'); } catch {}
  process.exit(2);
}, 45_000);

const USER_DATA = `/tmp/chrome-smoke-${process.pid}`;
const chrome = spawn(CHROME, [
  '--headless=new',
  '--disable-gpu',
  '--no-sandbox',
  '--remote-allow-origins=*',
  `--user-data-dir=${USER_DATA}`,
  `--remote-debugging-port=${PORT}`,
  APP_URL,
], { stdio: ['ignore', 'pipe', 'pipe'] });
chrome.stderr.on('data', () => {});
chrome.on('exit', (code) => { console.log(`chrome exit ${code}`); });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitForCdp() {
  for (let i = 0; i < 50; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json`);
      const list = await res.json();
      const page = list.find((t) => t.type === 'page');
      if (page && page.webSocketDebuggerUrl) return page.webSocketDebuggerUrl;
    } catch { /* not up yet */ }
    await sleep(200);
  }
  throw new Error('CDP not reachable');
}

async function main() {
  console.log('[smoke] waiting for CDP…');
  let wsUrl;
  try {
    wsUrl = await waitForCdp();
  } catch (e) {
    check('chrome CDP up', false, e.message);
    process.exit(1);
  }
  console.log(`[smoke] CDP at ${wsUrl}`);
  // Chrome's CDP endpoint rejects the permessage-deflate WebSocket extension;
  // the ws client enables it by default, so disable it explicitly.
  const ws = new WebSocket(wsUrl, { perMessageDeflate: false, maxPayload: 64 * 1024 * 1024 });
  await new Promise((r, j) => { ws.on('open', r); ws.on('error', j); });
  console.log('[smoke] CDP ws open');

  let seq = 0;
  const pending = new Map();
  const consoleLines = [];
  // Message handler MUST be registered before any send() so command responses
  // and console events are captured from the first request onward.
  ws.on('message', (data) => {
    const msg = JSON.parse(data.toString());
    if (msg.method === 'Runtime.consoleAPICalled') {
      consoleLines.push('console: ' + (msg.params.args || []).map((a) => a.value || a.description || '').join(' '));
    }
    if (msg.method === 'Runtime.exceptionThrown') {
      consoleLines.push('exception: ' + JSON.stringify(msg.params.exceptionDetails).slice(0, 500));
    }
    if (msg.id && pending.has(msg.id)) {
      pending.get(msg.id)(msg);
      pending.delete(msg.id);
    }
  });
  const send = (method, params = {}) => new Promise((res) => {
    const id = ++seq;
    pending.set(id, res);
    ws.send(JSON.stringify({ id, method, params }));
  });
  const evalJS = async (expression) => {
    const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.result && r.result.result && r.result.result.value !== undefined) return r.result.result.value;
    if (r.result && r.result.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails));
    return undefined;
  };

  console.log('[smoke] enabling domains…');
  await send('Runtime.enable');
  await send('Page.enable');
  await send('Log.enable');
  await sleep(1000); // let the page load and modules run

  const flow = `
(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const out = { pairForm: false, enteredWs: false, wsPage: false, session: false, terminal: false, snapshot: false, inputSent: false, clickErr: null, sessionErr: null, rowsFound: 0, pageDisplayAtClick: '' };

  const urlInput = document.querySelector('#ws-url');
  const tokenInput = document.querySelector('#pair-token');
  out.pairForm = !!(urlInput && tokenInput);
  if (!out.pairForm) return out;

  urlInput.value = '${WS_URL}';
  tokenInput.value = '${TOKEN}';
  out.enteredWs = (urlInput.value.includes('ws://') && tokenInput.value.length > 0 && tokenInput.type === 'password');
  document.querySelector('#pair-form').requestSubmit();

  // wait for workspaces page
  for (let i = 0; i < 80; i++) {
    if (document.querySelector('#workspaces-page').style.display === 'flex') break;
    await sleep(100);
  }
  out.wsPage = document.querySelector('#workspaces-page').style.display === 'flex';

  // click first session row
  for (let i = 0; i < 60; i++) {
    const row = document.querySelector('.ws-session');
    if (row) { out.rowsFound++; row.click(); break; }
    await sleep(100);
  }
  out.pageDisplayAtClick = document.querySelector('#session-page').style.display;
  await sleep(400);
  try {
    out.session = document.querySelector('#session-page').style.display === 'flex';
    out.terminal = !!document.querySelector('.session-panel [data-role="terminal"] .xterm');
    if (out.terminal && !window.__app.sessionPage) out.sessionErr = 'sessionPage is null but xterm present';
    out.sessionErr = out.sessionErr || (window.__app.sessionPage ? null : 'sessionPage is null');
    out.clickErr = null;
  } catch (e) {
    out.clickErr = String(e && e.message);
    out.sessionErr = out.sessionErr || String(e && e.message);
  }

  // wait for snapshot text in the xterm grid
  for (let i = 0; i < 80; i++) {
    const rows = document.querySelector('.session-panel [data-role="terminal"] .xterm-rows');
    if (rows && rows.textContent.trim().length > 0) { out.snapshot = true; break; }
    await sleep(100);
  }

  // send an input through the real UI input bar
  const inp = document.querySelector('.session-panel [data-role="input"]');
  if (inp) {
    inp.value = 'echo browser-e2e';
    inp.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    out.inputSent = true;
  }
  await sleep(300);
  return out;
})()
`;

  const res = await evalJS(flow);
  console.log(`[smoke] flow diag: ${JSON.stringify(res)}`);
  check('pair form rendered', res && res.pairForm);
  check('token input is type=password (not plaintext)', res && res.enteredWs);
  check('workspaces page shown after connect', res && res.wsPage);
  check('session page shown after clicking session', res && res.session);
  check('xterm terminal mounted', res && res.terminal);
  check('snapshot rendered into xterm grid', res && res.snapshot);
  check('input sent through UI bar', res && res.inputSent);

  // also dump the workspace list state
  const listInfo = await evalJS(`(() => ({
    wsCount: document.querySelectorAll('.ws-group').length,
    sessions: document.querySelectorAll('.ws-session').length,
    badges: document.querySelectorAll('.badge').length,
  }))()`);
  console.log(`info: ${JSON.stringify(listInfo)}`);
  for (const l of consoleLines.slice(0, 15)) console.log(l);

  clearTimeout(bail);
  ws.close();
  try { chrome.kill('SIGKILL'); } catch {}
  const failed = results.filter(([, c]) => !c).length;
  console.log(`\n=== ${results.length - failed}/${results.length} browser checks passed ===`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  clearTimeout(bail);
  console.error('browser smoke error', e);
  try { chrome.kill('SIGKILL'); } catch {}
  process.exit(1);
});
