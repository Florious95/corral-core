# 知识基底 · ledger.vz.v1 / t.bg（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
## 知识基底（已内联） —— tools/basegen_ledger.py 现算的模块影响闭包，**正向依赖=你消费的契约，反向依赖=你的回归自查范围**。⛔ 不看它就动手 = 凭空猜架构。原件 .team/nodes/bg/BASE.md。

# 知识基底 · ledger.vz.v1 / t.bg（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
修 **显式背景色不受主题控制**——契约 083 **§2**（先读）。
🖼 现场：/Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots/11-…jpg 与用户描述。
用户原话：「Claude Code 它黑底白底唯一的问题，那就是**我发的消息，它依然是白底，不是灰底**。
然后 **grok 的黑底，在白色的手机主题上，它不会展示为白色**。」

**机理**：我们翻转的是**默认背景**；CLI 用**显式背景色**画的块不受影响——
Claude Code 用它画"你发的消息"高亮、grok 用它画整屏底色。它们假设自己在深色终端上，
于是浅色主题下留下刺眼的白块 / 黑块。

🔴 **第一动作是查清是哪种颜色，⛔ 不许猜着改**：
| 类型 | 能不能翻 |
|---|---|
| ANSI 索引色（16 / 256） | ✅ 能重映射 |
| 反显 `ESC[7m` | ✅ 能特判（前后景对调） |
| **24 位真彩色 `ESC[48;2;r;g;b`** | ❌ 翻不了（`TerminalColor.Rgb` 原样画），只能**亮度守卫**（浅底下压暗、保色相） |
⇒ **抓一次原始字节**（服务端帧或 tmux `capture-pane -e`）定案，把结论写进说明。
⚠️ Claude Code 与 grok **可能不是同一种**，要分别查、分别写。

**修法**：做背景色重映射。设计包已备好 `TerminalPalette.userBlockBackground`
（浅 `E6F5F2` / 深 `10241F`）与整套两版 ANSI 16 色，只是没接到重映射上。
🔴 色值集中在 `TerminalSpec.kt`，⛔ 不许散落进绘制代码。

**判据 A-bg-map**（先验红）：
① 浅色主题下打开一个 **grok** 会话 ⇒ 终端整体背景**不得是黑的**（断言采样像素亮度 > 阈值）。
② 浅色主题下打开 **Claude Code** 会话 ⇒ "你发的消息"块底色**不得是白的**，必须是**比整体背景更深的灰**。
🔴 断言的是**相对关系**（块底色 ≠ 整体底色且更深），⛔ 不许只断言"背景是浅的"——
   把块也刷成同色照样能变绿，**那是骗判据**。
③ 深色主题下关系反过来且仍可辨。
④ `adb shell cmd uimode night yes|no` 切换，断言切换前后**真的变了**。
配套单测名须含 `TermBgRemap`。说明写 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-bg/说明.md。

---
🔴🔴 **判据形态**：机械判据用 `python3 /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py` 做内容断言，
配可重跑探针 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-bg/ui-check.sh`（**改前必须红、改后必须绿**，红证据写进说明）。
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

- write_paths: app/, .team/nodes/vz-bg/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md, /Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots, /Volumes/nvme/Projects/远程Agent安卓/design/compose-handoff, /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py, /Volumes/nvme/Projects/远程Agent安卓/app/app/src/
- 判据: A-bg-test, A-bg-suite, A-bg-ui, A-bg-shot, A-bg-doc

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

修 **显式背景色不受主题控制**——契约 083 **§2**（先读）。
🖼 现场：/Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots/11-…jpg 与用户描述。
用户原话：「Claude Code 它黑底白底唯一的问题，那就是**我发的消息，它依然是白底，不是灰底**。
然后 **grok 的黑底，在白色的手机主题上，它不会展示为白色**。」

**机理**：我们翻转的是**默认背景**；CLI 用**显式背景色**画的块不受影响——
Claude Code 用它画"你发的消息"高亮、grok 用它画整屏底色。它们假设自己在深色终端上，
于是浅色主题下留下刺眼的白块 / 黑块。

🔴 **第一动作是查清是哪种颜色，⛔ 不许猜着改**：
| 类型 | 能不能翻 |
|---|---|
| ANSI 索引色（16 / 256） | ✅ 能重映射 |
| 反显 `ESC[7m` | ✅ 能特判（前后景对调） |
| **24 位真彩色 `ESC[48;2;r;g;b`** | ❌ 翻不了（`TerminalColor.Rgb` 原样画），只能**亮度守卫**（浅底下压暗、保色相） |
⇒ **抓一次原始字节**（服务端帧或 tmux `capture-pane -e`）定案，把结论写进说明。
⚠️ Claude Code 与 grok **可能不是同一种**，要分别查、分别写。

**修法**：做背景色重映射。设计包已备好 `TerminalPalette.userBlockBackground`
（浅 `E6F5F2` / 深 `10241F`）与整套两版 ANSI 16 色，只是没接到重映射上。
🔴 色值集中在 `TerminalSpec.kt`，⛔ 不许散落进绘制代码。

**判据 A-bg-map**（先验红）：
① 浅色主题下打开一个 **grok** 会话 ⇒ 终端整体背景**不得是黑的**（断言采样像素亮度 > 阈值）。
② 浅色主题下打开 **Claude Code** 会话 ⇒ "你发的消息"块底色**不得是白的**，必须是**比整体背景更深的灰**。
🔴 断言的是**相对关系**（块底色 ≠ 整体底色且更深），⛔ 不许只断言"背景是浅的"——
   把块也刷成同色照样能变绿，**那是骗判据**。
③ 深色主题下关系反过来且仍可辨。
④ `adb shell cmd uimode night yes|no` 切换，断言切换前后**真的变了**。
配套单测名须含 `TermBgRemap`。说明写 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-bg/说明.md。

---
🔴🔴 **判据形态**：机械判据用 `python3 /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py` 做内容断言，
配可重跑探针 `/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-bg/ui-check.sh`（**改前必须红、改后必须绿**，红证据写进说明）。
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
🔴 **本格追加：grok 浅色下是灰底，应为纯白**（用户 2026-08-19）
🖼 /Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots/15-grok灰底应为纯白.png
用户原话：「grok 它的背景是**灰底，不是纯白**，使得它不太好看。」

⚠️ 这条说明重映射**已经部分生效**（不再是黑底），但落点错了：映射到了灰而不是白。
⇒ 查清那块灰是哪来的：是 ANSI 索引色映射表里的值（设计包 `TerminalPaletteLight.background = 0xFFF7F8FB` 近白，
但 ANSI 0 号 black 映射成了 `0xFFE7EAF0` 的"浅底暗格"）、还是 grok 显式发的背景被映射到了 0 号色？
🔴 **整屏背景**应落到 `TerminalPalette.background`（近白），⛔ 不该走 ANSI 0 号那条"暗格"路径——
那个值是给**局部色块**用的，不是给整屏的。两者混用就会出现"整屏灰"。
**判据**：浅色主题下 grok 会话整屏背景采样值必须 ≈ `background`（容差内），⛔ 不得等于 ANSI 0 号色。
```

- write_paths: app/, .team/nodes/vz-bg/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md, /Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots, /Volumes/nvme/Projects/远程Agent安卓/design/compose-handoff, /Volumes/nvme/Projects/远程Agent安卓/tools/uiassert.py, /Volumes/nvme/Projects/远程Agent安卓/app/app/src/, .team/nodes/bg/BASE.md
- 判据: A-bg-test, A-bg-suite, A-bg-ui, A-bg-shot, A-bg-doc

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
