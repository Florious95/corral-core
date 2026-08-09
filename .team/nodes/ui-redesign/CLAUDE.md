# 知识基底 · ui-redesign（全 App 视觉重设计——Fable 5 攻坚，018 标准）

## 0. 任务（taskbook.yaml#ui-redesign）
- 判定权威：requirement-base/entries/018（视觉标准七条+审查关）——**先完整读，逐条内化**。这不是修 bug，是把 App 从"功能骨架"做成"生产级产品"的设计任务。
- 用户原话（回炉动因）："已经跟你说了用户体验要达到什么样的标准，结果就这么简陋的 UI，直接可以扔进垃圾桶。"实锤缺陷：图 28 列表页内容顶进状态栏/无标题栏/长路径四行撑爆/数字徽章悬空无层级；图 29 会话页顶栏被完整会话名撑爆两行压住返回键。
- 范围（三页+基座）：
  1. **Theme 基座**：M3 token 化——色板（品牌主色你定，工程无 VI，选专业克制的开发者工具审美）、字阶、间距、圆角；深浅双套完整；状态栏图标适配。
  2. **列表页**：TopAppBar（产品名 agentmirror + 连接状态徽标）；工作区卡片/行重设计——目录名为主信息（basename 加粗大字）、完整路径次信息（中段省略单行）、右侧会话数+聚合状态徽章对齐；五态徽章色彩语义（工作中/阻塞/完成/空闲/未知，色弱可辨）；空态/加载态/断连态设计。
  3. **会话页**：紧凑顶栏（会话名尾部省略单行+返回键安全区）；键条与输入条视觉统一；终端区最大化。
  4. **配对页**：扫码/手填布局梳理、错误与重试态视觉。
- 红线：**不动业务逻辑**——VM/conn/service 层零改动，只动 Compose UI 层与 Theme；不动 termview 画布内部（豆腐块归 fix-term-glyph-render）。SessionScreen 的键条/输入条只调视觉不改行为。
- **视觉验收关（018 §二，你的交件门槛）**：全页全态截图（配对/列表/会话 × 正常/空/错误 × 深/浅色）落 e2e/artifacts/ui-review/（模拟器 adb screencap），leader 逐图目检。无截图=不受理交件。
- 验收：`cd app && ./gradlew -q :app:testDebugUnitTest`（238+ 全绿，UI 改动不破坏既有测试——测试断言的语义文本/contentDescription 保持或同步更新）+ 截图落档。

## 1. 现场基
- 三页现场：AgentMirrorApp.kt（导航壳）、workspace/WorkspaceScreen.kt+StateBadge.kt、session/SessionScreen.kt、pairing/PairingScreen.kt；Theme：ui/theme/（现状裸 M3 默认）。
- StateBadge 已有 contentDescription（R-7，appseams 加的）——保持语义断言兼容（StateBadgeTest 9 测锁定五态文案语义，改视觉别改文案；要改文案先改测试同步）。
- **并行环境**：fix-reconnect-stale-config 席位动 conn/service 层不动 UI；fix-term-glyph-render 动 app/terminal/ 与 termview/。你与他们文件零交集（termview 例外：SessionScreen 引用它，只调其外围布局不进画布内部）。每次落盘保持 :app 可编译。
- 模拟器截图：`adb exec-out screencap -p > e2e/artifacts/ui-review/<page>-<state>-<theme>.png`；深色切换 `adb shell cmd uimode night yes|no`。

## 2. 需求基（指针）
1. requirement-base/entries/018（判定权威——七条逐条对照）
2. requirement-base/entries/002（两级结构——列表信息架构的语义根据）
3. requirement-base/entries/003（对话体验——会话页布局的语义根据）
4. requirement-base/entries/017 R-6（当期锁中文）/R-7（contentDescription）

## 3. 经验基
- 你是 Fable 5 攻坚席：审美自主裁量，但每个决策注释里写"为什么"（后继席位要能从代码读出设计系统）；token 集中定义禁止散落魔法值；交件前自跑全量+全套截图自检 018 七条；净化前缀 env -u TEAM_AGENT_*。


## 4. 架构基（build_wiki.py 现算，2026-08-09，18 包 22 边；全卡见 docs/wiki/README.md）
- 本案 write_scope 包：app_workspace, app_session, app_pairing, ui_theme
- 正向依赖（你消费的契约，只读）：kt_dev_agentmirror_app_conn, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_termview
- **反向依赖（改动波及面，回归自查范围）**：kt_dev_agentmirror_app
- 各包职责/导出面/依赖边以 docs/wiki/README.md 对应架构卡为准（现算产物，与代码同步）。

## 5. 需求基增补（librarian 撞库，2026-08-09）
- 003 对话体验四标准是体验根标准；016 首触清单即体验验收面
- 视觉规范需求库此前无沉淀——018 是首个视觉条目，你的 Theme token 设计即为其实现基准（后继 UI 任务将对照你定的 token）

## 6. 影响闭包架构卡内联（契约级，build_wiki.py 现算）

### Kotlin · dev.agentmirror.app.workspace

- **职责**：工作区：两级导航（舰队 → 会话），对应需求 001「舰队视角」、002「两级分组」。
- **导出面**：ConnectionUi, SessionUi, StateBadge, StateBadgeStyle, WorkspaceScreen, WorkspaceUi, WorkspaceUiState, WorkspaceViewModel
- **依赖边**：dev.agentmirror.app.conn
- **doc 全文**：工作区：两级导航（舰队 → 会话），对应需求 001「舰队视角」、002「两级分组」。 - [WorkspaceViewModel]：纯 JVM 视图模型，消费 conn 层 listing/list_delta 帧流 → UI 状态；聚合字段（session_count / aggregate_state）为服务端权威值，只渲染不重算（012）。 - [WorkspaceScreen] / [StateBadge]：薄 Compose 渲染层；状态徽章五值（008）。 二级导航进入会话页由根路由（AgentMirrorApp）占位跳转，会话页归 session-ui 任务。

