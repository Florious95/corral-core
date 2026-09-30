# herdr 状态检测机制研究 · study-herdr-agent-state

> 调研者：w-librarian（库务/账本车道）
> 调研对象：herdrdev/herdr 如何判定 agent CLI 工作状态（FIELD.md 指定仓库）
> 约束：只调研不施工；契约级议题（contention: contract），定夺前相关模块不动
> 外部源码 clone 到 /tmp 只读研究，用完已清理，未落进本工程
> 许可证：herdrdev/herdr = Apache-2.0（可借鉴算法/规则思想）；herdr-remote = AGPL-3.0（只借鉴模型，不复制代码）

---

## 〇、TL;DR 与裁定拆分（2026-08-13 leader 裁定）

**herdr 判定状态也是「区域文本匹配 + OSC title/progress」为主，没有魔法般的进程信号进状态判定。**
它比我们强的不是规则本身（我们的 adapters.go 本来就是从它派生的），而是**时序/仲裁层**。

**leader 裁定（msg_517d9b3d7bb0）**：herdr 研究拆成**两半**——

- **技术判据（leader 已批，可施工）**：① working/idle 判定不再依赖 done 类文本，字形白名单不再扩充（✳/◐ 退场），working 保留 braille spinner；② 采纳时序确认（working→idle 需连续多次采样 + 时间窗），**但参数须按我们自己的采样周期推导，不许照抄 herdr 的 3次/700ms**；③ OSC 9 progress 采集**先验证 tmux 透传再设计**（w-dev-repaint 已证 tmux 会吞 capture-pane 路径的 2026，OSC 9 完全可能同理）。
- **产品语义（待用户裁定）**：「done = idle + 用户未 seen」改的是 App **显示什么**，引入已读/未读概念——这是产品行为不是修 bug，leader 不去替用户决定。

**顺序调整（leader 裁定 2026-08-13，见 §8.4）**：OSC 9 **降级为后置**，先落 4.A 前两条（字形白名单退场 + 时序确认，只靠我们自己的采样即可实现）；误检消失后再看是否需要 OSC 9。一般性判断：**优先做「只依赖自己」的判据，把「依赖对方主动配合」的判据放后面。**

**下文结构**：§1 机制、§2 许可、§3 现状差距为事实；§4 建议判据分「4.A 技术判据（可施工）」与「4.B 产品语义（待用户）」两半；§5 字形白名单退场；§6 可移植性；§7 出处。§8 为 OSC 9 透传验证方案 + 顺序调整（§8.4）。

---

## 一、herdr 的实际机制（数据来源 / 信号 / 判据）

### 1.1 状态模型：四态，无 done

`src/detect/mod.rs` AgentState 枚举（APACHE-2.0，原文摘录）：
```rust
pub enum AgentState {
    /// Agent finished, prompt visible, nothing happening.
    Idle,
    /// Agent is actively working/processing.
    Working,
    /// Agent needs human input and is blocked on a response.
    Blocked,
    /// Plain shell or unrecognized program.
    Unknown,
}
```

**没有 `Done`。** `Done` 是 UI/API 层从 `(Idle, seen=false)` 派生的（`src/app/agent_view.rs:393`、`src/app/api_helpers.rs:104`）：
```rust
fn status_name(state: AgentState, seen: bool) -> String {
    let status = match (state, seen) {
        (AgentState::Idle, false) => AgentStatus::Done,   // 未看 + idle = 完成
        (AgentState::Idle, true)  => AgentStatus::Idle,    // 已看 + idle = 空闲
        (AgentState::Working, _)  => AgentStatus::Working,
        (AgentState::Blocked, _)  => AgentStatus::Blocked,
        (AgentState::Unknown, _)  => AgentStatus::Unknown,
    };
    ...
}
```

**这是最关键的一条**：herdr 完成/空闲的分界是**用户是否 seen**，不是输出文本。`Brewed for 42m 3s` 和 `Churned for 3m 37s` 在 herdr 里**都是 Idle**——它们永远不会被判成 Working。

### 1.2 判据引擎：区域文本规则 + OSC 信号

`src/detect/manifest.rs` 的 `detect_agent_with_osc`（261-284 行）接收三个输入：
- `screen_content`：pane 屏幕文本（`detection_text()` 采集）
- `osc_title`：OSC title（`agent_osc_title()`）
- `osc_progress`：OSC 9 progress（`agent_osc_progress()`）

规则表（`src/detect/manifests/claude.toml`）按 `region` 匹配，region 有：`osc_title`、`osc_progress`、`bottom_non_empty_lines(5)`、`after_last_horizontal_rule`、`prompt_box_body`、`whole_recent`、`after_last_prompt_marker` 等。每规则带 `priority`、`state`、`visible_*` 标志。**这和我们 rules.go 的表驱动第一匹配获胜完全同构**——因为我们的 adapters.go 就是从 herdr 派生的（Apache-2.0 合规）。

### 1.3 结构性信号（不刮屏的那部分）

