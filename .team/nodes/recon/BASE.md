# 知识基底 · ledger.pr1.v1 / t.recon（tools/basegen_ledger.py 编译产物，手工编辑无效）

## 1. 任务信封（账本原文，机械抽取）
```
# t.recon · 回前台不重连（回归）＋ 收藏页不重连（契约 090）

**用户 2026-08-21 实测两条：**
1. **E17（回归）**：在会话页把 APP 切到后台，再回前台，**它不会尝试重连**；
   必须退出会话页再进来才行。用户原话：「**这属于改动引发，因为之前可以**」。
2. **E18**：**收藏页不会进入重连状态** —— 必须点进会话，它才发现自己没连上，然后才开始重连。

## 🔴 这是同一个形状的第三次
上一轮修过「指示灯不实时 + 查看菜单空」，根因是**会话页在屏时未订阅当前工作区**。
现在收藏页又是同一回事。⇒ **必须在「可见性变化」的共同入口上修，
⛔ 不许再给收藏页单独补一个订阅**。否则下一个页面还会坏。

## 先做定位，⛔ 不许直接改
E17 说「之前可以」⇒ **是某次改动引入的**。先用 `git log -S` / `git log -p` 在
`conn/`、`service/`、`workspace/`、`MainActivity.kt` 里找出**哪一次改动去掉了回前台重连**，
把那个 commit sha 写进说明.md。找不到就明写「查不清」，⛔ 不许编一个说得通的因果。

## 判据
- `A-recon-fg`：模拟「会话页 → 后台 → 回前台」且连接已断，断言**触发了一次重连尝试**。
  🔴 先验红（现在不触发）。断言的是**真的发起了重连**，⛔ 不是「状态变量被设了」。
- `A-recon-fav`：模拟「停在收藏页 + 连接已断」，断言**收藏页自己就进入重连**，
  ⛔ 不需要点进会话。🔴 先验红。
- `A-recon-once`：共同入口修完后，断言**两条路径走的是同一个入口**（例如同一个函数被调用），
  这条防的是「又给收藏页单独补了一个」。

## 诊断日志纪律
凡有守卫/阈值/比较的地方，**把参与比较的两边的原始数值都记下来**再记结论，
并记**触发来源**（可见性/网络/用户操作）。⛔ 不许只写「未触发」。

---
## 🔴 本轮流程：PR 链（一格一分支，判据过了才并线）

**开工第一件事，跑这两条自检，把输出贴进说明.md：**
```
pwd                        # 你必须在自己的 worktree 里，不是仓根
git branch --show-current
```

1. **建你自己的分支**：`git checkout -b pr/recon-foreground`。
2. **只提交到本分支**。⛔ 不许并线、⛔ 不许碰 main、⛔ 不许 `git stash apply` 别人的改动。
3. **⛔ 你不要 push。** 本仓本地没配 remote，远端是 `tools/mirror-push.sh` 过滤后推的镜像仓，
   **PR 由 leader 代开**。你的交付＝分支名 + commit sha + 说明.md。
4. **⛔ 判据红了不许改判据让它变绿。** 判据本身写错 ⇒ 报 `blocked` 并指出错在哪，不要自己改。
5. **必须写合规的外骨骼注释** —— 架构维基从注释现算，注释不合规 ⇒ 维基缺节点缺边 ⇒
   下一个席位的知识基底是残的。你的机械判据含 `archwiki --check --strict-t3`，会红给你看。
6. **一次只修一个缺陷。** ⛔ 顺手改相邻代码 / 顺手重构 / 顺手改格式，全部禁止。
   每一行改动都要能追溯到本格需求。

## 🔴 判据纪律（三铁律）
- 判据要断言「世界变了」；**写完先验红**（改之前跑，必须红），再改，再验绿。
- **先验红的原始输出必须贴进 `说明.md`**，⛔ 没有先验红的绿不算数。
- 断言「某物不应出现」时**必须先制造出让它出现的条件**，否则是恒真判据。
- 判据**查代码内容，⛔ 不查 commit 身份**（revert/cherry-pick 会让「commit 在不在」说谎）。

## 说明.md 必须包含
分支名 / commit sha / `pwd` 与 `git branch --show-current` 的输出 / 改了哪些文件 /
**每条判据的先验红原始输出** / 每条判据的验绿原始输出 / 查不清的地方明写「查不清」。

```

- write_paths: app/app/src/main/java/dev/agentmirror/app/conn/, app/app/src/main/java/dev/agentmirror/app/service/, app/app/src/main/java/dev/agentmirror/app/workspace/, app/app/src/main/java/dev/agentmirror/app/MainActivity.kt, .team/nodes/pr1-recon/
- read_paths: /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/090-连接生命周期两条.md, /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/082-收藏页跨工作区在线判定失真.md
- 判据: A-recon-suite, A-recon-wiki, A-recon-doc

