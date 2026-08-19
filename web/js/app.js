/*
 * AgentMirror web client — app shell: three pages + routing + wiring.
 *
 * Pages:
 *   1. Pair (配对) — ws address + token (type=password). Token is held in
 *      memory only, never persisted, never rendered back, never logged.
 *   2. Workspaces (工作区) — two-level group (cwd → sessions) with state badges.
 *   3. Session (会话) — xterm.js live mirror + input bar + special-key bar +
 *      "load older history" (scrollback).
 *
 * The binary stream (snapshot/delta/scrollback) is decoded in binary.js and
 * routed to the open session's TerminalView. The workspace model is built by
 * Client from listing/list_delta (server-computed aggregates). Reconnect keeps
 * the current page alive; the session page re-subscribes automatically.
 */

import { Client } from './client.js';
import { BINARY_KIND } from './binary.js';
import { TerminalView } from './terminal.js';
import { fetchOlder, acceptScrollback } from './scrollback.js';
import { loadConfig, saveConfig, clearConfig, loadTheme, saveTheme, resolvedTheme } from './preferences.js';
import { OverlayEmulator, renderOverlayFrame, sessionSocketFromRef } from './overlay.js';

const $ = (sel) => document.querySelector(sel);

const STATE_BADGE = Object.freeze({
  working: { label: 'working', cls: 'badge-working' },
  idle: { label: 'idle', cls: 'badge-idle' },
  blocked: { label: 'blocked', cls: 'badge-blocked' },
  done: { label: 'done', cls: 'badge-done' },
  unknown: { label: 'unknown', cls: 'badge-unknown' },
});

function badgeFor(state) {
  return STATE_BADGE[state] || STATE_BADGE.unknown;
}

/**
 * Owns one live session panel: terminal, input, named keys, and read-only history.
 * @contract
 * @pre app has a Client and session/ref identify one listed session
 * @post mount subscribes and wires UI; unmount unsubscribes and releases DOM/terminal resources
 * @err connection/input failures remain visible in the panel status
 * @inv binary frames route only to this page's ref and history stays separate from the live grid
 */
class SessionPage {
  constructor(app, ref, session) {
    this.app = app;
    this.ref = ref;
    this.session = session;
    this.client = app.client;
    this.term = null;
    this.pendingScrollback = null;
  }

  mount() {
    this.view = document.createElement('section');
    this.view.className = 'session-panel';
    this.view.dataset.ref = this.ref;
    this.view.innerHTML = `<div class="session-meta">
      <span data-role="state" class="badge"></span><span data-role="ref" class="session-ref"></span>
      <span data-role="cwd" class="session-title-cwd"></span><span class="conn-spacer"></span>
      <button data-role="history" class="btn" title="加载更早的历史">历史</button>
      <button data-role="clear" class="btn" title="清屏（仅本地视图）">清屏</button>
    </div><div data-role="scrollback" class="scrollback-panel" style="display:none">
      <div class="scrollback-head"><span data-role="range"></span><button data-role="history-close" class="btn">关闭</button></div>
      <div data-role="history-body" class="scrollback-body"></div>
    </div><div data-role="terminal" class="terminal-host"></div>
    <div data-role="keys" class="key-bar">
      <button data-key="esc">Esc</button><button data-key="ctrl_c">Ctrl-C</button><button data-key="tab">Tab</button>
      <button data-key="up">↑</button><button data-key="down">↓</button><button data-key="left">←</button><button data-key="right">→</button>
    </div><div class="input-bar"><input data-role="input" type="text" placeholder="输入命令，回车发送" autocomplete="off" spellcheck="false">
      <button data-role="send" class="btn btn-primary">发送</button></div><div data-role="status" class="status">—</div>`;
    $('#session-panels').appendChild(this.view);
    this._renderMeta();
    const host = this.$('[data-role="terminal"]');
    this.term = new TerminalView(host, {
      onResize: (rows, cols) => this.client.resize(this.ref, rows, cols),
      onHistoryBoundary: () => this.loadHistory(),
    });
    this.term.open();

    const input = this.$('[data-role="input"]');
    input.value = '';
    input.disabled = true;
    this.$('[data-role="send"]').disabled = true;
    this._setStatus('subscribing…');

    this._bindButtons();
    this.resizeObserver = new ResizeObserver(() => this._onWindowResize());
    this.resizeObserver.observe(host);
    this._start();
  }

