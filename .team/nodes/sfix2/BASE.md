# 知识基底 · ledger.hl1.v1 / t.sfix2（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.sfix2 · 返工 pr/srv-first-frame：首帧顺序（契约 092 §8）

必读：.team/artifacts/audit-20260821-opus-findings.json（server 维度与 branches 维度对该处的两份分析 + 一份相反读码的驳回意见）、.team/nodes/hl1-sprobe/说明.md。
第一步：在封版 sha f116dd8d1 上裁决两位审计谁读对了（capture/Resize/Subscribe 真实顺序），把裁决写进说明.md。
然后在分支 pr/srv-first-frame 上继续改（同一分支，新 commit 由 leader 封版）：
- 目标不变量：①订阅静止 pane 2s 内收到含字形首帧；②**会重绘的 TUI 的 SIGWINCH 重绘字节不许丢**（pipe 必须在任何会触发重绘的动作之前挂上）；③首帧几何必须是 Resize 后的（参照 handleResize 注释：tmux 同步 reflow，Resize 后 capture 内容正确）。
- 探针补第二世界：现探针只钉冻结 TUI，加一个「trap WINCH 后重绘」的 TUI 用例，断言重绘字节到达客户端。
- 先验红：新探针在当前分支上必须红（贴输出）。
交付 .team/nodes/hl1-sfix2/说明.md：status=done、裁决=、顺序=、先验红输出=、转绿输出=。
纪律：只在 .worktrees/hl1.sfix2 里干（checkout pr/srv-first-frame）；⛔ 不碰 main、不 push；临时文件只写 .team/nodes/hl1-sfix2/tmp/；判据红不许改判据；如实报不可判是合法出口（那会红，leader 处理）。

```

- write_paths: server/, .team/nodes/hl1-sfix2/
- read_paths: .team/artifacts/audit-20260821-opus-findings.json, .team/nodes/hl1-sprobe/说明.md, requirement-base/entries/092-会话页白屏回归与两处简陋UI.md
- 判据: A-sfix2-go, A-sfix2-doc

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
