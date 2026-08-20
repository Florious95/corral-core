# 知识基底 · ledger.v72.v1 / t.menu（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
把右上角「查看」改成**可点的二级菜单列表**；**Ctrl-B W 那套抓屏实现模块化归档、不介入**。契约 requirement-base/entries/072-查看改二级菜单与图标修正.md §1。手测照 .claude/skills/emulator-manual-test/SKILL.md。
用户 2026-08-19 追加裁定（**覆盖契约里「删干净」那句**）：「右上角查看 ctrl-b w 的实现模块化，归档不介入，**暂不修展示不完全的问题**」。
⇒ 三条硬要求：
① **新形态**：点「查看」⇒ 悬浮窗里是**和二级菜单一样的会话列表**（会话名/路径/状态徽章），**点一行直接跳到那个会话**；点列表以外区域关闭。
② **抓屏那套（choose-tree / scratch 客户端 / OverlayEmulator）整体收拢成一个独立模块并归档**，⛔ **不要删**、⛔ **不要修它的展示不完全问题**（用户明令暂不修）。归档 = 移到一个边界清晰的包/目录，不再被主流程调用，并在包头注释写明「已归档，2026-08-19 用户令暂不介入；展示不完全问题未修」。
③ 归档后**确认服务端不再 attach 任何 tmux 客户端**（061 零 attach 不变），且不再起 scratch 会话（070 §7 那条 scratch 跨重启复用的坑随之消失）。
🔴 新列表**优先复用已有的 level2 数据通路**，⛔ 不要再造一份取数逻辑（一二级已统一走同一个 provider 识别函数，见 068 §7——两份取数会给出互相矛盾的世界）。
配套测试名须含 `OverlayMenu`。模拟器截图 ≥3 张（打开/列表内容/点一行跳过去），存 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/ov-menu/。
✅ 模拟器已解禁。AVD `agentmirror_geo_1260x2800` 在跑，adb=~/Library/Android/sdk/platform-tools/adb，`adb reverse tcp:9900 tcp:9900` 已建。
🔴 截图必须是**不同现场**（判据会核 md5 互不相同）。
🔴 worktree_id 只是并发互斥标签。**必须在仓根干活**。⛔ 不要 git worktree add。
🔴 静默纪律：不给 leader 发进度消息。干完调一次 report_result，**不要传 task_id 参数**。
⛔⛔ 绝不碰用户真实 tmux。⛔⛔ 遍历进程只取 comm，禁止取 argv。
🔴 **先完整读知识基底 .team/nodes/menu/BASE.md**（模块影响闭包现算产物：正向依赖=你消费的契约，反向依赖=你的回归自查范围）。⛔ 不读就动手 = 凭空猜架构。
```

- write_paths: app/, server/, .team/nodes/ov-menu/
- read_paths: requirement-base/entries/072-查看改二级菜单与图标修正.md, .claude/skills/emulator-manual-test/SKILL.md, app/app/src/, server/internal/, .team/nodes/menu/BASE.md
- 判据: A-om-test, A-om-shots, A-om-doc, A-om-suite, A-om-go, A-om-archived

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：dev.agentmirror.app, internal/api
- 正向依赖（你消费的契约，只读）：go_internal_agentstate, go_internal_bridge, go_internal_discovery, go_internal_protocol, kt_dev_agentmirror_app_pairing, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_session, kt_dev_agentmirror_app_ui_theme, kt_dev_agentmirror_app_workspace
- **反向依赖（波及面 = 回归自查范围）**：go_cmd_agentmirrord, kt_dev_agentmirror_app_service

### 闭包架构卡内联

### Kotlin · dev.agentmirror.app

- **职责**：Compose 应用根组合。
- **导出面**：AgentMirrorApp, MainActivity, MainNavState, Session
- **依赖边**：dev.agentmirror.app.pairing, dev.agentmirror.app.service, dev.agentmirror.app.session, dev.agentmirror.app.ui.theme, dev.agentmirror.app.workspace
- **doc 全文**：Compose 应用根组合。 依需求 004「客户端无状态」，本组件只做路由，不持有任何业务状态。 首启路由（pairing-ui 知识基底 §1）： - 无配对配置 → 配对页（扫码/手填，可跳过进空工作区）； - 有配对配置 → 直进工作区列表； - 配对页可从设置/重配入口重进（重新配对）。 会话页跳转沿用 session-ui 挂载的 [SessionRoute]。 导航态由 [navState]（D-3 修复）注入：MainActivity 在 onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。 工作区 VM（[workspaceViewModel]）由 MainActivity 持有（fix-workspace-wiring 修复， navState 同模式提升），本组件只负责在工作区分支用 [DisposableEffect] 把它接入 [ServiceWire.uiConnector]——配对成功后列表能渲染（此前 VM 裸建从未接线，uiConnector 全仓无调用点，见 fix-workspace-wiring 知识基底）。 @consumes dev.agentmirror.app.pairing @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.session @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.app.workspace

### Go · internal/api

- **职责**：Package api implements the service-side WebSocket API and the image upload endpoint, wiring together discovery and bridge (task ws-api).
- **导出面**：Discoverer, NewServer, NewStateProvider, Options, Server, StateProvider, TokenValidator
- **依赖边**：internal/agentstate, internal/bridge, internal/discovery, internal/protocol

### Go · cmd/agentmirrord

- **职责**：Command agentmirrord is the service-side daemon of AgentMirror (product github.com/agentmirror/agentmirror): a sidecar that mirrors the user's existing tmux sessions to the Android app over WebSocket.
- **导出面**：main
- **依赖边**：internal/api, internal/config, internal/pairing, internal/tsnetd

### Kotlin · dev.agentmirror.app.service

- **职责**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。
- **导出面**：AppClockPump, MirrorForegroundService, NetworkConnectivityWatcher, NoopTransportFactory, NotificationHelper, OkHttpTransportFactory, OkHttpWebSocketTransport, OnScreenFallbackPump, ServiceWire, StateWatcher, TsnetBootstrap
- **依赖边**：dev.agentmirror.app, dev.agentmirror.app.conn, dev.agentmirror.app.tsnet
- **doc 全文**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。 分层（fg-service 知识基底 §1）： - [StateWatcher]：纯 JVM 核心逻辑（验收单测全打这里），消费 conn 层 listing/list_delta 流，检测会话状态沿变化（→blocked/→done）→ 通知；同状态重复抑制；unknown 不通知。 - [NotificationHelper]：通知渠道（常驻/状态两条）+ 常驻通知与状态通知 + 会话页深链 PendingIntent（action/extra 由 [MainActivity] 的 handleDeepLink 消费，非本包）。 - [MirrorForegroundService]：薄 Android 层，startForeground（dataSync）+ 生命周期绑定 [ConnectionManager]（经 [ServiceWire]）；断连静默重连归 conn 层，本服务只反映状态。 已接线（feat-fg-service-wiring）：配对成功/冷启动/进入会话经 [MirrorForegroundService.start] 启动（startForegroundService），连接与时钟泵由本服务承接（004/011 前台服务路线）。 - [ServiceWire]：接线点——传输工厂（默认 [OkHttpTransportFactory]）、UI 监听桥 （[uiConnector]）与服务监听槽（[serviceListener]）、连接配置注入；进程级持有唯一 [ConnectionManager]，服务与 UI 都经它访问同一单例。 电量策略（004 裁定）：服务被系统杀 → 冷启动重连即恢复（客户端无状态，没有丢失可言）。 服务**不持有连接状态**（004 无状态底线）：连接是 [ServiceWire] 进程级单例，配置唯一来源 是 SharedPreferences，服务只经 [ServiceWire.managerOrNull] 读取并驱动时钟泵 （[MirrorForegroundService.pumpOnce]，2s 一拍，在屏组合不再各自持有）。服务不可用时 在屏兜底泵 [OnScreenFallbackPump] 接管（fix-app-runtime-sa：服务被杀前台仍推进）， 服务恢复即让出（泵归属判据 [ServiceWire.servicePumpActive]，不双泵）。 @consumes dev.agentmirror.app @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.tsnet

## 3. 需求基
- 标题引用条目：requirement-base/entries/068*, requirement-base/entries/072*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
