# 知识基底 · ledger.land.v1 / t.term（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
## 知识基底（已内联） —— tools/basegen_ledger.py 现算的模块影响闭包，**正向依赖=你消费的契约，反向依赖=你的回归自查范围**。⛔ 不看它就动手 = 凭空猜架构。原件 .team/nodes/term/BASE.md。

# 知识基底 · ledger.land.v1 / t.term（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
**会话页外壳 + 「查看」sheet + 终端色板与度量**。契约 080。这格同时收口 078 §1 与 §2。

① **会话页外壳** → `ui/screens/SessionShellScreen.kt`（顶栏 + 终端槽位 + 功能键排 + 输入条）。
   终端正文仍是自绘，按设计 README 的装配示例塞进槽位：`AndroidView(factory = { TerminalSurfaceView(it) })`。
   🔴 顶栏标题用**会话显示名**（077 §1 已修，⛔ 不得退回 `claude_code`），可能是中文。
   🔴 顶栏左边的灯改三态：进行中绿（脉冲）/ 空闲灰 / **未知红**。
② **「查看」= 「切换会话」**（用户澄清：「切换会话我们是做了的，就是右上角的查看」）
   → `ui/components/SessionSwitchSheet.kt` 替换现有浮层。
   🔴 ⛔ **不得倒退 076 §1**：取数必须按**当前会话 ref**，**不是**收藏簿里最后写入的那条。
③ **终端色板与度量** → 用 `ui/theme/TerminalSpec.kt` 喂给自绘层 `termview/`。
   - **078 §2 主题**：`TerminalPaletteLight` / `TerminalPaletteDark` 两套，跟随系统深浅色 + 受设置页「外观」覆盖。
     🔴 浅色下 `background`(近白) 与 `userBlockBackground` 的**主次关系必须成立**（白底上开一块更深的）。
     ⛔ 判据不许只断言「背景是浅的」——把消息块也刷成同色照样能变绿，**那是骗判据**。
   - **078 §1 左侧遮挡**：`paddingLeft = 14.dp` / `paddingRight = 14.dp`（**以设计为准**）。
     ⚠️ 上一格 `t.clip` 已修好并定了 `LEFT_MARGIN_DP=8` / `RIGHT_MARGIN_DP=4` —— **它的根因诊断是对的、要保留**
     （判别为 CLIPPED 而非 LAYOUT_PUSHED：格子原点=contentLeft=0，`drawCentered` 在 `advance>cellW` 时 x 为负 ⇒ 画到 clip 边左侧被裁），
     **只把数值换成设计的 14dp 并左右对称**。设计注释独立命中同一机理：「字形绘制原点必须是 x = paddingLeft（不是 0，也不是 -0.5f）」。
   - 🔴 **色值集中在 `TerminalSpec.kt`，⛔ 不许散落进绘制代码**。

⚠️ **可能有半成品**：上一张账本的 `t.theme` 席位被 leader 中途撤下，工作树里可能残留它改了一半的主题代码。
**先 `git status` 看清楚再动**，以设计为准；⛔ 但绝不 `git checkout` 回退（仓根有大量未提交产品代码）。

**判据 `A-tm-ui`**（先验红）：`uiassert.py` 断言会话页顶栏是显示名不是 `claude_code`；
点「查看」后 sheet 内容属于**当前会话所在工作区**；`adb shell cmd uimode night yes|no` 切深浅色，断言**切换前后终端背景真的变了**。
配套单测名含 `LandTerm`。说明写 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/land-term/说明.md。

---
🔴 **本轮总纲：这是「落位」，不是参考、不是重写。**
设计包已落库 `/Volumes/nvme/Projects/远程Agent安卓/design/compose-handoff/`（13 个 .kt，leader 已审：零新依赖、纯展示、目录直接对位）。
规格书 `/Volumes/nvme/Projects/远程Agent安卓/design/终端会话页.dc.html`（1299 行，每个数值写死在 inline style 里）。
用户原话：「**这个样式我是完全看了，并且把关了的，能够还原就行了。**」
⇒ 🔴 **视觉一律照还原，⛔ 不许"优化"、不许换色、不许调间距。** 任何视觉偏差算没做完，不算改进。
⇒ ⛔ **不要打开 html 截图看**——数值在源码里，读源码比看渲染准（设计方 README 也这么要求）。

🔴 **三态按本工程，⛔ 不按设计的两态**：设计 `Models.kt` 是 `{ Busy, Idle }` 且注释说「映射由你那边做」——
**那句话在本工程是陷阱**。062 铁律是三态，`unknown` 绝不能当 `idle`（那是把「不知道」染成「确定空闲」）。
⇒ 扩成 `{ Busy, Idle, Unknown }`。**灯色由用户直接指定**：进行中=**绿灯亮**（保留脉冲）/ 空闲=**灰灯** / 未知=**红灯**。

