# 知识基底 · ledger.vz.v1 / t.glyph（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
## 知识基底（已内联） —— tools/basegen_ledger.py 现算的模块影响闭包，**正向依赖=你消费的契约，反向依赖=你的回归自查范围**。⛔ 不看它就动手 = 凭空猜架构。原件 .team/nodes/glyph/BASE.md。

# 知识基底 · ledger.vz.v1 / t.glyph（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
修 **框线断点 + Claude Code logo「黑底」**——契约 requirement-base/entries/083-真机视觉收口六条.md **§1**（先读全文）。
🖼 现场：/Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots/11-浅色下框线断点与logo黑底.jpg（放大图能看清块与块之间的缝）
用户原话：「线段中间有断点」「Claude Code 那个图标存在黑底……长得很别扭」。

🔴 **这两条是同一个根因，一处修两处好，⛔ 不要当成两个问题分开修。**
logo 不是"有黑底"，是**块与块之间接不上、缝里露出了背景色**。

**机理**（leader 已定位）：框线 U+2500–257F 与块元素 U+2580–259F 在 `GlyphSlot.kt` 走**同一条 SYSTEM_FALLBACK 路径**，
而该路径是 `TermSurfaceView.drawCentered` —— **逐格把字形在格内居中**：
```kotlin
val actual = paint.measureText(text, i, j)   // 字形自然宽度 < 格宽
centeredGlyphX(x, cellPx, actual)            // 居中 ⇒ 两侧各留 (cellPx-actual)/2
```
⇒ 框线接不上（断点）、色块接不上（露背景）。
🔴 **准确的根因表述**：绘制依赖「**字形自然宽度 + 亚像素取整**」，**在非整数密度上必崩**；居中只是放大器。
这也解释了为什么模拟器（density 整数 3.0）看不出来、真机（非整数密度）满屏是缝。

**修法（已定，⛔ 不要改成拉伸字形 textScaleX）**：这两类字符**改用 Canvas 画几何，不用字形**。
- 块元素：`█` 整格 / `▀` 上半 / `▄` 下半 / `▌` 左半 / `▐` 右半 / `░▒▓` 按密度
- 框线：128 个字符映射成「本格画哪几条边 + 粗细」，**格边界按整数像素对齐**
✅ 附带收益：免疫换字体——将来换终端字体，框线和块不会跟着崩。

**判据 A-gl-seam**（先验红）：造一屏含连续框线（`─────`、`│` 竖排）与实心块（`███`）的内容，
**在非整数密度下**断言相邻格之间**无背景色像素**（可截屏后按像素采样，或单测断言绘制矩形首尾相接：`rect[n].right == rect[n+1].left`）。
🔴 判据必须能区分「**缝**」和「**字形本来就细**」——量**相邻格绘制矩形的边界坐标**，两个数都要记。
🔴 **必须在整数密度与非整数密度各跑一次**，并把两组读数都写进说明（整数那台可能一开始就是绿的，那正是模拟器骗过我们的原因）。
配套单测名须含 `GlyphSeam`。说明写 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-glyph/说明.md。

---
🔴🔴 **判据形态**：机械判据用 `python3 /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py` 做内容断言，
配可重跑探针 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-glyph/ui-check.sh`（**改前必须红、改后必须绿**，红证据写进说明）。
颜色/尺寸 UI 树读不到 ⇒ **单测断言常量值**。⛔ 不许用「截图 md5 互不相同」当判据。⛔ 脚本 trap 收尾不留后台进程。

🔴🔴🔴 **模拟器在渲染层没有分辨力（契约 083 §0，本轮最重要的一条）**
用户原话：「上面那个展示的问题，**在模拟器上是完全正常的**，但是我在**手机上就完全不正常**。」
⇒ 密度 / 字体 fallback / 亚像素取整都不同，**模拟器绿 ≠ 真机绿**。
⇒ 凡是渲染类判据，**必须在至少两个不同 density 的 AVD 上各跑一遍**，
   其中**必须有一个非整数密度**（2.75 或 3.5）——整数密度会把取整误差掩盖掉。
   建 AVD 用 `~/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager` + `-prop hw.lcd.density=...`，
   或用 `adb shell wm density 420` 临时改密度再跑（后者更快，⛔ 跑完必须 `wm density reset`）。

🔴🔴🔴 **最高优先级 · 与派单正文下方「## 工作目录」那段冲突时以本条为准**：忽略那段，一律在**仓根** /Volumes/nvme/Projects/远程Agent安卓 干活。所有判据 cwd 都是仓根。⛔ 不要 `git worktree add`。
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不发进度/提问/完工通知。唯一对外动作是干完调一次 `report_result`。卡住写进说明.md。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux。
✅ 模拟器 `emulator-5554` 在跑；adb=~/Library/Android/sdk/platform-tools/adb；先 `adb reverse tcp:9900 tcp:9900`。⛔ 截图前关输入法。
🟢 **⛔ 不得倒退**：073 身份键含 socket / 075 一级转圈 / 076 三条 / 077 §1 会话页标题 / 078 §1 首列不被裁 / 081 cols 仪表 / 082 收藏页按各工作区取数 / land-v1 设计落位五格。

```

