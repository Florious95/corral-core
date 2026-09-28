// d38_resume.test.js — D-38 红测（test/ 框架半边）：订阅首帧 snapshot 必须锚定终端底部。
//
// 场景（后台返回显示半截，必现）：客户端后台返回/重连时，服务端经 subscribe 重发一帧
// snapshot（004 无状态重放，handleSubscribe 语义），客户端 replaySnapshot 清屏重建。
// 若这帧 snapshot 不是"当前可见屏"（底部锚定），客户端重放后 viewport 必然不在底部，
// 对话显示在半截。本用例在协议层锁住 D-38 的服务器侧契约：
//   connect → subscribe → 收 snapshot → 断言 snapshot 即终端当前可见屏（底部锚定、
//   全屏高），末行 == pane 最末输出行，首行是中间序号行（证明是整屏而非裁切尾巴）。
// 客户端侧红测见 Kotlin：TermViewPresenter.snapshotReplayResetsViewportToBottomAfterHistoryLock
// 与 SessionViewModelTest.snapshotReplayOnResumeRestoresFollowingBottom。

const fs = require('fs');
const path = require('path');
const { globalRegistry } = require('../framework/registry');
const { AgentMirrorClient } = require('../framework/ws_client');
const { splitLines } = require('../framework/binary');
const {
  expectListing,
  expectBinarySnapshot,
  expect,
  toText,
} = require('../framework/assertions');
const { getEnvironment } = require('../framework/context');

// 会话内输出：先铺满 120 行序号，最后一行是唯一底部标记。订阅（客户端尺寸重排）后，
// 当前可见屏的末行必须是该标记 —— 否则 snapshot 没锚定底部（客户端重放即"半截"）。
const BOTTOM_MARKER = 'D38_BOTTOM_MARKER';
const SEQUENCE_LINES = 120;
const SUBSCRIBE_ROWS = 24;
const SUBSCRIBE_COLS = 80;

globalRegistry.define({
  name: 'd38:subscribe-snapshot-anchors-bottom-of-terminal',
  tags: ['d38', 'snapshot', 'smoke'],
  description: 'D-38：订阅首帧 snapshot 锚定终端底部（末行=最末输出、全屏高非半截）',
  async fn(ctx, helpers) {
    const { port, token, workDir, tmuxInfo } = getEnvironment();
    const marker = `d38_${Date.now().toString(36)}`;
    const sessionDir = path.join(workDir, marker);
    fs.mkdirSync(sessionDir, { recursive: true });
    // 用脚本文件承载多行命令，避免 tmux new-session 的引号嵌套（隔离 tmux 无交互 shell）。
    const scriptFile = path.join(sessionDir, 'd38.sh');
    const script = [
      '#!/bin/sh',
      `i=0; while [ $i -lt ${SEQUENCE_LINES} ]; do i=$((i+1)); echo line-$i; done`,
      `echo ${BOTTOM_MARKER}`,
      'sleep 3600',
      '',
    ].join('\n');
    fs.writeFileSync(scriptFile, script);
    fs.chmodSync(scriptFile, 0o755);
    tmuxInfo.tmux(['new-session', '-d', '-s', marker, '-c', sessionDir, scriptFile]);
    // 等输出落定（120 行打印微秒级，500ms 足够；避免订阅瞬间捕获滚动态半截）。
    await sleep(800);

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

    // 订阅：客户端尺寸 80x24 → pane 重排；首帧 snapshot 是客户端重放的地基（004）。
    const bin = await client.subscribe(target.ref, { rows: SUBSCRIBE_ROWS, cols: SUBSCRIBE_COLS });
    expectBinarySnapshot(bin, target.ref, { label: 'd38 snapshot' });
    expect(bin.payload.length > 0, 'd38 snapshot payload must be non-empty');

    const lines = splitLines(toText(bin.payload));
    expect(lines.length >= 2, `d38 snapshot expected multi-line visible screen, got ${lines.length} line(s)`);
    // D-38 核心：末行（去尾空行后）必须 == 底部标记 —— snapshot 锚定终端底部，
    // 客户端 replaySnapshot 重放后即落在最新输出（viewport 底部）。
    const last = lines[lines.length - 1].trim();
    expect(
      last.includes(BOTTOM_MARKER),
      `snapshot last line must be the bottom marker "${BOTTOM_MARKER}", got ${JSON.stringify(last)}`,
    );
    // 全屏高（非半截）：订阅 24 行，snapshot 应接近全屏（≥20 行）。
    expect(
      lines.length >= SUBSCRIBE_ROWS - 4,
      `d38 snapshot should be near full screen height (got ${lines.length} lines for ${SUBSCRIBE_ROWS} requested)`,
    );
    // 首行是中间序号行而非顶部/标记行：证明是"当前可见屏"整屏，不是裁切的尾巴。
    expect(/^line-\d+/.test(lines[0].trim()), `d38 snapshot first line should be a sequence line, got ${JSON.stringify(lines[0])}`);

    helpers.log(`d38 snapshot bottom-anchored: ${lines.length} lines, last=${JSON.stringify(last)}`);
    client.close();
  },
});

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}
