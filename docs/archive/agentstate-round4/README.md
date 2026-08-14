# agentstate 归档 round-4（058 强制前置：先归档回退，再重建）

> 归档动作：2026-08-15，任务 `t.archive`（账本 `ledger.state-detection.v1`），执行席 `r.dev-state`。
> 裁定依据：[`requirement-base/entries/058-状态检测先归档回退再重建.md`](../../requirement-base/entries/058-状态检测先归档回退再重建.md)（强制前置）、
> [`025-工作状态检测准确率.md`](../../requirement-base/entries/025-工作状态检测准确率.md)（三次修复三次失败）、
> [`docs/herdr-agent-state-study.md`](../../herdr-agent-state-study.md)（518 行调研）。
> 状态：**已回退归档，live 包为 unknown 占位 stub，待第四轮重建**。

## 一、归档了什么（文件 + 行数）

原 `server/internal/agentstate/` 全部 11 个 Go 文件，共 **1699 行**（与 058 记录一致）。字节级拷贝保留，未删一字：

| 文件 | 行数 | 角色 |
|---|---|---|
| `rules.go` | 219 | 表驱动规则引擎 + `spinnerFrames` 字形白名单 + `❯` 锚点区域限定 |
| `adapters.go` | 196 | claude/codex 适配器 + `stateFromTitle`（认字形）+ `blockedStateFromScreen` |
| `identify.go` | 267 | 进程树识别 agent 身份（ps + classifyArgv） |
| `sample.go` | 126 | `Sample` 输入契约 + `Registry`/`Detect`/`DetectForKind` |
| `track.go` | 36 | `Track`：working→idle ⇒ done 边 |
| `ansi.go` | 127 | ANSI 剥除 |
| `doc.go` | 16 | 包文档 |
| `adapter_test.go` | 208 | 适配器红测 |
| `anchor_red_test.go` | 134 | 锚点方案红测 |
| `identify_test.go` | 266 | 进程树识别测试 |
| `state_test.go` | 104 | Track/done 测试 |
| **合计** | **1699** | |

## 二、为什么被否定（用户裁定 + 实证）

**用户 2026-08-15 原话（058 出处）**：状态检查"基本上是**完全不可用、完全不准确**的状态"，动手解决前**必须回退与归档**。

三轮修复（025）全部是**往字形白名单里加/减字符**——每一轮都在同一个错误结构上加代码，而那个结构本身没有答案：

> **信息不在字形里，在导数里。三次都在调一张不含答案的白名单。**

实证（058 记录 + herdr 调研 §9/§10）：
- 白名单记的是 braille `⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏`，而 2026-08-15 用户截图显示 Claude Code 当前吐的是 **`◐`（工作中）/ `✳`（空闲）**——字形改版，白名单**静默失明**。
- 更硬的一击（herdr 调研 §9 实锤）：同一屏里完成态 `✻ Brewed for 6s` 与工作中 `✻ Galloping…` **共用同一个 `✻` 前缀**——任何"字形/前缀匹配"从根上无法区分完成态与工作中。
- 结论：被否定的是**决策函数**，不是**取数链路**。

## 三、哪些取数可复用（保留，不在本次归档范围）

**取数链路在 `server/internal/agentstate/` 之外，058 边界 1 明确不动**：

- `RecentOutput` 采集 — `server/internal/api/state_wiring.go` 的 `capturePaneOutput`（`tmux -S <socket> capture-pane -p -t <id> -e`），保留。
- `PaneTitle` 采集 — `server/internal/discovery/model.go` 的 `Pane.PaneTitle` 字段（`#{pane_title}`），保留。
- `pane_pid` / `pane_current_command` — `discovery.Pane.PanePID` / `Command`，保留（t.impl 若要恢复身份识别可用）。

## 四、哪些决策函数被否定（从 live 代码移除，作为根因探针输入）

- **`spinnerFrames` 字形白名单**（`rules.go`）——字形改版即静默失明；且同字形无法分辨完成态与工作中（§二实证）。**禁止再扩充字形表。**
- **`stateFromTitle` 认字形**（`adapters.go`）——`◐`/`✳`/braille 均不参与判定。
- **`rules.go` 全表 + `❯` 锚点区域**——锚点方案解决的是"残留文本"误判，不是"信息不在字形里"；一并归档，由第四轮决定是否复活。
- **`track.go` 的 done 边（working→idle ⇒ done）**——用户 2026-08-13 裁定"服务端取消完成，完成≡空闲"；`protocol.StateDone` 同步删除（本归档范围）。
- **`identify.go` 进程树身份识别**——本身是取数（解析 pane 跑哪个 CLI），但它是决策派发链的一环，一并归档；是否恢复由 t.impl 定。

## 五、归档后的 live 状态（边界 3：不许留会骗人的旧值）

`server/internal/agentstate/` 现为 **unknown 占位 stub**（`placeholder.go`）：保留 `api/state_wiring.go` 编译所需的类型表面
（`Registry` / `IdentifyInput` / `AgentKind` / `AgentKindClaude` / `DefaultRegistry` / `Identify` / `Track` / `Sample` / `State`），
**所有决策一律返回 `StateUnknown`**。依据 [[012]]「unknown 不计入」——兜得住判不出，兜不住判错。
**空窗期状态字段明确报 unknown，绝不回吐一个过期的 working/idle 骗人。**

**空窗期测试状态**（2026-08-15 归档后实测）：`internal/api` 三个集成测试转红，且这正是预期——
它们断言的是被归档的决策层（wrapper 树→blocked、title→working/idle、屏文本→working/blocked），
占位 stub 诚实报 unknown 所以断言落空。这**不是回归**，是「先删再做」清场后的**正当红测**（t.impl 的探针目标）：
- `TestStateWiringWrapperProcessTreeBlocksListing` — 断言 wrapper 树 + 屏文本判 blocked
- `TestStateProviderTitleSignalDrivesState` — 断言 PaneTitle 字形判 working/idle
- `TestConnectedIdleEconomySamplingRateFairnessAndVisibility` — 采样公平性/60s 可见性机制（机制本身仍工作），
  但断言 blocked/working 检测落地

四席各取所需：`t.oracle` 用本目录做根因探针，`t.impl` 重建后让这三个变绿。

## 六、这是回炉流程第 2 步的输入，不是垃圾桶

按 [[053]]/[[054]] 回炉流程，审查席（`t.oracle`）读本次被回退的 diff 反推根因，产出**根因探针**；
**回退后跑探针必须命中**，不命中说明诊断错了，不许往下走。本目录即探针的输入语料。

## 关联

[[025]] 工作状态检测准确率（三次修复三次失败的历史）、[[058]] 状态检测先归档回退再重建（本次裁定）、
[[053]] 携带问题上线的标准处置流程、[[054]] 回炉审查流程升级、[[012]] 工作区聚合状态规则（底层判错，聚合必跟着错）。