### Kotlin · dev.agentmirror.app.session

- **职责**：会话页：单个 tmux 会话的交互界面。
- **导出面**：Attachment, Failed, Failure, HttpUrlConnectionUploader, SessionRoute, SessionScreen, SessionViewModel, Success
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.termview
- **doc 全文**：会话页：单个 tmux 会话的交互界面。 组合终端渲染（termview）与输入下发（conn），承载缩放、手势与快捷输入条。 本包为占位骨架，由 session 任务落位实现。

### Kotlin · dev.agentmirror.app.pairing

- **职责**：配对：扫码连接（路线 a：QR 载服务端地址 + 配对 token，可选 TS authkey，需求 011）。
- **导出面**：Failed, Pairing, PairingConfig, PairingConfigStore, PairingFailCause, PairingRoute, PairingScreen, PairingViewModel, QrParseException, QrPayload, QrPayloadParser, SharedPreferencesPairingConfigStore
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service
- **doc 全文**：配对：扫码连接（路线 a：QR 载服务端地址 + 配对 token，可选 TS authkey，需求 011）。 负责相机扫码、地址解析与配对握手；替代"终端 App + Tailscale App + SSH 配置"三件套 （需求 001 单一 App 原则）。本包为占位骨架，由 pairing 任务落位实现。

### Kotlin · dev.agentmirror.app.ui.theme

- **职责**：品牌主色：深蓝（终端深夜配色基调，与资源 colors.xml 一致）。
- **导出面**：AgentMirrorTheme
- **依赖边**：（无）

### Kotlin · dev.agentmirror.app.conn

- **职责**：连接层：与主机 sidecar 的 WebSocket 连接、会话枚举、帧协议编解码。
- **导出面**：AgentState, AuthAckFrame, AuthFrame, BinaryFrame, BinaryFrameCodec, BinaryKind, Clock, Connection, ConnectionConfig, ConnectionManager, ConnectionState, ErrorCode, ErrorFrame, FrameCodec, FrameDecodeException, FrameEncodeException, FrameError, InputAckFrame, InputFailReason, InputFrame, InputKey, ListDeltaFrame, ListFrame, Listener, ListingFrame, ProtocolVersion, Real, ReconnectPolicy, ResizeFrame, ScrollbackFrame, Session, SubscribeFrame, TransportListener, UnsubscribeFrame, WebSocketTransport, Workspace
- **依赖边**：（无）
- **doc 全文**：连接层：与主机 sidecar 的 WebSocket 连接、会话枚举、帧协议编解码。 分层（自底向上）： 1. [FrameCodec] / [BinaryFrameCodec] —— 纯函数编解码：JSON 控制帧（信封 + 12 类帧 载荷，docs/protocol.md §4）+ 二进制流帧（§6，含 scrollback 12 字节收敛区间头）。 编解码都消费同一份契约夹具做字节级断言（server/internal/protocol/testdata/）。 2. [Connection] —— 单条 WS 生命周期状态机（握手 → 就绪 → 关闭）。 3. [ConnectionManager] —— 重连策略 + 订阅簿记：重连后自动重放 auth + 全部活跃 subscribe（004 无状态铁律的重放语义）；listing seq 不连续 → 自动重新 list。 上层（UI/service）只见 Flow/回调，不见 WS 细节。本层不持久任何会话状态。

### Kotlin · dev.agentmirror.app.service

- **职责**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。
- **导出面**：MirrorForegroundService, NoopTransportFactory, NotificationHelper, OkHttpTransportFactory, OkHttpWebSocketTransport, ServiceWire, StateWatcher
- **依赖边**：dev.agentmirror.app, dev.agentmirror.app.conn
- **doc 全文**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。 分层（fg-service 知识基底 §1）： - [StateWatcher]：纯 JVM 核心逻辑（验收单测全打这里），消费 conn 层 listing/list_delta 流，检测会话状态沿变化（→blocked/→done）→ 通知；同状态重复抑制；unknown 不通知。 - [NotificationHelper]：通知渠道（常驻/状态两条）+ 常驻通知与状态通知 + 会话页深链 PendingIntent。 - [MirrorForegroundService]：薄 Android 层，startForeground（dataSync）+ 生命周期绑定 [ConnectionManager]（经 [ServiceWire]）；断连静默重连归 conn 层，本服务只反映状态。 - [ServiceWire]：接线点——传输工厂（默认 [NoopTransportFactory]）、UI 监听桥、连接配置注入。 电量策略（004 裁定）：仅在有活跃订阅或用户开启后台守望时运行前台服务；服务被系统杀 → 冷启动重连即恢复（客户端无状态，没有丢失可言）。UI/配对层经 [ServiceWire] 控制启动/停止。

### Kotlin · dev.agentmirror.app.termview

- **职责**：终端渲染：快照/增量渲染 + 本地滚动视口（60fps，需求 006）。
- **导出面**：ANSI_COLORS, TermSurfaceView, TermViewPresenter
- **依赖边**：（无）
- **doc 全文**：终端渲染：快照/增量渲染 + 本地滚动视口（60fps，需求 006）。 [TermViewPresenter] 纯 JVM 视口状态机（跟随/锁定历史、可见行窗口、捏合行列数换算、 脏区合并），单测全部打在它上；[TermSurfaceView] 薄 Android 层（Canvas 画格、拖动/捏合 手势、Choreographer 帧调度）。内核为 :terminal 模块；resize 协议帧由上层接线（conn/session）。
