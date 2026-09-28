# 架构维基（自动生成）

> ⚠️ **生成物，勿手改。** 本文件由 `tools/archwiki/build_wiki.py` 从源码现算生成，改源码后重跑 `python3 tools/archwiki/build_wiki.py` 刷新；重跑无 diff（幂等）。人工改动会被覆盖。

扫描 **19** 个包（Go 9 + Kotlin 10）、**31** 条包间依赖边。

## 判据结果

| 判据 | 说明 | 结果 |
|---|---|---|
| T1-1 | 无环（9 个 Go 包） | ✅ 通过 |
| T1-2 | Go 9 包、Kotlin 10 包均有模块 doc | ✅ 通过 |
| _预留_ | 零消费者 / 孤儿子图 | 未落地（进 --check 前须配红测） |

## 总依赖图

```mermaid
flowchart LR
    go_cmd_agentmirrord["cmd/agentmirrord"]
    go_internal_agentstate["internal/agentstate"]
    go_internal_api["internal/api"]
    go_internal_bridge["internal/bridge"]
    go_internal_config["internal/config"]
    go_internal_discovery["internal/discovery"]
    go_internal_pairing["internal/pairing"]
    go_internal_protocol["internal/protocol"]
    go_internal_tsnetd["internal/tsnetd"]
    kt_dev_agentmirror_app["dev.agentmirror.app"]
    kt_dev_agentmirror_app_ui_theme["dev.agentmirror.app.ui.theme"]
    kt_dev_agentmirror_app_pairing["dev.agentmirror.app.pairing"]
    kt_dev_agentmirror_app_workspace["dev.agentmirror.app.workspace"]
    kt_dev_agentmirror_app_termview["dev.agentmirror.app.termview"]
    kt_dev_agentmirror_app_service["dev.agentmirror.app.service"]
    kt_dev_agentmirror_app_tsnet["dev.agentmirror.app.tsnet"]
    kt_dev_agentmirror_app_conn["dev.agentmirror.app.conn"]
    kt_dev_agentmirror_app_session["dev.agentmirror.app.session"]
    kt_dev_agentmirror_terminal["dev.agentmirror.terminal"]
    go_cmd_agentmirrord --> go_internal_api
    go_cmd_agentmirrord --> go_internal_config
    go_cmd_agentmirrord --> go_internal_pairing
    go_cmd_agentmirrord --> go_internal_tsnetd
    go_internal_agentstate --> go_internal_protocol
    go_internal_api --> go_internal_agentstate
    go_internal_api --> go_internal_bridge
    go_internal_api --> go_internal_discovery
    go_internal_api --> go_internal_protocol
    kt_dev_agentmirror_app --> kt_dev_agentmirror_app_pairing
    kt_dev_agentmirror_app --> kt_dev_agentmirror_app_service
    kt_dev_agentmirror_app --> kt_dev_agentmirror_app_session
    kt_dev_agentmirror_app --> kt_dev_agentmirror_app_ui_theme
    kt_dev_agentmirror_app --> kt_dev_agentmirror_app_workspace
    kt_dev_agentmirror_app_pairing --> kt_dev_agentmirror_app_conn
    kt_dev_agentmirror_app_pairing --> kt_dev_agentmirror_app_service
    kt_dev_agentmirror_app_pairing --> kt_dev_agentmirror_app_tsnet
    kt_dev_agentmirror_app_pairing --> kt_dev_agentmirror_app_ui_theme
    kt_dev_agentmirror_app_workspace --> kt_dev_agentmirror_app_conn
    kt_dev_agentmirror_app_workspace --> kt_dev_agentmirror_app_tsnet
    kt_dev_agentmirror_app_workspace --> kt_dev_agentmirror_app_ui_theme
    kt_dev_agentmirror_app_termview --> kt_dev_agentmirror_terminal
    kt_dev_agentmirror_app_service --> kt_dev_agentmirror_app
    kt_dev_agentmirror_app_service --> kt_dev_agentmirror_app_conn
    kt_dev_agentmirror_app_service --> kt_dev_agentmirror_app_tsnet
    kt_dev_agentmirror_app_session --> kt_dev_agentmirror_app_conn
    kt_dev_agentmirror_app_session --> kt_dev_agentmirror_app_service
    kt_dev_agentmirror_app_session --> kt_dev_agentmirror_app_termview
    kt_dev_agentmirror_app_session --> kt_dev_agentmirror_app_tsnet
    kt_dev_agentmirror_app_session --> kt_dev_agentmirror_app_ui_theme
    kt_dev_agentmirror_app_session --> kt_dev_agentmirror_terminal
```