🔴🔴 **判据形态（077 §2）**：机械判据用 `python3 /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py` 做**内容断言**，
配一个可重跑的探针 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/land-term/ui-check.sh`（**改前跑必须红、改后跑必须绿**，红证据写进说明）。
颜色/尺寸 UI 树读不到 ⇒ **用单测断言 token 常量值**（比对 `/Volumes/nvme/Projects/远程Agent安卓/design/compose-handoff/` 里的源文件）。
⛔ 不许用「截图 md5 互不相同」当判据——那连图里画的是什么都没看。截图留 ≥1 张给人看即可。
⛔ 脚本跑完不许留后台进程（用 trap 收尾）。

🔴 **⛔ 不得倒退这些已完成的**：073 身份键含 socket；075 一级菜单转圈；
076 §1 查看菜单按**当前会话 ref** 取数（不是收藏簿最后写入的）、§2 横滑不抢竖滑/顶部空隙/删右上角设置、§3 显示名与状态标；077 §1 会话页标题用显示名。
🔴 会话显示名**可能是中文**（`远控 leader`），⛔ 不得按 ASCII 宽度排版、不得截断。

🔴🔴🔴 **最高优先级 · 与派单正文下方「## 工作目录」那段冲突时以本条为准**：忽略那段，一律在**仓根** /Volumes/nvme/Projects/远程Agent安卓 干活。所有判据 cwd 都是仓根，你改在 worktree 里判据什么都看不到。⛔ 不要 `git worktree add`。
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件（仓根有大量未提交产品代码）。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不发进度/提问/完工通知。唯一对外动作是干完调一次 `report_result`。卡住也不发消息，写进说明.md。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux。
✅ 模拟器 `emulator-5554` 在跑；adb=~/Library/Android/sdk/platform-tools/adb；先 `adb reverse tcp:9900 tcp:9900`。⛔ 截图前关输入法。
✅ 用户裁定：模拟器 1:1 还原他手机 ⇒ 模拟器验收 = 用户验收的等价物。

```

- write_paths: app/, .team/nodes/land-term/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/080-Compose设计包落位.md, /Volumes/nvme/Projects/远程Agent安卓/design/compose-handoff, /Volumes/nvme/Projects/远程Agent安卓/design/终端会话页.dc.html, /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py, /Volumes/nvme/Projects/远程Agent安卓/app/app/src/
- 判据: A-tm-test, A-tm-suite, A-tm-ui, A-tm-shot, A-tm-doc

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
- 标题引用条目：（无编号引用）
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。


---
以上是基底，以下是任务。

**会话页外壳 + 「查看」sheet + 终端色板与度量**。契约 080。这格同时收口 078 §1 与 §2。

① **会话页外壳** → `ui/screens/SessionShellScreen.kt`（顶栏 + 终端槽位 + 功能键排 + 输入条）。
   终端正文仍是自绘，按设计 README 的装配示例塞进槽位：`AndroidView(factory = { TerminalSurfaceView(it) })`。
   🔴 顶栏标题用**会话显示名**（077 §1 已修，⛔ 不得退回 `claude_code`），可能是中文。
   🔴 顶栏左边的灯改三态：进行中绿（脉冲）/ 空闲灰 / **未知红**。
② **「查看」= 「切换会话」**（用户澄清：「切换会话我们是做了的，就是右上角的查看」）
   → `ui/components/SessionSwitchSheet.kt` 替换现有浮层。
   🔴 ⛔ **不得倒退 076 §1**：取数必须按**当前会话 ref**，**不是**收藏簿里最后写入的那条。
③ **终端色板与度量** → 用 `ui/theme/TerminalSpec.kt` 喂给自绘层 `termview/`。
   - **078 §2 主题**：`TerminalPaletteLight` / `TerminalPaletteDark` 两套，跟随系统深浅色 + 受设置页「外观」覆盖。
     🔴 浅色下 `background`(近白) 与 `userBlockBackground` 的**主次关系必须成立**（白底上开一块更深的）。
     ⛔ 判据不许只断言「背景是浅的」——把消息块也刷成同色照样能变绿，**那是骗判据**。
   - **078 §1 左侧遮挡**：`paddingLeft = 14.dp` / `paddingRight = 14.dp`（**以设计为准**）。
     ⚠️ 上一格 `t.clip` 已修好并定了 `LEFT_MARGIN_DP=8` / `RIGHT_MARGIN_DP=4` —— **它的根因诊断是对的、要保留**
     （判别为 CLIPPED 而非 LAYOUT_PUSHED：格子原点=contentLeft=0，`drawCentered` 在 `advance>cellW` 时 x 为负 ⇒ 画到 clip 边左侧被裁），
     **只把数值换成设计的 14dp 并左右对称**。设计注释独立命中同一机理：「字形绘制原点必须是 x = paddingLeft（不是 0，也不是 -0.5f）」。
   - 🔴 **色值集中在 `TerminalSpec.kt`，⛔ 不许散落进绘制代码**。

