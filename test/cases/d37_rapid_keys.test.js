// d37_rapid_keys.test.js — D-37 红测（test/ 框架协议层半边）：连续快速发两个 input keys
// （Esc+Esc）必须各自收到 input_ack（req_id 独立路由）。
//
// D-37 goal（taskbook）：特殊键条不支持连按。MVP 方案已出（.team/evidence/research-keybar-rapid.md）：
// conn 返回 req_id + VM 键去闸按 req_id 路由 + UI 键条放开。
//
// 阻塞点在哪一层（调研文档 §1/§2 已实锤）：
//   * 服务端（ws_conn.go readLoop）**单协程串行**读帧 + WebSocket over TCP 保序 ⇒ 连发
//     N 个 input.keys 帧必然按到达顺序逐个注入 tmux、逐个回 input_ack——服务端**零改动**，
//     连按在协议层不被阻塞（本文件实测：两帧背靠背发、不等 ack，两帧都收到 ok:true）。
//   * 客户端（SessionViewModel.sendKey）用单一 InputStatus.Sending 闸合并草稿+键，在途
//     最多一个——连按的第二键被 VM 直接吞掉（return），根本不发帧。这才是 D-37 的「阻塞」。
//
// 因此本文件在协议层锁的是**服务端对连按的保证**（D-37 客户端修复所依赖的下游契约）：
//   ① 背靠背连发两个 input.keys（不等第一个 ack）→ 两个 input_ack 都到达；
//   ② 两 ack 都 ok:true（字节确实进面板）；
//   ③ 两 ack 的 req_id 各自对应（单调递增、互不相同）——客户端按 req_id 路由的前提
//      （§5.1 conn 层返回 req_id + pendingInputs 每 req 独立簿记）。
// 客户端 VM 侧的红测（sendKey 去 Sending 闸）在 Kotlin 层（SessionViewModelTest），
// 本文件不覆盖——协议层证明服务端绝不阻塞第二个 ack，修复只需放开客户端闸。

const fs = require('fs');
const path = require('path');
const { globalRegistry } = require('../framework/registry');
const { AgentMirrorClient } = require('../framework/ws_client');
const { expectListing, expect, expectEqual, expectFramePayloadField } = require('../framework/assertions');
const { getEnvironment } = require('../framework/context');

function createSession(tmuxInfo, workDir, marker) {
  const sessionDir = path.join(workDir, marker);
  fs.mkdirSync(sessionDir, { recursive: true });
  tmuxInfo.tmux(['new-session', '-d', '-s', marker, '-c', sessionDir, 'sh -c "sleep 3600"']);
  return sessionDir;
}

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

// D-37 协议层红测：连发两个 Esc（背靠背，不等 ack）→ 两个 input_ack 都到、都 ok、
// req_id 独立且单调。今日服务端已满足（绿色契约锁）；若未来服务端被改成串行阻塞
// （等前一个 ack 才读下一帧）→ 本用例立即红，锁死「连按不被服务端阻塞」。
globalRegistry.define({
  name: 'd37:rapid-two-input-keys-both-acked',
  tags: ['d37', 'input'],
  description: 'D-37：连发两个 input.keys(Esc+Esc) → 两个 input_ack 都到、都 ok、req_id 独立',
  async fn(ctx, helpers) {
    const { port, token, workDir, tmuxInfo } = getEnvironment();
    const marker = `d37_${Date.now().toString(36)}`;
    const sessionDir = createSession(tmuxInfo, workDir, marker);
    const { client, ref } = await connectAndList(port, token, sessionDir);
    try {
      await client.subscribe(ref, { rows: 24, cols: 80 });
      await sleep(200);

      // 记录当前 req_id 基线（客户端自动递增 nextReqId，订阅前已用 1）。
      const baseReqId = client.nextReqId;

      // 背靠背连发两个 Esc，**不等第一个 ack**——D-37 的「连按」时序。
      // ws_client.input 内部各自独立 req_id（nextReqId++），连发即双帧在途。
      const p1 = client.input(ref, null, { keys: ['esc'] });
      const p2 = client.input(ref, null, { keys: ['esc'] });

      // ① 两个 ack 都必须到达（任一超时即红）。
      const ack1 = await p1;
      const ack2 = await p2;

      // ② 都 ok:true（字节确实进面板）。
      expectFramePayloadField(ack1, 'ok', true, { label: 'd37 ack1' });
      expectFramePayloadField(ack2, 'ok', true, { label: 'd37 ack2' });

      // ③ req_id 各自对应且单调递增、互不相同——按 req_id 独立路由的前提。
      const r1 = ack1.payload.req_id;
      const r2 = ack2.payload.req_id;
      helpers.log(`d37 ack req_ids: ack1=${r1} ack2=${r2} (base=${baseReqId})`);
      expectEqual(r1, baseReqId, 'd37 ack1.req_id matches first input request');
      expectEqual(r2, baseReqId + 1, 'd37 ack2.req_id matches second input request');
      expect(r1 !== r2, `d37 the two acks must have distinct req_ids (got ${r1}, ${r2})`);

      helpers.log(`d37 both Esc acks delivered: req_id ${r1} & ${r2}, ok:true`);
    } finally {
      client.close();
    }
  },
});

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}
