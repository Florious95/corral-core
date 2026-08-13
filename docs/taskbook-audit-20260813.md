# taskbook 账本审计 · 2026-08-13（订正版）

> 审计者：w-librarian（库务/账本车道）
> 审计范围：taskbook.yaml + .team/evidence/ 与代码事实、requirement-wiki/raw/ 真相源的对账
> 方法：只读源码 + git 全历史核验 + 证据 JSON 比对。只提议、不改 taskbook.yaml，改由 leader 执行。
> 不可变真相源：`requirement-wiki/raw/`，与任何后来的措辞冲突时以 raw 为准。
> 订正：2026-08-13 依 leader 裁定（msg_5c01e1d5dbf8）与 librarian 对 fix-upload-token-chain 硬判据的复核，订正第一节三份证据的判定。

---

## 〇、新纪律（leader 裁定，措辞照抄，比本次审计本身更重要）

> **① 回退期间立的账，不能用回退后的代码去核。**
> 核之前必须先确认：这份记录声称的实现，在**它被写下的那个时间点的代码状态**里是否存在。
> 判断「假账」的唯一硬判据是 `git log --all -S<特征串>` 全历史零命中；
> 只在当前 HEAD 找不到，只能得出「已被回退」，不能得出「从未存在」。

> **② 取证结论要带时间戳。**
> 「当前全历史零命中」只在观测那一刻成立；
> 引用一个旧取证结论之前，先确认它的观测时点之后有没有新提交改变结论。

> **③ 写「用户没报过 / 没抱怨过 X」之前，必须去 `requirement-wiki/raw/` 与现场基查证。**
> 这类顺手下的断言是账面失真的主要来源之一。本轮实例：抑制机制差点因「用户没抱怨过
> resize 闪烁」被回退，而 `raw/041` 白纸黑字记着用户报过捏合重绘闪烁，链路正是该机制
> 对症的那条。

> **④ 用户的肯定性反馈同样要核实，不能因为是「好消息」就直接入账。**
> 我们对坏消息很谨慎，对好消息太轻信——这是同一形状的两面（凭印象下断言），只是方向相反。
> 本轮实例（D-36）：leader 根据用户半句「向上滑好了」就准备结案，没有等用户把话说完，
> 也没要求任何验证步骤；用户下一句就纠正「向上滑**没有好**，发一条消息就不能滑了」。
> 任何「用户说好了」的确认，都必须有可复现的验证步骤 + 单变量条件，与缺陷确认同等严格。

> **⑤ 状态升级类指令不得在信息未完整时发出；状态升级应有一道确认。**
> 「用户说好了」不是完整信息，要等他把现象、条件、复现步骤说完。
> 涉及把证据升为 `user_confirmed` 的操作，执行前回一句确认，而不是直接改。
> 本轮实例：leader 听到半句「向上滑好了」就派升级指令 → 用户补完后半句「向上滑没有好」→
> leader 撤回 → **但撤回没赶上执行** → 账上凭空多出一条假 PASS，形状与本轮开头抓到的
> 那两份历史假账**完全相同**。我们花一整晚清理别人留下的假账，然后自己在两小时内造了一条。

**两种错误的层次要区分（对后来人有用的方法论）**：
- **D-27 那种错（方法错）**：拿回退后的代码核回退前的账——核错了对象。
- **fix-upload-token-chain 那种错（时效错）**：方法没错，是把一个**有时效的取证结论**当成了**无时效的事实**。观测在 4cba23618 之前，「零命中」当时是真的、也确实证明了 archive 那份账是假账；错在复述时把「一个时间点的观测」说成了「任何提交里都没存在过」。引用旧取证结论前必须重跑观测。

leader 今晚在这两条上各栽一次（fix-d27-v3 / fix-scrollback-d36 为①，fix-upload-token-chain 为②）。
本报告第一节三份证据的判定，全部按此两条纪律复核。

**纪律①的最完整实例：D-36（2026-08-13 用户纠正，未闭环）**
D-36 完整故事是「回退期间立的账不能用回退后的代码核」最有力的证明：
> 它早在 v5 就修好过 → leader 把主干退到 v2 基线时**连带弄丢**了服务端那一半 →
> 而生产 daemon 进程从未重启，内存里仍是 v5 服务端，**所以整整一天没人发现它丢了** →
> 直到 2026-08-13 凌晨重启 daemon，缺陷现形 →
> leader 把服务端整体恢复到 v5（07f065db0）→ **服务端那一半确实回到代码里，且会话名恢复是用户亲验**。

