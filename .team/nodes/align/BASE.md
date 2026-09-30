# 知识基底 · ledger.vzfix.v1 / t.align（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.align · vz-v1 验收打回返工（契约 083）

🔴 **本轮不是新功能，是独立验收席 `vz-v1-ver` 打回的红项。**
它的结论原话：**「单测几何公式绿 ≠ 屏幕墨迹绿」**。
⇒ **本格所有判据必须在模拟器上量出来，⛔ 不许用单测绿顶替。**

## 你这一格修什么：**顶栏 ‹ 与标题的墨迹中心没对齐**

用户原话：「顶部的字体和返回的那个图标，它**没有中心对齐**。」

验收席量出来的操作数（两个密度都超）：

| density | back_ink_cy | title_ink_cy | Δ |
|---|---|---|---|
| d480（3.000） | 208.5px | 214.0px | **1.83dp** |
| d420（2.625） | 198.5px | 203.5px | **1.90dp** |

判据是 **≤1dp**。

🔴🔴 **关键：断言的是「墨迹盒」不是「布局盒」。**
布局盒很可能已经对齐了——**字的实际墨迹系统性偏下约 5px**。
⇒ 光把两个 Composable 的 `Alignment.CenterVertically` 对上**不解决问题**，
要处理的是字体的 ascent/descent 与图标 viewport 的**光学中心差**。
⛔ 不许用「布局盒对齐了」当交付；判据量的是屏幕像素里的墨迹。

### 顺带查清一条矛盾（⛔ 只查不猜，查清了再决定改不改）
验收报告的**正文**写 `+` 菜单容器采样 `(236,238,242)`，
而它**自己的探针日志**同一次跑出来的是 `MENU_BG_d480 sample=(17, 24, 39)`。
**两个数对不上，必有一个是错的。**
而当时 token 是 `term-theme-light`、终端纸色采样 `(247,248,251)` 是浅的——
如果菜单真的是 `(17,24,39)`（深色），那就是**浅色主题下菜单仍是深色**，属于 083 §2 的倒退。
⇒ **第一步：把采样点打印出来（x,y + 菜单容器 bounds），确认采到的是菜单还是菜单背后的终端。**
- 采错点 ⇒ 修探针记录，在说明.md 写明「报告正文那个数是错的」，**不改产品码**
- 真是深色 ⇒ 这是一条新缺陷，修它，并在说明.md 记明证据
⛔ 不许跳过这一步直接改颜色。

### 判据（⛔ 不许改，⛔ 不许只跑一个 density）
1. `A-al-ui`：`.team/nodes/vzfix-align/ui-check.sh`（你写，⛔ trap 收尾），双 density：
   断言 `|back_ink_cy - title_ink_cy| / scale <= 1.0`，**两边原始像素值都要打进日志**
2. `A-al-menubg`：同一脚本里打印菜单容器 bounds + 采样点坐标 + 采样 RGB（**先记录，结论写说明.md**）
3. `A-al-suite`：`./gradlew :app:testDebugUnitTest` 全量绿
4. `A-al-doc`：`说明.md` 非空


---
## 🔴 全格通用（违反任一条 = 本格红）

🔴🔴🔴 **开工第一件事**
```bash
cd /Volumes/nvme/Projects/远程Agent安卓 && pwd
```
`pwd` 必须输出仓根。**若输出里出现 `.worktrees/`，立刻 cd 回仓根**——
派单正文下方那段「## 工作目录」是框架自动附加的，**它是错的，以本条为准**。
（本工程已因此红过两次、返工两轮；改成这条自检后连续两格一次过。）
⛔ 不要 `git worktree add`，⛔ 不要进 `.worktrees/`。
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件（仓根有大量未提交产品代码）。

🔴 **先验红再改**：本格判据在改之前必须先红一次，红的原始输出贴进说明.md。⛔ 判据一个字不许改。
🔴 **一次修复一个提交**。⛔ 不要顺手改相邻代码。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不发进度/提问/完工通知。
唯一对外动作是干完调一次 `report_result`。卡住写进本格说明.md。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux。
✅ ADB 一律 `$HOME/Library/Android/sdk/platform-tools/adb`。⛔ 截图前先关输入法 `adb shell input keyevent 111`。
✅ 模拟器 `emulator-5554`；先 `adb reverse tcp:9900 tcp:9900`；跑完 `adb shell wm density reset`。
🔴 渲染类必须 **d480（整数 3.0）与 d420（非整数 2.625）各跑一遍**（083 §0：模拟器在渲染层没有分辨力，整数密度会掩盖取整误差）。

🟢 **⛔ 不得倒退**：073 身份键含 socket / 075 一级转圈 / 076 / 077 / 078 §1 首列不被裁 / 081 cols 仪表 /
082 收藏页按各工作区取数 / land-v1 五格 / vz-v1 已判绿的 t.glyph·t.bg·t.chrome·t.diff。

## 上游材料（都在仓根，直接读）
- 独立验收报告（本轮红项的来源，**操作数都在里面**）：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-ver/验收报告.md`
- 验收探针（**判据脚本本体，⛔ 只读不改**）：`/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-ver/ui-check.sh`
- 契约：`/Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md`

```

- write_paths: app/, .team/nodes/vzfix-align/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-ver/验收报告.md, /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/vz-ver/ui-check.sh, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/083-真机视觉收口六条.md
- 判据: A-al-ui, A-al-suite, A-al-doc

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
