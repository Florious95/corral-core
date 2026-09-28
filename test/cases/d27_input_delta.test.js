// d27_input_delta.test.js — D-27 红测：input 后 delta 到达模式必须与 CLI 自行输出一致。
//
// 缺陷背景（taskbook fix-d27-v3 / fix-refresh-direction 诊断）：
//   现象「发消息后概率性从上往下逐行刷新、底部是旧内容」只在 input 后出现。
//   前席诊断根因链：客户端 IME 重布局（键盘弹出/输入框增行）→ onViewportSizeChanged
//   → 无几何锁时 recomputeGeometry() 重算 rows/cols → 发 resize → 服务端 handleResize
//   补发整帧 snapshot → 客户端 replaySnapshot 清屏重建 →「逐行刷新」观感。
//   CLI 自己吐字时无 IME 交互、无 resize，服务端只流 delta——所以两者表现不同。
//
// 本文件的协议层断言（用 test/framework/ 的 WS 客户端，独立于产品代码）：
//   [1] input 响应窗口与 CLI 自行输出窗口的帧型模式一致：纯 delta，零 snapshot，
//       重组后无全清屏序列（\x1b[2J / \x1b[3J）——发消息追加是纯增量字节，
//       服务端绝不应为 input 补发 snapshot/清屏/整帧重绘。
//       （今天服务端已满足 ⇒ 绿；锁住"input 绝不重放 snapshot"的契约，
//        防未来有人用错误方式修 D-27——在 input 路径直接补发 snapshot。）
//   [2] 【红测本体】input 后客户端因 IME 重布局发出的「冗余同尺寸 resize」：
//       resize 实际未改变 pane 尺寸（no-op），服务端不得补发 snapshot。
//       今天服务端对任何 resize（含同尺寸）都补发 snapshot ⇒ 本用例红；
//       修复后（服务端在 resize 未改变实际尺寸时跳过补发，或客户端不发该 resize）
//       变绿。这是 D-27 的逐行刷新在协议层的唯一生产入口（已实证：
//       subscribe 80x30 → input → 同尺寸 resize 80x30 → 服务端补发 snapshot）。
//   [3] 阳性对照：真正改变尺寸的 resize（60x20）仍必须补发 snapshot——
//       证明本测试的 snapshot 探测是活的，且 [2] 只拦"no-op resize 补发"，
//       不破坏文档化行为（docs/protocol.md §4.2 resize：成功应用后补发 snapshot）。
//
// 夹具：test/fixtures/stream_cli.py —— 一个确定性流式 CLI：
//   自行输出（status-tick 每 0.8s 底部追加一行）＋ 收到输入回显 user-said/reply。
//   两路都是纯底部追加（D-27 goal 原文：「两者本质都是 CLI 底部追加内容」）。
//
// 隔离纪律：本文件只经 runner 的隔离 daemon/tmux；每用例自建唯一 cwd 会话。

const fs = require('fs');
const path = require('path');
const { globalRegistry } = require('../framework/registry');
const { AgentMirrorClient } = require('../framework/ws_client');
const {
  expectListing,
  expectBinarySnapshot,
  expectEqual,
  expect,
} = require('../framework/assertions');
const { getEnvironment } = require('../framework/context');

// 夹具脚本绝对路径（runner 的隔离 tmux 从 workDir 起会话，须用绝对路径）。
const FIXTURE = path.join(__dirname, '..', 'fixtures', 'stream_cli.py');

// 新建一个隔离 tmux 会话运行流式 CLI 夹具；工作目录必须是本用例唯一的子目录
// （runner 单次运行共享一个隔离 tmux，各用例会话累积；按 cwd 精确匹配取自己刚建的）。
function createSession(tmuxInfo, workDir, marker) {
  const sessionDir = path.join(workDir, marker);
  fs.mkdirSync(sessionDir, { recursive: true });
  tmuxInfo.tmux(['new-session', '-d', '-s', marker, '-c', sessionDir,
    `python3 -u ${FIXTURE}`]);
  return sessionDir;
}

// 读取隔离 pane 的实际尺寸（tmux 真相源；格式 "#{pane_width}x#{pane_height}"）。
function paneSize(tmuxInfo, session) {
  return tmuxInfo.tmux(['list-panes', '-t', session, '-F', '#{pane_width}x#{pane_height}']).trim();
}

// 连接 + auth + list，按 cwd（realpath 归一，macOS /var→/private/var）定位目标会话 ref。
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