**但 D-36 本身未闭环**（leader msg_f0c1788d4ad0 撤回）：用户先报「向上滑好了」，leader 据此准备结案；用户随即纠正「向上滑**没有好**，第一次进 CLI 大量从上往下加载时可以滑，**只要发一条消息就不能滑了**」。教训见第四条纪律——好消息同样要核实。

教训比抽象规则更直接：**代码在、进程旧、账却新**——用「回退后的代码」去核「回退前的账」，同时被「从未重启的旧进程」掩盖，整整一天没人发现。判断假账必须回到「记录被写下的那个时间点的代码状态」+「那个时间点进程里跑的是什么」。服务端恢复 v5 成立（会话名亲验），**但不等于 D-36 修好**。

**同一天、同一原因、方向相反的第二例：D-26 误检（2026-08-13，可能已随服务端恢复消失）**
D-36 与 D-26 是「回退期间立的账不能用回退后的代码核」**两个方向**的实例：
> D-36 是「**以为修好其实没修**」——用户先报「向上滑好了」，实际没修；
> D-26 是「**以为没修其实可能修好了**」——用户报「已停止却显示工作中」，但那次报告在**昨夜重启 daemon 之前**，当时跑的是回退前旧二进制（含被否决的 ◐ 白名单）。

**daemon 归属已核实（leader 实测）**：
- pid 92111 = `server/agentmirrord`，监听 :9900，**用户手机连的就是它**，v5 恢复（07f065db0）后清理过的源码现编——**生产进程不脏**；
- pid 43450 = w-base-v2 正在用的隔离测试 daemon（端口 38368），不是残留。

**推论（leader 已请用户确认）**：误检很可能已随服务端恢复消失——因为用户报告时跑的是旧二进制（含 ◐ 白名单），重启后是干净源码。**用户报告与当前跑的代码不是同一时间点**，与 D-36 同形。

两个方向栽在同一件事上：**账（用户报告）与代码（当前跑什么）不是同一个时间点的东西。** 判断「修没修」必须确认报告对应的是哪个进程、哪个版本的代码。

---

## 一、三份「与代码不符」的证据

### 1.1 fix-upload-token-chain — ✅ 唯一真假账（archive FALSIFIED），真实修复已提交且验收

| 载体 | 状态 | 判定 |
|---|---|---|
| `.team/evidence/archive/fix-upload-token-chain.FALSIFIED-20260812.json` | status=pass，已冠 FALSIFIED 入 archive | ✅ **真假账，归档正确** |
| `.team/evidence/fix-upload-token-chain.json`（主目录） | status=`pass_user_confirmed`（w-dev-d22/w-rev-d22/w-test-d22/w-base-v2 四席） | ✅ **真实修复的验收** |

**两条线要分清**：
1. **archive FALSIFIED 是真假账**（leader 判断正确）：w-fix-upload2 席声称「验证了工作树已实现完整链路」，但该链路当时只存在于未提交工作区，验证席据此立 status=pass 账——**那笔账当时是假的**。
2. **但该任务不是「全历史零命中」**（leader 补充的硬判据不成立）：4cba23618（2026-08-12 21:54）已提交真实修复——ServiceWire.currentConfig()（ServiceWire.kt:171）、SessionRoute.kt:133、SessionViewModel.kt:57/295、HttpUrlConnectionUploader.kt:113-116/161 的 Bearer 头。主目录 pass_user_confirmed 证据正是该提交的验收记录（commit message 自述成因：写码席工作区未提交→验证席立假账→改动被 checkout 抹除→「未提交的工作不是交付物」）。
   **`git log --all -S"Bearer" -- app/` 实际命中 4 个提交**：0fa842ace、**4cba23618**、d6f450e16、0140d5ac7。leader 所述「全历史零命中、任何提交都没存在过」**不成立**——真实修复代码在 4cba23618 完整存在，且 archive FALSIFIED 档案本身就是 4cba23618 主动创建并提交的（commit message「附带取证」）。
3. **误判根因**：leader 把「archive 那份是假账」与「该任务全历史零命中」混为一谈。正确表述：**archive 那份记录是假账（其声称验证的链路当时未提交）；但该任务随后被真实修复并验收，主目录 pass_user_confirmed 证据有效。**「唯一的假账」指 archive 那份文件，不是指该任务从未修复。