  _start() {
    this.client.subscribe(this.ref, this.term.rows, this.term.cols);
    this._updateStatus();
    const input = this.$('[data-role="input"]');
    input.disabled = false;
    this.$('[data-role="send"]').disabled = false;
    input.focus();
  }

  _bindButtons() {
    this.$('[data-role="clear"]').onclick = () => { if (this.term) this.term.clear(); };
    this.$('[data-role="history"]').onclick = () => this.loadHistory();

    for (const btn of this.$('[data-role="keys"]').querySelectorAll('button[data-key]')) {
      btn.onclick = () => this.client.keys(this.ref, btn.getAttribute('data-key'));
    }

    const input = this.$('[data-role="input"]');
    input.onkeydown = (ev) => {
      if (ev.key === 'Enter') this._submitInput();
    };
    this.$('[data-role="send"]').onclick = () => this._submitInput();

    window.addEventListener('keydown', this._onGlobalKey);
    document.addEventListener('visibilitychange', this._onVisibility);
  }

  _onWindowResize = () => {
    if (this.term) this.term.fit();
  };

  loadHistory() {
    if (this.pendingScrollback) return;
    fetchOlder(() => this, {
      onLoading: (n) => this._setStatus(`loading ${n} lines of history…`),
      onError: (m) => this._setStatus(m),
    });
  }

  /** Route Esc/Tab/arrow keys to the session when the user is not typing. */
  _onGlobalKey = (ev) => {
    if (!this.term) return;
    if (document.activeElement === this.$('[data-role="input"]')) return;
    if (ev.ctrlKey || ev.altKey || ev.metaKey) return;
    const map = {
      Escape: 'esc', Tab: 'tab',
      ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right',
    };
    const key = map[ev.key];
    if (key) {
      ev.preventDefault();
      this.client.keys(this.ref, key);
    }
  };

  _onVisibility = () => {
    // @contract Returning from background follows the newest output (D-38).
    if (!document.hidden && this.term && this === this.app.sessionPage) this.term.scrollToBottom();
  };

  _submitInput() {
    const input = this.$('[data-role="input"]');
    const text = input.value;
    // Empty text = bare Enter (allowed per §4.2); non-empty injects text+Enter.
    const reqId = this.client.input(this.ref, text);
    if (reqId === null) this._setStatus('input not sent (connection not ready)');
    else this.pendingInput = reqId;
    input.value = '';
  }

  _updateStatus() {
    if (this.client.isReady) {
      this._setStatus(this.client.activeRefs.includes(this.ref) ? 'mirroring' : 'subscribing…');
    } else {
      this._setStatus(this.client.state);
    }
  }

  _setStatus(text) {
    const el = this.$('[data-role="status"]');
    if (el) el.textContent = text;
  }

  /** The client delivered a binary frame for this page. */
  acceptBinary(frame) {
    if (!this.term) return;
    switch (frame.kind) {
      case BINARY_KIND.SNAPSHOT:
        this.term.writeSnapshot(frame.data);
        break;
      case BINARY_KIND.DELTA:
        this.term.writeDelta(frame.data);
        break;
      case BINARY_KIND.SCROLLBACK:
        acceptScrollback(this, frame);
        break;
      default:
        break; // unknown kinds are rejected at decode time
    }
  }

