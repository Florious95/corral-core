# agentmirror 自动化测试工程

完全独立于产品代码的测试工程。**产品不知道测试的存在**；测试单方向消费
`docs/protocol.md` 定义的 WebSocket 协议与 `server/` 的 daemon 二进制。

**写新用例前先读** [`docs/use-case-design.md`](../docs/use-case-design.md)：
需求基派生的 UC-ID、§11 历史缺陷回归账、§12 用户日组合场景。
测试层 L0 即本目录。`cases/*.test.js` 的 `name` 建议带对应 UC-ID
（如 `uc-p06:wrong-token-rejected`），旧名字不强制改。零件绿不能代替 DAY 组合。

## 快速开始

```bash
cd test
npm test              # 构建隔离 daemon + 起隔离 tmux + 跑全部用例 + 落 JSON 报告
npm run test:pairing  # 只跑 pairing 标签用例
npm run test:workspace
npm test -- --tag=auth,negative   # 按标签选择性执行（OR）
npm test -- --name=token          # 按名字子串过滤
npm test -- --keep                # 保留临时工作目录（排查用）
```

要求：Node.js >= 22（内置 WebSocket，零 npm 依赖）、Go（构建 daemon）、tmux。

## 目录结构

```
test/
├── package.json            # npm test 入口
├── runner.js               # 执行器：收集→过滤→起隔离环境→逐条跑→JSON 报告
├── framework/
│   ├── binary.js           # 二进制流帧编解码（protocol.md §6）+ ANSI 剥离
│   ├── ws_client.js        # WebSocket 协议客户端（§3–§4 全帧型）
│   ├── assertions.js       # 通用断言：帧/终端文本/截图尺寸
│   ├── fixtures.js         # 环境管理：隔离 daemon go build+起停、隔离 tmux
│   ├── reporter.js         # 结果持久化（JSON，支持趋势聚合）
│   ├── registry.js         # 用例注册 + 按标签选择
│   └── context.js          # runner↔用例共享环境句柄（打破循环依赖）
├── cases/
│   ├── pairing.test.js     # 例1：配对成功（auth → auth_ack ok）
│   ├── auth_reject.test.js # 例2：错误 token 被拒（ack ok:false + 关闭）
│   └── workspace.test.js   # 例3：工作区列表非空 + 会话可订阅
├── results/                # 运行报告（<runId>.json + latest.json；git 忽略）
└── README.md
```

## 隔离纪律（铁律）

- **env 净化**：`fixtureEnv()` 剔除全部 `TEAM_AGENT_*` 与 `TMUX` 变量，凡 spawn
  （daemon / tmux / go build）必用它，杜绝席位/框架环境泄漏进被测进程。
- **隔离 daemon**：`go build -o <tmp>/daemon/agentmirrord`，监听 `127.0.0.1` 的
  **高端口（>= 19983）**，显式 `-token`、独立 `-state-dir` / `-upload-dir`。
  绝不触碰生产 daemon（默认端口 9900 的实机连接）。
- **隔离 tmux**：独立 `TMUX_TMPDIR`，socket 落 `$TMUX_TMPDIR/tmux-<uid>/<name>`，
  **目录权限 0700**（tmux 要求 owner-only，否则报 "unsafe permissions"）。
- **daemon 扫描面限定**：daemon 环境设 `AGENTMIRROR_E2E_DISCOVERY_SOCKET_DIRS`
  指向隔离 socket 目录 —— daemon 只扫这个目录，**绝不枚举生产 `/tmp/tmux-<uid>`**。
- **scoped kill**：只杀自建 daemon 的 pid（SIGTERM→2s→SIGKILL）与自建 tmux
  socket（`kill-server`），禁 `pkill` 扫射。
- **收尾自证零残留**：`assertNoResidue()` 确认 daemon 进程与隔离 tmux server 均
  已消失，有残留打 RESIDUE WARNING。

## 写测试用例

用例文件放在 `cases/*.test.js`，用 `globalRegistry.define()` 注册：

```js
const { globalRegistry } = require('../framework/registry');
const { AgentMirrorClient } = require('../framework/ws_client');
const { expectAuthAck } = require('../framework/assertions');

globalRegistry.define({
  name: 'pairing:correct-token-accepted',
  tags: ['pairing', 'smoke'],
  async fn(ctx, helpers) {
    const { port, token } = require('../framework/context').getEnvironment();
    const c = new AgentMirrorClient({ url: `ws://127.0.0.1:${port}/ws` });
    await c.connect();
    const ack = await c.auth(token);
    expectAuthAck(ack, true);
    c.close();
  },
});
```

- `name` 唯一；`tags` 供选择性执行；`fn` 异步，断言失败抛 `AssertionError`。
- 环境句柄从 `context.getEnvironment()` 取（端口/token/workDir/tmuxInfo/daemon），
  也可用 `ctx.tmuxInfo` / `helpers.tmuxInfo` 操作隔离 tmux。
- 断言见 `framework/assertions.js`：`expectAuthAck` / `expectListing` /
  `expectBinarySnapshot` / `expectTerminalContains` / `expectTerminalNonEmpty` /
  `expectImageSize`（PNG 尺寸）等。

## 结果报告

每次运行写 `results/<runId>.json`（时间戳命名）与 `results/latest.json`。
`reporter.loadRuns()` 可聚合历史做趋势对比。报告含 filter / summary / 逐用例
status·duration·tags·error。

## 协议覆盖

- 控制帧：`auth` / `auth_ack` / `list` / `listing` / `subscribe` / `unsubscribe` /
  `input` / `input_ack` / `scrollback` / `resize` / `error`
- 二进制流帧：`snapshot(kind=1)` / `delta(kind=2)` / `scrollback(kind=3，含 12 字节
  元数据头)`；magic/version/ref 校验
- 契约依据：`docs/protocol.md`（人读规范）与 `server/internal/protocol/testdata/`
  （字节级夹具，协议的一部分）