**结论**：archive FALSIFIED 保持标死（假账没错）；主目录 pass_user_confirmed 证据有效保留。仅需在账本上明确「archive=FALSIFIED、主目录=真实验收」两份的区分，即达「不可能被当已修引用」。无待办。

### 1.2 fix-scrollback-d36 — ✅ 回退受害者，非假账；从 archive 取回或改 FALSIFIED 标注（leader 已批）

| 载体 | 状态 | 判定 |
|---|---|---|
| `.team/evidence/archive/fix-scrollback-d36.FALSIFIED-20260812.json` | 已冠 FALSIFIED 入 archive | ❌ **误标**，需取回或改标注 |
| `.team/evidence/fix-scrollback-d36.json`（主目录） | status=pass，在主目录 | ✅ 记录属实 |

**代码事实**：当前 HEAD（07f065db0 之后）的 ws_handler.go **确实包含** D-36 修复逻辑——
- handleScrollback 已去 `-pane.Height` 平移（237-259 行注释直引「through (D-36)」）；
- historySize 用 `capture -E -1`（不含屏）行数，不再双计屏（386-396 行）；
- 分支边界 `requestEnd <= oldest`、裁尾空行（270 行）均在。

**leader 裁定（msg_5c01e1d5dbf8）**：v5 里本就有 D-36 服务端修复（恢复服务端时亲见那段 D-36 注释），它是**回退受害者，不是假账**。处置：从 archive 取回，或至少改掉 FALSIFIED 命名与措辞，改标注同 fix-d27-v3。落地方式由 librarian 给方案、leader 批。

**librarian 落地建议（供批）**：
- 主目录 `.team/evidence/fix-scrollback-d36.json` 记录属实、保留；
- archive 版 `fix-scrollback-d36.FALSIFIED-20260812.json` 更名/改措辞为「回退受害者」而非 FALSIFIED，标注改为：**记录属实；所述实现曾随主干回退丢失，07f065db0 恢复服务端至 v5 后已回到 HEAD；待模拟器实测复验。**
- 若 leader 选择「取回」，则移回主目录并与主目录 pass 记录合并成一份。
- 07f065db0 提交信息自述「v5 与今晚对 historySize 两种算法待定夺」——leader 已裁过时（见第四节），撤销此项。

**注意**：git log 中该逻辑由 07f065db0 恢复带入（v5 分支 2874c54 原本就有），故按新纪律，「回退受害者」判定成立。

### 1.3 fix-d27-v3 — ✅ leader 撤销判断，记录属实不标 FALSIFIED

| 载体 | 状态 | 判定 |
|---|---|---|
| `.team/evidence/fix-d27-v3.json` | status=pass，未标记 | ✅ 记录属实，改标注 |

**leader 裁定（msg_5c01e1d5dbf8，已独立复核 ws_handler.go:340-347 确有 no-op skip）**：
「当初说『代码里根本不存在』时，核的是**回退后**的代码，而那份账是**回退前**立的。方法错了。**这份记录不标 FALSIFIED。**」

**代码事实（当前 HEAD，07f065db0 之后）**：`ws_handler.go` handleResize 确实包含 no-op resize skip——
- 322-327 行 `br.Size()` 读 before dims；329 行 resize；337-341 行读 after dims；
- 342-348 行 `beforeW == afterW && beforeH == afterH` 则 `Debug("ws: resize no-op, skip snapshot")` 并 return，不补发 snapshot。
- git log -S"resize no-op, skip snapshot" 证实该逻辑由 **07f065db0（v5 恢复）引入**。

**时间线**：fix-d27-v3 证据立账于 2026-08-12 12:21（v5 时代）；07f065db0（2026-08-13 00:28）恢复服务端至 v5，no-op skip 回到 HEAD；恢复前基线（07f065db0^）的 handleResize 确实无条件补发——leader 当初判断基于回退后代码，故误判。

**处置（leader 裁定）**：改标注为——**记录属实；所述实现曾随主干回退丢失，07f065db0 恢复服务端至 v5 后已回到 HEAD；待模拟器实测复验。** D-27 的 no-op skip 当前在 HEAD，可据此先做模拟器眼见为实验证，不必先回退。

---

## 二、重复立案（同一缺陷多条 task id）

