# 知识基底 · ledger.prov.v1 / t.prov（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.prov · 白名单认出 `pi` 与 `cursor`（契约 086）

🔴 **用户原话（2026-08-20）**：「服务端把 Agent 和 Pi 都开一下。**agent 是 cursor**」

契约：`/Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/086-白名单认 pi 与 cursor.md`（**先完整读它，操作数都在里面**）

## 现状（leader 已实测，⛔ 不必重测，但可复核）

两个 CLI 已经起在专用 socket `/tmp/tmux-501/ta-user-agents`（session `agents`，window `cursor` / `pi`），**都活着**。
```
nodeprobe -S /tmp/tmux-501/ta-user-agents   →   "nodes": []      ← 一个都认不出
```

| window | pane 进程 comm（⛔ 只取 comm，未取 argv） | 基名 |
|---|---|---|
| `pi` | `pi` | **`pi`** |
| `cursor` | `/Users/alauda/.local/share/cursor-agent/versions/2026.08.11-e8db854/node` | **`node`** |

⇒ `pi` 不在表里；`cursor-agent` 在表里但**是死条目**——现实中 comm 基名是 `node`，永远匹配不到。

## 你要做什么

1. **`pi` 进白名单**：`pi` → `pi` → `Pi`（基名直接命中）
2. **让 `cursor` 能被认出**：需要一个不依赖 argv 的办法。候选**你自己定并给理由**：
   - 匹配 comm **完整路径**是否含 `/cursor-agent/`（不只看基名）
   - 或走 068 §8 已有的 identity-first / pane 标题路径
   - ⛔⛔ **绝不允许把裸 `node` 加进白名单**——那会把所有 node 系 CLI 全认成 cursor
   - ⛔⛔ **绝不允许读 argv**（凭据红线：argv 含席位明文 token）
3. Go 与 Rust **共用同一张 TSV**（表头注释明写 `Do not fork this table`），⛔ 不许各改一份

## 判据（⛔ 一个字不许改）

| id | 内容 |
|---|---|
| `A-pv-live` | 🔴 **断言世界变了**：`nodeprobe -S /tmp/tmux-501/ta-user-agents` 必须返回 **2 个节点**，provider 分别是 `pi` 与 `cursor`。**改前是 0，先跑一次留红证据。** ⛔ 单测绿而真 socket 上仍是 0 = 没做 |
| `A-pv-nobroad` | 🔴 **反向断言**：TSV 里⛔不得出现基名为 `node` 的条目（防过度放宽） |
| `A-pv-go` | `cd /Volumes/nvme/Projects/远程Agent安卓/server && go test ./...` 全绿（`table_test.go` 现断言 `rows=5`，加行后同步更新，⛔ 不许删这条断言） |
| `A-pv-rust` | `cd /Volumes/nvme/Projects/远程Agent安卓/tools/nodeprobe && cargo test` 全绿 |
| `A-pv-doc` | `说明.md` 非空，含你为 cursor 选的识别办法**及理由**、以及改前/改后 `nodeprobe` 两次读数 |

## ⛔ 不在你范围内
cursor pane 现在停在 `⚠ Workspace Trust Required`，**那是用户自己要按的**（授予代码执行权限）。
⛔ 你不许代按、不许绕过、不许改成自动信任。**它停在那里不影响本格判据**——
识别看的是进程，不是它跑没跑起来。

---
## 全格通用（违反任一条 = 本格红）

🔴🔴🔴 **开工第一件事**
```
cd /Volumes/nvme/Projects/远程Agent安卓 && pwd
```
`pwd` 必须输出仓根。**若输出里出现 `.worktrees/`，立刻 cd 回仓根**——
派单正文下方那段「## 工作目录」是框架自动附加的，**它是错的，以本条为准**。
⛔ 不要 `git worktree add`，⛔ 不要进 `.worktrees/`。
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件。
⛔⛔ **绝不碰用户真实 tmux（默认 socket）**，也⛔不许动 `/tmp/tmux-501/ta-user-agents` 上那两个 pane
（那是用户要用的，你只读它做判据）。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔ 禁读 `.team/current/profiles/*.env`。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不发进度/提问/完工通知。
唯一对外动作是干完调一次 `report_result`。卡住写进说明.md。
🔴 **先验红再改**：`A-pv-live` 改前必须先红一次（0 个节点），红的输出贴进说明.md。
🟢 **⛔ 不得倒退**：068 白名单语义（按 comm 过滤、不认领则落回 062 三态）、其余已判绿契约。

```

- write_paths: server/, tools/nodeprobe/, .team/nodes/prov-whitelist/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/086-白名单认 pi 与 cursor.md
- 判据: A-pv-live, A-pv-nobroad, A-pv-go, A-pv-rust, A-pv-doc

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
- 标题引用条目：requirement-base/entries/086*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
