/*
 * AgentMirror web client — xterm.js terminal wrapper.
 *
 * xterm (MIT, npm "xterm") is loaded as a classic script tag (UMD, exposes
 * `window.Terminal`) from web/vendor/xterm/xterm.js — copied there by
 * `npm run postinstall`. This wrapper owns fit/resize (rows/cols reporting so
 * the CLI redraws for the web client, requirement 005) and the snapshot/delta
 * application path:
 *   - snapshot → reset + write  (clear-screen rebuild; §6.2)
 *   - delta    → write          (append; pipe-pane)
 * Both write raw Uint8Array bytes so ANSI/VT passes through unescaped and
 * UTF-8 sequences may span writes safely.
 */

/**
 * Adapts an xterm instance to snapshot/delta rendering and coalesced resize reporting.
 * @contract
 * @pre global Terminal is loaded and container is an HTMLElement
 * @post open mounts xterm; snapshot resets then writes; delta appends; dispose releases resources
 * @err construction throws when xterm.js is unavailable
 * @inv terminal bytes remain raw and resize callbacks report the current rows/cols pair
 */
export class TerminalView {
  /**
   * @param {HTMLElement} container sized element the xterm mounts into
   * @param {(rows:number, cols:number)=>void} [onResize] fired when the fit
   *        changes rows/cols; the caller sends a resize frame
   */
  constructor(container, { onResize, onHistoryBoundary, scrollback = 5000, fontSize = 14 } = {}) {
    this.container = container;
    this.onResize = onResize || (() => {});
    this.onHistoryBoundary = onHistoryBoundary || (() => {});
    const TerminalCtor = (typeof globalThis !== 'undefined' && globalThis.Terminal);
    if (!TerminalCtor) {
      throw new Error('xterm.js not loaded — expected window.Terminal from web/vendor/xterm/xterm.js');
    }
    this.term = new TerminalCtor({
      scrollback,
      fontSize,
      fontFamily: 'Menlo, Consolas, "DejaVu Sans Mono", monospace',
      cursorBlink: true,
      allowProposedApi: true,
    });
    this._lastDims = null;
    this._lastScrollLine = null;
    this._resizeTimer = null;
  }

  /** Mount into the container and fit to it. */
  open() {
    this.term.open(this.container);
    this._scrollDisposable = this.term.onScroll((line) => {
      if (line <= 0 && this._lastScrollLine > 0) this.onHistoryBoundary();
      this._lastScrollLine = line;
    });
    this.fit();
  }

  /**
   * Recompute rows/cols from container pixels and report changes. The caller
   * must send the resize frame (server answers with a fresh snapshot).
   */
  fit() {
    if (!this.container.isConnected || this.container.clientWidth === 0) return;
    const cols = Math.max(2, Math.floor(this.container.clientWidth / this._charWidth()));
    const rows = Math.max(2, Math.floor(this.container.clientHeight / this._lineHeight()));
    if (cols !== this.term.cols || rows !== this.term.rows) {
      this.term.resize(cols, rows);
      this._report();
    }
  }

  /** Full-screen snapshot: clear and rebuild (docs/protocol.md §6.2). */
  writeSnapshot(u8) {
    this.term.reset();
    this.term.write(u8);
  }

  /** Incremental delta: append to the current screen. */
  writeDelta(u8) {
    this.term.write(u8);
  }

  clear() { this.term.reset(); }

  focus() { this.term.focus(); }

  scrollToBottom() { this.term.scrollToBottom(); }

  dispose() {
    clearTimeout(this._resizeTimer);
    if (this._scrollDisposable) this._scrollDisposable.dispose();
    try { this.term.dispose(); } catch { /* already disposed */ }
  }

  get rows() { return this.term.rows; }
  get cols() { return this.term.cols; }

  _report() {
    const dims = `${this.term.rows}x${this.term.cols}`;
    if (dims !== this._lastDims) {
      this._lastDims = dims;
      clearTimeout(this._resizeTimer);
      // @contract Server resize returns a snapshot; coalesce layout churn to
      // avoid flashing redraws while a window is being resized (D-29).
      this._resizeTimer = setTimeout(() => this.onResize(this.term.rows, this.term.cols), 120);
    }
  }

  /** Measure one em-width via a probe span inside xterm's rows element. */
  _charWidth() {
    const rowsEl = this.term.element && this.term.element.querySelector('.xterm-rows');
    if (!rowsEl) return 8;
    const probe = document.createElement('span');
    probe.textContent = 'M';
    probe.style.position = 'absolute';
    probe.style.visibility = 'hidden';
    rowsEl.appendChild(probe);
    const w = probe.getBoundingClientRect().width;
    probe.remove();
    return w > 0 ? w : 8;
  }

  /** Measure one line height from xterm's row style. */
  _lineHeight() {
    const rowsEl = this.term.element && this.term.element.querySelector('.xterm-rows');
    if (!rowsEl) return 16;
    const h = parseFloat(window.getComputedStyle(rowsEl).lineHeight);
    return h > 0 ? h : 16;
  }
}
