# 知识基底 · ledger.spin.v1 / t.spin（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
修 **一级菜单（工作区列表）转圈不回弹**。契约 requirement-base/entries/075-一级菜单转圈不回弹.md（**先读，§0 说明它和 074 §1 不是同一个 bug**）。
用户实测截图：工作区列表内容全对（5 个工作区、路径、会话数都正确），但一个**浅灰圆形指示器悬在第 1、2 行之间不消失**。用户判断：确定是最新 app 引入的（他手上的包是 commit 044158c4c）。

🔴 **先把它量出来再改，⛔ 不许照契约 §2 那段猜测直接动手**——那只是方向不是结论。
进入一级菜单后静置 10 秒，**两个数都要记**：① `list` 请求数 ② 刷新态标志（`isRefreshing` 之类）的**翻转序列**。
判据要能区分三种世界：**请求一直在飞** / **请求早停了但状态没回写** / **状态回写了但 UI 没重组**。
⛔ 光记「转圈还在」等于什么都没记——有守卫/阈值的地方要把参与比较的两边原始数值都记下来（2026-08-14 诊断日志纪律）。

🔴 **先在当前实现上验红**（现在必红），把红证据（两个计数 + 翻转序列）写进说明。判不红就是白写。
⛔ **不得倒退**：069「进菜单即时刷新」和下拉手动刷新**都要保住**，修法不是把它们关掉。用户原话「刷新及时，状态很准确，手感很不错」。⛔ 不得让列表出现空帧（062 缓存优先）。

配套测试名须含 `SpinnerIdle`。模拟器截图 ≥3 张（进入瞬间有指示器 / 首帧到达后消失 / 静置 10 秒仍无），存 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/spin/。判据会核 md5 互不相同，🔴 必须是不同现场。
✅ 模拟器 `emulator-5554` 已在跑，adb=~/Library/Android/sdk/platform-tools/adb，连之前先 `adb reverse tcp:9900 tcp:9900`。⛔ 截图前先关输入法（候选栏曾把配对 token 明文显示进截图）。
产出说明写 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/spin/说明.md。

🔴🔴🔴 **最高优先级 · 与派单正文下方「## 工作目录」那一段冲突时以本条为准**：那段会说「必须在 git worktree 里做（不是仓根）」——⛔ **忽略它**，一律在**仓根** /Volumes/nvme/Projects/远程Agent安卓 干活。理由：本工程所有判据的 cwd 都是仓根，你改在 worktree 里判据什么都看不到，代码写对了也全红。2026-08-19 已实发一次。
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件（仓根有大量未提交产品代码，覆盖即丢失）。⛔ 不要 `git worktree add`。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不许发进度/提问/完工通知。唯一对外动作是干完调一次 `report_result`。被卡住也不发消息，把卡点写进说明.md。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
```

- write_paths: app/, .team/nodes/spin/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/075-一级菜单转圈不回弹.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/074-无限刷新与未知回归收尾.md, /Volumes/nvme/Projects/远程Agent安卓/app/app/src/
- 判据: A-sp-test, A-sp-suite, A-sp-shots, A-sp-doc

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
- 标题引用条目：requirement-base/entries/075*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
