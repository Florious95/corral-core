VERDICT: supports

# t.uiplus.rv · 异源评审（只读）

评审席：hl1-judge-ui（Claude 订阅 / Opus 5，与实现席 grok 异源）
被审：分支 `pr/ui-agent-dialog`（worktree `.worktrees/hl1.uiplus`）
本席工作目录：`.worktrees/hl1.rv.ui`；未改任何产品代码，未 commit/push。

## 0. 必须先说的一件事：分支上没有提交

```
git log --oneline main..pr/ui-agent-dialog   → 空
git diff --stat main...pr/ui-agent-dialog    → 空
```

改动全部以**未提交工作区**形式存在于 `.worktrees/hl1.uiplus`：

```
 M app/app/src/main/java/dev/agentmirror/app/workspace/NewAgentDialog.kt
 M app/app/src/main/java/dev/agentmirror/app/workspace/PackageDoc.kt
?? app/app/src/test/kotlin/dev/agentmirror/app/workspace/NewAgentDialogUiTest.kt
 2 files changed, 366 insertions(+), 56 deletions(-)
```

这与实现席的硬约束（禁 `git commit`）一致，说明.md 也如实写了「改动在工作区未提交」，
**不算隐瞒**。但 leader 必须知道：**现在没有任何东西可 land**，
`git diff main...pr/ui-agent-dialog` 是空的。要落地必须由有提交权的一方在 hl1.uiplus 里提交。
本席按「工作区 diff」评审，下面所有结论都指这份未提交 diff。

## 1. 说明 vs diff 逐条核对（一致）

| 说明写的 | diff 真做的 | 判 |
|---|---|---|
| Provider 两列卡片 + `ProviderIcon`，不用裸 RadioButton | `ProviderCardGrid` `ids.chunked(2)` + `ProviderPickCard` 内 `ProviderIcon(provider = id)`；无 RadioButton | ✅ |
| 工作区短行可滚 | cwd 段 `weight(1f, fill=false)` + `heightIn(max=168.dp)` + `verticalScroll`，`CwdRow` 单行 Ellipsis | ✅ |
| Bypass 带说明 | `NEW_AGENT_BYPASS_HINT` 常量 + `BypassBlock` 内第二行 Text | ✅ |
| 底栏描边「取消」+ 主色「创建」 | `DialogOutlineButton` / `DialogPrimaryButton`，在 `HorizontalDivider` 之下 | ✅ |
| `buildNewAgentArgv` / `confirmNewAgent` 未改 | `git diff --stat` 只含 NewAgentDialog.kt / PackageDoc.kt；`NewAgentArgv.kt`、`WorkspaceViewModel.kt:275` 均未动 | ✅ |
| PackageDoc 补 `@consumes ...ui.components` | 该行确在 diff 中，且新文件确实 import 了 `ui.components.ProviderIcon` | ✅ |

**说明没有夸大。** 没有发现「说明写了但 diff 没做」或「diff 做了但说明没写」的项。

## 2. argv 行为无变化（核到调用点）

- `NewAgentProviders.ids` / `displayName` 未改；`buildNewAgentArgv(ui.providerId, ui.bypass)` 调用点
  `WorkspaceViewModel.kt:275` 未在 diff 内。
- bypass 掩码语义与改前等价：改前 `checked = ui.bypass && !pi`、`enabled = !pi`；
  改后 `BypassBlock(enabled = ui.providerId != "pi", checked = ui.bypass && ui.providerId != "pi")`。
- 确认钮 enabled 条件逐字未变：`!ui.inFlight && ui.cwd.isNotEmpty() && ui.providerId.isNotEmpty()`。
- 全部 testTag 原样保留（`new-agent-dialog` / `-cwd` / `-provider-$id` / `-bypass` / `-error` / `-ok` / `-cancel`），
  已有测试的寻址面没被打断。
- 唯一可察的语义差：改前 `clickable{ onToggleBypass(!ui.bypass) }`，改后 `onToggle(!checked)`（checked 已被 pi 掩码）。
  两者只在「provider=pi 且 ui.bypass=true」时不同，而那一态下 `enabled=false` 点不动 ⇒ **不可达**。

## 3. 先验红（本条我给的是「过，但有保留」）

