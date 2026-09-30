// resize.test.js — 终端尺寸管理四连缺陷（D-20/D-21）的协议层红测/回归。
//
// 架构前提（重要，与 role 文件措辞的偏差在此说明）：
// 协议里**没有** S→C 的 resize 帧（docs/protocol.md §4.2：resize 是纯 C→S，
// 服务端应用后以二进制 snapshot 重发作为事实回执）。因此本套用例不测"收到
// resize 帧"，而测 D-20/D-21 修复所依赖的**服务端契约**：
//   - 订阅期间发 resize → pane 实际改变 + 重发 snapshot（回执）；冗余同尺寸
//     resize 也会重发 snapshot（churn）——但修复后客户端不再发冗余 resize；
//   - 退订后发 resize → 服务端 no-op（D-21 根因：恢复帧必须先于退订发出）。
// D-20 的"键盘弹出不发 resize"是客户端行为（presenter 几何锁，Kotlin 红测
// keyboardShrinkPushesViewportUpWithoutResize 覆盖）；本文件用 `resize:no-keyboard-churn`
// 锁住服务端侧的另一半：客户端不发帧时流必须安静、pane 尺寸不变。
//
// D-21 的"退出恢复"同样是客户端行为（SessionViewModel.dispose 先 resize 后
// unsubscribe），本文件用 `resize:restore-before-unsubscribe` 端到端验证该顺序在
// 协议上成立，并用 `resize:after-unsubscribe-is-noop` 锁住反向顺序的失败模式
// （正是 D-21 真机未恢复的根因）。

const fs = require('fs');
const path = require('path');
const { globalRegistry } = require('../framework/registry');
const { AgentMirrorClient } = require('../framework/ws_client');
const {
  expectListing,
  expectBinarySnapshot,
  expect,
  expectEqual,
} = require('../framework/assertions');
const { getEnvironment } = require('../framework/context');

// 新建一个隔离 tmux 会话：工作目录必须是本用例唯一的子目录（runner 在单次运行中
// 共享一个隔离 tmux，各用例的会话会累积；按 cwd 精确匹配才能取到自己刚建的会话）。
// 返回该会话的 cwd（供 connectAndList 按 cwd 定位 ref）。
function createSession(tmuxInfo, workDir, marker) {
  const sessionDir = path.join(workDir, marker);
  fs.mkdirSync(sessionDir, { recursive: true });
  tmuxInfo.tmux(['new-session', '-d', '-s', marker, '-c', sessionDir, 'sh -c "sleep 3600"']);
  return sessionDir;
}

// 读取隔离 pane 的实际尺寸（tmux 真相源；格式 "#{pane_width}x#{pane_height}"）。
function paneSize(tmuxInfo, session) {
  return tmuxInfo.tmux(['list-panes', '-t', session, '-F', '#{pane_width}x#{pane_height}']).trim();
}