herdr 有三类非文本信号，**但都不进状态判定，只用于上层发布仲裁**：

| 信号 | 来源 | 用途 |
|---|---|---|
| `foreground_pgid` | `tcgetpgrp(fd)`（pty）+ Linux `/proc/<pid>/stat` 的 tpgid / macOS `e_tpgid`（`src/platform/*.rs`） | 识别前台作业 / 前台 agent 身份 |
| `process_exit_reported` | `pending_foreground_shell_clear`（前台 shell 退出） | 进程退出后清 agent 身份 |
| OSC 9 progress（`^4;0`） | CLI 发出的进度转义 | **idle 信号**：进度归零 = 无活动 |

关键：**进程状态信号（pgid / 退出）用于「agent 身份识别」和「进程生命周期」，不用于「工作状态判定」**。工作状态仍由文本规则 + OSC 决定。这是与我们 FIELD.md 假设不同的地方——herdr 没有「用进程状态直接判 working/idle」。

### 1.4 时序仲裁（herdr 真正领先我们的地方）

`src/pane/agent_detection.rs` 的 `PendingIdleConfirmation`：
```rust
// working→idle 不立即发布，需：
//   AGENT_PENDING_IDLE_RECHECK = 100ms
//   AGENT_PENDING_IDLE_CONFIRMATIONS = 3（连续 3 次）
//   AGENT_PENDING_IDLE_CAP = 700ms（窗口上限）
fn should_hold_working_to_idle(...) -> bool {
    let is_working_to_plain_idle = previous == Working
        && next == Idle
        && !next.visible_idle
        && !next.visible_blocker
        && !agent_changed
        && !process_exited;
    ...
}
```

语义：working→idle 必须连续 3 次采样、700ms 窗口内确认，且不是 agent 切换/进程退出，才发布。**防「短暂停顿 / 一帧空白」误判为完成。**

---

## 二、许可证结论

| 仓库 | 许可证 | 可否引用 |
|---|---|---|
| `herdrdev/herdr` | **Apache-2.0**（Cargo.toml `license = "Apache-2.0"`，LICENSE 全文为 Apache License 2.0） | ✅ 可借鉴算法、规则思想，合规使用（保留版权声明 + LICENSE + 标注修改） |
| `dcolinmorgan/herdr-remote` | **AGPL-3.0-or-later**（dual-licensed，开源侧为 AGPL） | ⚠️ 只借鉴模型，**不复制代码**（本工程 GPL 隔离红线） |

我们现状（adapters.go）引用的是 herdrdev/herdr（Apache-2.0），许可证合规。**herdr-remote 是 AGPL，不得复制其代码**，仅可参考其「手机驱动 agent」的产品形态。

---

## 三、我们现状与差距

### 3.1 我们现在的实现（`server/internal/agentstate/`）

- **判据**：`rules.go` 表驱动规则，匹配 `RecentOutput` 尾窗口文本（stripANSI 后）+ `PaneTitle`（OSC title，stateFromTitle 读 braille spinner / ✳）。
- **状态**：五态（working/idle/blocked/done/unknown）——`protocol/state.go` 定义 `StateDone`，是**独立的第五态**。
- **done 判定**：`track.go` 的 `Track(prev, sample)`：仅 `prev=working && 当前=idle` 时返回 `Done`。
- **进程信号**：`identify.go` 用 `ps` 进程树识别**agent 身份**（claude/codex），不判状态。
- **OSC progress**：**无**。不采集 OSC 9 progress。
- **时序**：`LastOutputAge` 字段存在但**无规则消费**（adapter_test.go:151 `TestLastOutputAgeDoesNotChangeDecision` 明确固定「决策不随 age 变」）。

### 3.2 与 herdr 的差距（按重要性）

| # | 差距 | herdr | 我们 | 影响 |
|---|---|---|---|---|
| 1 | **done 模型** | 四态，Done 是 `(Idle, !seen)` 派生 | 五态，`StateDone` 独立 + Track 过渡近似 | **用户批评的核心**：我们靠文本判断「完成」，herdr 靠「用户看过没」 |
| 2 | **OSC progress** | 采集 OSC 9，`^4;0` 是 idle 信号 | 不采集 | 缺一个「进度归零=空闲」的结构性信号 |
| 3 | **时序稳定性** | working→idle 需 3 次确认 + 700ms 窗口 | 无（单次采样即发布） | 短暂停顿会误判为 done |
| 4 | **seen 状态** | UI 层追踪用户是否查看 | 无 | 无法表达「已完成但用户没看」 |
| 5 | **visible_* 标志** | 区分状态与状态可见性 | 无 | 导航过滤 / 通知触发用 |

### 3.3 我们的规则 vs herdr 规则（同源，细节差异）

我们的 claudeRules 与 herdr claude.toml 高度同构（permission box、`esc to interrupt`、braille spinner、✳ idle），因为同源派生。**差异不在规则内容，在仲裁层**。

---

## 四、建议的判据（能解释为何不会把 Brewed/Churned 判成 working）

