/*
 * Session overlay (064/065/066): render overlay_frame through a grid emulator
 * so CSI is not painted as text, and each refresh replaces the screen.
 */

const SCRATCH_TOKENS = Object.freeze(['am-overlay', 'ov-spin']);
const SCRATCH_PANE = /\b(?:tree|sleep)\*/;

/** ref = socket + U+001F + pane_id；订阅只取 socket。 */
export function sessionSocketFromRef(ref) {
  if (typeof ref !== 'string' || ref.length === 0) return '';
  const sep = ref.indexOf('\u001f');
  return sep > 0 ? ref.slice(0, sep) : ref;
}

export function cellWidth(ch) {
  const c = ch.codePointAt(0);
  if (c <= 0x1f || (c >= 0x7f && c <= 0x9f)) return 0;
  if (c < 0x1100) return 1;
  if (
    (c >= 0x2e80 && c <= 0xa4cf) ||
    (c >= 0xac00 && c <= 0xd7a3) ||
    (c >= 0xf900 && c <= 0xfaff) ||
    (c >= 0xfe10 && c <= 0xfe19) ||
    (c >= 0xfe30 && c <= 0xfe6f) ||
    (c >= 0xff00 && c <= 0xff60) ||
    (c >= 0xffe0 && c <= 0xffe6) ||
    (c >= 0x20000 && c <= 0x3fffd)
  ) {
    return 2;
  }
  return 1;
}

/**
 * Small VT grid used only for overlay_frame (same job as Android TerminalEmulator).
 * Interprets alt-screen, CUP, ED, EL, SGR so refresh replaces instead of appending.
 */
export class OverlayEmulator {
  constructor(cols = 80, rows = 24) {
    this.cols = cols;
    this.rows = rows;
    this.cx = 0;
    this.cy = 0;
    this.grid = this._blank();
  }

  resize(cols, rows) {
    this.cols = cols;
    this.rows = rows;
    this.cx = 0;
    this.cy = 0;
    this.grid = this._blank();
  }

  _blank() {
    return Array.from({ length: this.rows }, () => Array(this.cols).fill(' '));
  }

  feed(text) {
    if (typeof text !== 'string' || text.length === 0) return;
    let i = 0;
    while (i < text.length) {
      const ch = text[i];
      if (ch === '\x1b') {
        i = this._consumeEsc(text, i);
        continue;
      }
      if (ch === '\r') {
        this.cx = 0;
        i += 1;
        continue;
      }
      if (ch === '\n') {
        this._newline();
        i += 1;
        continue;
      }
      if (ch === '\b') {
        if (this.cx > 0) this.cx -= 1;
        i += 1;
        continue;
      }
      const cp = text.codePointAt(i);
      const glyph = String.fromCodePoint(cp);
      i += glyph.length;
      this._put(glyph);
    }
  }

  _consumeEsc(text, i) {
    const next = text[i + 1];
    if (next === '[') {
      let j = i + 2;
      while (j < text.length && !/[A-Za-z@`~]/.test(text[j])) j += 1;
      const body = text.slice(i + 2, j);
      const final = text[j] || '';
      this._csi(body, final);
      return j + 1;
    }
    if (next === ']') {
      let j = i + 2;
      while (j < text.length && text[j] !== '\x07' && !(text[j] === '\x1b' && text[j + 1] === '\\')) j += 1;
      if (text[j] === '\x07') return j + 1;
      if (text[j] === '\x1b') return j + 2;
      return text.length;
    }
    if (next === '(' || next === ')') return i + 3;
    return i + 2;
  }

  _csi(body, final) {
    const priv = body.startsWith('?') || body.startsWith('>');
    const nums = (priv ? body.slice(1) : body)
      .split(';')
      .map((n) => (n === '' ? 0 : parseInt(n, 10)))
      .map((n) => (Number.isFinite(n) ? n : 0));
    if (final === 'h' && priv && nums.includes(1049)) {
      this.grid = this._blank();
      this.cx = 0;
      this.cy = 0;
      return;
    }
    if (final === 'l' && priv && nums.includes(1049)) {
      this.grid = this._blank();
      this.cx = 0;
      this.cy = 0;
      return;
    }
    if (final === 'H' || final === 'f') {
      const row = Math.max(1, nums[0] || 1) - 1;
      const col = Math.max(1, nums[1] || 1) - 1;
      this.cy = Math.min(this.rows - 1, row);
      this.cx = Math.min(this.cols - 1, col);
      return;
    }
    if (final === 'J') {
      const n = nums[0] || 0;
      if (n === 2 || n === 3) {
        this.grid = this._blank();
        this.cx = 0;
        this.cy = 0;
      }
      return;
    }
    if (final === 'K') {
      const n = nums[0] || 0;
      const row = this.grid[this.cy];
      if (!row) return;
      if (n === 0) {
        for (let x = this.cx; x < this.cols; x += 1) row[x] = ' ';
      } else if (n === 1) {
        for (let x = 0; x <= this.cx; x += 1) row[x] = ' ';
      } else if (n === 2) {
        for (let x = 0; x < this.cols; x += 1) row[x] = ' ';
      }
      return;
    }
    if (final === 'A') this.cy = Math.max(0, this.cy - (nums[0] || 1));
    if (final === 'B') this.cy = Math.min(this.rows - 1, this.cy + (nums[0] || 1));
    if (final === 'C') this.cx = Math.min(this.cols - 1, this.cx + (nums[0] || 1));
    if (final === 'D') this.cx = Math.max(0, this.cx - (nums[0] || 1));
    // SGR / window ops / DEC private: ignore (do not emit as glyphs)
  }

  _put(glyph) {
    const w = cellWidth(glyph);
    if (w <= 0) return;
    if (this.cx + w > this.cols) this._newline();
    const row = this.grid[this.cy];
    if (!row) return;
    row[this.cx] = glyph;
    if (w === 2 && this.cx + 1 < this.cols) row[this.cx + 1] = '';
    this.cx += w;
  }

  _newline() {
    this.cx = 0;
    if (this.cy < this.rows - 1) this.cy += 1;
  }

  plainText() {
    return this.grid
      .map((row) => row.join('').replace(/ +$/g, ''))
      .join('\n')
      .replace(/\n+$/g, '');
  }
}

export function dropScratchLines(text) {
  return text
    .split('\n')
    .filter((line) => {
      for (const tok of SCRATCH_TOKENS) {
        if (line.includes(tok)) return false;
      }
      if (SCRATCH_PANE.test(line)) return false;
      return true;
    })
    .join('\n');
}

export function renderOverlayFrame(text, cols = 80, rows = 24, emu = null) {
  const screen = emu || new OverlayEmulator(cols, rows);
  if (screen.cols !== cols || screen.rows !== rows) screen.resize(cols, rows);
  screen.feed(text);
  return dropScratchLines(screen.plainText());
}