> 处置原则（leader 交代）：合并定义必须回用户原话（`requirement-wiki/raw/`）；定义冲突不挑一个，并列交裁。

### 2.1 D-38 — ✅ 已合并为一条（根因实测锁定，定义写准）

| task id | 状态 | 判定 |
|---|---|---|
| `fix-viewport-restore-d38` | 与 `fix-bg-resume-d38` 合并 | ✅ 合并为一条 |
| `fix-bg-resume-d38` | 旧定义「viewport 没滚到底部」**作废** | ❌ 旧定义错误，标作废 |

**合并后权威定义（leader 确证，2026-08-13；须回用户原话 + 客观数字，不用我们措辞）**：

**用户原话**：
> 「切到后台之后，再回到前台，对话界面的输入框会跑到中间去」「终端内容仅占屏幕顶部约 1/4，中间大片空黑」

**客观数字**：用户真实截图 `bottomMarginPx=1123`（健康 ≈6，差 160 倍）；内容画到 y=1676、行高 20px → 只画了 84 行，而视口装得下 140 行，**差 56 行 = 量到的空白**。

**实测复现序列**：进会话 → 点输入框唤起键盘 → 切后台 → 回前台（键盘仍在屏）→ 收起键盘 → `bottomMarginPx=106`，5 秒后仍 106（稳定态非渲染延迟）。

**根因**：回前台走 `onRealViewportChanged` 重算并上报，但那一刻键盘在屏，把**被挤压的几何当成了真实视口**；随后收起键盘走 `onViewportSizeChanged`，按 fix-ime-no-resize 规则不上报 → **挤压值被提拔成永久基线**。

**fix-bg-resume-d38 旧定义作废原因**：它把「内容没顶到底」当成了**滚动位置问题**（viewport 没滚到底部），实际是**行数不足**——视口装得下 140 行只画了 84 行，不是没滚到底，是几何没恢复。旧定义方向错误，不得沿用。
**另注**：合并后 fix-ime-no-resize 引入的风险（onViewportSizeChanged 不上报挤压值）由合并后本条承接，无需单独任务。

### 2.2 D-26 — 三条立案，非完全重复而是「路线三次转向」，需交裁分工

| task id | 定义/性质 | evidence |
|---|---|---|
| `fix-state-detection` | 「提升检测准确率，参考 herdr，state_wiring 已有框架需改进算法」 | 无 evidence 文件 |
| `fix-agentstate-detection-d26` | 「指示符换成了 ◐ 半填充圆动画，检测仍在匹配旧字形；只补新字形治标，两层一起做」 | status=`partial_layer1_live_layer2_dormant` |
| `study-herdr-agent-state` | 「调研 herdr 如何检测，终结『靠刮屏幕字符判状态』路线」；contention=contract | 无 evidence 文件 |

**用户原话（raw/025 + intake draft D-26）**：
> 「会话工作状态检测成功率和正确率非常低」「参考 herdr」「这三个状态实际是完成态……完全走偏了……通过 herdr 仓库去确定」「前两次修复：finalizeState 无效已回退；pane_title 信号+规则收紧用户初测正常但持续使用后仍未知；三次修复三次失败，升级到用户讨论。」

**分析**：这是 D-26 三次失败的路线演进，非同时重复立案。raw/025 记录：① finalizeState 已回退；② pane_title 已试；③ 升级讨论。`fix-state-detection` 是最初泛化立案（已过时）；`fix-agentstate-detection-d26` 是第二次方向（字形匹配）；`study-herdr-agent-state` 是用户裁定的最新方向（herdr 路线，contract 级）。**建议**：保留 `study-herdr-agent-state`（现行方向）为 D-26 唯一施工项；`fix-agentstate-detection-d26` 的 partial 状态需明确是否已被 study-herdr 吸收；`fix-state-detection` 建议归档或改注「已被后续立案取代」。分工交 leader 裁。

### 2.3 D-28/D-29 — cluster 与专项重叠

| task id | 覆盖 | evidence |
|---|---|---|
| `fix-terminal-resize-cluster` | D-20/D-21/D-28/D-29 四连（终端尺寸管理） | **无 evidence 文件** |
| `fix-pinch-preview-commit` | D-29 捏合闪烁 + D-31 缩放不持久化，并为 D-28 提供收敛点（raw/041 规则兑现） | 无 evidence 文件 |