> 判据必须满足 FIELD.md 要求：能说明为什么不会把 `Brewed for 42m 3s` / `Churned for 3m 37s` 两个完成态判成 working。
> **2026-08-13 按 leader 裁定拆两半**：§4.A 技术判据（已批，可施工）；§4.B 产品语义（待用户裁定）。

### 4.A 技术判据（leader 已批，可施工）

#### 4.A.1 核心：working/idle 判定不依赖 done 类文本

- `working` 判定只靠 **braille spinner / `esc to interrupt` / `⏹`**（working 信号）；
- `Brewed/Churned` 输出时**这些信号都不存在** → 判 `idle`，**不是 working**；
- 文本匹配根本不参与「完成」判定。

这从原理上消灭了「完成态误判为 working」——working 的判定条件在 Brewed/Churned 输出时**都不存在**。

#### 4.A.2 采纳时序确认（防「该 idle 时误报 working」）

`working→idle` 不立即发布，需**连续多次采样 + 时间窗**确认。**这是直接解决用户报的「已停止工作却显示工作中」的关键**——误检往往是单次采样抖动。

**⚠️ 参数不许照抄 herdr 的 3次/700ms**。那是 herdr 的采样周期（其检测循环粒度）下的值；我们的采样周期、tmux 捕获延迟都不同。必须按我们的参数推导：

**我们的实际采样参数**（`server/internal/api/state_wiring.go`）：
- `defaultStateRefreshTTL = 1s`（状态刷新最短间隔，每个 pane 的 refresh 受此节流）
- `defaultStateDispatchInterval = 250ms`（dispatch 扫描间隔）
- `defaultStateSamplingBudget = 3s`（单次采样上限）
- 状态发布依赖 ttl 节流 + 后台 refresh，**非每 250ms 必采一帧**

**建议的时序确认参数**（推导依据，非照抄 herdr）：
- **确认次数 N = 2**：因为我们的 ttl=1s，一次 refresh 已是一秒的稳定采样；连续 2 次 refresh（≈2s）判 idle，足以滤掉「调工具间隙」的单帧停顿，又不会把真正的快速完成拖得太久。
- **时间窗 ≈ 2×ttl = 2s**：与「连续 N 次 refresh」自洽（每次 refresh 间隔 ≥1s），窗口 = 2s 表示「连续两次采样都读 idle」。herdr 的 700ms 是因为它采样更密；我们用 2s 对应我们的 1s 节流。
- **不做成硬等待**：2 次确认是「窗口内连续 2 个采样点判 idle」，不是阻塞 2s 无输出。与 requirement 003/008 同步、非阻塞一致。
- **边界**：若 ttl 调整，N 与窗口应随之缩放（保持「≈2 次 refresh」语义），参数集中在常量处便于维护。

> 依据链：`state_wiring.go:66` defaultStateRefreshTTL=1s、`:73` dispatchInterval=250ms、`:84` pruneAge=60s。采样刷新被 ttl 节流，故「连续 2 次 refresh 判 idle」≈2s 稳定窗口。最终参数须由开发席按实测采样抖动校准（模拟器红测锚定），此处给推导起点。

#### 4.A.3 OSC 9 progress 采集 —— ⚠️ 先验证透传再设计（见 §8）

tmux 对 OSC 9 的透传**未验证**。w-dev-repaint 已证 tmux 会吃掉 capture-pane 路径的 2026（`capture-pane -e` 丢转义），OSC 9 完全可能同理。**在验证透传之前，不设计 OSC 9 采集方案**。验证方案见 §8，验证通过后才立项。

#### 4.A.4 明确不做：不把进程信号直接当状态

herdr 也不这么做（进程信号只用于身份/生命周期）。我们同样不引入「进程存在=working」——这会把「CLI 挂着等输入」误判成 working。

#### 4.A.5 判断链演示（Brewed/Churned 场景）

```
输入：屏幕含 "✳ Brewed for 42m 3s"（或 "Churned for 3m 37s · 1 shell still running"）
判定（技术判据，不需用户）：
  1. osc_title 无 braille spinner（✳ 是 idle 前缀，不是 working）
  2. 屏幕无 "esc to interrupt" / "⏹"（working 判定条件不存在）
  3. 规则命中 idle（✳ 前缀 / 无 working 信号）
  4. → 状态 = Idle（时序确认：连续 2 次 refresh 仍 idle 才发布）
  5. App 派生（§4.B 已裁定）：服务端 working→idle 转变 → 置 unread（显示「完成」）；用户看过 → 清 unread（显示「空闲」）
```

**Brewed/Churned 从文本到状态全程不经过 working**，故不可能误判。**服务端只报 idle，不报 done**——「完成」由 App 的 unread 布尔量表达。

#### 4.A.6 规则集重审（服务端取消完成态后）—— 2026-08-13 用户裁定后

**核心判断**：服务端不再产出 done 后，**所有为「识别完成态」而存在的逻辑全部可删**。逐条核实现状：