- write_paths: app/, .team/nodes/vz-glyph/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md, /Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots, /Volumes/nvme/Projects/远程Agent安卓/design/compose-handoff, /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py, /Volumes/nvme/Projects/远程Agent安卓/app/app/src/
- 判据: A-gl-test, A-gl-suite, A-gl-ui, A-gl-shot, A-gl-doc

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：dev.agentmirror.app
- 正向依赖（你消费的契约，只读）：kt_dev_agentmirror_app_pairing, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_session, kt_dev_agentmirror_app_ui_theme, kt_dev_agentmirror_app_workspace
- **反向依赖（波及面 = 回归自查范围）**：kt_dev_agentmirror_app_service

### 闭包架构卡内联

### Kotlin · dev.agentmirror.app

- **职责**：Compose 应用根组合。
- **导出面**：AgentMirrorApp, MainActivity, MainNavState, Session
- **依赖边**：dev.agentmirror.app.pairing, dev.agentmirror.app.service, dev.agentmirror.app.session, dev.agentmirror.app.ui.theme, dev.agentmirror.app.workspace
- **doc 全文**：Compose 应用根组合。 依需求 004「客户端无状态」，本组件只做路由，不持有任何业务状态。 首启路由（pairing-ui 知识基底 §1）： - 无配对配置 → 配对页（扫码/手填，可跳过进空工作区）； - 有配对配置 → 直进工作区列表； - 配对页可从设置/重配入口重进（重新配对）。 会话页跳转沿用 session-ui 挂载的 [SessionRoute]。 导航态由 [navState]（D-3 修复）注入：MainActivity 在 onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。 工作区 VM（[workspaceViewModel]）由 MainActivity 持有（fix-workspace-wiring 修复， navState 同模式提升），本组件只负责在工作区分支用 [DisposableEffect] 把它接入 [ServiceWire.uiConnector]——配对成功后列表能渲染（此前 VM 裸建从未接线，uiConnector 全仓无调用点，见 fix-workspace-wiring 知识基底）。 @consumes dev.agentmirror.app.pairing @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.session @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.app.workspace

### Kotlin · dev.agentmirror.app.service