  /** Show the returned history page in the read-only panel above the terminal. */
  showScrollbackPanel(fromLine, lineCount, data) {
    const panel = this.$('[data-role="scrollback"]');
    const text = new TextDecoder('utf-8').decode(data);
    this.$('[data-role="history-body"]').innerHTML = renderAnsi(text);
    this.$('[data-role="range"]').textContent =
      `history ${fromLine}..${fromLine + lineCount - 1} (${lineCount} lines)`;
    panel.style.display = 'flex';
    panel.scrollTop = 0;
    this._setStatus(`showing ${lineCount} lines of history`);
    this.$('[data-role="history-close"]').onclick = () => { panel.style.display = 'none'; };
  }

  setActive(active) {
    this.view.style.display = active ? 'flex' : 'none';
    if (active && this.term) requestAnimationFrame(() => { this.term.fit(); this.term.focus(); });
  }

  updateSession(session) { this.session = session; this._renderMeta(); }

  _renderMeta() {
    if (!this.view) return;
    const badge = badgeFor(this.session.state);
    const state = this.$('[data-role="state"]');
    state.textContent = badge.label;
    state.className = `badge ${badge.cls}`;
    this.$('[data-role="ref"]').textContent = this.ref;
    this.$('[data-role="cwd"]').textContent = this.session.cwd || '';
  }

  $(selector) { return this.view.querySelector(selector); }

  unmount() {
    if (this.resizeObserver) this.resizeObserver.disconnect();
    window.removeEventListener('keydown', this._onGlobalKey);
    document.removeEventListener('visibilitychange', this._onVisibility);
    if (this.term) {
      this.term.dispose();
      this.term = null;
    }
    this.client.unsubscribe(this.ref);
    if (this.view) this.view.remove();
  }
}

/**
 * Converts ANSI color text into escaped HTML for the read-only history panel.
 * @contract
 * @pre text is a string containing terminal text and optional ANSI escapes
 * @post returns one escaped pre element; markup characters from text cannot become HTML
 * @err none
 * @inv live mirroring never uses this renderer; unsupported escape sequences stay invisible
 */
function renderAnsi(text) {
  const COLORS = ['#282c34', '#e06c75', '#98c379', '#e5c07b', '#61afef', '#c678dd', '#56b6c2', '#dcdfe4'];
  const BRIGHT = ['#5c6370', '#ff7b6b', '#a8e37f', '#ffd866', '#7ec2ff', '#e391ff', '#7fe0d6', '#f8f8f2'];
  let out = '';
  let fg = null, bg = null;
  let i = 0;
  while (i < text.length) {
    if (text[i] === '\x1b' && text[i + 1] === '[') {
      let j = i + 2;
      while (j < text.length && !'m@HKABCDJ'.includes(text[j])) j++;
      if (j < text.length && text[j] === 'm') {
        const codes = text.slice(i + 2, j).split(';').map((c) => parseInt(c, 10));
        fg = null; bg = null;
        for (let k = 0; k < codes.length; k++) {
          const c = codes[k];
          if (c === 0) { fg = null; bg = null; }
          else if (c >= 30 && c <= 37) fg = COLORS[c - 30];
          else if (c >= 90 && c <= 97) fg = BRIGHT[c - 90];
          else if (c >= 40 && c <= 47) bg = COLORS[c - 40];
          else if (c >= 100 && c <= 107) bg = BRIGHT[c - 100];
        }
      }
      i = j + 1;
    } else if (text[i] === '\x1b') {
      i += 2; // non-color ESC sequences are invisible in the read-only view
    } else {
      const ch = text[i];
      if (ch === '&') out += '&amp;';
      else if (ch === '<') out += '&lt;';
      else if (ch === '>') out += '&gt;';
      else out += ch;
      i++;
    }
  }
  const style = `color:${fg || '#c8ccd4'};background:${bg || '#111418'}`;
  return `<pre style="${style}">${out}</pre>`;
}

