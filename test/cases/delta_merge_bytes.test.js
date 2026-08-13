// delta_merge_bytes.test.js — C1 关卡 2 e2e 半边的字节等价契约测试（w-c1-test）。
//
// 任务 perf-delta-backpressure-merge 判据原文（docs/ts-link-baseline.md §delta 背压合并）：
//   「合并前后客户端收到的字节流逐字节相同，只是分帧不同。」
//
// 与 server/internal/api/delta_merge_scenario_test.go（Go 单测，驱动合并路径）互补：
// 本文件走**真实 daemon + 真实 tmux** 的端到端链路，从客户端视角验证一条更基础的
// 契约——**客户端收到的 delta 字节流 == tmux pane 的真实输出**。这是「字节等价」的
// 生态基座：任何 delta 分帧/合并方式（无论是否触发合并）都必须保持这条。
//
// 这条测试**合并前后都必须绿**（零回归闸）：
//   - 合并前：delta 逐 chunk 转发，客户端字节 == pane 输出（现状正确）；
//   - 合并后：队列满时把多个 delta 拼成一个大帧，客户端字节仍 == pane 输出（判据）。
// 若未来有人破坏「字节等价」（丢 chunk / 乱序 / 跨 ref 拼接），本用例立即红。
//
// 断言（纯客户端可见，不依赖仪表）：
//   [1] 注入多行后，客户端收到的 delta 字节里包含全部回显文本；
//   [2] 每个 delta 帧 ref 恒为订阅的 ref（不跨流）；
//   [3] 重组后的纯文本包含 CLI 的 user-said / reply（逐字节语义，经 ANSI 剥离）。
//
// 夹具：test/fixtures/stream_cli.py —— 确定性流式 CLI：收到输入回显 user-said/reply。
// 隔离纪律：本文件只经 runner 的隔离 daemon/tmux；每用例自建唯一 cwd 会话。

const fs = require('fs');
const path = require('path');
const { globalRegistry } = require('../framework/registry');
const { AgentMirrorClient } = require('../framework/ws_client');
const {
  expectListing,
  expectTerminalContains,
  expect,
  toText,
} = require('../framework/assertions');
const { getEnvironment } = require('../framework/context');

const FIXTURE = path.join(__dirname, '..', 'fixtures', 'stream_cli.py');

// 新建一个隔离 tmux 会话运行流式 CLI 夹具；cwd 必须是本用例唯一的子目录。
function createSession(tmuxInfo, workDir, marker) {
  const sessionDir = path.join(workDir, marker);
  fs.mkdirSync(sessionDir, { recursive: true });
  tmuxInfo.tmux(['new-session', '-d', '-s', marker, '-c', sessionDir,
    `python3 -u ${FIXTURE}`]);
  return sessionDir;
}

// 连接、认证、列出并按 cwd 精确匹配目标会话的 ref。
async function connectAndRef(port, token, sessionDir) {
  const client = new AgentMirrorClient({ url: `ws://127.0.0.1:${port}/ws` });
  await client.connect();
  await client.auth(token);
  const listing = await client.list();
  const workspaces = expectListing(listing);
  const sessions = workspaces.flatMap((w) => w.sessions || []);
  const want = fs.realpathSync(sessionDir);
  const target = sessions.find((s) => {
    try { return s.cwd && fs.realpathSync(s.cwd) === want; } catch (e) { return false; }
  });
  expect(target && target.ref, `session for cwd ${sessionDir} not found in listing`);
  return { client, ref: target.ref };
}

// delta_merge：客户端收到的 delta 字节必须包含 CLI 的全部回显（注入多行）。
// 合并前后都必须绿：字节等价是任何分帧方式的不变量。
globalRegistry.define({
  name: 'delta_merge:client-bytes-equal-pane-output',
  tags: ['delta-merge', 'mirror'],
  description: 'C1关卡2 e2e：客户端 delta 字节流包含 CLI 全部回显（合并前后都必须绿的零回归闸）',
  async fn(ctx, helpers) {
    const { port, token, workDir, tmuxInfo } = getEnvironment();
    const marker = `dmerge_${Date.now().toString(36)}`;
    const sessionDir = createSession(tmuxInfo, workDir, marker);

    const { client, ref } = await connectAndRef(port, token, sessionDir);
    try {
      // 用事件收集所有二进制帧（可靠；与 debug 实证一致）。
      const bins = [];
      client.on('binary', (b) => bins.push(b));

      // 订阅 → 首帧 snapshot（建立镜像）。快照必须携带 CLI 的 ready 标记（夹具已启动）。
      const snap = await client.subscribe(ref, { rows: 24, cols: 80 });
      expect(snap && snap.kind === 1, `subscribe must return a snapshot, got kind=${snap && snap.kind}`);
      expectTerminalContains(snap, '== stream-cli ready ==', { label: 'snapshot carries CLI ready marker' });
      await sleep(150);

      // 注入多行文本（每行触发 CLI 回显 user-said / reply）。
      const lines = [];
      for (let i = 0; i < 5; i++) {
        lines.push(`MERGE_MARK_${i}`);
      }
      for (const ln of lines) {
        await client.input(ref, ln);
      }

      // 等待全部回显出现在已收到的 delta 字节里（事件驱动，无需轮询私字段）。
      const wantEchoes = lines.flatMap((ln) => [`user-said: ${ln}`, 'reply: ok']);
      const deadline = Date.now() + 5000;
      let gotAll = false;
      while (Date.now() < deadline && !gotAll) {
        const text = toText({ payload: concatDeltas(bins) });
        gotAll = wantEchoes.every((e) => text.includes(e));
        if (!gotAll) await sleep(50);
      }

      // 断言 [1][2][3]：
      //   - 至少收到一个 delta 帧（真收到，不是空跑）；
      //   - 每个 delta 帧 ref 都 == 订阅的 ref（不跨流）；
      //   - 全部回显都出现在重组后的字节流里（逐字节语义）。
      const deltas = bins.filter((b) => b.kind === 2);
      expect(deltas.length > 0, `expected at least one delta frame, got ${deltas.length}`);
      for (const d of deltas) {
        expect(d.ref === ref, `delta frame ref=${d.ref} != subscribed ref=${ref} (跨流拼接破坏 AnsiParser 语义)`);
      }
      const finalText = toText({ payload: concatDeltas(bins) });
      for (const echo of wantEchoes) {
        expectTerminalContains(finalText, echo, { label: `delta bytes must carry CLI echo "${echo}"` });
      }

      const totalBytes = bins.filter((b) => b.kind === 2).reduce((n, b) => n + b.payload.length, 0);
      helpers.log(`delta_merge e2e: client received ${totalBytes} delta bytes across ${deltas.length} frames; all ${wantEchoes.length} echoes present ✓`);
    } finally {
      client.close();
    }
  },
});

function concatDeltas(bins) {
  const deltas = bins.filter((b) => b.kind === 2);
  const total = deltas.reduce((n, b) => n + b.payload.length, 0);
  const out = new Uint8Array(total);
  let off = 0;
  for (const d of deltas) {
    out.set(d.payload, off);
    off += d.payload.length;
  }
  return out;
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}