**① track.go 的 done 派生 —— 删**
`Track(prev, sample)`：`prev=working && 当前=idle` → `StateDone`。用户裁定「服务端取消完成，完成≡空闲」→ **整个 done 分支删除**，Track 退化为纯 Detect（或直接不再需要 Track）。`protocol.StateDone` 常量、`listing.go statePriority` 的 done 分支同步移除。

**② 服务端判 working 的规则 —— 保留但收窄**
现状 `adapters.go`：
- `claude-working-action-bar`（`esc to interrupt` / `⏹`）—— **保留**（正确 working 信号）；
- `claude-working-spinner`（braille spinner 帧）—— **保留**（正确 working 信号）；
- `codex-working-status`（`• Working (…) · esc to interrupt`）—— **保留**；
- `codex-working-spinner` —— **保留**。
**这些规则本意是判 working，不是判完成**。误检根因是**字形白名单污染**（leader 曾把 ✳/◐ 加进 spinnerFrames），不是规则本身——见⑤。

**③ 服务端判 idle 的规则 —— 保留**
`claude-idle-rest-bar`（`bypass permissions on`）、`claude-idle-prompt`（`❯`）、`codex-idle-prompt`。**保留**（正确 idle 信号，Brewed/Churned 就是判到这里）。

**④ blocked 规则 —— 保留（待 leader 裁服务端是否还需要）**
`claude-blocked-permission-box`、`codex-blocked-approval`。**pending**：leader 已问用户「blocked 态服务端还要不要」，未回。若保留则这条继续用；若并入「非工作中」则删。

**⑤ 字形白名单清理 —— 关键改动**
当前 `spinnerFrames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"`（纯 braille，正确）。**✳/◐ 不得加入**——它们是 idle 字形（✳ 是 idle 前缀，◐ 是 Claude Code 2.1.228 的 busy spinner 半圆，herdr 把它放 working 但**我们实测用户场景它出现在完成态**，见 FIELD.md）。**白名单保持纯 braille，不扩充**。若历史曾混入 ✳/◐ 则回退。

**删除后剩余规则集**（服务端）：
| 适配器 | 规则 | 状态 | 处置 |
|---|---|---|---|
| claude | blocked-permission-box | blocked | 保留（待裁） |
| claude | working-action-bar | working | 保留 |
| claude | working-spinner | working | 保留 |
| claude | idle-rest-bar | idle | 保留 |
| claude | idle-prompt | idle | 保留 |
| codex | blocked-approval | blocked | 保留（待裁） |
| codex | working-status | working | 保留 |
| codex | working-spinner | working | 保留 |
| codex | idle-prompt | idle | 保留 |
| — | Track done 派生 | done | **删** |
| — | protocol.StateDone / 聚合 done 分支 | done | **删** |

**结论**：规则集**不会变复杂**——删掉 done 派生链路（Track 分支 + StateDone 常量 + 聚合分支），规则表本身几乎不动（只有字形白名单治理）。服务端确实变简单。若实施中出现「为兼容旧 done 加逻辑」的倾向，那是理解错了，应停下问。

### 4.B 产品语义（✅ 已裁定 2026-08-13 用户，契约级解除）

#### 4.B.1 用户裁定原话（一字不改，本条锚）

> 「因为完成一开始是一个进行中的结束标为完成，app 看过一次后再标为空闲。
> **服务端取消完成**，app 端进行一个简单的逻辑，完成后标为完成，点进去看过后标为空闲，**服务端没有完成或完成等于空闲**。」
> 「此外，服务端从空闲变成进行中，**app 端还是要从完成或空闲变成进行中**。」

#### 4.B.2 完整状态机（用户裁定形态，最简）

**服务端只报两态**：working / idle（blocked 待裁，见 4.B.4）。
**App 端每个会话只维护一个布尔量 `unread`**：

| 事件 | unread 变化 |
|---|---|
| 服务端 **working → idle** 转变 | **置** unread（=「完成」） |
| 用户**点进去看过** | **清** unread（=「空闲」） |
| 服务端**变回 working** | **清** unread（用户补充的原话） |

**显示**：
| 服务端状态 | unread | App 显示 |
|---|---|---|
| working | — | 进行中 |
| idle | true | **完成** |
| idle | false | 空闲 |

**本质：「完成」不是状态，是未读标记。**

#### 4.B.3 与 herdr 模型的交叉验证

用户独立给出与 herdr 完全一致的模型：herdr 无 done 状态，Brewed/Churned 都是 Idle，Done 是 UI 派生 = `(idle + 用户未 seen)`。**用户没有看过 herdr 源码，独立得出同一结论——交叉验证成立。**

#### 4.B.4 待裁定（leader 已问用户，未回；写文档不填默认值）

1. **blocked（等用户输入）态服务端还要不要**：App 只需要「工作中 / 非工作中」+ 派生的完成，blocked 可并入「非工作中」；但 blocked 有独立产品价值（提示"有会话在等你回答"）。leader 倾向保留，未裁。
2. **「看过」的定义**（进入页面即算 / 需停留）；**unread 是否跨 App 重启持久化**。leader 倾向「进入即算、不持久化」，未裁。