- **职责**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。
- **导出面**：AppClockPump, MirrorForegroundService, NetworkConnectivityWatcher, NoopTransportFactory, NotificationHelper, OkHttpTransportFactory, OkHttpWebSocketTransport, OnScreenFallbackPump, ServiceWire, StateWatcher, TsnetBootstrap
- **依赖边**：dev.agentmirror.app, dev.agentmirror.app.conn, dev.agentmirror.app.tsnet
- **doc 全文**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。 分层（fg-service 知识基底 §1）： - [StateWatcher]：纯 JVM 核心逻辑（验收单测全打这里），消费 conn 层 listing/list_delta 流，检测会话状态沿变化（→blocked/→done）→ 通知；同状态重复抑制；unknown 不通知。 - [NotificationHelper]：通知渠道（常驻/状态两条）+ 常驻通知与状态通知 + 会话页深链 PendingIntent（action/extra 由 [MainActivity] 的 handleDeepLink 消费，非本包）。 - [MirrorForegroundService]：薄 Android 层，startForeground（dataSync）+ 生命周期绑定 [ConnectionManager]（经 [ServiceWire]）；断连静默重连归 conn 层，本服务只反映状态。 已接线（feat-fg-service-wiring）：配对成功/冷启动/进入会话经 [MirrorForegroundService.start] 启动（startForegroundService），连接与时钟泵由本服务承接（004/011 前台服务路线）。 - [ServiceWire]：接线点——传输工厂（默认 [OkHttpTransportFactory]）、UI 监听桥 （[uiConnector]）与服务监听槽（[serviceListener]）、连接配置注入；进程级持有唯一 [ConnectionManager]，服务与 UI 都经它访问同一单例。 电量策略（004 裁定）：服务被系统杀 → 冷启动重连即恢复（客户端无状态，没有丢失可言）。 服务**不持有连接状态**（004 无状态底线）：连接是 [ServiceWire] 进程级单例，配置唯一来源 是 SharedPreferences，服务只经 [ServiceWire.managerOrNull] 读取并驱动时钟泵 （[MirrorForegroundService.pumpOnce]，2s 一拍，在屏组合不再各自持有）。服务不可用时 在屏兜底泵 [OnScreenFallbackPump] 接管（fix-app-runtime-sa：服务被杀前台仍推进）， 服务恢复即让出（泵归属判据 [ServiceWire.servicePumpActive]，不双泵）。 @consumes dev.agentmirror.app @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.tsnet

## 3. 需求基
- 标题引用条目：requirement-base/entries/083*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。


---
以上是基底，以下是任务。

修 **框线断点 + Claude Code logo「黑底」**——契约 requirement-base/entries/083-真机视觉收口六条.md **§1**（先读全文）。
🖼 现场：/Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots/11-浅色下框线断点与logo黑底.jpg（放大图能看清块与块之间的缝）
用户原话：「线段中间有断点」「Claude Code 那个图标存在黑底……长得很别扭」。

🔴 **这两条是同一个根因，一处修两处好，⛔ 不要当成两个问题分开修。**
logo 不是"有黑底"，是**块与块之间接不上、缝里露出了背景色**。

**机理**（leader 已定位）：框线 U+2500–257F 与块元素 U+2580–259F 在 `GlyphSlot.kt` 走**同一条 SYSTEM_FALLBACK 路径**，
而该路径是 `TermSurfaceView.drawCentered` —— **逐格把字形在格内居中**：
```kotlin
val actual = paint.measureText(text, i, j)   // 字形自然宽度 < 格宽
centeredGlyphX(x, cellPx, actual)            // 居中 ⇒ 两侧各留 (cellPx-actual)/2
```
⇒ 框线接不上（断点）、色块接不上（露背景）。
🔴 **准确的根因表述**：绘制依赖「**字形自然宽度 + 亚像素取整**」，**在非整数密度上必崩**；居中只是放大器。
这也解释了为什么模拟器（density 整数 3.0）看不出来、真机（非整数密度）满屏是缝。

**修法（已定，⛔ 不要改成拉伸字形 textScaleX）**：这两类字符**改用 Canvas 画几何，不用字形**。
- 块元素：`█` 整格 / `▀` 上半 / `▄` 下半 / `▌` 左半 / `▐` 右半 / `░▒▓` 按密度
- 框线：128 个字符映射成「本格画哪几条边 + 粗细」，**格边界按整数像素对齐**
✅ 附带收益：免疫换字体——将来换终端字体，框线和块不会跟着崩。

**判据 A-gl-seam**（先验红）：造一屏含连续框线（`─────`、`│` 竖排）与实心块（`███`）的内容，
**在非整数密度下**断言相邻格之间**无背景色像素**（可截屏后按像素采样，或单测断言绘制矩形首尾相接：`rect[n].right == rect[n+1].left`）。
🔴 判据必须能区分「**缝**」和「**字形本来就细**」——量**相邻格绘制矩形的边界坐标**，两个数都要记。
🔴 **必须在整数密度与非整数密度各跑一次**，并把两组读数都写进说明（整数那台可能一开始就是绿的，那正是模拟器骗过我们的原因）。
配套单测名须含 `GlyphSeam`。说明写 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-glyph/说明.md。

