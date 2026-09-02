import assert from "node:assert/strict";
import { createConnection } from "node:net";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
const dir = mkdtempSync(join(tmpdir(), "nodeprobe-pi-ext-"));
process.env.NODEPROBE_PI_ACTIVITY_DIR = dir;
process.env.NODEPROBE_PI_SEAT = "seat-test";
const extensionPath = new URL("../pi/nodeprobe-pi-activity.js", import.meta.url).pathname;
const importOnly = spawnSync(process.execPath, ["--input-type=module", "-e", `await import(${JSON.stringify(extensionPath)})`], {
  env: process.env,
  timeout: 2000,
  encoding: "utf8",
});
assert.equal(importOnly.status, 0, importOnly.stderr);
assert.equal(importOnly.signal, null);
assert.equal(readdirSync(dir).length, 0);
const { default: extension } = await import(extensionPath);
const handlers = new Map();
const pi = { on(event, handler) { handlers.set(event, handler); } };
extension(pi);
const path = join(dir, `${process.pid}.json`);
const read = () => JSON.parse(readFileSync(path, "utf8"));
const ctx = { sessionManager: { getSessionName: () => "build-main" } };
const challenge = (socketPath, value) => new Promise((resolve, reject) => {
  const socket = createConnection(socketPath);
  let data = "";
  socket.on("data", (chunk) => { data += chunk.toString(); });
  socket.on("error", reject);
  socket.on("end", () => resolve(JSON.parse(data)));
  socket.on("connect", () => socket.end(JSON.stringify({ challenge: value }) + "\n"));
});

await handlers.get("session_start")({}, ctx);
await new Promise((resolve) => setTimeout(resolve, 20));
assert.equal(read().activity, "idle");
assert.equal(existsSync(read().socket_path), true);
assert.equal((await challenge(read().socket_path, "probe")).challenge, "probe");
assert.equal(read().session_name, "build-main");
await handlers.get("agent_start")({});
assert.equal(read().activity, "working");
assert.equal((await challenge(read().socket_path, "working")).activity, "working");
await handlers.get("tool_execution_start")?.({});
await handlers.get("tool_execution_end")?.({});
assert.equal(read().activity, "working");
await handlers.get("agent_end")?.({});
assert.equal(read().activity, "working", "agent_end is not settled");
await handlers.get("agent_settled")({});
assert.equal(read().activity, "idle");
assert.equal((await challenge(read().socket_path, "settled")).activity, "idle");
await handlers.get("session_info_changed")({ name: "release-candidate" });
assert.equal(read().session_name, "release-candidate");
await handlers.get("session_shutdown")({});
assert.equal(existsSync(path), false);
assert.equal(existsSync(join(dir, `${process.pid}.sock`)), false);
rmSync(dir, { recursive: true, force: true });
console.log("pi extension lifecycle protocol ok");