说明里**有**改前判据的原始输出，四条都带时间戳与 exit：

- `A-uiplus-doc` **红 exit=1**：`candidates=[...] hit=[] 产物两处都不存在`
- `A-uiplus-wiki` 绿 exit=0（基线=1 本次=1）
- `A-uiplus-smell` 绿 exit=0（基线=16 本次=16）
- `A-uiplus-suite` 绿 `EXIT:0`

**保留意见**：唯一那条红是**文档存在性闸**（「说明.md 还没写」），它对「弹窗简陋」这个缺陷本身
是**同义反复**，不构成缺陷探针。实现席自己也明写了这一点，没有把它包装成缺陷红 —— 这份诚实是我不判 refutes 的原因之一。

真正承担「先验红」职责的是另外两件，且都成立：

1. `shot-before.png` 是**改前 debug 包**在同一模拟器上拍的，画面里就是缺陷本体
   （裸 `○/● Claude Code`、Bypass 无说明、「创建」是灰色弱 TextButton）。视觉类缺陷，这是最强的红。
2. 新测断言 `onNodeWithText("○ Claude Code").assertDoesNotExist()`。改前代码逐字渲染
   `"○ ${NewAgentProviders.displayName(id)}"`（见 diff 删除行），**该断言在改前必然失败**。
   我是从 diff 静态确认的，**说明里没有这条测在旧码上跑红的录制输出** —— 这是本次证据链最薄的一环，记在这里。

## 4. 截图判读（用户标准：互洽 / 好看 / 不突兀 / 看了还想看）

- **互洽**：Provider 卡片用的是 088 §3 那套 `ProviderIcon`（灰底圆角 well + 图形），
  与列表页既有卡片语言同族；选中态 accent 描边 + accent 文字 + `sheetCurrentRowBg` 底，
  与全局选中语义一致。用的全是 `Spacing/Radii/Dims/TypeSizes/LocalAppPalette` 令牌，
  我逐个核过 19 个 palette 键与 Radii.card/chip/cardButton、Dims.hairline/cardButtonHeight、
  TypeSizes 五个字号 **全部在 DesignTokens.kt 中存在**，没有魔数硬编码色。
- **好看**：改后信息层级清楚（标题 → Provider → 工作区 → Bypass → 分隔线 → 底栏）；
  改前是一坨等权重的文字列表，长路径把整个弹窗撑成一屏。对比极明显。
- **不突兀**：底栏「取消」描边 + 「创建」实心主色，是移动端弹层常规范式；分隔线压住底栏。
- **看了还想看**：六格图标卡是这一版唯一有「可看性」的元素，改前完全没有。这条我给过。

**两处观感级保留（不影响判决，供后续格考虑）**：
- 后图里 9 个工作区只露出 2 行，且**没有任何可滚提示**（无渐隐、无滚动条）。功能上可滚，
  但用户第一眼可能以为只有两个工作区。
- 只有 cwd 段可滚，Provider 网格与 Bypass 块在外层不可滚；外层 `heightIn(max=560.dp)`。
  在更矮的屏（或大字号）上有被裁的风险。当前 411×891dp 与模拟器实拍都没问题。
- `new-agent-error` 用 `p.unknownChipText` 当错误色，语义上借了「未知」而不是「错误」的键。极小。

## 5. 我没做的事（诚实边界）

- **没有独立跑 `:app:testDebugUnitTest`**：跑它会在被审 worktree 里落 build 产物，
  超出我被允许写入的路径。改后判据全绿这一段我是**采信实现席自报**，未复核。
- 没有在模拟器上复跑。截图我按「实现席在改前/改后各拍一张」采信；两图状态栏时间
  6:55 → 7:03、SESSIONS 48 → 49，与「中间装了一次包」的叙述自洽。

## 结论

说明与 diff 一致；argv 路径确未被触碰；截图证明缺陷改前存在、改后消失且明显变好；
判据有原始输出。先验红那条是文档闸而非缺陷探针（且实现席主动交代），
缺陷本身的红由改前截图与被删的 `○` 字面量共同支撑，够用。

**VERDICT: supports** —— 但 land 之前必须先解决 §0：分支上一个提交都没有。