// 取 seq 之后到达的二进制帧窗口（ws_client 内部环形缓冲，防事件监听错位丢帧）。
function window(client, after) {
  return client._recentBins.filter((b) => b._seq > after);
}

// 重组合并窗口内全部 payload 字节（delta 可能把 ANSI 转义拆在多帧，须重组再判全清屏）。
function reassemble(bins) {
  const parts = bins.map((b) => b.payload);
  const total = parts.reduce((n, p) => n + p.length, 0);
  const out = new Uint8Array(total);
  let off = 0;
  for (const p of parts) { out.set(p, off); off += p.length; }
  return out;
}

function toText(bytes) {
  return new TextDecoder().decode(bytes);
}

// 全清屏序列：ED 2J（清整屏）或 ED 3J（清屏含回滚缓冲）。D-27 的"整帧重绘"观感
// 在字节层即此类序列（客户端 replaySnapshot 还会整体重建，见 SessionViewModel）。
function hasFullClear(bytes) {
  const t = toText(bytes);
  return t.includes('\x1b[2J') || t.includes('\x1b[3J');
}

function frameSummary(bins) {
  return {
    kinds: bins.map((b) => b.kindName).join(','),
    n: bins.length,
    snapshots: bins.filter((b) => b.kindName === 'snapshot').length,
  };
}

// [1] 核心契约：input 响应窗口与 CLI 自行输出窗口，帧型模式一致（纯 delta，
//     零 snapshot、零全清屏序列）。这是 D-27 goal 的协议层表述。
globalRegistry.define({
  name: 'd27:input-response-pattern-matches-cli-self-output',
  tags: ['d27', 'smoke'],
  description: 'D-27：input 响应与 CLI 自行输出同为纯 delta 流（无 snapshot/清屏/整帧重绘）',
  async fn(ctx, helpers) {
    const { port, token, workDir, tmuxInfo } = getEnvironment();
    const marker = `d27a_${Date.now().toString(36)}`;
    const sessionDir = createSession(tmuxInfo, workDir, marker);
    const { client, ref } = await connectAndList(port, token, sessionDir);
    try {
      await client.subscribe(ref, { rows: 30, cols: 80 });

      // 窗口一：CLI 自行输出基线（夹具每 0.8s 追加一行 status-tick；等 ~1.8s 收 2 行）。
      const b0 = client._binSeq;
      await sleep(1800);
      const selfOut = window(client, b0);
      helpers.log(`self-output window: ${frameSummary(selfOut).kinds} (${selfOut.length} frames)`);
      expect(selfOut.length >= 1, 'self-output window captured at least one frame (fixture tick)');
      const s0 = frameSummary(selfOut);
      expect(s0.snapshots === 0, 'self-output must be delta-only (no snapshot)');
      expect(!hasFullClear(reassemble(selfOut)), 'self-output must contain no full-clear sequence');

      // 窗口二：input 响应（发一条消息，等回执 + 追加稳定）。
      const b1 = client._binSeq;
      const ack = await client.input(ref, 'red test marker\n');
      expect(ack.payload.ok === true, 'input_ack must be ok:true');
      await sleep(900);
      const afterInput = window(client, b1);
      helpers.log(`after-input window: ${frameSummary(afterInput).kinds} (${afterInput.length} frames)`);
      expect(afterInput.length >= 1, 'after-input window captured at least one delta');
      const s1 = frameSummary(afterInput);
      expect(s1.snapshots === 0, 'D-27: input must NOT trigger a snapshot replay (逐行刷新根因)');
      expect(!hasFullClear(reassemble(afterInput)), 'D-27: input response must contain no full-clear sequence');

      // 到达模式一致性：两个窗口的帧型集合都必须只是 delta。
      expect(s0.kinds.split(',').every((k) => k === 'delta'), 'self-output frames all delta');
      expect(s1.kinds.split(',').every((k) => k === 'delta'), 'input-response frames all delta');

      // 阳性内容对照：input 响应里确实有输入回显 + 夹具应答（证明窗口捕获的是真实帧）。
      const respText = toText(reassemble(afterInput));
      expect(respText.includes('red test marker'), 'input response must echo the sent marker');
      expect(respText.includes('user-said: red test marker'), 'input response must carry the CLI echo');
      helpers.log(`input response deltas: ${s1.kinds}`);
    } finally {
      client.close();
    }
  },
});