/**
 * Coordinates pairing, page routing, workspace rendering, sessions, and theme persistence.
 * @contract
 * @pre required application DOM elements exist before init runs
 * @post init wires controls and restores saved configuration/theme; connect owns one Client
 * @err connection and persistence failures surface through status/toast UI
 * @inv user-controlled text is rendered via textContent or escaping and only one client is active
 */
class App {
  constructor() {
    this.client = null;
    this.sessions = new Map();
    this.sessionPage = null;
    this.currentPage = null;
    this.theme = 'system';
    this.overlayOpen = false;
    this.overlayEmu = new OverlayEmulator(80, 24);
    this.overlayLastAt = 0;
  }

  init() {
    const saved = loadConfig();
    if (saved) { $('#ws-url').value = saved.url; $('#pair-token').value = saved.token; }
    this.initTheme();

    $('#pair-form').addEventListener('submit', (ev) => {
      ev.preventDefault();
      this.pair();
    });
    $('#btn-disconnect').addEventListener('click', () => this.disconnect());
    $('#btn-back').addEventListener('click', () => this.show('workspaces'));
    $('#btn-reconnect').addEventListener('click', () => this.reconnect());
    $('#btn-refresh-list').addEventListener('click', () => {
      if (this.client) this.client.list();
    });
    $('#btn-overlay').addEventListener('click', () => this.openOverlay());
    $('#btn-overlay-close').addEventListener('click', () => this.closeOverlay());
    $('#overlay-scrim').addEventListener('click', () => this.closeOverlay());
    this.show('pair');
    if (saved) this.connect(saved.url, saved.token, { automatic: true });
  }

  pair() {
    const url = $('#ws-url').value.trim();
    const token = $('#pair-token').value;
    if (!url || !token) {
      this.toast('请填写 ws 地址和 token', 'error');
      return;
    }
    try { saveConfig(url, token); } catch { this.toast('无法保存配置，刷新后不会自动连接', 'error'); }
    this.connect(url, token);
  }

  connect(url, token, { automatic = false } = {}) {
    if (this.client) this.resetConnection();
    this.setPairStatus(automatic ? '正在恢复上次连接…' : '正在连接…');
    const onBinary = (frame) => {
      const page = this.sessions.get(frame.ref);
      if (page) page.acceptBinary(frame);
    };
    const app = this;
    this.client = new Client({
      url,
      token,
      onStateChange: (s) => {
        $('#conn-state').textContent = s;
        app.renderHeader(s);
        app.setPairStatus(app.connectionLabel(s), s === 'stopped' ? 'error' : '');
        if (s === 'ready') {
          app.setPairStatus('已连接');
          if (app.currentPage === 'pair') app.show('workspaces');
        }
        for (const page of app.sessions.values()) page._updateStatus();
      },
      onFrame: (type, payload) => {
        if (type === 'listing' || type === 'list_delta') { app.renderWorkspaces(); app.syncOpenSessions(); }
        if (type === 'auth_ack' && payload.ok === false) app.setPairStatus(`认证失败：${payload.reason || '服务器拒绝连接'}`, 'error');
        if (type === 'overlay_frame') app.applyOverlayFrame(payload);
      },
      onBinary,
      onInputResult: (reqId, ok, reason) => {
        const page = [...app.sessions.values()].find((p) => p.pendingInput === reqId) || app.sessionPage;
        if (page) { page.pendingInput = null; page._setStatus(ok ? 'sent' : `send failed: ${reason || 'unknown'}`); }
      },
      onLocalError: (code, msg) => app.toast(`decode error ${code}: ${msg}`, 'error'),
      onConnectionIssue: (reason) => app.setPairStatus(`连接失败：${reason}`, 'error'),
    });
    this.client.connect();
  }

  disconnect() {
    this.resetConnection();
    clearConfig();
    $('#pair-token').value = '';
    this.renderHeader('stopped');
    this.show('pair');
  }