## 包架构卡

### Go · cmd/agentmirrord

- **职责**：Command agentmirrord is the service-side daemon of AgentMirror (product github.com/agentmirror/agentmirror): a sidecar that mirrors the user's existing tmux sessions to the Android app over WebSocket.
- **导出面**：main
- **依赖边**：internal/api, internal/config, internal/pairing, internal/tsnetd

### Go · internal/agentstate

- **职责**：Package agentstate maps per-agent CLI output and process trees to a normalized state (working/idle/blocked/done), degrading to unknown when undecidable.
- **导出面**：Adapter, AgentKind, ClaudeCodeAdapter, CodexAdapter, Confidence, DefaultRegistry, Identify, IdentifyInput, Proc, Registry, Sample, State, Track
- **依赖边**：internal/protocol

### Go · internal/api

- **职责**：Package api implements the service-side WebSocket API and the image upload endpoint, wiring together discovery and bridge (task ws-api).
- **导出面**：Discoverer, NewServer, NewStateProvider, Options, Server, StateProvider, TokenValidator
- **依赖边**：internal/agentstate, internal/bridge, internal/discovery, internal/protocol

### Go · internal/bridge

- **职责**：Package bridge exposes a single tmux pane as a terminal bridge: first-frame snapshot, incremental output stream, whole-message input injection with a decidable ack, resize, and scrollback paging.
- **导出面**：ErrInvalidKey, ErrPaneNotFound, ErrServerUnreachable, ErrTmuxTimeout, NewPane, Pane
- **依赖边**：（无）

### Go · internal/config

- **职责**：Package config loads the daemon configuration from command-line flags and environment variables.
- **导出面**：Config, Load
- **依赖边**：（无）

### Go · internal/discovery

- **职责**：Package discovery enumerates every tmux server socket on the host and aggregates their sessions and panes into the two-level workspace model (requirements 001 and 002).
- **导出面**：DefaultSocketDirs, Discover, DiscoverWithDirs, Model, Pane, Workspace
- **依赖边**：（无）

### Go · internal/pairing

- **职责**：Package pairing implements token-based device pairing and QR-code onboarding for the Android app.
- **导出面**：Address, DetectAddresses, EnsureToken, GenerateToken, KindLAN, KindLoopback, KindTailnet, LoadToken, NewPayload, NewPayloadWithCandidates, Onboarding, Payload, PayloadVersion, PrimaryHost, PrintOnboarding, PrintOnboardingAll, PrintOnboardingWith, RenderQR, SaveToken, TokenDir, WSURL, WithTailnet
- **依赖边**：（无）

### Go · internal/protocol

- **职责**：Package protocol defines the wire contract between the Android app and the agentmirrord service.
- **导出面**：AgentState, Auth, AuthAck, BinaryHeaderLen, BinaryKind, BinaryMagic, BinaryMaxPayloadLen, BinaryMaxRefLen, BinaryPayload, DecodeBinary, DefaultBinarySessionRefLen, EncodeBinary, Envelope, ErrBadMagic, ErrBadPayload, ErrInvalidCount, ErrInvalidField, ErrInvalidGeometry, ErrInvalidRef, ErrInvalidState, ErrMissingVersion, ErrRefTooLong, ErrTruncated, ErrUnknownKind, ErrUnknownType, ErrUnsupportedVersion, ErrorCode, ErrorFrame, FrameType, Input, InputAck, InputFailReason, Key, List, ListDelta, Listing, MarshalFrame, Resize, Scrollback, Session, Subscribe, Typed, UnmarshalFrame, Unsubscribe, UploadResp, Workspace
- **依赖边**：（无）

