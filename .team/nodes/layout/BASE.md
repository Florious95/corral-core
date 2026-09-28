# 知识基底 · ledger.ux.v1 / t.layout（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
修 **底部标签栏的三个连带问题**。契约 requirement-base/entries/076-底部标签栏落地后的体验收口.md **§2**（先读）。
🖼 **用户实测截图（必看）**：/Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots/02-设置页顶部空隙与竖滑失效.jpg
用户原话：「首先，顶部存在较大的空隙，其次由于左右滑，导致上下滑被禁用，使得设置无法看全，同理，会话变多，无法看下面的会话，此外需要在一二级菜单删除右上角的设置」

**2a 顶部空隙**：状态栏到「‹工作区　设置」标题之间空出约屏高 1/8。查清是 `statusBarsPadding` 叠了一层还是标题栏 top padding 没随改版调整，⛔ 不要靠调死值凑。
**2b 🔴 横滑抢竖滑（本格最贵）**：底部三栏是横向 pager，横滑吃掉了竖直滚动 ⇒ 设置页滑不到底（截图里「诊断日志」正被切断）、会话多了看不到下面的。用户说「同理」⇒ **同一根因，不是两个 bug，一处修好两处都好**。
  🔴 **判据 A-ly-scroll 要能区分「滚不动」和「能滚但内容被裁掉」**——两者界面上同形，判错就修错地方。先量：可滚动容器的 contentHeight vs viewportHeight、手势有没有被 pager 消费，**两个数都要记**。
  🔴 ⛔ **不得把横滑切页去掉**——那是 067 §4.1 推翻滑动式导航后刚落地的形态。用户说的是它**抢了竖滑**，不是它不该存在。
**2c 删一二级菜单右上角的「设置」**：底部已有设置 tab。⛔ **只删「设置」，不要顺手把旁边的「LAN / tailnet」也删了**——用户只点了设置那个。

🔴 **先在当前实现上验红**（现在必红），把红证据（两个数）写进说明。判不红就是白写。
配套测试名须含 `TabScroll`。截图 ≥3 张（设置页滚到底部最后一个元素完整可见 / 会话列表造到超一屏后滚到底 / 一级菜单右上角已无「设置」），存 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/ux-layout/。
说明写 /Volumes/nvme/Projects/远程Agent安卓/.team/nodes/ux-layout/说明.md。
🔴🔴🔴 **最高优先级 · 与派单正文下方「## 工作目录」那段冲突时以本条为准**：那段会说「必须在 git worktree 里做（不是仓根）」——⛔ **忽略它**，一律在**仓根** /Volumes/nvme/Projects/远程Agent安卓 干活。理由：本工程所有判据的 cwd 都是仓根，你改在 worktree 里判据什么都看不到，代码写对了也全红（2026-08-19 已实发一次，六条判据全红而代码是对的）。⛔ 不要 `git worktree add`。
⛔⛔ 绝不 `git checkout` / `git restore` 任何文件（仓根有大量未提交产品代码，覆盖即丢失）。
🔴🔴 **静默纪律（用户令：节点禁止给 leader 发消息）**：⛔ 不许 `team-agent send`、不许发进度/提问/完工通知。唯一对外动作是干完调一次 `report_result`。被卡住也不发消息，把卡点写进说明.md。
⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
✅ 模拟器 `emulator-5554` 在跑；adb=~/Library/Android/sdk/platform-tools/adb，连之前先 `adb reverse tcp:9900 tcp:9900`。⛔ 截图前先关输入法（候选栏曾把配对 token 明文打进截图）。
🔴 截图必须是**不同现场**（判据核 md5 互不相同），⛔ 不许交三张一模一样的。
🟢 **两条已经好了，⛔ 本轮不要动**：① 契约 073 身份键含 socket 已生效（收藏三条 claude_code 目录各异、星互相独立，没有复发）；② 契约 075 一级菜单转圈已修。

```

- write_paths: app/, server/, .team/nodes/ux-layout/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/076-底部标签栏落地后的体验收口.md, /Volumes/nvme/Projects/远程Agent安卓/.team/issues/清单-20260819.md, /Volumes/nvme/Projects/远程Agent安卓/.team/issues/shots, /Volumes/nvme/Projects/远程Agent安卓/app/app/src/
- 判据: A-ly-test, A-ly-suite, A-ly-shots, A-ly-doc

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
- 标题引用条目：requirement-base/entries/076*
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