## 2. 架构基（wiki 现算影响闭包）
- 写作用域包：dev.agentmirror.app.MainActivity.kt, dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.workspace
- 正向依赖（你消费的契约，只读）：kt_dev_agentmirror_app, kt_dev_agentmirror_app_tsnet, kt_dev_agentmirror_app_ui_theme
- **反向依赖（波及面 = 回归自查范围）**：kt_dev_agentmirror_app, kt_dev_agentmirror_app_pairing, kt_dev_agentmirror_app_session

### 闭包架构卡内联

### Kotlin · dev.agentmirror.app.conn

- **职责**：连接层：与主机 sidecar 的 WebSocket 连接、会话枚举、帧协议编解码。
- **导出面**：AgentState, AuthAckFrame, AuthFrame, BinaryFrame, BinaryFrameCodec, BinaryKind, Clock, Connection, ConnectionConfig, ConnectionManager, ConnectionState, ErrorCode, ErrorFrame, FrameCodec, FrameDecodeException, FrameEncodeException, FrameError, InputAckFrame, InputFailReason, InputFrame, InputKey, ListDeltaFrame, ListFrame, Listener, ListingFrame, ProtocolVersion, Real, ReconnectPolicy, ResizeFrame, ScrollbackFrame, Session, SubscribeFrame, TransportListener, UnsubscribeFrame, WebSocketTransport, Workspace
- **依赖边**：（无）
- **doc 全文**：连接层：与主机 sidecar 的 WebSocket 连接、会话枚举、帧协议编解码。 分层（自底向上）： 1. [FrameCodec] / [BinaryFrameCodec] —— 纯函数编解码：JSON 控制帧（信封 + 12 类帧 载荷，docs/protocol.md §4）+ 二进制流帧（§6，含 scrollback 12 字节收敛区间头）。 编解码都消费同一份契约夹具做字节级断言（server/internal/protocol/testdata/）。 2. [Connection] —— 单条 WS 生命周期状态机（握手 → 就绪 → 关闭）。 3. [ConnectionManager] —— 重连策略 + 订阅簿记：重连后自动重放 auth + 全部活跃 subscribe（004 无状态铁律的重放语义）；listing seq 不连续 → 自动重新 list。 上层（UI/service）只见回调（[ConnectionManager.Listener] / [Connection.Listener]），不见 WS 细节。本层不持久任何会话状态。

### Kotlin · dev.agentmirror.app.service

- **职责**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。
- **导出面**：AppClockPump, MirrorForegroundService, NetworkConnectivityWatcher, NoopTransportFactory, NotificationHelper, OkHttpTransportFactory, OkHttpWebSocketTransport, OnScreenFallbackPump, ServiceWire, StateWatcher, TsnetBootstrap
- **依赖边**：dev.agentmirror.app, dev.agentmirror.app.conn, dev.agentmirror.app.tsnet
- **doc 全文**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。 分层（fg-service 知识基底 §1）： - [StateWatcher]：纯 JVM 核心逻辑（验收单测全打这里），消费 conn 层 listing/list_delta 流，检测会话状态沿变化（→blocked/→done）→ 通知；同状态重复抑制；unknown 不通知。 - [NotificationHelper]：通知渠道（常驻/状态两条）+ 常驻通知与状态通知 + 会话页深链 PendingIntent（action/extra 由 [MainActivity] 的 handleDeepLink 消费，非本包）。 - [MirrorForegroundService]：薄 Android 层，startForeground（dataSync）+ 生命周期绑定 [ConnectionManager]（经 [ServiceWire]）；断连静默重连归 conn 层，本服务只反映状态。 已接线（feat-fg-service-wiring）：配对成功/冷启动/进入会话经 [MirrorForegroundService.start] 启动（startForegroundService），连接与时钟泵由本服务承接（004/011 前台服务路线）。 - [ServiceWire]：接线点——传输工厂（默认 [OkHttpTransportFactory]）、UI 监听桥 （[uiConnector]）与服务监听槽（[serviceListener]）、连接配置注入；进程级持有唯一 [ConnectionManager]，服务与 UI 都经它访问同一单例。 电量策略（004 裁定）：服务被系统杀 → 冷启动重连即恢复（客户端无状态，没有丢失可言）。 服务**不持有连接状态**（004 无状态底线）：连接是 [ServiceWire] 进程级单例，配置唯一来源 是 SharedPreferences，服务只经 [ServiceWire.managerOrNull] 读取并驱动时钟泵 （[MirrorForegroundService.pumpOnce]，2s 一拍，在屏组合不再各自持有）。服务不可用时 在屏兜底泵 [OnScreenFallbackPump] 接管（fix-app-runtime-sa：服务被杀前台仍推进）， 服务恢复即让出（泵归属判据 [ServiceWire.servicePumpActive]，不双泵）。 @consumes dev.agentmirror.app @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.tsnet