> 注：用户补充已顺带回答「同一会话再次 working→idle 是否重标完成」= **是**（working 时已清 unread，下次 working→idle 重新置位），不再问用户。

#### 4.B.5 跨端部署顺序与兼容性（2026-08-13 librarian 核实）

服务端停止产 done + App 端落 unread 派生 = **跨端协议改动**。用户手机装的是旧包（v6-9653be0，期待 done）。

**已核实的兼容性事实**：
1. **旧 App 不崩**：App 枚举 `AgentState.kt` **已含 DONE**，新服务端停止产 done 只是「不再发 done」，发 working/idle/blocked 旧 App 全可解析（`fromWire` 命中闭集）。未识别值才抛 `FrameDecodeException`，但新服务端不发新值。
2. **旧 App 功能降级**：服务端把完成态判 idle → 旧 App 显示「空闲」而非「完成」——**降级不崩**。
3. **聚合安全**：`listing.go aggregateState` 移除 done 分支后 blocked=4/working=2/idle=1，聚合正常。
4. **部署顺序结论**：**可先发服务端**（旧 App 降级不崩）。App 端 unread 派生是后续（需新 App 包）。

**⚠️ 前置疑问（待 leader 确认）**：用户手机上连的是哪个 daemon？ps 实测有两个：pid 92111（`server/agentmirrord`，12:25 启动 = v5 恢复后）+ pid 43450（`/private/tmp` 测试隔离 daemon）。若用户连的是**旧二进制**（v5 恢复前编译，含 ◐ 白名单），则「新服务端」还没真正生效——**先确认用户连的哪个，再谈部署**。

#### 4.B.6 临时裁定（leader 给出，标「待用户确认可撤销」，非用户裁定）

1. **blocked 服务端保留**：已存在、规则已在、删是净损失；「有会话在等你回答」对用户可能有价值。App 端可先不展示（当非工作中），等用户表态。
2. **「看过」= 进入会话页面即算**；**unread 不跨 App 重启持久化**（重启后全部当空闲）：用户明确说「简单的逻辑」，持久化要引入存储与失效策略。

**两条均标 `leader 临时裁定，待用户确认，可撤销`**，不写成用户裁定。

---

## 五、D-26 rules.go 字形白名单应如何退场

FIELD.md 已裁定：生产 daemon 暂不重编，字形白名单应退场而非继续扩充。

**建议退场方式**（只提议，改由 leader 定夺施工）：
1. `spinnerFrames` 白名单**保持现状、不再扩充**——`✳`/`◐◑◒◓◔◕` 等**不得**加入 working 白名单；
2. 新增 `done` 派生模型（4.1）后，**字形白名单的角色减弱**：working 判定仍靠它（braille spinner），但 done/idle 判定**不再依赖字形**（改为状态+seen）；
3. `rules.go` 的 `claude-idle-rest-bar`（`bypass permissions on`）与 `✳` idle 前缀规则**保留**（它们是正确的 idle 信号）；
4. **glyph 白名单扩补历史**（leader 曾让补 `✳` 进 spinnerFrames）**作废**，若已进则回退。

---

## 六、可移植性结论（在我们「旁路镜像 tmux pane」约束下）

herdr 的机制**大部分可移植**到我们的「不托管、只旁观 tmux pane」约束：

| herdr 信号 | 我们能否获得 | 方式 |
|---|---|---|
| 屏幕文本 | ✅ 已有 | `capture-pane` RecentOutput |
| OSC title | ✅ 已有 | tmux `#{pane_title}`（tmux 吞 title 的先例已由 fix-state-detection 处理） |
| OSC 9 progress | ⚠️ 待测 | tmux 是否透传 OSC 9？需实测；若被吞则不可得 |
| foreground_pgid | ⚠️ 部分可得 | tmux `#{pane_pid}` 是 pane 首进程，非前台 pgid；`tcgetpgrp` 需 pty 权限，旁路场景未必有 |
| process_exit | ⚠️ 部分可得 | tmux pane 死了可测（`pane_dead`），但 agent CLI 退出不等于 pane 死 |
| seen（用户查看） | ✅ 可得 | 客户端上报「用户已查看该会话」 |

**最关键的可移植项**：状态+seen 的 done 模型（4.B）与时序确认（4.A.2）**不依赖任何新采集**，纯靠现有 RecentOutput/PaneTitle + 客户端上报 seen。这是最干净的第一步。

---

## 七、调研出处