---
🔴🔴 **判据形态**：机械判据用 `python3 /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py` 做内容断言，
配可重跑探针 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-glyph/ui-check.sh`（**改前必须红、改后必须绿**，红证据写进说明）。
颜色/尺寸 UI 树读不到 ⇒ **单测断言常量值**。⛔ 不许用「截图 md5 互不相同」当判据。⛔ 脚本 trap 收尾不留后台进程。

🔴🔴🔴 **模拟器在渲染层没有分辨力（契约 083 §0，本轮最重要的一条）**
用户原话：「上面那个展示的问题，**在模拟器上是完全正常的**，但是我在**手机上就完全不正常**。」
⇒ 密度 / 字体 fallback / 亚像素取整都不同，**模拟器绿 ≠ 真机绿**。
⇒ 凡是渲染类判据，**必须在至少两个不同 density 的 AVD 上各跑一遍**，
   其中**必须有一个非整数密度**（2.75 或 3.5）——整数密度会把取整误差掩盖掉。
   建 AVD 用 `~/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager` + `-prop hw.lcd.density=...`，
   或用 `adb shell wm density 420` 临时改密度再跑（后者更快，⛔ 跑完必须 `wm density reset`）。

🔴🔴🔴 **最高优先级 · 与派单正文下方「## 工作目录」那段冲突时以本条为准**：忽略那段，一律在**仓根** /Volumes/nvme/Projects/远程Agent安卓 干活。所有判据 cwd 都是仓根。⛔ 不要 `git worktree add`。
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不发进度/提问/完工通知。唯一对外动作是干完调一次 `report_result`。卡住写进说明.md。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux。
✅ 模拟器 `emulator-5554` 在跑；adb=~/Library/Android/sdk/platform-tools/adb；先 `adb reverse tcp:9900 tcp:9900`。⛔ 截图前关输入法。
🟢 **⛔ 不得倒退**：073 身份键含 socket / 075 一级转圈 / 076 三条 / 077 §1 会话页标题 / 078 §1 首列不被裁 / 081 cols 仪表 / 082 收藏页按各工作区取数 / land-v1 设计落位五格。


---
🔴 **本格追加：诊断日志每帧刷屏（用户 2026-08-19 实测）**
🖼 /Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots/14-诊断日志每帧刷屏.png
用户原话：「有必要把日志，这些重复日志，让它减少打印。」

现场：`[term-left-edge] source=onDraw contentLeft=49 col0Origin=49 cellW=17 viewW=1204 verdict=OK`
**每次 onDraw 一条**，100ms 内连打 6 条，全部数值完全相同，把导出日志淹掉。

🔴 **修法不是删仪表**（081 刚立了「补仪表」的规矩，删掉等于自废武功），而是**去重**：
- **只在参与比较的操作数发生变化时才记**（`contentLeft` / `col0Origin` / `cellW` / `viewW` 任一变了才打）。
- 值不变时**至多按时间节流**（如 ≥5s 一条），或干脆不打。
- 🔴 **verdict 从 OK 变成非 OK 必须立刻打**，⛔ 不许被去重吃掉——那是「失败可见」。
- 同样处理 `t.reflow` 新加的 cols 仪表（`derived_cols` / `frame cols`），别让它重蹈覆辙。

**判据 A-gl-quiet**：连续 10 秒正常重绘，`[term-left-edge]` 条数 **≤ 3**；
**人为改一次 viewW（旋转或改密度）后必须立刻出现一条新记录**。
🔴 两个断言缺一不可——只断言"变少了"会诱导把仪表整个关掉。
```

- write_paths: app/, .team/nodes/vz-glyph/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md, /Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots, /Volumes/nvme/Projects/远程Agent安卓/design/compose-handoff, /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py, /Volumes/nvme/Projects/远程Agent安卓/app/app/src/, .team/nodes/glyph/BASE.md
- 判据: A-gl-test, A-gl-suite, A-gl-ui, A-gl-shot, A-gl-doc

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：dev.agentmirror.app
- 正向依赖（你消费的契约，只读）：kt_dev_agentmirror_app_pairing, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_session, kt_dev_agentmirror_app_ui_theme, kt_dev_agentmirror_app_workspace
- **反向依赖（波及面 = 回归自查范围）**：kt_dev_agentmirror_app_service