**用户原话（raw/040 D-28 / raw/041 D-29）**：
> D-28「捏合放大字体后总有一部分文本在最右侧屏幕外面看不到」（原因推测：字号变大 cols 应变少，但 resize 被 D-20 锁挡）——需修复。
> D-29「捏合缩放过程中屏幕不断闪烁重绘，不流畅」——优化方向：捏合只本地视觉缩放、松手才发一次 resize。

**分析**：cluster 把 D-28/D-29 收在四连大盘下；pinch 专项单独把 D-29/D-31 立账并为 D-28 提供收敛点。二者对 D-28/D-29 重复覆盖。建议：以 `fix-pinch-preview-commit`（更贴合 raw/041 用户裁定的「捏合预览/松手生效」）为捏合链主立账，`fix-terminal-resize-cluster` 中 D-28/D-29 两项标注「由 fix-pinch-preview-commit 承接」，cluster 仅保留 D-20/D-21 或整体重构。分工交 leader 裁。

---

## 三、gate-static-analysis 38 条存量

> 核对方式：以 `docs/stage3-issue-inventory.md` 38 条逐条对照当前源码（staticcheck 未安装，以源码 grep/阅读为准；Android Lint 以源码现状为准）。

**当前存量：38 条中已修 31 条，剩 7 条。**

### staticcheck 9 条 — 全部已修（9/9）

| # | 原规则 | 现状 |
|---|--------|------|
| 1 | server.go:60 `loopOnce` unused | ✅ 已删（注释「a previously present loopOnce field was…」） |
| 2 | session.go:98 `bridgeFor` unused | ✅ 无命中 |
| 3 | connected_idle_economy_test.go:299 `snap` SA4006 | ✅ 已改 `_ = buildSnapshot(...)` 显式丢弃 |
| 4 | state_wiring_test.go:278 select 单 case | ✅ 无命中（已重构） |
| 5 | ws_test.go:114 `readControlTimeout` unused | ✅ 无命中 |
| 6 | ws_test.go:171 `readBinary` unused | ✅ 无命中 |
| 7 | rules.go:132 append 简化 | ✅ 已重构（splitLines 直接切片裁剪） |
| 8 | ws_test.go:215 `modelForSocket` unused | ✅ 无命中 |
| 9 | pidfile.go:26 `pidfileName` unused | ✅ 无命中 |

### Android Lint 29 条 — 已修 22，剩 7

**已修（22）**：
- #10 Manifest camera uses-feature → ✅ 已补（AndroidManifest.xml:44-45）
- #11 InlinedApi FOREGROUND_SERVICE_TYPE_DATA_SYNC → ✅ 已加 `SDK_INT >= Q` 守卫（MirrorForegroundService.kt:100）
- #12 ApplySharedPref commit → ✅ 改 KTX `prefs.edit(commit=true)`（PairingConfigStore.kt:135）
- #13/#14/#15 UseKtx edit() → ✅ 改 KTX `prefs.edit {}`（PairingConfigStore.kt:77/92/116）
- #16 UseKtx String.toUri → ✅ 已改（PairingScreen.kt:188）
- #17 ObsoleteSdkInt → ✅ 无命中（NotificationHelper.kt 已清理）
- #18 mipmap-anydpi-v26 → ✅ 目录已删
- #19 brand_primary UnusedResources → ✅ 已删（Theme.kt:51 注释说明随 stage3 #19 删除）
- #23 gradle 8.14.5 → ✅ wrapper 已升（gradle-wrapper.properties:3）
- #24/#30 compose-bom 2026.06.01 → ✅ 已升（build.gradle.kts:95/132）
- #26-29 camera 1.6.1 → ✅ 已升（build.gradle.kts:112-115）
- #32 serialization-json 1.11.0 → ✅ 已升
- #34 zxing 3.5.4 → ✅ 已升
- #36-38 tsnetbind Aligned16KB ×3 → ✅ build.sh 已加 `-Wl,-z,max-page-size=16384`（fix-tsnetbind-align 注释自述 readelf 验证 4KB→16KB）；AAR 时间戳 8/11 12:07 在修复之后