### Go · internal/tsnetd

- **职责**：Package tsnetd embeds Tailscale networking (tsnet) so the daemon's WebSocket service is reachable over the tailnet as well as the LAN.
- **导出面**：DefaultDir, ErrTailnetDisabled, Group, New, Options
- **依赖边**：（无）

### Kotlin · dev.agentmirror.app

- **职责**：Compose 应用根组合。
- **导出面**：AgentMirrorApp, MainActivity, MainNavState, Session
- **依赖边**：dev.agentmirror.app.pairing, dev.agentmirror.app.service, dev.agentmirror.app.session, dev.agentmirror.app.ui.theme, dev.agentmirror.app.workspace
- **doc 全文**：Compose 应用根组合。 依需求 004「客户端无状态」，本组件只做路由，不持有任何业务状态。 首启路由（pairing-ui 知识基底 §1）： - 无配对配置 → 配对页（扫码/手填，可跳过进空工作区）； - 有配对配置 → 直进工作区列表； - 配对页可从设置/重配入口重进（重新配对）。 会话页跳转沿用 session-ui 挂载的 [SessionRoute]。 导航态由 [navState]（D-3 修复）注入：MainActivity 在 onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。 工作区 VM（[workspaceViewModel]）由 MainActivity 持有（fix-workspace-wiring 修复， navState 同模式提升），本组件只负责在工作区分支用 [DisposableEffect] 把它接入 [ServiceWire.uiConnector]——配对成功后列表能渲染（此前 VM 裸建从未接线，uiConnector 全仓无调用点，见 fix-workspace-wiring 知识基底）。 @consumes dev.agentmirror.app.pairing @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.session @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.app.workspace

### Kotlin · dev.agentmirror.app.ui.theme

- **职责**：历史品牌深蓝（`0xFF1B2A4A`）。
- **导出面**：AgentMirrorTheme, DarkStateTones, LightStateTones, LocalStateTones, MonoFontFamily, Spacing, StateTone, StateTones
- **依赖边**：（无）
- **doc 全文**：历史品牌深蓝（`0xFF1B2A4A`）。 ui-redesign（018）后 M3 深浅两套 primary 均已改用独立取值（深 `0xFF9DBDFF`、 浅 `0xFF2F5DA8`），本 token 自彼时起无任何消费点——保留仅为存档（原 colors.xml 的 `brand_primary` 资源随 stage3 #19 删除，本 token 不再有资源对照）。

### Kotlin · dev.agentmirror.app.pairing

- **职责**：配对：扫码连接（路线 a：QR 载服务端地址 + 配对 token，可选 TS authkey，需求 011）。
- **导出面**：Failed, Pairing, PairingConfig, PairingConfigStore, PairingFailCause, PairingRoute, PairingScreen, PairingViewModel, QrParseException, QrPayload, QrPayloadParser, SharedPreferencesPairingConfigStore
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme
- **doc 全文**：配对：扫码连接（路线 a：QR 载服务端地址 + 配对 token，可选 TS authkey，需求 011）。 负责相机扫码、地址解析与配对握手、配置持久化与常驻连接装配；替代 "终端 App + Tailscale App + SSH 配置"三件套（需求 001 单一 App 原则）。 配对成功与冷启动重连共用 [startPersistentConnection] 作为唯一装配入口。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme

### Kotlin · dev.agentmirror.app.workspace

- **职责**：工作区：两级导航（舰队 → 会话），对应需求 001「舰队视角」、002「两级分组」。
- **导出面**：ConnectionUi, SessionUi, StateBadge, StateBadgeStyle, WorkspaceScreen, WorkspaceUi, WorkspaceUiState, WorkspaceViewModel
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme
- **doc 全文**：工作区：两级导航（舰队 → 会话），对应需求 001「舰队视角」、002「两级分组」。 - [WorkspaceViewModel]：纯 JVM 视图模型，消费 conn 层 listing/list_delta 帧流 → UI 状态；聚合字段（session_count / aggregate_state）为服务端权威值，只渲染不重算（012）。 - [WorkspaceScreen] / [StateBadge]：薄 Compose 渲染层；状态徽章五值（008）。 二级导航经 [WorkspaceScreen] 的 onOpenSession 回调把 (ref, name) 交给根路由 [AgentMirrorApp]，由其挂载 [SessionRoute] 进入会话页。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme

