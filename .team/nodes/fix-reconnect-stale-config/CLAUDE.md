# 知识基底 · fix-reconnect-stale-config（锁屏后无限重连——P0 活现场）

## 0. 任务（taskbook.yaml#fix-reconnect-stale-config）
- 活现场（2026-08-09 四次真机）：用户真实序列=扫旧码(ws://10.20.55.20:9900/ws 不可达)→改扫新码/手填(ws://192.168.31.116:9900/ws)连接成功进列表→锁屏数分钟→解锁→无限「重连中」会话全点不开，持续 10+ 分钟。**daemon 侧铁证：全程零入连（lsof 仅 LISTEN）**——重连请求根本没到达正确地址。
- 根因候选（两条都取证定性，可能并存）：
  1. **单例陈旧配置**：ServiceWire.manager 幂等复用（`manager != null` 即复用）。若首次 manager 创建发生在旧地址时代（试配对/首扫失败路径是否创建过 manager？取证），后续 setConfig 只更新持久层/后续新建者，已存在实例重连拨旧址。红测：改配置→触发重连→断言拨号目标是新址（修前应红）。
  2. **E2 网络回调缺口**（审计已标记）：ConnectivityManager.NetworkCallback 从未注册（testNetworkAvailableSkipsWait 只测入参级信号）。锁屏 WiFi 休眠断连→指数退避爬到长间隔→解锁网络恢复无人通知→干等退避，观感=永远重连中。修复：注册回调，网络恢复即打断退避立即重试。
- 附带（018 标准5 失败可见）：重连中 UI 显示当前拨号地址+已试次数——用户可一眼看出「在拨错的地址」这类问题。
- 验收：全量 :app:testDebugUnitTest；自查走查：模拟器复现真实序列（连接成功→`adb shell input keyevent 26` 锁屏→等 60s→解锁→断言 30s 内列表恢复；以及换地址后重连拨新址的日志断言）。

## 1. 现场基
- ServiceWire.manager 单例与 setConfig：service/ServiceWire.kt（fix-cold-start-reconnect 刚加的注释写明双层幂等——正是嫌疑点，读透再动）。
- 重连退避：conn/ReconnectPolicy + ConnectionManager（E1 30+ 测是逻辑层形状）。
- PersistentConnection.kt：唯一启动入口（今天刚立，你的修改要保持该入口语义）。
- 试配对路径是否独立 manager：fix-pairing-scan-flow 证据写「试配对走独立 ConnectionManager+注入工厂」——若属实，候选①的「首建于旧地址」要另找路径（如首次扫码成功即 setConfig+start，之后用户改地址重连……取证为准，不要预设）。
- **并行环境**：ui-redesign 席位同期动 app/ 全域 UI 层——它不动 conn/；你不动 Screen 的视觉（重连状态文案数据你提供，展示归它）。每次落盘保持 :app 可编译。

## 2. 需求基（指针）
1. requirement-base/entries/003（状态零丢失/需要时被唤醒）
2. requirement-base/entries/018 标准5（失败可见）
3. docs/scenario-coverage.md E1/E2 行（审计原文）

## 3. 经验基
- 红测先行；取证先于预设（两候选都验，实锤哪条修哪条，都实锤都修）；净化前缀；交件前全量+锁屏走查脚本自跑；代码必须有注释。


## 4. 架构基（build_wiki.py 现算，2026-08-09，18 包 22 边；全卡见 docs/wiki/README.md）
- 本案 write_scope 包：app_conn, app_service, app_pairing
- 正向依赖（你消费的契约，只读）：kt_dev_agentmirror_app, kt_dev_agentmirror_app_conn, kt_dev_agentmirror_app_service
- **反向依赖（改动波及面，回归自查范围）**：kt_dev_agentmirror_app, kt_dev_agentmirror_app_pairing, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_session, kt_dev_agentmirror_app_workspace
- 各包职责/导出面/依赖边以 docs/wiki/README.md 对应架构卡为准（现算产物，与代码同步）。

## 5. 需求基增补（librarian 撞库，2026-08-09）
- 004 无状态免疫：冷启动→重连→首屏快照 1 秒恢复（你的修复要守住的承诺）
- 013/015：20 轮杀-恢复、connection-drop 20/20 老化实证——逻辑层绿但真机锁屏路径未覆盖（本案正是补此落差）
- 016 首触清单：锁屏重连在列；失败必有明确报错
- 「退避算法」需求库无沉淀（实现细节自由度在你，但网络恢复必须打断退避）

## 6. 影响闭包架构卡内联（契约级，build_wiki.py 现算）

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

### Kotlin · dev.agentmirror.app.pairing

- **职责**：配对：扫码连接（路线 a：QR 载服务端地址 + 配对 token，可选 TS authkey，需求 011）。
- **导出面**：Failed, Pairing, PairingConfig, PairingConfigStore, PairingFailCause, PairingRoute, PairingScreen, PairingViewModel, QrParseException, QrPayload, QrPayloadParser, SharedPreferencesPairingConfigStore
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service
- **doc 全文**：配对：扫码连接（路线 a：QR 载服务端地址 + 配对 token，可选 TS authkey，需求 011）。 负责相机扫码、地址解析与配对握手；替代"终端 App + Tailscale App + SSH 配置"三件套 （需求 001 单一 App 原则）。本包为占位骨架，由 pairing 任务落位实现。

### Kotlin · dev.agentmirror.app.session

- **职责**：会话页：单个 tmux 会话的交互界面。
- **导出面**：Attachment, Failed, Failure, HttpUrlConnectionUploader, SessionRoute, SessionScreen, SessionViewModel, Success
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service, dev.agentmirror.app.termview
- **doc 全文**：会话页：单个 tmux 会话的交互界面。 组合终端渲染（termview）与输入下发（conn），承载缩放、手势与快捷输入条。 本包为占位骨架，由 session 任务落位实现。

### Kotlin · dev.agentmirror.app.workspace

- **职责**：工作区：两级导航（舰队 → 会话），对应需求 001「舰队视角」、002「两级分组」。
- **导出面**：ConnectionUi, SessionUi, StateBadge, StateBadgeStyle, WorkspaceScreen, WorkspaceUi, WorkspaceUiState, WorkspaceViewModel
- **依赖边**：dev.agentmirror.app.conn
- **doc 全文**：工作区：两级导航（舰队 → 会话），对应需求 001「舰队视角」、002「两级分组」。 - [WorkspaceViewModel]：纯 JVM 视图模型，消费 conn 层 listing/list_delta 帧流 → UI 状态；聚合字段（session_count / aggregate_state）为服务端权威值，只渲染不重算（012）。 - [WorkspaceScreen] / [StateBadge]：薄 Compose 渲染层；状态徽章五值（008）。 二级导航进入会话页由根路由（AgentMirrorApp）占位跳转，会话页归 session-ui 任务。