- `herdrdev/herdr`（Apache-2.0）：`src/detect/mod.rs`（AgentState 四态枚举）、`src/detect/manifests/claude.toml` / `codex.toml`（规则表）、`src/detect/manifest.rs`（detect_with_osc 引擎 + region）、`src/pane/agent_detection.rs`（PendingIdleConfirmation 时序）、`src/app/agent_view.rs`（Done 派生 `(Idle,!seen)`）、`src/platform/{macos,linux}.rs`（tcgetpgrp / /proc tpgid）、`src/pane/terminal.rs`（OSC 9 progress 采集）
- `dcolinmorgan/herdr-remote`（AGPL-3.0）：LICENSE 确认为 AGPL-3.0-or-later，仅借鉴模型
- 我们现状：`server/internal/agentstate/`（rules.go / adapters.go / track.go / identify.go / sample.go）、`server/internal/api/state_wiring.go`、`server/internal/protocol/state.go`（五态含 StateDone）

---

## 八、OSC 9 透传验证方案（新增，验证通过前不设计采集）

> 背景：w-dev-repaint 已证 tmux 会吃掉 capture-pane 路径的 **2026 转义**（`capture-pane -e` 丢 CSI/OSC），OSC 9 完全可能同理。leader 裁定「先验证透传再设计」。本节给可执行的验证方案，供能跑隔离 tmux 的席位执行。

### 8.1 要验证的核心问题

**tmux 对 OSC 9（progress）转义的透传行为**，分两个路径：
1. **capture-pane 路径**（服务端采样用）：`capture-pane -e` 抓到的字节里，OSC 9 序列是否保留？
2. **pipe-pane 路径**（镜像流用）：`pipe-pane` 增量字节流里，OSC 9 是否透传给下游？

### 8.2 怎么验（隔离 tmux，不碰用户 pane）

**夹具**：自建隔离 tmux socket（`tmux -L /tmp/osc9-test-$$`），起一个 pane，往 pane 里注入 OSC 9 序列。

**注入命令**（在 pane 内写入 OSC 9 progress 转义）：
```bash
printf '\033]9;42\007'    # OSC 9 ;42 = 进度 42%（BEL 终止）
printf '\033]9;4;0\007'   # OSC 9 ;4 ;0 = 进度归零（herdr 的 idle 信号形态）
printf '\033]9;4;100\007' # 进度 100%
```
> 注：herdr claude.toml 里 `osc_progress_idle` 匹配 `^4;0`，即 OSC 9 的 `4;0` 形态。夹具应覆盖该形态 + 一般数值形态。

**验证命令**：
```bash
# ① capture-pane 路径：抓字节，看 OSC 9 是否被吞
tmux -L /tmp/osc9-test-$$ capture-pane -e -p -t 0 | xxd | grep -i '1b 5d 39'   # \e]9 是否在

# ② pipe-pane 路径：订阅增量流，看 OSC 9 是否透传
tmux -L /tmp/osc9-test-$$ pipe-pane -o -t 0 'cat > /tmp/osc9-stream.txt' &
printf '\033]9;42\007' > $(tmux -L /tmp/osc9-test-$$ display-message -p -t 0 "#{pane_tty}")
sleep 0.5
xxd /tmp/osc9-stream.txt | grep -i '1b 5d 39'   # 流里是否有 OSC 9
```

### 8.3 判定与后续

| 结果 | 含义 | 后续 |
|---|---|---|
| capture-pane 保留 OSC 9 | 服务端采样可采到 progress | 可设计 `osc_progress` 采集 → idle 结构性信号 |
| capture-pane 吞掉 OSC 9 | 采样路径拿不到 | 走 pipe-pane 流（若有）或放弃 |
| 两路径都吞 | tmux 不透传 OSC 9 | **放弃 OSC 9 方案**，靠 4.A.1+4.A.2（不依赖它也能解决 D-26 误检） |
| OSC 9 被重写成别的 | tmux 改写了转义 | 需确认改写后的形态 |

**验证产物**：一条简短结论（哪个路径保留/吞掉）+ 若保留则附 `xxd` 样本。落 `docs/osc9-pass-through.md` 或直接在证据 JSON 备注。

**红线**：只碰隔离 socket，**不碰生产 daemon（pid 39489）与用户真实 tmux**（FIELD.md 边界）。

### 8.4 ⚠️ 顺序调整（leader 裁定 2026-08-13：OSC 9 降级，先不派验证）

**OSC 9 验证暂不派**。理由：**2026 那条路刚关闭**——两次实测在 tmux 内零命中，且无法确认 tmux 能力声明的正确方式。OSC 9 很可能撞同一堵墙：同样依赖 CLI 主动发、同样要过 tmux 这一关，而 tmux 对 OSC 序列的处理只会更主动。

**调整后的顺序**：
1. **先落地 §4.A 前两条**（字形白名单退场 + 时序确认）——它们**只靠我们自己的采样就能实现**，不依赖任何外部信号是否透传；
2. 等它们上线并验证误检是否消失，**再看还需不需要 OSC 9**；
3. 若确需 → 再按 §8.1-8.3 验证透传，通过后才设计采集。

**一般性判断（leader，记入报告）**：**优先做「只依赖自己」的判据，把「依赖对方主动配合」的判据放后面。** 今晚 2026 已证明后者的风险——花了很多轮才发现对方根本不发。OSC 9、OSC title 都是「依赖 CLI 主动配合 + tmux 透传」的信号，可靠性低于「我们自己采样就能判」的路径。

