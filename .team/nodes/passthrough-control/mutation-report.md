# 定点变异报告：判据「直通输入」验红测真的会红？

- 席位：状态判定对照席（r.control，零上下文定点变异）
- 账本：`.team/ledgers/passthrough-input-v3.json`（任务 t.pt-verify）
- 日期：2026-08-15
- 判据命令：`bash .team/nodes/passthrough-oracle/run-probe.sh`（结构 grep/awk 检查，全 PASS exit 0 / 任一 FAIL exit 1）
- 只读文档：`.team/nodes/passthrough-oracle/判据基底摘要.md`（唯一允许的判据文档）
- 被测文件（4 个）：`server/internal/bridge/bridge.go`、`server/internal/protocol/keys.go`、
  `app/.../session/SessionViewModel.kt`、`app/.../session/SessionScreen.kt`

## 结论：refuses（判据无效）

**四条机械检查 S1/S2/S3/S4 全部被注释文本污染，判据对真实回归漏报（假绿）、对好代码误报（假红）二者并存。**
核心矛盾：
- **S2 假绿（漏报真回归）**：把删除键 backspace 映射彻底删光（keys.go 常量值 + bridge.go 唯一映射全删）判据仍 exit 0——被注释与标识符 `KeyBackspace` 里的 `backspace` 字样骗过。这正是摘要 §3 自检明说的「把删除键退回本地消费 → S2 必须 FAIL」形态，判据不响。
- **S1/S3/S4 假红（误伤好代码）**：往注释加一行含关键字的结构，判据立即 FAIL——对无实际回归的好代码误报。

## 变异实验总表（五阶段）

| 阶段 | 变异内容 | 判据输出 | 退出码 | 判据响不响 |
|---|---|---|---|---|
| 基线（未变异） | — | S1/S2/S3/S4 全 PASS | **0** | — |
| 变异 1（删除键退回本地·部分） | `keys.go` `KeyBackspace = "del"`（bridge.go 映射保留） | S1/S2/S3/S4 全 PASS | **0** ❌ | 不响 |
| 变异 1b（删除键退回本地·完整） | keys.go + bridge.go 的 backspace 映射**全删**（代码零 backspace 映射） | S1/S2/S3/S4 全 PASS | **0** ❌ | **假绿** |
| 变异 2（逐键绑回提交） | `TypeKeys` 内追加代码 Enter | S1 **FAIL**，其余 PASS | **1** ✓ | 响应 |
| 对照（S1 注释 Enter） | Enter 挪进注释（代码无提交） | S1 **FAIL**，其余 PASS | **1** ❌ | **假红** |
| 变异 3（发送退回整条注入） | `sendDraft()` 改 `sendInput(ref, textFieldValue.text, ...)` | S3 **FAIL**，其余 PASS | **1** ✓ | 响应 |
| 对照（S3 注释注入） | 整条注入字样挪进注释（代码正常） | S3 **FAIL**，其余 PASS | **1** ❌ | **假红** |
| 变异 4（键盘退回本地累积） | `onValueChange` 改 `viewModel.textFieldValue = it` | S4 **FAIL**，其余 PASS | **1** ✓ | 响应 |
| 对照（S4 注释累积） | `textFieldValue = it` 挪进注释（代码已恢复直通） | S4 **FAIL**，其余 PASS | **1** ❌ | **假红** |
| 恢复（现场还原） | 四个文件回到基线 | S1/S2/S3/S4 全 PASS | **0** | — |

## 为什么判据无效：四条检查全部不剥注释

`run-probe.sh` 用 `grep -rnE`（S2/S3/S4）与 `awk` 按函数块扫（S1），全部在 raw 文本上匹配，**不排除注释**。实测证据：

1. **S2 假绿（漏报）**：删光代码里的 backspace 映射后，grep `-rniE 'backspace|"bspace"|"bksp"'` 仍命中
   - `keys.go:4` 注释 `// shortcut bar, requirement 017; backspace added by requirement 059`
   - `keys.go:10` 注释 `// The closed set is Esc / Ctrl-C / ... / Backspace`
   - `keys.go:30` 注释 `// KeyBackspace is the delete/backspace key ...`
   - `keys.go:33`/`:40` 标识符名 `KeyBackspace`（`-i` 大小写不敏感，常量值已改成 `"del"` 仍命中）
   → R 判据对「删除键退回本地」这个真实回归**静默失明**。**这是最关键的失效**：059 直通删除键是用户裁定，映射被删正是「退回本地消费」的代码侧表现，判据给绿。
