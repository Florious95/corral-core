import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

test('app frame callback receives payload used by auth rejection feedback', () => {
  const source = readFileSync(new URL('../js/app.js', import.meta.url), 'utf8');
  assert.match(source, /onFrame:\s*\(type, payload\)\s*=>/);
});
