// auth_reject.test.js — 示例用例 2：错误 token 被拒绝。
//
// 覆盖协议 §3/§4.2：错误 token → auth_ack ok:false + reason，随后服务端立即关闭
// 连接（客户端可把「auth 后立即断开」视作拒绝）。token 只上行一次、不回显、不落日志。

const { globalRegistry } = require('../framework/registry');
const { AgentMirrorClient } = require('../framework/ws_client');
const { expectAuthAck, expect } = require('../framework/assertions');

const { getEnvironment } = require('../framework/context');

globalRegistry.define({
  name: 'auth:wrong-token-rejected',
  tags: ['auth', 'smoke', 'negative'],
  description: '错误配对 token → auth_ack ok:false 且连接被关闭',
  async fn(ctx, helpers) {
    const { port, token } = getEnvironment();
    const client = new AgentMirrorClient({ url: `ws://127.0.0.1:${port}/ws` });

    // 记录连接关闭事件。
    const closes = [];
    client.on('close', (c) => closes.push(c));

    await client.connect();
    // 错误 token：保证与正确 token 不同。
    const wrongToken = token + '-WRONG';
    let rejected = false;
    let rejectedReason = null;
    try {
      await client.auth(wrongToken);
    } catch (err) {
      rejected = true;
      rejectedReason = err.message;
    }

    // 必须被拒绝（auth 抛错或随后连接关闭）。
    expect(rejected, `auth with wrong token should reject, but resolved (reason=${rejectedReason})`);
    // 服务端按 §4.2 在 auth_ack ok:false 后立即关闭连接。
    await waitFor(() => closes.length > 0, 3000, 'expected server to close connection after auth reject');
    client.close();
  },
});

function waitFor(pred, timeoutMs, msg) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const tick = () => {
      if (pred()) return resolve();
      if (Date.now() > deadline) return reject(new Error(msg));
      setTimeout(tick, 25);
    };
    tick();
  });
}
