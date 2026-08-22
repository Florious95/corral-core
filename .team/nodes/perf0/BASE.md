# 知识基底 · ledger.hl1.v1 / t.perf0（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.perf0 · 打开速度 P0：量具+扫描帧出 readLoop（契约 098，分支 pr/open-latency-p0）

⚠️ 方案原文（必读，落点/判据/风险全在里面，⛔ 不自己另想方案）：.team/artifacts/open-latency-plan.md 的 P0-a 与 P0-b 两节。
要点：
- P0-a：ws_conn.go readLoop/handleFrame 逐帧 recv/start/done/queue_ms 日志（⛔ 不打 pane 内容/凭据）；
  scan.go 每 socket 耗时；proctree ps_ms。先验红判据：注入慢 Discover 的 `ws_frame_latency_test.go` 断言 queue_ms>700（今天红=缺陷在）。
- P0-b：Level2Subscribe 移出 readLoop（List 在 pr/listing-ps-storm 已 go 化——先 `git merge pr/listing-ps-storm` 进你的分支再干）；
  Discover errgroup 并发(上限8)、死 socket 超时 5s→500ms；proctree readProcTable 出 f.mu 临界区。
- 修完同测试断言 queue_ms<50 转绿；DiscoverWithDirs 20 socket(15 stale)<1s；一次 tick 全表 ps ≤1。
- 并发 List 回复不发倒退 seq 的测试锁住（方案里的风险条）。
交付 .team/nodes/hl1-perf0/说明.md：status=done、先验红输出=、转绿输出=、各判据输出=、风险自查=。
纪律：分支 pr/open-latency-p0（基于 main，先 merge pr/listing-ps-storm；冲突如实报 blocked ⛔ 不硬解）；⛔ 不碰 main、不 push；临时文件只写 .team/nodes/hl1-perf0/tmp/；如实报不可判是合法出口。

```

- write_paths: server/, .team/nodes/hl1-perf0/
- read_paths: .team/artifacts/open-latency-plan.md, requirement-base/entries/098-会话打开速度方案.md, .team/nodes/hl1-probe16/说明.md
- 判据: A-perf0-go, A-perf0-doc

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
