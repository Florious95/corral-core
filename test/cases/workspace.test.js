// workspace.test.js — 示例用例 3：工作区列表非空。
//
// 前置：runner beforeAll 已起隔离 tmux（socket 落 $TMUX_TMPDIR/tmux-<uid>/，name=iso）
// 且 daemon 的扫描面限定到隔离目录。本用例自建一个会话并注入标记文本，
// 然后通过 WS auth → list 拉取，断言：workspaces 非空、至少一个会话 ref 非空、
// 该 ref 可被订阅（binary snapshot 帧头校验）。
//
// 覆盖协议 §5.1（两级分组模型）与 §4.2 subscribe 语义的端到端握手。

const { globalRegistry } = require('../framework/registry');
const { AgentMirrorClient } = require('../framework/ws_client');
const {
  expectListing,
  expectTerminalContains,
  expectBinarySnapshot,
  expectFrame,
  expect,
} = require('../framework/assertions');

const { getEnvironment } = require('../framework/context');

globalRegistry.define({
  name: 'workspace:listing-non-empty-and-subscribable',
  tags: ['workspace', 'smoke'],
  description: '隔离 tmux 会话进入列表且可订阅（snapshot 帧解码成功）',
  async fn(ctx, helpers) {
    const { port, token, workDir, tmuxInfo } = getEnvironment();
    // 1) 在隔离 tmux 中创建一个会话并注入唯一标记，确保 listing 非空。
    const marker = `CASE_MARKER_${Date.now().toString(36)}`;
    tmuxInfo.tmux(['new-session', '-d', '-s', 'wks', '-c', workDir, 'sh -c "echo ' + marker + '; sleep 3600"']);
    helpers.log(`injected marker=${marker} into isolated tmux`);

    // 2) 连隔离 daemon，auth → list。
    const client = new AgentMirrorClient({ url: `ws://127.0.0.1:${port}/ws` });
    await client.connect();
    await client.auth(token);
    const listing = await client.list();
    const workspaces = expectListing(listing);

    // 3) 工作区列表非空，且至少一个会话。
    expect(workspaces.length > 0, 'expected at least one workspace in listing');
    const sessions = workspaces.flatMap((w) => w.sessions || []);
    expect(sessions.length > 0, 'expected at least one session across workspaces');

    // 4) 找到我们刚创建的那个会话（含 marker 的会话，cwd 匹配我们的 workDir）。
    const target = sessions.find((s) => s.cwd === workDir) || sessions[0];
    expect(target.ref && target.ref.length > 0, `session ref must be non-empty (got ${JSON.stringify(target.ref)})`);

    // 5) 订阅它，校验二进制 snapshot 帧：kind=1、ref 匹配。
    const bin = await client.subscribe(target.ref, { rows: 24, cols: 80 });
    expectBinarySnapshot(bin, target.ref, { label: 'snapshot' });
    expect(bin.payload.length > 0, 'snapshot payload must be non-empty');
    helpers.log(`subscribed ref=${JSON.stringify(target.ref)} snapshot bytes=${bin.payload.length}`);

    client.close();
  },
});