### Kotlin · dev.agentmirror.app.termview

- **职责**：终端渲染：快照/增量渲染 + 本地滚动视口（60fps，需求 006）。
- **导出面**：ANSI_COLORS, DRAWABLE_EQUIVALENTS, GlyphFallbackPolicy, GlyphFontProvider, GlyphRunBuilder, GlyphSegment, GlyphSlot, TermSurfaceView, TermViewPresenter, XTERM_256
- **依赖边**：dev.agentmirror.terminal
- **doc 全文**：终端渲染：快照/增量渲染 + 本地滚动视口（60fps，需求 006）。 [TermViewPresenter] 纯 JVM 视口状态机（跟随/锁定历史、可见行窗口、捏合行列数换算、 脏区合并），单测全部打在它上；[TermSurfaceView] 薄 Android 层（Canvas 画格、拖动/捏合 手势、Choreographer 帧调度）。内核为 :terminal 模块；resize 协议帧由上层接线（conn/session）。 @consumes dev.agentmirror.terminal

### Kotlin · dev.agentmirror.app.service

- **职责**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。
- **导出面**：AppClockPump, MirrorForegroundService, NetworkConnectivityWatcher, NoopTransportFactory, NotificationHelper, OkHttpTransportFactory, OkHttpWebSocketTransport, OnScreenFallbackPump, ServiceWire, StateWatcher, TsnetBootstrap
- **依赖边**：dev.agentmirror.app, dev.agentmirror.app.conn, dev.agentmirror.app.tsnet
- **doc 全文**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。 分层（fg-service 知识基底 §1）： - [StateWatcher]：纯 JVM 核心逻辑（验收单测全打这里），消费 conn 层 listing/list_delta 流，检测会话状态沿变化（→blocked/→done）→ 通知；同状态重复抑制；unknown 不通知。 - [NotificationHelper]：通知渠道（常驻/状态两条）+ 常驻通知与状态通知 + 会话页深链 PendingIntent（action/extra 由 [MainActivity] 的 handleDeepLink 消费，非本包）。 - [MirrorForegroundService]：薄 Android 层，startForeground（dataSync）+ 生命周期绑定 [ConnectionManager]（经 [ServiceWire]）；断连静默重连归 conn 层，本服务只反映状态。 已接线（feat-fg-service-wiring）：配对成功/冷启动/进入会话经 [MirrorForegroundService.start] 启动（startForegroundService），连接与时钟泵由本服务承接（004/011 前台服务路线）。 - [ServiceWire]：接线点——传输工厂（默认 [OkHttpTransportFactory]）、UI 监听桥 （[uiConnector]）与服务监听槽（[serviceListener]）、连接配置注入；进程级持有唯一 [ConnectionManager]，服务与 UI 都经它访问同一单例。 电量策略（004 裁定）：服务被系统杀 → 冷启动重连即恢复（客户端无状态，没有丢失可言）。 服务**不持有连接状态**（004 无状态底线）：连接是 [ServiceWire] 进程级单例，配置唯一来源 是 SharedPreferences，服务只经 [ServiceWire.managerOrNull] 读取并驱动时钟泵 （[MirrorForegroundService.pumpOnce]，2s 一拍，在屏组合不再各自持有）。服务不可用时 在屏兜底泵 [OnScreenFallbackPump] 接管（fix-app-runtime-sa：服务被杀前台仍推进）， 服务恢复即让出（泵归属判据 [ServiceWire.servicePumpActive]，不双泵）。 @consumes dev.agentmirror.app @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.tsnet

### Kotlin · dev.agentmirror.app.tsnet