**未修（7，全部为低风险基建/视觉补缺）**：
| # | 规则 | 位置 | 严重度 |
|---|------|------|--------|
| 20 | MonochromeLauncherIcon | ic_launcher.xml | P3（Android 13+ 主题图标补缺） |
| 21 | MonochromeLauncherIcon | ic_launcher_round.xml | P3（同上） |
| 22 | OldTargetApi | targetSdk 35（build.gradle.kts:50，有注释「targetSdk 保持 35，不改变运行时行为」） | P2（排期决策，非缺陷） |
| 25 | GradleDependency | core-ktx 1.18.0 | P3 |
| 31 | NewerVersionAvailable | kotlin serialization 2.2.0 | P3 |
| 33 | NewerVersionAvailable | okhttp 4.12.0 | P3 |
| 35 | NewerVersionAvailable | mockwebserver 4.12.0 | P3 |

**结论**：剩余 7 条全为依赖升级（5 条）+ 图标/排期（2 条），无即时风险，与 inventory 附录「依赖升级类归入基建排期」的判断一致。staticcheck 9 条已全部清零。**建议**：7 条可随依赖升级专项或图标专项顺手收口；`gate-static-analysis` 若仍挂 red，可据此更新存量账。

---

## 四、需 leader 裁定的点（订正版，已裁 5 项、挂起 2 项）

### 已裁（leader msg_5c01e1d5dbf8）
1. **fix-d27-v3**：✅ 不标 FALSIFIED，改标注「记录属实；所述实现曾随主干回退丢失，07f065db0 恢复服务端至 v5 后已回到 HEAD；待模拟器实测复验」。D-27 先按 HEAD 逻辑做模拟器眼见为实验证，不必先回退。

2. **fix-scrollback-d36**：✅ 回退受害者非假账。处置：从 archive 取回或改掉 FALSIFIED 命名/措辞，标注同上。librarian 建议：主目录 pass 保留、archive 版改标注为「回退受害者」或取回合并（见 1.2 落地建议，待 leader 批）。

3. **D-36 historySize 两种算法「待定夺」**：✅ 已过时撤销。librarian 比对为同一算法（Scrollback(-∞,-1) 去屏高），仅变量名/边界微调。

4. **gate-static-analysis 存量 7 条**：✅ 数字接受，按低优先级挂账，不插队缺陷线。

### 已裁（本轮更新）
5. **D-38 权威定义**：✅ 根因已实测锁定，`fix-viewport-restore-d38` 与 `fix-bg-resume-d38` **合并为一条**（见 2.1 节，用户原话 + 客观数字 + 复现序列 + 根因）。`fix-bg-resume-d38` 旧定义「viewport 没滚到底部」**作废**（错把行数不足当滚动位置问题）。

### 挂起（leader 裁定：连同实测结论一起裁）
6. **D-26 分工**：`study-herdr-agent-state`（用户裁定 herdr 路线，contract）是否为 D-26 唯一施工项；`fix-agentstate-detection-d26` partial 是否被吸收；`fix-state-detection` 是否归档改注。一并等实测裁。

7. **D-28/D-29 承接**：以 `fix-pinch-preview-commit`（贴合 raw/041「捏合预览/松手生效」）为主，cluster 中 D-28/D-29 标注承接，cluster 保留 D-20/D-21 或重构。

---

## 五、librarian 复核发现的额外出入（顶回记录）

leader 在裁定中说「真正的假账只有一份 fix-upload-token-chain——`git log --all -S"Bearer" -- app/` 全历史零命中」。
**复核结果：该硬判据不成立。** `git log --all -S"Bearer" -- app/` 实际命中 4 个提交（0fa842ace / **4cba23618** / d6f450e16 / 0140d5ac7），其中 **4cba23618 就是 D-22 真实修复**（含 ServiceWire.currentConfig + Bearer 头上送），且 archive FALSIFIED 档案本身就是 4cba23618 主动创建提交的。

**正确定位**：archive 那份 FALSIFIED **是真假账**（w-fix-upload2 验证的是未提交工作区改动）；但「该任务从未修复、全历史零命中」不成立——真实修复在 4cba23618，主目录 pass_user_confirmed 是它的验收。

**错误层次（leader 已裁定，msg_8e1d1bcd91e8）**：这次**不是方法错**（与 D-27 不同），而是**时效错**——把「一个有时效的取证结论」当成了「无时效的事实」。当初取证在 21:54 之前（4cba23618 尚未存在），「零命中」在当时是真的、也确实证明了 archive 那份账是假账；错在后来的复述把「观测那一刻的全历史零命中」说成了「任何提交里都没存在过」。