⚠️ **可能有半成品**：上一张账本的 `t.theme` 席位被 leader 中途撤下，工作树里可能残留它改了一半的主题代码。
**先 `git status` 看清楚再动**，以设计为准；⛔ 但绝不 `git checkout` 回退（仓根有大量未提交产品代码）。

**判据 `A-tm-ui`**（先验红）：`uiassert.py` 断言会话页顶栏是显示名不是 `claude_code`；
点「查看」后 sheet 内容属于**当前会话所在工作区**；`adb shell cmd uimode night yes|no` 切深浅色，断言**切换前后终端背景真的变了**。
配套单测名含 `LandTerm`。说明写 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/land-term/说明.md。

---
🔴 **本轮总纲：这是「落位」，不是参考、不是重写。**
设计包已落库 `/Volumes/nvme/Projects/远程Agent安卓/design/compose-handoff/`（13 个 .kt，leader 已审：零新依赖、纯展示、目录直接对位）。
规格书 `/Volumes/nvme/Projects/远程Agent安卓/design/终端会话页.dc.html`（1299 行，每个数值写死在 inline style 里）。
用户原话：「**这个样式我是完全看了，并且把关了的，能够还原就行了。**」
⇒ 🔴 **视觉一律照还原，⛔ 不许"优化"、不许换色、不许调间距。** 任何视觉偏差算没做完，不算改进。
⇒ ⛔ **不要打开 html 截图看**——数值在源码里，读源码比看渲染准（设计方 README 也这么要求）。

🔴 **三态按本工程，⛔ 不按设计的两态**：设计 `Models.kt` 是 `{ Busy, Idle }` 且注释说「映射由你那边做」——
**那句话在本工程是陷阱**。062 铁律是三态，`unknown` 绝不能当 `idle`（那是把「不知道」染成「确定空闲」）。
⇒ 扩成 `{ Busy, Idle, Unknown }`。**灯色由用户直接指定**：进行中=**绿灯亮**（保留脉冲）/ 空闲=**灰灯** / 未知=**红灯**。

🔴🔴 **判据形态（077 §2）**：机械判据用 `python3 /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py` 做**内容断言**，
配一个可重跑的探针 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/land-term/ui-check.sh`（**改前跑必须红、改后跑必须绿**，红证据写进说明）。
颜色/尺寸 UI 树读不到 ⇒ **用单测断言 token 常量值**（比对 `/Volumes/nvme/Projects/远程Agent安卓/design/compose-handoff/` 里的源文件）。
⛔ 不许用「截图 md5 互不相同」当判据——那连图里画的是什么都没看。截图留 ≥1 张给人看即可。
⛔ 脚本跑完不许留后台进程（用 trap 收尾）。

🔴 **⛔ 不得倒退这些已完成的**：073 身份键含 socket；075 一级菜单转圈；
076 §1 查看菜单按**当前会话 ref** 取数（不是收藏簿最后写入的）、§2 横滑不抢竖滑/顶部空隙/删右上角设置、§3 显示名与状态标；077 §1 会话页标题用显示名。
🔴 会话显示名**可能是中文**（`远控 leader`），⛔ 不得按 ASCII 宽度排版、不得截断。

🔴🔴🔴 **最高优先级 · 与派单正文下方「## 工作目录」那段冲突时以本条为准**：忽略那段，一律在**仓根** /Volumes/nvme/Projects/远程Agent安卓 干活。所有判据 cwd 都是仓根，你改在 worktree 里判据什么都看不到。⛔ 不要 `git worktree add`。
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件（仓根有大量未提交产品代码）。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不发进度/提问/完工通知。唯一对外动作是干完调一次 `report_result`。卡住也不发消息，写进说明.md。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux。
✅ 模拟器 `emulator-5554` 在跑；adb=~/Library/Android/sdk/platform-tools/adb；先 `adb reverse tcp:9900 tcp:9900`。⛔ 截图前关输入法。
✅ 用户裁定：模拟器 1:1 还原他手机 ⇒ 模拟器验收 = 用户验收的等价物。

```

- write_paths: app/, .team/nodes/land-term/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/080-Compose设计包落位.md, /Volumes/nvme/Projects/远程Agent安卓/design/compose-handoff, /Volumes/nvme/Projects/远程Agent安卓/design/终端会话页.dc.html, /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py, /Volumes/nvme/Projects/远程Agent安卓/app/app/src/, .team/nodes/term/BASE.md
- 判据: A-tm-test, A-tm-suite, A-tm-ui, A-tm-shot, A-tm-doc

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
- 标题引用条目：requirement-base/entries/080*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
