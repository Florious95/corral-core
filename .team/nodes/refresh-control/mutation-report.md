# 定点变异报告：判据「会话列表刷新模型」验红测真的会红？

- 席位：状态判定对照席（r.control，零上下文定点变异）
- 账本：`.team/ledgers/refresh-and-contract-v2.json`（任务 t.refresh-verify）
- 日期：2026-08-15
- 判据命令：`bash .team/nodes/refresh-oracle/run-probe.sh`（静态 grep 结构检查，全 PASS exit 0 / 任一 FAIL exit 1）
- 只读文档：`.team/nodes/refresh-oracle/判据基底摘要.md`（唯一允许的判据文档）
- 被测对象：`app/app/src/main/java/dev/agentmirror/app/workspace/`

## 结论：refuses（判据无效）

**判据的三条机械检查 R1/R2/R3 全部被注释文本污染，判不出真实回归。**
定点变异实证：删掉唯一真实 `list()` 调用点（真正的回归）判据仍绿（exit 0）；
注释里加一行 `while (true)`（无任何周期逻辑）判据红（exit 1）。

## 变异实验总表

| 阶段 | 变异内容 | 判据输出 | 退出码 | 判据响不响 |
|---|---|---|---|---|
| 基线（未变异） | — | R1 PASS / R2 PASS / R3 PASS | **0** | — |
| 变异 1（删刷新型） | 唯一真实调用 `list()` → `listSessions()`（进入/下拉刷断链） | R1 PASS / R2 PASS / R3 PASS | **0** ❌ | **不响（假绿）** |
| 变异 2（加周期型） | 代码里加 `while (true) { break }`（真实周期结构） | R1 PASS / R2 PASS / R3 **FAIL** | **1** ✓ | 响（R3 真红） |
| 对照（注释周期型） | 注释里加 `while (true)`（无实际周期逻辑） | R1 PASS / R2 PASS / R3 **FAIL** | **1** ❌ | **误伤（假红）** |
| 恢复（现场还原） | — | R1 PASS / R2 PASS / R3 PASS | **0** | — |

## 为什么判据无效：R1/R2/R3 三条 grep 全部不排除注释

`run-probe.sh` 用 `grep -rnE ... --include='*.kt'` 直接扫 raw 文本，**不剥注释**。三条判据的模式都命中注释里的字样：

1. **R1/R2 假绿（漏报真回归）**：workspace 包唯一真实 `list()` 调用在
   `WorkspaceViewModel.kt:122` `private val requestList: () -> Unit = { ServiceWire.managerOrNull()?.list() }`。
   变异 1 把它改成 `listSessions()` 后，grep 仍命中**注释** `WorkspaceViewModel.kt:144`
   `@pre none（连接未就绪时 list() 内部自判返回 false，不抛）` 里的 `list()` 字样 → R1 PASS。
   R2 的第二个条件（`list()` 存在）同样被该注释满足 → R2 PASS。
   **用户实测的「所有界面都不刷新」在代码侧就是「list() 零调用点」——而判据对这种坏代码给绿。**
2. **R3 假红（误伤好代码）**：workspace 包现有注释 `WorkspaceScreen.kt:101`
   `// 不周期重复（零周期禁令）。下拉手动刷见 PullToRefreshBox.onRefresh。` 不含 while，所以基线 R3 绿；
   但对照实验证明：往注释加 `while (true)` 一行，R3 立即 FAIL——周期结构**根本没进代码**，判据就红。
   真实周期型（变异 2，`while (true) { break }`）也红，但判据**分不清**「代码里的周期」和「注释里的周期」，
   所以 R3 对好代码也会误报。

**判据有效（supports）要求「基线绿且变异红」；此处删刷新型（最关键的进入即刷回归）判据假绿。故 refuses。**

## 变异点明细（可审计）

- 变异 1：`WorkspaceViewModel.kt:122` 的 `? .list()` → `? .listSessions()`（唯一真实调用点删除）。
- 变异 2：`WorkspaceViewModel.kt` `refresh()` 内 `requestList()` 后加 `while (true) { break }`。
- 对照：同上位置改为**注释** `// while (true) { ... }`。
- 全部改在 live 源码，跑完判据后已还原（见哈希对照）。

## 哈希对照（可审计）

| 文件 | 阶段 | SHA-256 |
|---|---|---|
| `WorkspaceViewModel.kt` | 基线 | `7be826580219255a59f476cb75d02c1e03234ae1e16b436bc70d0862ebe2374a` |
| `WorkspaceViewModel.kt` | 恢复 | `7be826580219255a59f476cb75d02c1e03234ae1e16b436bc70d0862ebe2374a`（回到基线） |
| `WorkspaceScreen.kt` | 全程 | `38151fc8d875ab542bf25d31b6146f15174d9396850b2d6de87ae31f369033bf`（未动） |

## 现场状态（收尾自查）

- live 变异已还原：两个文件回到基线哈希，判据命令恢复 exit 0。
- 只写过 `.team/nodes/refresh-control/mutation-report.md`（本报告）；未 commit / push / 未发 team-agent send / 未读禁读文件。
- 判据命令只读源码、不写文件，红测日志 `.team/nodes/refresh-oracle/probe-red.log` 未动。

## 建议（供顾问席裁 verdict 时参考，非越权改任务）

判据方向（结构三态：进入即刷 / 下拉刷 / 零周期）正确，但 grep 需剥离注释才算有效判据。
可修路径（开发/顾问席决定）：`grep -E` 前先剥注释（如 `sed 's://.*$::'` 或 Kotlin 注释剥离），
或改用能感知 AST/注释的工具；修好后的判据应能对「list() 零调用点」（R1/R2 红）与
「注释含 while」（R3 不红）分别给出正确判定。本报告不修改任务定义，仅如实上报判据现状。