---

## 九、区域限定施工方案（2026-08-13 更新：固定行数 N → 锚点方案）

> 根因：**全屏扫描把历史残留 `esc to interrupt` 当 working**（用户提问「从底部判定，herdr 也这么做吗」直接命中，Go 测试已实证）。修法选 **方案 B（规则侧限定）**，非 A。
> **2026-08-13 重大更新**：固定行数 N 是脆的（版式一变就破），改用**锚点方案**（§10 真实语料支撑）：取「**最后一个 `❯` 提示符之后**」的区域 = 当前 UI 区。无需行数参数。

---

### ⛔ 实锤：任何基于字形/前缀的状态规则从根上不可能正确

> **同一屏里，完成态 `✻ Brewed for 6s` 与工作中 `✻ Galloping…` 用的是同一个 `✻` 前缀。**
> （2026-08-13 librarian 真实 Claude Code 会话抓取，见 §10.4，非转述。）

**推论**：
1. **两个状态共用同一个字形** → 任何「字形/前缀匹配」都无法区分完成态与工作中。
2. 能分辨状态的是**位置**（工作信号 `esc to interrupt` 是否出现在当前 UI 区），不是内容本身。
3. **本工程曾据字形判定状态并试图往白名单补字形（✳/◐），方向从一开始就是错的。** 补进 `spinnerFrames` 只会让完成态被判成工作中，越补越糟。

**这条必须被后来人一眼看到**——否则将来还会有人提「把 ✻ 加进 working 白名单」。

### 9.1 为什么 B 不选 A（leader 裁定，理由记录）

| | 方案 A（采样侧 `capture-pane -E -5`） | 方案 B（规则侧限定） |
|---|---|---|
| 语义 | **物理行**，表达不了「非空」 | **非空行**，与 herdr 语义一致 |
| 空行问题 | 状态栏上方常有空行，只抓最后 5 物理行可能抓到空白，状态栏恰在第 6 行被切 → 从误判 working 变**永远判 idle**（方向反了） | 取最后 N 个**非空**行，状态栏必在 |
| 数据源 | 采样侧砍数据，**不可逆** | 保留整屏采样，**可逆** |
| 影响面 | RecentOutput 只剩底部，其他用途被砍 | 不影响其他用途 |

### 9.2 锚点方案（取代固定行数 N，2026-08-13 更新）

**行数是版式的函数，锚点是语义的函数。** 固定 N 会被下一种版式打破（配置变化/换 CLI/窄窗口折行/多行输入框），每次调大都让「残留文本又能命中」的老问题回来。**改用锚点**（herdr 的 `after_last_prompt_marker` 思路）：

- **锚点 = 屏幕上最后一个 `❯` 提示符**。取「最后一个 `❯` 之后的所有行」= 当前 UI 区（见 §10 真实语料：working 的 `esc to interrupt`、blocked 的 `❯ 1. Yes`、idle 的状态栏，都在最后 `❯` 之后）。
- 历史残留的 `esc to interrupt` 一定在**更早的提示符之前** → 天然排除，**无需行数参数**。
- **blocked 同一锚点覆盖**：权限框的 `❯ 1. Yes, I trust this folder` 是选项行，也在最后 `❯` 之后 → 「按状态不同取法」的复杂度消失。
- **兜底**：无 `❯` 锚点（全屏 TUI / 刚清屏）→ 退回 `bottom_non_empty_lines(4)`，此时 4 只在兜底路径生效，风险小。

### 9.3 改动点（已核实）

- `server/internal/agentstate/rules.go`：新增函数**「取最后一个 `❯` 提示符之后的非空行」**（`linesAfterLastPrompt(lines []string) []string`）；`evaluateRules` 在 `rule.match` 前把 `text`/`lines` 限定为该函数结果；无锚点时退回 `lastNonEmptyLines(lines, 4)`。
- `server/internal/agentstate/adapters.go`：`Detect` 调用 `evaluateRules` 的路径不变（限定在 evaluateRules 内做，适配器无感）。
- **不改** `capture-pane`（保留整屏采样），**不改** `RecentOutput` 语义（唯一消费方是规则匹配，见 9.5）。

### 9.4 红测五条（缺一不可，全部用真实语料 §10）

1. **残留测试（红→绿）**：历史残留 `esc to interrupt`（在更早的 `❯` 之前）+ 真实 idle 底部（§10.1）→ **当前判 working（红），修复后判 idle**。
2. **守卫一**：真实 working 版式（§10.2，`esc to interrupt` 在底部 `❯` 之后）→ **必须仍判 working**。防反向误判成「永远判空闲」。
3. **守卫二**：真实完成态 `✻ Churned for 4s` 在底部（§10.5 实测语料）→ 判 idle（完成态不被误判）。
4. **守卫三**：真实 blocked 版式（§10.3 权限框）→ **必须仍判 blocked**（权限框的 `❯ 1. Yes, ...` 在锚点区）。
5. **守卫四（新）**：**无 `❯` 锚点**的情况（全屏 TUI / 刚清屏）→ 退回兜底 `bottom_non_empty_lines(4)`，**不崩、不误判**。
   ⚠️ 此条可能**无真实语料**（全屏 TUI/刚清屏版式未取到）→ **标 NOT_COVERED**，不手写版式；但代码必须保证这条路径不崩。

