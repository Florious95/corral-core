# 知识基底 · ledger.v74.v1 / t.unk（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
收尾「新会话判未知」回归的 **Rust 侧**。契约 requirement-base/entries/074-无限刷新与未知回归收尾.md §2，根因见 068 §8。
Go 侧 leader 已改完并全绿；Rust 侧 `classify_for` 也已同步改好，但 `cargo test -q` 仍红：`footer rows 2 < 3` —— 语料 `tools/nodeprobe/fixtures/titles.tsv` 里 footer 行少一条。
⚠️ **成因交代**：leader 用 `git checkout titles.tsv` 回退时误删了一份未提交的 footer 语料，已凭报错原文恢复两条，**第三条内容不详**。
⇒ 按 `server/internal/api/l2detect_footer_test.go` 与 `tools/nodeprobe/src/classify.rs` 的**实际用例**把第三条补回来。⛔ **不要凭空编一条让计数达标** —— 那是骗判据。补不回来就发 `class="blocking"` 找 leader 裁定，halt 是默认。
🔴 注意 footer 语料的 payload 是**子串针**（`strings.Contains(footer, needle)`），不是整句样本。
判据：`cargo test -q` 全绿 + `go test ./... -count=1` 全绿，且两侧对同一语料判定一致。说明写 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/unk-fix/说明.md。
🔴 worktree_id 只是并发互斥标签，**不是 git worktree**。必须在**仓根**干活。⛔⛔ 绝对不要 `git worktree add` —— 上一轮有席位建了 wt15.ident，然后卡在「worktree 在 detached HEAD、仓根在 main」的矛盾里出不来。
🔴 ⚠️ **仓库工作区当前有未提交改动**（leader 手改的 detect.go / classify.rs / titles.tsv / provider_whitelist_test.go）。**在此基础上继续改，⛔ 不要 git checkout / git restore 任何文件** —— leader 就是这么误删了一份未提交语料。
🔴 静默纪律：不给 leader 发进度消息。干完调一次 report_result，**不要传 task_id 参数**。
⛔ 模拟器**已关闭**，本轮不要启动它（用户令：用完要关）。验收走单测 + 计数证据。
⛔⛔ 绝不碰用户真实 tmux。⛔⛔ 遍历进程只取 comm，禁止取 argv。
```

- write_paths: tools/nodeprobe/, server/, .team/nodes/unk-fix/
- read_paths: requirement-base/entries/074-无限刷新与未知回归收尾.md, requirement-base/entries/068-节点白名单按进程comm过滤.md, server/internal/api/, tools/nodeprobe/src/
- 判据: A-uk-rust, A-uk-go, A-uk-doc

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
- 标题引用条目：requirement-base/entries/068*, requirement-base/entries/074*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
