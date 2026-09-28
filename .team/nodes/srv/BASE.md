# 知识基底 · ledger.refresh.v1 / t.srv（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
服务端实现「进菜单即时刷新」。契约 requirement-base/entries/069-进菜单必触发一次即时刷新.md。探针 .team/nodes/rf-probe/probe-rf.sh 只读，⛔ 不许改它让判据变绿。
①**一级**：handleList 必须触发一次**真实重扫**，不能只吃 ensureInitialScan 的缓存。先回缓存保证「立刻有内容」，重扫结果到达即推新列表——两者都要，不是二选一。
②**二级**：进入二级菜单必须触发一次即时刷新。⛔ 不能只靠 0→1 的唤醒——同一连接重新进入菜单（重订阅或显式 refresh 帧）也必须唤醒一次。
🔴 **不许把轮询打开**：「进入时刷新」是事件驱动的一次，061 的零订阅零轮询不得倒退。
🔴 **不许因刷新失败而清空列表**：tmux 不可达时保留缓存内容并按 061 失败可见规则提示。
配套 Go 测试名须含 TestRefreshOnOpen。产出说明写 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/rf-srv/说明.md。
🔴 worktree_id 只是并发互斥标签，不是 git worktree。**必须在仓根干活**。⛔ 不要 git worktree add。
🔴 静默纪律：不给 leader 发进度消息。唯一例外被卡住需裁定（class="blocking"）。
🔴 干完调一次 report_result，**不要传 task_id 参数**。
⛔ 禁止启动安卓模拟器。⛔⛔ 绝不碰用户真实 tmux（默认 socket）；tmux 实验自起隔离 server 并 list-sessions 自检。
⛔⛔ 遍历进程只取 comm，禁止取 argv。
🔴 **先完整读知识基底 .team/nodes/srv/BASE.md**（模块影响闭包现算产物：正向依赖=你消费的契约，反向依赖=你的回归自查范围）。⛔ 不读就动手 = 凭空猜架构。
🔴🔴 **静默纪律（用户 2026-08-19 令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send` 给 leader、不许发进度、不许发提问、不许发完工通知。唯一允许的对外动作是**干完调一次 `report_result`**（⛔ 不要传 task_id 参数）——那条走编排通道，不进 leader 的对话。被卡住也不要发消息：把卡点写进你的说明.md，用 report_result 的 status 表达，让判据和账本去说话。

```

- write_paths: server/, .team/nodes/rf-srv/
- read_paths: requirement-base/entries/069-进菜单必触发一次即时刷新.md, .team/nodes/rf-probe/, .team/nodes/srv/BASE.md
- 判据: A-s-test, A-s-suite, A-s-doc

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
- 标题引用条目：requirement-base/entries/069*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