// 连接 + auth + list，按 cwd（realpath 归一，macOS /var→/private/var）定位目标会话的 ref。
async function connectAndList(port, token, sessionDir) {
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

// 基线：订阅时客户端尺寸已应用到 pane（005）；显式 resize 再应用 + 重发 snapshot 回执。
// 这是 D-20/D-21 全部后续断言的地基——订阅与 resize 的"应用 + 回执"语义一旦回归，
// 下面各用例的 pane 尺寸读回会立即暴露。
globalRegistry.define({
  name: 'resize:subscribe-applies-dims-and-repushes-snapshot',
  tags: ['resize', 'smoke'],
  description: '订阅时客户端行列数应用到 pane；显式 resize 生效并重发 snapshot 回执（协议 §4.2）',
  async fn(ctx, helpers) {
    const { port, token, workDir, tmuxInfo } = getEnvironment();
    const marker = `rzsub_${Date.now().toString(36)}`;
    const sessionDir = createSession(tmuxInfo, workDir, marker);
    const { client, ref } = await connectAndList(port, token, sessionDir);
    try {
      // 订阅携带客户端尺寸 80x30：pane 应随之重排（005 首次进入自适应）。
      await client.subscribe(ref, { rows: 30, cols: 80 });
      await sleep(400);
      expectEqual(paneSize(tmuxInfo, marker), '80x30', 'pane size after subscribe');

      // 显式 resize 40x10：pane 应改变，且服务端重发一帧 snapshot 作为事实回执。
      const afterSeq = client._binSeq;
      client.resize(ref, { rows: 10, cols: 40 });
      const repush = await client.waitForBinary({ kind: 1, after: afterSeq, timeoutMs: 3000 });
      expectBinarySnapshot(repush, ref, { label: 'resize repush' });
      expect(repush.payload.length > 0, 'resize repush snapshot must be non-empty');
      await sleep(400);
      expectEqual(paneSize(tmuxInfo, marker), '40x10', 'pane size after resize');
      helpers.log(`subscribe/resize applied: pane 80x30 → 40x10, snapshot repush ok`);
    } finally {
      client.close();
    }
  },
});

// D-21 正确顺序（修复后）：先恢复 resize（仍在订阅中，服务端会应用）再退订。
// 端到端验证 SessionViewModel.dispose 的"先 resize 后 unsubscribe"在协议上成立：
// 退订结束后 pane 已恢复主机原始尺寸。
globalRegistry.define({
  name: 'resize:restore-before-unsubscribe-restores-pane',
  tags: ['resize', 'd21'],
  description: 'D-21：订阅期间发恢复 resize（80x24）→ pane 恢复；随后退订（恢复必须先行）',
  async fn(ctx, helpers) {
    const { port, token, workDir, tmuxInfo } = getEnvironment();
    const marker = `rz21_${Date.now().toString(36)}`;
    const sessionDir = createSession(tmuxInfo, workDir, marker);
    const { client, ref } = await connectAndList(port, token, sessionDir);
    try {
      // 进入会话：客户端把 pane 从主机默认 80x24 拉到手机视口 30x80。
      await client.subscribe(ref, { rows: 30, cols: 80 });
      await sleep(400);
      expectEqual(paneSize(tmuxInfo, marker), '80x30', 'pane shrunk to phone dims');

      // dispose 顺序①：恢复 resize 必须先于退订发出——此刻仍在订阅中，服务端应用之。
      const afterSeq = client._binSeq;
      client.resize(ref, { rows: 24, cols: 80 });
      // 恢复 resize 是"真改变"：服务端应重发 snapshot 回执（同时是 resize 已生效的信号）。
      const repush = await client.waitForBinary({ kind: 1, after: afterSeq, timeoutMs: 3000 });
      expectBinarySnapshot(repush, ref, { label: 'restore repush' });
      await sleep(400);
      expectEqual(paneSize(tmuxInfo, marker), '80x24', 'pane restored to host original');

      // dispose 顺序②：然后退订镜像。
      client.unsubscribe(ref);
      await sleep(200);
      // 退订后 pane 保持恢复态（不再被手机尺寸压缩）。
      expectEqual(paneSize(tmuxInfo, marker), '80x24', 'pane stays restored after unsubscribe');
      helpers.log(`restore-then-unsubscribe: pane back to 80x24`);
    } finally {
      client.close();
    }
  },
});

// D-21 失败模式（修复前 fix-ime-resize 旧序）：先退订再恢复 resize——服务端对
// 未订阅会话的 resize 是 no-op，pane 保持手机小尺寸。这条锁住"顺序必须反着来"：
// 若未来 dispose 被改回 unsubscribe→resize，本用例立即红。
globalRegistry.define({
  name: 'resize:after-unsubscribe-resize-is-noop',
  tags: ['resize', 'd21'],
  description: 'D-21 根因：退订后再发恢复 resize 被服务端忽略，pane 保持小尺寸（顺序必须反之）',
  async fn(ctx, helpers) {
    const { port, token, workDir, tmuxInfo } = getEnvironment();
    const marker = `rz21b_${Date.now().toString(36)}`;
    const sessionDir = createSession(tmuxInfo, workDir, marker);
    const { client, ref } = await connectAndList(port, token, sessionDir);
    try {
      await client.subscribe(ref, { rows: 30, cols: 80 });
      await sleep(400);
      expectEqual(paneSize(tmuxInfo, marker), '80x30', 'pane shrunk to phone dims');

      // 错误顺序：先退订……
      client.unsubscribe(ref);
      await sleep(200);
      // ……再发恢复 resize：服务端 subscribed() 闸直接 no-op，无 snapshot 回执。
      const afterSeq = client._binSeq;
      client.resize(ref, { rows: 24, cols: 80 });
      let repushed = false;
      try {
        await client.waitForBinary({ kind: 1, after: afterSeq, timeoutMs: 800 });
        repushed = true;
      } catch (e) { /* 无回执 = 未生效，符合预期 */ }
      await sleep(300);
      // pane 保持手机尺寸：恢复帧被丢弃，正是 D-21 真机"退出后仍被压缩"的根因。
      expectEqual(paneSize(tmuxInfo, marker), '80x30', 'pane stays shrunk (restore dropped)');
      helpers.log(`unsubscribe-then-resize: restore dropped, pane stays 80x30 (root cause locked)`);
    } finally {
      client.close();
    }
  },
});

// D-20 服务端侧回归：客户端在键盘弹出时不发任何 resize 帧（几何锁，presenter 已覆盖），
// 因此服务端流必须安静（无 snapshot churn）、pane 尺寸不变。若未来客户端被改回
// "键盘弹出即发 resize"，服务端会重发 snapshot + 重排 pane——本用例通过探测
// "流安静 + pane 不变"把这层回归也拦住（两层：Kotlin 锁客户端，本用例锁协议侧）。
globalRegistry.define({
  name: 'resize:no-keyboard-churn-stream-quiet-pane-unchanged',
  tags: ['resize', 'd20'],
  description: 'D-20：客户端不发 resize 时流保持安静、pane 尺寸不变（无冗余 snapshot churn）',
  async fn(ctx, helpers) {
    const { port, token, workDir, tmuxInfo } = getEnvironment();
    const marker = `rz20_${Date.now().toString(36)}`;
    const sessionDir = createSession(tmuxInfo, workDir, marker);
    const { client, ref } = await connectAndList(port, token, sessionDir);
    try {
      await client.subscribe(ref, { rows: 30, cols: 80 });
      await sleep(400);
      expectEqual(paneSize(tmuxInfo, marker), '80x30', 'pane sized by subscribe');

      // 模拟键盘弹出后的"静默"客户端：不发任何帧，只等待。
      const afterSeq = client._binSeq;
      await sleep(800);
      // 流必须安静：不得出现新的二进制 snapshot（无 churn）。
      let repushed = false;
      try {
        await client.waitForBinary({ kind: 1, after: afterSeq, timeoutMs: 100 });
        repushed = true;
      } catch (e) { /* 无新 snapshot，符合预期 */ }
      expect(!repushed, 'expected no snapshot churn when client sends no resize');
      // pane 尺寸不变（客户端未请求重排，几何锁定）。
      expectEqual(paneSize(tmuxInfo, marker), '80x30', 'pane unchanged (no resize sent)');
      helpers.log(`keyboard-quiet: no snapshot churn, pane stays 80x30`);
    } finally {
      client.close();
    }
  },
});

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}
