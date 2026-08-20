# 知识基底 · ledger.refresh.v1 / t.probe（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
写「进菜单即时刷新」的根因探针。契约 requirement-base/entries/069-进菜单必触发一次即时刷新.md（**§2 已经把两级各自的根因定位好了，先读**）。
一级：handleList 调 ensureInitialScan——只在第一次扫，之后每次进来都返回缓存，**永远不重扫**。
二级：markLevel2 只在订阅数 0→1 时唤醒轮询；App 离开不退订、回来不重订阅 ⇒ 服务端收不到「用户又进来了」。
产出 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/rf-probe/probe-rf.sh，四条断言：
①**A-rf-l1**：无人订阅时改变 tmux 世界（增/删一个含白名单 CLI 的会话）⇒ 客户端发一次 list ⇒ **返回列表必须是新世界**；
②**A-rf-l2**：无人订阅时把某节点由空闲切到工作 ⇒ 进入二级菜单 ⇒ **首个非缓存帧就是新状态**，且不得等满一个 2s cadence；
③**不倒退**：进入瞬间仍不得出现空列表帧（062 缓存优先）；
④**不倒退**：没人在菜单里时仍然零轮询（061 零订阅零轮询）。
⛔ 判据不许写成「代码里有一次 scan 调用」——那是验东西在那儿，不是验世界变了。
🔴 现在跑必须红（①一定红）。说明写 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/rf-probe/说明.md，记清每条红在哪。
⛔ 这一格只产探针，不改产品代码。
🔴 worktree_id 只是并发互斥标签，不是 git worktree。**必须在仓根干活**。⛔ 不要 git worktree add。
🔴 静默纪律：不给 leader 发进度消息。唯一例外被卡住需裁定（class="blocking"）。
🔴 干完调一次 report_result，**不要传 task_id 参数**。
⛔ 禁止启动安卓模拟器。⛔⛔ 绝不碰用户真实 tmux（默认 socket）；tmux 实验自起隔离 server 并 list-sessions 自检。
⛔⛔ 遍历进程只取 comm，禁止取 argv。
🔴 **先完整读知识基底 .team/nodes/probe/BASE.md**（模块影响闭包现算产物：正向依赖=你消费的契约，反向依赖=你的回归自查范围）。⛔ 不读就动手 = 凭空猜架构。
```

- write_paths: .team/nodes/rf-probe/
- read_paths: requirement-base/entries/069-进菜单必触发一次即时刷新.md, server/internal/, app/app/src/, .team/nodes/probe/BASE.md
- 判据: A-p-exec, A-p-doc, A-p-red

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：dev.agentmirror.app, internal/
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
- 标题引用条目：requirement-base/entries/069*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
