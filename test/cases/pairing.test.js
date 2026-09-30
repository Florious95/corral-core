// pairing.test.js — 示例用例 1：配对成功（auth → auth_ack ok）。
//
// 覆盖 TESTPLAN A3 的服务端语义（协议层配对）：正确 token 在有限时间内拿到
// auth_ack ok，连接保持。隔离 daemon 由 runner 的 beforeAll 起好（端口 >= 19983）。
//
// 隔离纪律：本用例只通过 ws_client 连隔离 daemon，不触碰生产 daemon/tmux。

const { globalRegistry } = require('../framework/registry');
const { AgentMirrorClient } = require('../framework/ws_client');
const {
  expectAuthAck,
  expectFrame,
} = require('../framework/assertions');

const { getEnvironment } = require('../framework/context');

globalRegistry.define({
  name: 'pairing:correct-token-accepted',
  tags: ['pairing', 'smoke'],
  description: '正确配对 token → auth_ack ok，连接保持',
  async fn(ctx, helpers) {
    const { port, token } = getEnvironment();
    const client = new AgentMirrorClient({ url: `ws://127.0.0.1:${port}/ws` });
    await client.connect();
    const ack = await client.auth(token);
    expectAuthAck(ack, true, { label: 'auth_ack' });
    // 认证通过后连接必须保持（服务端不关闭）。
    expectFrame(ack, 'auth_ack');
    client.close();
  },
});
