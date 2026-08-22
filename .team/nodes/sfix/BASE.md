# 知识基底 · ledger.hl1.v1 / t.sfix（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.sfix · 修服务端订阅首帧（分支 pr/srv-first-frame）

输入：pr/srv-probe 的根因探针 + .team/nodes/hl1-sprobe/说明.md。
1. 把探针测试合入你的分支（⛔ 不许改探针本身）。
2. **修根因**：订阅静止 pane 时也必须及时发出含内容的首帧快照（handleSubscribe 的 Resize/capture 顺序或等 delta 的逻辑）。⛔ 不许「等到有 delta 才发」的症状层绕法。
3. 探针转绿 + server 全量 go test 不倒退。
## 交付 .team/nodes/hl1-sfix/说明.md
必含：status=done、根因=、修法=、先验红输出=、探针绿输出=。
纪律：只提交到 pr/srv-first-frame；⛔ 不碰 main、不 push；临时文件只写 .team/nodes/hl1-sfix/tmp/；⛔ 判据红了不许改判据；如实报不可判是合法出口。

```

- write_paths: server/, .team/nodes/hl1-sfix/
- read_paths: .team/nodes/hl1-sprobe/说明.md, .team/nodes/hl1-repro2/复现.md
- 判据: A-sfix-go, A-sfix-doc

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：internal/api
- 正向依赖（你消费的契约，只读）：go_internal_agentstate, go_internal_bridge, go_internal_discovery, go_internal_protocol
- **反向依赖（波及面 = 回归自查范围）**：go_cmd_agentmirrord

### 闭包架构卡内联

### Go · internal/api

- **职责**：Package api implements the service-side WebSocket API and the image upload endpoint, wiring together discovery and bridge (task ws-api).
- **导出面**：Discoverer, NewServer, NewStateProvider, Options, Server, StateProvider, TokenValidator
- **依赖边**：internal/agentstate, internal/bridge, internal/discovery, internal/protocol

### Go · cmd/agentmirrord

- **职责**：Command agentmirrord is the service-side daemon of AgentMirror (product github.com/agentmirror/agentmirror): a sidecar that mirrors the user's existing tmux sessions to the Android app over WebSocket.
- **导出面**：main
- **依赖边**：internal/api, internal/config, internal/pairing, internal/tsnetd

## 3. 需求基
- 标题引用条目：（无编号引用）
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