- **职责**：内嵌 Tailscale 联网（需求 007/011 路线 a：服务端/App 内嵌 tailscale）。
- **导出面**：ConnectionPath, Environment, Error, GomobileTsnetBackend, NetIfSnapshot, TsnetAuthKeys, TsnetBackend, TsnetDial, TsnetInterfaceCodec, TsnetManager, TsnetProxy, TsnetProxySocketFactory, TsnetSocks, TsnetSocksAuthenticator, TsnetWire, Up
- **依赖边**：（无）
- **doc 全文**：内嵌 Tailscale 联网（需求 007/011 路线 a：服务端/App 内嵌 tailscale）。 提供 LAN 之外的可达通道：QR 可选携带 TS authkey，[TsnetManager] 用它在 App 进程内起 tsnet 用户态节点（gomobile 绑定 libs/tsnetbind.aar，无 VpnService、 零系统权限），Up 后经 [TsnetDial] 给 OkHttp 配 loopback SOCKS5 即达 tailnet。 分层（native 隔离红线）：[TsnetBackend] 薄适配接口 → [GomobileTsnetBackend] 唯一触达 native；状态机/authkey 校验/dial 选择均纯 JVM 可测。 路线裁定与实测数字见 docs/decisions/app-tsnet.md。

### Kotlin · dev.agentmirror.app.conn

- **职责**：连接层：与主机 sidecar 的 WebSocket 连接、会话枚举、帧协议编解码。
- **导出面**：AgentState, AuthAckFrame, AuthFrame, BinaryFrame, BinaryFrameCodec, BinaryKind, Clock, Connection, ConnectionConfig, ConnectionManager, ConnectionState, ErrorCode, ErrorFrame, FrameCodec, FrameDecodeException, FrameEncodeException, FrameError, InputAckFrame, InputFailReason, InputFrame, InputKey, ListDeltaFrame, ListFrame, Listener, ListingFrame, ProtocolVersion, Real, ReconnectPolicy, ResizeFrame, ScrollbackFrame, Session, SubscribeFrame, TransportListener, UnsubscribeFrame, WebSocketTransport, Workspace
- **依赖边**：（无）
- **doc 全文**：连接层：与主机 sidecar 的 WebSocket 连接、会话枚举、帧协议编解码。 分层（自底向上）： 1. [FrameCodec] / [BinaryFrameCodec] —— 纯函数编解码：JSON 控制帧（信封 + 12 类帧 载荷，docs/protocol.md §4）+ 二进制流帧（§6，含 scrollback 12 字节收敛区间头）。 编解码都消费同一份契约夹具做字节级断言（server/internal/protocol/testdata/）。 2. [Connection] —— 单条 WS 生命周期状态机（握手 → 就绪 → 关闭）。 3. [ConnectionManager] —— 重连策略 + 订阅簿记：重连后自动重放 auth + 全部活跃 subscribe（004 无状态铁律的重放语义）；listing seq 不连续 → 自动重新 list。 上层（UI/service）只见回调（[ConnectionManager.Listener] / [Connection.Listener]），不见 WS 细节。本层不持久任何会话状态。

### Kotlin · dev.agentmirror.app.session

- **职责**：会话页：单个 tmux 会话的交互界面。
- **导出面**：Attachment, Failed, Failure, HttpUrlConnectionUploader, SessionRoute, SessionScreen, SessionViewModel, Success
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.termview, dev.agentmirror.app.tsnet, dev.agentmirror.app.ui.theme, dev.agentmirror.terminal
- **doc 全文**：会话页：单个 tmux 会话的交互界面。 组合终端渲染（termview）与输入下发（conn），承载缩放、手势与快捷输入条； 图片附件走 multipart HTTP 上传（上传基地址由 service 装配的 ServiceWire 统一注入）， 跨层共享连接经 service 的 ServiceWire.uiConnector 扇出订阅。会话页已完整落位： 镜像流（snapshot/delta/scrollback 本地滚动补页）、发送必达回执、附件路径注入光标处。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.termview @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme @consumes dev.agentmirror.terminal

### Kotlin · dev.agentmirror.terminal

- **职责**：解析事件接收方：把字节流解析结果（可打印字符/控制符/转义序列）交给终端状态机。
- **导出面**：BLANK, CONTINUATION, Cell, CharWidth, DEFAULT, Indexed, Rgb, ScreenSnapshot, ScrollbackBuffer, TerminalEmulator, TextStyle
- **依赖边**：（无）