2. **S1 假红（误伤）**：`awk` 按函数块匹配 `"-l"` 与 `"Enter"` 字样，注释里的 `runTmux(..., "Enter")` 一行即触发 `has_enter=1` → 判 S1 FAIL。好代码（TypeKeys 无提交）被注释误杀。
3. **S3/S4 假红（误伤）**：负向判据 `grep -nE` 同样命中注释里的 `sendInput(ref, textFieldValue.text`（S3）与 `textFieldValue = it`（S4）。坏结构在注释里、代码完全正常，判据仍红。

**对照铁证**：同一关键字，放代码里红、放注释里也红——判据分不清「结构在代码里」还是「结构只在注释里」。而最该响的 S2 删除键回归却不响。

## 变异点明细（可审计）

- 变异 1：`keys.go:33` `KeyBackspace Key = "del"`；变异 1b 追加 `bridge.go:256` `"backspace": "BSpace"` → `"del": "BSpace"`。
- 变异 2/对照：`bridge.go:313` `TypeKeys` 内追加/注释 `runTmux(..., "Enter")`。
- 变异 3/对照：`SessionViewModel.kt` `sendDraft()` 内 `sendInput(ref, "", ...)` ↔ `sendInput(ref, textFieldValue.text, ...)`（代码/注释两形态）。
- 变异 4/对照：`SessionScreen.kt` `onValueChange` 内 `viewModel.textFieldValue = it`（代码/注释两形态）。
- 全部改在 live 被测文件，跑完判据后已还原。

## 哈希对照（三阶段，可审计）

| 文件 | 基线 | 恢复后 |
|---|---|---|
| `server/internal/bridge/bridge.go` | `42bf2af7…9ca` | `42bf2af7…9ca`（回到基线） |
| `server/internal/protocol/keys.go` | `2a722d8c…2fb` | `2a722d8c…2fb`（回到基线） |
| `app/.../session/SessionViewModel.kt` | `847ba017…ed3` | `847ba017…ed3`（回到基线） |
| `app/.../session/SessionScreen.kt` | `06fe7a98…246` | `06fe7a98…246`（回到基线） |

## 现场状态（收尾自查）

- live 四个文件变异已全部还原到基线哈希，判据命令恢复 exit 0。
- 只写过 `.team/nodes/passthrough-control/mutation-report.md`（本报告）；未 commit / push / 未发 team-agent send / 未读禁读文件。
- 判据命令只读不写，红测日志 `.team/nodes/passthrough-oracle/probe-red.log` 未动。

## 建议（供顾问席裁 verdict 时参考，非越权改任务）

判据方向（行为结构三态：每键直通 / 删除键直通 / 发送只提交）正确，但 grep/awk 需剥注释才算有效判据。可修路径：
- grep/awk 前剥注释（如 `sed 's://.*$::'` 处理 Kotlin/Go 行注释，块注释另处理），或
- 改用能感知 AST/注释的工具。
- S2 还需警惕标识符名 `KeyBackspace` 的大小写不敏感命中——匹配应限定在**字符串字面量/键名映射**（`"backspace"`/`"bspace"`），不能靠任意 `backspace` 字样。

修好后判据应能：删光 backspace 映射 → S2 红；注释里含关键字 → 不误伤；代码真实回归 → 各判据正确转红。本报告不修改任务定义，仅如实上报判据现状。

## 重验记录（2026-08-15 同任务重派）

leader 重派 t.pt-verify（判据未修复，run-probe.sh mtime 15:17 未变、哈希 `01807672…6b` 未变；四被测文件哈希与上轮恢复后一致）。重跑决定性变异确认结论稳定：

| 阶段 | 退出码 | 判据输出 |
|---|---|---|
| 基线（重验） | **0** | S1/S2/S3/S4 全 PASS |
| 变异（重验：backspace 映射全删，删除键退回本地完整回归） | **0** ❌ | 仍全 PASS——S2 假绿复现 |
| 恢复（重验） | **0** | 四文件回基线哈希，全 PASS |

**结论不变：refuses**。判据未被修复，S2 对「删除键退回本地」真实回归仍静默失明（被注释与标识符 `KeyBackspace` 骗过）。重验证明该结论可复现、非单次偶然。