  reconnect() {
    const saved = loadConfig();
    const url = saved && saved.url ? saved.url : $('#ws-url').value.trim();
    const token = (saved && saved.token) || $('#pair-token').value;
    if (!token) {
      this.toast('重新连接需要 token', 'error');
      return;
    }
    this.connect(url, token);
  }

  show(page) {
    if (page !== 'session') this.closeOverlay();
    this.currentPage = page;
    for (const p of ['pair-page', 'workspaces-page', 'session-page']) {
      $(`#${p}`).style.display = p === `${page}-page` ? 'flex' : 'none';
    }
    if (page === 'workspaces' && this.client) this.renderWorkspaces();
  }

  renderHeader(s) {
    const bar = $('#conn-bar');
    bar.style.display = 'flex';
    $('#conn-state').textContent = s;
    $('#conn-state-dot').className = `conn-state-dot ${s}`;
  }

  renderWorkspaces() {
    const ws = this.client.workspaces;
    const list = $('#ws-list');
    list.innerHTML = '';
    if (ws.length === 0) {
      list.innerHTML = '<div class="ws-empty">暂无工作区</div>';
      return;
    }
    for (const w of ws) {
      const group = document.createElement('div');
      group.className = 'ws-group';
      const header = document.createElement('div');
      header.className = 'ws-header';
      const badge = badgeFor(w.aggregate_state);
      header.innerHTML =
        `<span class="ws-cwd" title="${esc(w.cwd)}">${esc(w.cwd)}</span>` +
        `<span class="badge ${badge.cls}">${badge.label}</span>` +
        `<span class="ws-count">${w.session_count} session${w.session_count === 1 ? '' : 's'}</span>`;
      group.appendChild(header);

      const sessions = document.createElement('div');
      sessions.className = 'ws-sessions';
      for (const s of (w.sessions || [])) {
        const b = badgeFor(s.state);
        const row = document.createElement('div');
        row.className = 'ws-session';
        row.innerHTML =
          `<span class="session-name">${esc(s.name || '(unnamed)')}</span>` +
          `<span class="badge ${b.cls}">${b.label}</span>` +
          `<span class="session-dims">${s.rows}×${s.cols}</span>` +
          `<span class="session-ref">${esc(s.ref)}</span>`;
        row.onclick = () => this.openSession(s);
        sessions.appendChild(row);
      }
      group.appendChild(sessions);
      list.appendChild(group);
    }
  }

  openSession(session) {
    let page = this.sessions.get(session.ref);
    if (!page) {
      page = new SessionPage(this, session.ref, session);
      this.sessions.set(session.ref, page);
      page.mount();
      this.renderTabs();
    }
    this.sessionPage = page;
    for (const p of this.sessions.values()) p.setActive(p === page);
    this.show('session');
    this.renderTabs();
  }

  openOverlay() {
    const page = this.sessionPage;
    if (!page || !this.client) return;
    const socket = sessionSocketFromRef(page.ref);
    if (!socket) {
      this.toast('当前会话没有可订阅的 socket', 'error');
      return;
    }
    this.overlayOpen = true;
    this.overlayEmu.resize(80, 24);
    const root = $('#overlay-root');
    root.hidden = false;
    root.dataset.idle = '0';
    $('#overlay-text').textContent = '';
    $('#overlay-state').textContent = '订阅中…';
    this.client.subscribeOverlay(socket);
  }

  closeOverlay() {
    if (!this.overlayOpen) return;
    this.overlayOpen = false;
    $('#overlay-root').hidden = true;
    $('#overlay-text').textContent = '';
    if (this.client) this.client.unsubscribeOverlay();
  }