### 闭包架构卡内联

### Kotlin · dev.agentmirror.app

- **职责**：Compose 应用根组合。
- **导出面**：AgentMirrorApp, MainActivity, MainNavState, Session
- **依赖边**：dev.agentmirror.app.pairing, dev.agentmirror.app.service, dev.agentmirror.app.session, dev.agentmirror.app.ui.theme, dev.agentmirror.app.workspace
- **doc 全文**：Compose 应用根组合。 依需求 004「客户端无状态」，本组件只做路由，不持有任何业务状态。 首启路由（pairing-ui 知识基底 §1）： - 无配对配置 → 配对页（扫码/手填，可跳过进空工作区）； - 有配对配置 → 直进工作区列表； - 配对页可从设置/重配入口重进（重新配对）。 会话页跳转沿用 session-ui 挂载的 [SessionRoute]。 导航态由 [navState]（D-3 修复）注入：MainActivity 在 onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。 工作区 VM（[workspaceViewModel]）由 MainActivity 持有（fix-workspace-wiring 修复， navState 同模式提升），本组件只负责在工作区分支用 [DisposableEffect] 把它接入 [ServiceWire.uiConnector]——配对成功后列表能渲染（此前 VM 裸建从未接线，uiConnector 全仓无调用点，见 fix-workspace-wiring 知识基底）。 @consumes dev.agentmirror.app.pairing @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.session @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.app.workspace

### Kotlin · dev.agentmirror.app.service

- **职责**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。
- **导出面**：AppClockPump, MirrorForegroundService, NetworkConnectivityWatcher, NoopTransportFactory, NotificationHelper, OkHttpTransportFactory, OkHttpWebSocketTransport, OnScreenFallbackPump, ServiceWire, StateWatcher, TsnetBootstrap
- **依赖边**：dev.agentmirror.app, dev.agentmirror.app.conn, dev.agentmirror.app.tsnet
- **doc 全文**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。 分层（fg-service 知识基底 §1）： - [StateWatcher]：纯 JVM 核心逻辑（验收单测全打这里），消费 conn 层 listing/list_delta 流，检测会话状态沿变化（→blocked/→done）→ 通知；同状态重复抑制；unknown 不通知。 - [NotificationHelper]：通知渠道（常驻/状态两条）+ 常驻通知与状态通知 + 会话页深链 PendingIntent（action/extra 由 [MainActivity] 的 handleDeepLink 消费，非本包）。 - [MirrorForegroundService]：薄 Android 层，startForeground（dataSync）+ 生命周期绑定 [ConnectionManager]（经 [ServiceWire]）；断连静默重连归 conn 层，本服务只反映状态。 已接线（feat-fg-service-wiring）：配对成功/冷启动/进入会话经 [MirrorForegroundService.start] 启动（startForegroundService），连接与时钟泵由本服务承接（004/011 前台服务路线）。 - [ServiceWire]：接线点——传输工厂（默认 [OkHttpTransportFactory]）、UI 监听桥 （[uiConnector]）与服务监听槽（[serviceListener]）、连接配置注入；进程级持有唯一 [ConnectionManager]，服务与 UI 都经它访问同一单例。 电量策略（004 裁定）：服务被系统杀 → 冷启动重连即恢复（客户端无状态，没有丢失可言）。 服务**不持有连接状态**（004 无状态底线）：连接是 [ServiceWire] 进程级单例，配置唯一来源 是 SharedPreferences，服务只经 [ServiceWire.managerOrNull] 读取并驱动时钟泵 （[MirrorForegroundService.pumpOnce]，2s 一拍，在屏组合不再各自持有）。服务不可用时 在屏兜底泵 [OnScreenFallbackPump] 接管（fix-app-runtime-sa：服务被杀前台仍推进）， 服务恢复即让出（泵归属判据 [ServiceWire.servicePumpActive]，不双泵）。 @consumes dev.agentmirror.app @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.tsnet

## 3. 需求基
- 标题引用条目：requirement-base/entries/083*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