**定论（leader 已接受，无需变更 taskbook）**：两线分离正确——archive FALSIFIED 保持标死（它当时确实是假账）；主目录 pass_user_confirmed 有效保留（4cba23618 是真修复，用户已亲验）。

---

## 六、新缺陷提议：客户端从不显示光标（w-dev-repaint 附带发现 + librarian 撞库）

> 本条目为**提议**，非裁定。撞库结果 + 客观代码事实如下；是否为缺陷/有意为之，**由 leader 问用户定夺**（librarian 不替用户判定）。

### 撞库结果（librarian，2026-08-13）

- `requirement-wiki/raw/` + `requirement-base/entries/` + `requirement-wiki/wiki/` 全库「光标/cursor」**零命中**；
- **未找到**「明确不要光标」的裁定，也**未找到**「砍掉/删除光标功能」的记录（对比先例「删除通知开关：追溯不到用户裁定」）；
- 结论：撞库无命中，未发现相关裁定。

### 代码事实（客观，w-dev-repaint 已核实 + librarian 复核一致）

- `ScreenSnapshot` 携带 `cursorX` / `cursorY` / `cursorVisible`（`app/terminal/src/main/kotlin/dev/agentmirror/terminal/TerminalEmulator.kt:22-30`），内核正常维护 cursor 状态（`setCursor` 等，269/284 行）；
- `TermSurfaceView` / `TermViewPresenter`（termview 层）对 cursor **零引用**，`grep cursor*` 无任何 draw 路径消费它；
- 即客观事实：**协议已把光标位置送达客户端，渲染层未消费。App 上不显示光标。**

### 待 leader 问用户（不预设答案）

1. 是否需要显示光标？
2. 若要 → 新立案（如 `fix-cursor-render`），写 `ScreenSnapshot.cursorX/Y/Visible` 的渲染路径；
3. 若不要 → 记入需求维基「明确不要光标」裁定，防后续重复立案。

### 关联

`[[UI视觉标准]]`（018）第 7 项终端页专项——终端渲染层是 UI 视觉标准的直接约束面。

---

## 七、用户 2026-08-13 确认记录

> 出处：用户 2026-08-13 上午原话「我的 app 不变，但是 ts 的连接接近 lan 了，且向上滑好了」——**前半句成立（TS 链路/App 未变），后半句「向上滑好了」为误报，用户随即纠正。**

### 7.1 D-36 — ❌ 未修，`refuted_by_user`（user_confirmed 升级已撤销）

- `.team/evidence/fix-scrollback-history-d36.json` 状态现为 **`refuted_by_user`**（2026-08-13）；
- **曾误升级**为 `pass_user_confirmed`（leader 据用户半句「向上滑好了」派指令，撤回与执行交叉）——已撤销，`user_confirmed_at`/`confirmation_conditions` 字段已删除；
- **用户纠正原话**：「向上滑**没有好**，如果第一次进入 cli，那么会大量从上往下加载，这个时候**可以向上滑**，但是**只要发一条消息，就不能向上滑了**。」
- **新复现步骤（四轮以来第一次可用的状态转移）**：进 CLI → 大量从上往下加载 → 此时可滑 → 发一条消息 → 不可滑。按此重验定位根因。
- **切割**：服务端恢复 v5（07f065db0）→ 会话名恢复**已用户亲验**；但**会话名恢复 ≠ D-36 修好**。完整故事见〇节纪律①实例 + 纪律④⑤。

### 7.2 用户报告 TS 链路质量改善（原因未确认，非我们的功劳）

- 用户原话「ts 的连接接近 lan 了」——**不要记成我们的功劳**：原因未确认（可能是 Tailscale 自己打通了直连）；
- 已记：**用户报告 TS 链路质量改善，原因未确认**。

### 7.3 用户裁定：延迟优化不因此降优先级（记入裁定清单）

- 用户对「TS 连接接近 lan」的回应是「能优化就优化，测出来延迟高就得优化」；
- 裁定：**延迟优化不因链路质量改善而降优先级**——若实测延迟高仍须优化；
- 这是用户裁定，须入库（待 leader 转 requirement-wiki 或 requirement-base 立案）。

---

*审计完成并订正 2026-08-13。仅写 docs/，未改 taskbook.yaml，未 commit。建议的 taskbook 改动由 leader 执行。*
