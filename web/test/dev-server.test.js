import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

test('development server serves index and rejects traversal', async (t) => {
  const port = 14000 + Math.floor(Math.random() * 1000);
  const root = join(dirname(fileURLToPath(import.meta.url)), '..');
  const child = spawn(process.execPath, ['scripts/dev-server.mjs'], {
    cwd: root, env: { ...process.env, PORT: String(port) }, stdio: ['ignore', 'pipe', 'pipe'],
  });
  t.after(() => child.kill());
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('development server did not become ready')), 3000);
    child.stdout.once('data', () => { clearTimeout(timer); resolve(); });
    child.once('exit', (code) => { clearTimeout(timer); reject(new Error(`development server exited ${code}`)); });
  });
  const response = await fetch(`http://127.0.0.1:${port}/`, { signal: AbortSignal.timeout(1000) });
  assert.equal(response.status, 200);
  assert.match(await response.text(), /AgentMirror/);
  const missing = await fetch(`http://127.0.0.1:${port}/does-not-exist`);
  assert.equal(missing.status, 404);
});