// [2] 红测本体：input 后客户端因 IME 重布局发出的冗余同尺寸 resize（实际未改变
//     pane 尺寸）不得补发 snapshot。今天服务端对任何 resize 都补发 ⇒ 红；
//     修复后（服务端在 no-op resize 跳过补发 / 客户端不发该 resize）变绿。
globalRegistry.define({
  name: 'd27:redundant-same-size-resize-after-input-must-not-repush-snapshot',
  tags: ['d27'],
  description: 'D-27 红测：input 后的冗余同尺寸 resize（pane 实际未变）不得补发 snapshot（清屏重绘）',
  async fn(ctx, helpers) {
    const { port, token, workDir, tmuxInfo } = getEnvironment();
    const marker = `d27r_${Date.now().toString(36)}`;
    const sessionDir = createSession(tmuxInfo, workDir, marker);
    const { client, ref } = await connectAndList(port, token, sessionDir);
    try {
      // 订阅携带客户端尺寸：pane 应随之为 80x30（005 首次进入自适应）。
      await client.subscribe(ref, { rows: 30, cols: 80 });
      await sleep(400);
      expectEqual(paneSize(tmuxInfo, marker), '80x30', 'pane sized by subscribe');
      const dimsBefore = paneSize(tmuxInfo, marker);

      // 复现 D-27 时序：发消息（触发 IME 重布局）→ 客户端发出冗余同尺寸 resize。
      const ack = await client.input(ref, 'd27 red test\n');
      expect(ack.payload.ok === true, 'input_ack must be ok:true');
      await sleep(200);
      const before = client._binSeq;
      client.resize(ref, { rows: 30, cols: 80 }); // 与订阅同尺寸：no-op resize
      await sleep(400);

      // 真实性自证：这次 resize 确实没有改变 pane 尺寸（否则 snapshot 是合法的）。
      expectEqual(paneSize(tmuxInfo, marker), dimsBefore, 'redundant resize is genuinely a no-op (pane unchanged)');

      // 核心断言：no-op resize 后不得出现 snapshot。出现 ⇒ 服务端在补发整帧快照，
      // 客户端 replaySnapshot 清屏重建 ⇒「从上往下逐行刷新、底部是旧内容」。
      let repushed = false;
      try {
        await client.waitForBinary({ kind: 1, after: before, timeoutMs: 800 });
        repushed = true;
      } catch (e) { /* 无 snapshot = 符合预期 */ }
      const w = window(client, before);
      helpers.log(`no-op resize window: ${frameSummary(w).kinds}`);
      expect(!repushed, 'D-27: redundant same-size resize must NOT re-push a snapshot (clear-and-rebuild)');
      helpers.log(`no-op resize produced no snapshot (${w.filter((b) => b.kindName === 'delta').length} deltas only)`);
    } finally {
      client.close();
    }
  },
});

// [3] 阳性对照：真正改变尺寸的 resize 仍必须补发 snapshot（docs/protocol.md §4.2，
//     fix-term-residuals：SIGWINCH 重排后补全屏快照清残影）。同时证明本文件的
//     snapshot 探测是活的——[2] 的"无 snapshot"断言才不是"探测失灵"。
globalRegistry.define({
  name: 'd27:real-resize-still-repushes-snapshot',
  tags: ['d27', 'smoke'],
  description: 'D-27 阳性对照：真实改尺寸的 resize（60x20）仍补发 snapshot（文档化行为保留）',
  async fn(ctx, helpers) {
    const { port, token, workDir, tmuxInfo } = getEnvironment();
    const marker = `d27p_${Date.now().toString(36)}`;
    const sessionDir = createSession(tmuxInfo, workDir, marker);
    const { client, ref } = await connectAndList(port, token, sessionDir);
    try {
      await client.subscribe(ref, { rows: 30, cols: 80 });
      await sleep(400);
      expectEqual(paneSize(tmuxInfo, marker), '80x30', 'pane sized by subscribe');

      // 真实 resize 60x20：pane 尺寸改变 → 服务端必须补发 snapshot 作为事实回执。
      const after = client._binSeq;
      client.resize(ref, { rows: 20, cols: 60 });
      const repush = await client.waitForBinary({ kind: 1, after, timeoutMs: 3000 });
      expectBinarySnapshot(repush, ref, { label: 'real resize repush' });
      expect(repush.payload.length > 0, 'real resize snapshot must be non-empty');
      await sleep(400);
      expectEqual(paneSize(tmuxInfo, marker), '60x20', 'pane resized by real resize');
      helpers.log(`real resize repushed snapshot (pane 80x30 → 60x20)`);
    } finally {
      client.close();
    }
  },
});

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}