### Kotlin · dev.agentmirror.app.workspace

- **职责**：工作区：两级导航（舰队 → 会话），对应需求 001「舰队视角」、002「两级分组」。
- **导出面**：ConnectionUi, SessionUi, StateBadge, StateBadgeStyle, WorkspaceScreen, WorkspaceUi, WorkspaceUiState, WorkspaceViewModel
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme
- **doc 全文**：工作区：两级导航（舰队 → 会话），对应需求 001「舰队视角」、002「两级分组」。 - [WorkspaceViewModel]：纯 JVM 视图模型，消费 conn 层 listing/list_delta 帧流 → UI 状态；聚合字段（session_count / aggregate_state）为服务端权威值，只渲染不重算（012）。 - [WorkspaceScreen] / [StateBadge]：薄 Compose 渲染层；状态徽章五值（008）。 二级导航经 [WorkspaceScreen] 的 onOpenSession 回调把 (ref, name) 交给根路由 [AgentMirrorApp]，由其挂载 [SessionRoute] 进入会话页。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme

### Kotlin · dev.agentmirror.app

- **职责**：Compose 应用根组合。
- **导出面**：AgentMirrorApp, MainActivity, MainNavState, Session
- **依赖边**：dev.agentmirror.app.pairing, dev.agentmirror.app.service, dev.agentmirror.app.session, dev.agentmirror.app.ui.theme, dev.agentmirror.app.workspace
- **doc 全文**：Compose 应用根组合。 依需求 004「客户端无状态」，本组件只做路由，不持有任何业务状态。 首启路由（pairing-ui 知识基底 §1）： - 无配对配置 → 配对页（扫码/手填，可跳过进空工作区）； - 有配对配置 → 直进工作区列表； - 配对页可从设置/重配入口重进（重新配对）。 会话页跳转沿用 session-ui 挂载的 [SessionRoute]。 导航态由 [navState]（D-3 修复）注入：MainActivity 在 onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。 工作区 VM（[workspaceViewModel]）由 MainActivity 持有（fix-workspace-wiring 修复， navState 同模式提升），本组件只负责在工作区分支用 [DisposableEffect] 把它接入 [ServiceWire.uiConnector]——配对成功后列表能渲染（此前 VM 裸建从未接线，uiConnector 全仓无调用点，见 fix-workspace-wiring 知识基底）。 @consumes dev.agentmirror.app.pairing @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.session @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.app.workspace

### Kotlin · dev.agentmirror.app.pairing

- **职责**：配对：扫码连接（路线 a：QR 载服务端地址 + 配对 token，可选 TS authkey，需求 011）。
- **导出面**：Failed, Pairing, PairingConfig, PairingConfigStore, PairingFailCause, PairingRoute, PairingScreen, PairingViewModel, QrParseException, QrPayload, QrPayloadParser, SharedPreferencesPairingConfigStore
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme
- **doc 全文**：配对：扫码连接（路线 a：QR 载服务端地址 + 配对 token，可选 TS authkey，需求 011）。 负责相机扫码、地址解析与配对握手、配置持久化与常驻连接装配；替代 "终端 App + Tailscale App + SSH 配置"三件套（需求 001 单一 App 原则）。 配对成功与冷启动重连共用 [startPersistentConnection] 作为唯一装配入口。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme

### Kotlin · dev.agentmirror.app.session

- **职责**：会话页：单个 tmux 会话的交互界面。
- **导出面**：Attachment, Failed, Failure, HttpUrlConnectionUploader, SessionRoute, SessionScreen, SessionViewModel, Success
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.termview, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme, dev.agentmirror.terminal
- **doc 全文**：会话页：单个 tmux 会话的交互界面。 组合终端渲染（termview）与输入下发（conn），承载缩放、手势与快捷输入条； 图片附件走 multipart HTTP 上传（上传基地址由 service 装配的 ServiceWire 统一注入）， 跨层共享连接经 service 的 ServiceWire.uiConnector 扇出订阅。会话页已完整落位： 镜像流（snapshot/delta/scrollback 本地滚动补页）、发送必达回执、附件路径注入光标处。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.termview @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.terminal

## 3. 需求基
- 标题引用条目：（无编号引用）
- requirement-base/REVISIONS.md 必读（被推翻的结论不回改条目）

## 4. 纪律（本工程通用，违反即返工）
- 判据要断言「世界变了」，不是「东西在那儿」。写完先拿它去跑坏状态，判不红就是白写。
- 单测绿 ≠ 功能通。凡是用户能点到的东西，必须模拟器实测截图（见 .claude/skills/emulator-manual-test）。
- ⛔⛔ 遍历进程只取 comm，禁止取 argv。⛔⛔ 绝不碰用户真实 tmux（默认 socket）。
- 干完调一次 report_result，不要传 task_id 参数。不给 leader 发进度消息。