### 9.5 影响面核实

`RecentOutput` 唯一消费方 = `agentstate` 规则匹配（adapters.go Detect + DetectForKind）。方案 B 保留整屏采样，不影响任何其他用途。

### 9.6 内容对比判据（用户 Ctrl+B w 动态效果）——暂不合并，保留评估

区域限定是已确证根因、改动小可验证；内容对比是另一套判据。**2026-08-13 实测 A 已确认：完成态秒数不自己涨**（真实 Claude Code 连续 10 次 capture 内容全同）——内容对比的头号障碍（假变化）**不存在**，可行性大幅上升。但「等 API 时 spinner 是否停」的生死线仍未测（实测 B）。**一次只改一个缺陷**：区域限定上线、误检消失与否有实测结论后，再评估内容对比。

---

## 十、真实版式语料与锚点（2026-08-13 librarian 隔离 tmux 实测）

> 来源：真实 Claude Code 2.1.228（deepseek 后端），隔离 tmux socket，未碰用户 tmux。屏幕文本只取版式结构，对话内容未落文档。

### 10.1 idle 版式（真实底部）

```
────────────────────────────────────────────
❯
────────────────────────────────────────────
  ? for shortcuts · ← for agents          ● high · /effort
```

### 10.2 working 版式（真实，任务运行中）

```
❯ Please list 5 US states, one per line.
✻ Galloping…
  ⎿ Tip: Name your conversations with /rename ...
────────────────────────────────────────────
❯
  esc to interrupt
```

**关键**：`esc to interrupt`（working 信号）在分隔线之下、提示符 `❯` 之下——**在「最后一个提示符之后」**。

### 10.3 blocked 版式（真实权限框）

```
Accessing workspace: /private/tmp
Quick safety check: Is this a project you created or one you trust? ...
 Security guide
❯ 1. Yes, I trust this folder
   2. No, exit
 Enter to confirm · Esc to cancel
```

**关键**：权限框的 `❯ 1. Yes, ...` 是选项行，也在「最后一个提示符之后」→ **同一锚点覆盖 blocked**。

### 10.4 关键实证——同字形无法分辨状态

同一屏里 `✻ Brewed for 6s`（完成态）和 `✻ Galloping…`（工作中进行时）**都用 `✻` 前缀**。坐实 FIELD.md 论点：**字形无法分辨状态，靠位置（底部 esc to interrupt 有无）+ 时序**。

### 10.5 实测 A：完成态秒数不自己涨（决定性）

真实 Claude Code 完成任务后，`✻ Churned for 4s` / `✻ Sautéed for 4s` **连续 10 次 capture（10 秒）内容完全相同**——秒数不自己涨。**内容对比判据的头号障碍（假变化）不存在**。

### 10.6 实测 B：NOT_MEASURED（不是不适用，是该测而暂不测）

**状态**：`NOT_MEASURED`。
**是什么**：内容对比判据的生死线——「工作中是否有长时间不变的窗口」（等 API 响应时 spinner 停不停）。
**为什么现在不测**：实测 B 为**内容对比判据**服务，而内容对比排在锚点方案后面、只是「可能更好」的备选。为还没轮到的备选方案去调 send-keys 时序，投入产出不划算（交互会话任务触发时序不可控，多次尝试失败）。
**已够用的定性证据**：working 期间 `✢ Synthesizing…` / `✻ Galloping…` **持续在动**——内容对比在 working 期确实能工作。精确「最长不变窗口」数字，等真做内容对比时再测。

---

*调研完成并拆分 2026-08-13。只写 docs/，未改 taskbook.yaml，未 commit，未碰生产代码与用户 tmux。外部源码已清理。技术判据（§4.A）leader 已批可施工；产品语义（§4.B）**已裁定**（用户，契约级解除）；OSC 9 采集**降级后置**（§8.4）；**区域限定锚点方案（§9 + §10）已定稿，红测五条已设计，待落代码**。`server/internal/agentstate/` 定夺前不施工。*

*调研完成并拆分 2026-08-13。只写 docs/，未改 taskbook.yaml，未 commit，未碰生产代码与用户 tmux。外部源码已清理。技术判据（§4.A）leader 已批可施工；产品语义（§4.B）**已裁定**（用户，契约级解除）；OSC 9 采集**降级后置**（§8.4）；**区域限定施工方案（§9，方案 B，锚点方案取代固定 N）已写，红测三条待扩至五条（多行输入框 + 权限框守卫），待 leader 过审后落代码**。`server/internal/agentstate/` 定夺前不施工。*
