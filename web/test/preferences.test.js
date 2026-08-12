import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadConfig, saveConfig, clearConfig, loadTheme, saveTheme, resolvedTheme } from '../js/preferences.js';

class MemoryStorage {
  constructor() { this.values = new Map(); }
  getItem(k) { return this.values.get(k) ?? null; }
  setItem(k, v) { this.values.set(k, String(v)); }
  removeItem(k) { this.values.delete(k); }
}

test('pairing config persists url and token for refresh recovery', () => {
  const storage = new MemoryStorage();
  saveConfig('ws://host:9900/ws', 'secret', storage);
  assert.deepEqual(loadConfig(storage), { url: 'ws://host:9900/ws', token: 'secret' });
  clearConfig(storage);
  assert.equal(loadConfig(storage), null);
});

test('invalid persisted config is ignored', () => {
  const storage = new MemoryStorage();
  storage.setItem('agentmirror:config', '{broken');
  assert.equal(loadConfig(storage), null);
});

test('theme supports system, dark and light only', () => {
  const storage = new MemoryStorage();
  saveTheme('light', storage);
  assert.equal(loadTheme(storage), 'light');
  assert.equal(resolvedTheme('system', true), 'dark');
  assert.equal(resolvedTheme('system', false), 'light');
  assert.throws(() => saveTheme('purple', storage));
});
