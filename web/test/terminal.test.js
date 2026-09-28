import { test } from 'node:test';
import assert from 'node:assert/strict';
import { TerminalView } from '../js/terminal.js';

class FakeTerminal {
  constructor() { this.rows = 24; this.cols = 80; this.element = null; }
  open() {}
  onScroll(handler) { this.scrollHandler = handler; return { dispose() {} }; }
  resize(cols, rows) { this.cols = cols; this.rows = rows; }
  dispose() {}
}

test('resize reports only the final dimensions after layout churn', async () => {
  globalThis.Terminal = FakeTerminal;
  const reports = [];
  const view = new TerminalView({ isConnected: true, clientWidth: 800, clientHeight: 400 }, {
    onResize: (rows, cols) => reports.push([rows, cols]),
  });
  view._charWidth = () => 8; view._lineHeight = () => 16;
  view.fit();
  view.container.clientWidth = 640; view.fit();
  await new Promise((resolve) => setTimeout(resolve, 160));
  assert.deepEqual(reports, [[25, 80]]);
  view.dispose(); delete globalThis.Terminal;
});

test('scrolling to the local top requests older history', () => {
  globalThis.Terminal = FakeTerminal;
  let boundaries = 0;
  const view = new TerminalView({ isConnected: false }, { onHistoryBoundary: () => boundaries++ });
  view.open();
  view.term.scrollHandler(0); // initial xterm position is not a user boundary hit
  view.term.scrollHandler(3); view.term.scrollHandler(0);
  assert.equal(boundaries, 1);
  view.dispose(); delete globalThis.Terminal;
});
