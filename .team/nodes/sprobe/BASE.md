# 知识基底 · ledger.hl1.v1 / t.sprobe（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.sprobe · 服务端首帧根因探针（分支 pr/srv-probe，Go 红测）

根因线索（两处独立指认，都指向 server 订阅首帧路径）：
- 探针席 r14：server `handleSubscribe`「先 Resize 再 capture」的顺序问题（.team/nodes/hl1-probe/说明.md）
- 评审席独立读码复核属实：Resize→pipe→capture 顺序（.worktrees/hl1.rv.blank/.team/nodes/hl1-fix-rv/verdict.md 末段）
- 复现事实（.team/nodes/hl1-repro2/复现.md）：冷订阅一个**静止**（无新 delta）的 pane——静态 alt-screen /
  空闲真 Claude / 大滚回——>16s 无首帧；持续重绘的 pane 则正常。base(dc9aab11b) 与 new(main) 同形 ⇒ 存量缺陷。

## 你要做
在分支 pr/srv-probe 写一个 Go 探针测试（server 侧，货真价实连 handleSubscribe 路径）：
- 断言：订阅一个已有内容但**不再产生新输出**的 pane，应在 2s 内收到含字形内容的首帧快照。
- **先验红**：当前 main 上必须红（贴原始输出进说明.md）。
- 探针要区分两个同形世界：「快照从没发」vs「快照发了但内容为空」——写清命中哪种。
## 交付 .team/nodes/hl1-sprobe/说明.md
必含：status=done、探针=（测试文件#函数）、main红输出=、机理=（订阅后快照为何不发/为空）。
纪律：只提交到 pr/srv-probe；⛔ 不改产品代码（只加测试）；⛔ 不碰 main、不 push；临时文件只写 .team/nodes/hl1-sprobe/tmp/；如实报不可判是合法出口。

```

- write_paths: server/, .team/nodes/hl1-sprobe/
- read_paths: .team/nodes/hl1-repro2/复现.md, .team/nodes/hl1-probe/说明.md, .worktrees/hl1.rv.blank/.team/nodes/hl1-fix-rv/verdict.md
- 判据: A-sprobe-go, A-sprobe-doc

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