  applyOverlayFrame(payload) {
    if (!this.overlayOpen) return;
    const cols = payload.cols > 0 ? payload.cols : 80;
    const rows = payload.rows > 0 ? payload.rows : 24;
    // overlay_frame 是整屏快照：每帧先清空再喂，替换不是追加。
    this.overlayEmu.resize(cols, rows);
    const shown = renderOverlayFrame(payload.text || '', cols, rows, this.overlayEmu);
    const el = $('#overlay-text');
    el.textContent = shown;
    const lines = shown === '' ? 0 : shown.split('\n').length;
    const idle = /idle|停止|空闲/i.test(shown);
    const working = /working|进行中|✳|◐|◑|◒|◓/i.test(shown);
    $('#overlay-root').dataset.idle = (!working && idle) ? '1' : '0';
    $('#overlay-state').textContent = working ? '工作' : (idle ? '停止' : `seq ${payload.seq || '—'} · ${lines} 行`);
    this.overlayLastAt = Date.now();
  }

  closeSession(ref) {
    const page = this.sessions.get(ref);
    if (!page) return;
    if (this.sessionPage === page) this.closeOverlay();
    page.unmount();
    this.sessions.delete(ref);
    if (this.sessionPage === page) {
      this.sessionPage = this.sessions.values().next().value || null;
      if (this.sessionPage) this.sessionPage.setActive(true); else this.show('workspaces');
    }
    this.renderTabs();
  }

  renderTabs() {
    const tabs = $('#session-tabs');
    tabs.replaceChildren();
    for (const page of this.sessions.values()) {
      const tab = document.createElement('button');
      tab.className = 'session-tab'; tab.setAttribute('role', 'tab');
      tab.setAttribute('aria-selected', String(page === this.sessionPage));
      const name = document.createElement('span'); name.className = 'session-tab-name';
      name.textContent = page.session.name || '(unnamed)';
      const close = document.createElement('span'); close.className = 'session-tab-close'; close.textContent = '×';
      close.setAttribute('role', 'button'); close.setAttribute('aria-label', `关闭 ${name.textContent}`);
      close.onclick = (event) => { event.stopPropagation(); this.closeSession(page.ref); };
      tab.append(name, close); tab.onclick = () => this.openSession(page.session); tabs.appendChild(tab);
    }
  }

  syncOpenSessions() {
    for (const [ref, page] of this.sessions) {
      const current = this.client.session(ref);
      if (current) page.updateSession(current);
    }
    this.renderTabs();
  }

  resetConnection() {
    this.closeOverlay();
    for (const page of [...this.sessions.values()]) page.unmount();
    this.sessions.clear(); this.sessionPage = null; this.renderTabs();
    if (this.client) { this.client.disconnect(); this.client = null; }
  }

  initTheme() {
    this.theme = loadTheme();
    $('#theme-select').value = this.theme;
    const media = matchMedia('(prefers-color-scheme: dark)');
    const apply = () => { document.documentElement.dataset.theme = resolvedTheme(this.theme, media.matches); };
    media.addEventListener('change', apply); apply();
    $('#theme-select').addEventListener('change', (event) => {
      this.theme = event.target.value;
      try { saveTheme(this.theme); } catch { /* visual preference remains active */ }
      apply();
    });
  }

  connectionLabel(state) {
    return ({ connecting: '连接中…', authenticating: '正在认证…', reconnecting: '连接中断，正在自动重连…', stopped: '连接已停止', ready: '已连接' })[state] || state;
  }

  setPairStatus(text, kind = '') {
    const status = $('#pair-status'); status.textContent = text; status.className = `pair-status ${kind}`;
  }

  toast(text, kind) {
    const el = $('#toast');
    el.textContent = text;
    el.className = `toast ${kind || ''}`;
    el.style.display = 'block';
    clearTimeout(this._toastTimer);
    this._toastTimer = setTimeout(() => { el.style.display = 'none'; }, 4000);
  }

}

function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

window.__app = new App();
document.addEventListener('DOMContentLoaded', () => window.__app.init());

export { App, SessionPage, renderAnsi };
