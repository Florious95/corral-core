# 知识基底 · ledger.hl1.v1 / t.lsfix（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.lsfix · 修 listing 每 tick 全表 ps 风暴（契约 095，分支 pr/listing-ps-storm）

必读：requirement-base/entries/095-listing每tick全表ps风暴.md、.team/artifacts/audit-20260821-opus-findings.json（perf 维度四条，含实测复现）。
在 .worktrees/hl1.lsfix 建分支 pr/listing-ps-storm：
1. 先验红探针：构造 N 个 session 的 listing tick，断言全表 ps fork 次数 ≤1（当前 main 为 N+1 次 ⇒ 红）。
2. 修法方向（095）：一次 tick 一张 ps 表共享；识别结果按 (pid,starttime) TTL 缓存（修掉「缓存键被单 pid 覆盖」）；
   handleList 移出连接读循环（审计：它同步阻塞其后所有帧）；过滤与打标用同一张表（消除每 tick 假 ChangedSessions）。
3. server 全量 go test 不倒退。
交付 .team/nodes/hl1-lsfix/说明.md：status=done、根因=、修法=、先验红输出=、转绿输出=、fork次数前后=。
纪律同前。⚠️ 你与 sfix2 同在 server/ 模块：账本已用依赖串行，你会在它之后被派。

```

- write_paths: server/, .team/nodes/hl1-lsfix/
- read_paths: requirement-base/entries/095-listing每tick全表ps风暴.md, .team/artifacts/audit-20260821-opus-findings.json
- 判据: A-lsfix-go, A-lsfix-doc

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
- 标题引用条目：requirement-base/entries/095*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
