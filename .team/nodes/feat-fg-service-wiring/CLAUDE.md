# 知识基底 · feat-fg-service-wiring（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: feat-fg-service-wiring
    goal: >
      兑现 011 已裁的「Android 前台服务 + 常驻连接」路线。当前 `MirrorForegroundService`
      在 AndroidManifest.xml 里声明了，但**全仓库没有任何 startService / startForegroundService /
      stopService 调用点**，它从未被启动过（其自身 KDoc 在 commit 105a2fe 即写明"死件家族第六例，
      接线留待后案"）；真实接线是 ConnectionManager 由 startPersistentConnection（MainActivity.onCreate）
      或 SessionRoute.createSessionViewModel 创建，时钟泵由在屏组合的 LaunchedEffect 驱动，
      fg-service 的 pumpRunnable 从未运行。
      本条不是新功能，是**未兑现的已裁需求**：011「推送/后台：Android 前台服务 + 常驻连接，
      不依赖 FCM/Google 服务」（依据 010 验收只含安卓 + 004 前台服务路线已裁 + 008 开源自托管友好）；
      004 亦明写「Android 额外提供前台服务选项（通知栏常驻），成本低收益实」。
      **架构底线（004 不得违背）**：前台服务是**体验增强，不得成为正确性依赖**。
      004 的主张是「不保活、客户端无状态、被杀即无所谓」——即便服务被杀，
      冷启动 → 重连 → 首屏快照仍须在 1 秒内恢复到被杀前的画面。
      所以接线后必须仍能通过「杀服务 → 冷启动恢复」的验证，不许把状态搬进服务里。
      范围：服务生命周期接线（何时启动/何时停止，与配对完成、会话进入、用户显式断开的关系）；
      连接与时钟泵归属改由服务承接（在屏组合不再各自持有）；通知栏常驻内容与点击深链
      （NotificationHelper 已有深链实现，真实消费方是 MainActivity.handleDeepLink）；
      前台服务类型与 Android 14+ 的 foregroundServiceType 合规声明；
      锁屏/后台期间连接维持与恢复。
      红线：不得依赖 FCM/Google 服务（011）；不得把客户端变成有状态（004）；
      通知内容不得含配对 token 明文（协议 §9）；静默经济红线照常适用——
      服务常驻期间 CPU 与子进程派生必须有界，须给出量测。
      红测先行：服务启动/停止的状态机断言、被杀后冷启动恢复断言、
      「连接由服务承接而非在屏组合」的归属断言。
    acceptance:
      - "bash -lc 'env -u TEAM_AGENT_* bash -lc \"cd app && ./gradlew -q :app:testDebugUnitTest\"'"
      - "bash -lc 'python3 tools/archwiki/build_wiki.py --check'"
      - "bash -lc 'grep -rn \"startForegroundService\\|startService\" app/app/src/main/java --include=*.kt | grep -v test'"
    deps: []
    write_scope: ["app/app/src/main/java/dev/agentmirror/app/service/", "app/app/src/main/java/dev/agentmirror/app/", "app/app/src/main/AndroidManifest.xml", "app/app/src/test/"]
    evidence: ".team/evidence/feat-fg-service-wiring.json"
    contention: impl
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：dev.agentmirror.app.service, dev.agentmirror.app
- 正向依赖（你消费的契约，只读）：kt_dev_agentmirror_app_conn, kt_dev_agentmirror_app_pairing, kt_dev_agentmirror_app_session, kt_dev_agentmirror_app_tsnet, kt_dev_agentmirror_app_ui_theme, kt_dev_agentmirror_app_workspace
- **反向依赖（波及面=回归自查范围）**：kt_dev_agentmirror_app_pairing, kt_dev_agentmirror_app_session

### 闭包架构卡内联（职责/导出面/依赖边）

### Kotlin · dev.agentmirror.app.service

- **职责**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。
- **导出面**：MirrorForegroundService, NetworkConnectivityWatcher, NoopTransportFactory, NotificationHelper, OkHttpTransportFactory, OkHttpWebSocketTransport, ServiceWire, StateWatcher, TsnetBootstrap
- **依赖边**：dev.agentmirror.app, dev.agentmirror.app.conn, dev.agentmirror.app.tsnet
- **doc 全文**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。 分层（fg-service 知识基底 §1）： - [StateWatcher]：纯 JVM 核心逻辑（验收单测全打这里），消费 conn 层 listing/list_delta 流，检测会话状态沿变化（→blocked/→done）→ 通知；同状态重复抑制；unknown 不通知。 - [NotificationHelper]：通知渠道（常驻/状态两条）+ 常驻通知与状态通知 + 会话页深链 PendingIntent（action/extra 由 [MainActivity] 的 handleDeepLink 消费，非本包）。 - [MirrorForegroundService]：薄 Android 层，startForeground（dataSync）+ 生命周期绑定 [ConnectionManager]（经 [ServiceWire]）；断连静默重连归 conn 层，本服务只反映状态。 已在 manifest 声明，但**当前无任何生产代码启动它**（死件家族第六例，接线留待后案， 见 fix-reconnect-stale-config 收口提交）——常驻连接实际由 [ServiceWire] 直接持有， 配对/冷启动入口（`startPersistentConnection`）经 [ServiceWire.manager] 启动。 - [ServiceWire]：接线点——传输工厂（默认 [OkHttpTransportFactory]）、UI 监听桥、 连接配置注入；进程级持有唯一 [ConnectionManager]，服务与 UI 都经它访问。 电量策略（004 裁定）：仅在有活跃订阅或用户开启后台守望时运行前台服务；服务被系统杀 → 冷启动重连即恢复（客户端无状态，没有丢失可言）。当前前台服务**未接线**：连接由 [ServiceWire.manager] 在配对成功/冷启动时直接启动（不经本服务），退避泵由在屏组合 的 LaunchedEffect 驱动（[SessionScreen]/[PairingScreen]），服务启动留待后案。 @consumes dev.agentmirror.app @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.tsnet

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
- **doc 全文**：会话页：单个 tmux 会话的交互界面。 组合终端渲染（termview）与输入下发（conn），承载缩放、手势与快捷输入条； 图片附件走 multipart HTTP 上传（上传基地址由 service 装配的 ServiceWire 统一注入）， 跨层共享连接经 service 的 ServiceWire.uiConnector 扇出订阅。会话页已完整落位： 镜像流（snapshot/delta/scrollback 本地滚动补页）、发送必达回执、附件路径注入光标处。 @consumes dev.agentmirror.app.conn @consumes dev.agentmirror.app.service @consumes dev.agentmirror.app.termview @consumes dev.agentmirror.app.tsnet @consumes dev.agentmirror.app.ui.theme

## 3. 需求基
- goal 引用条目：（goal 无编号引用）
- librarian 撞库：无回执文件（leader 未查或无命中）
- 修订记录 requirement-base/REVISIONS.md 必读（被推翻结论不回改条目）

## 4. 经验基（通用纪律+先例）
- 红测先行；每次落盘保持整模块可编译（共享编译单元互阻三次实案）；编译被他人半成品阻断→直接 send 文件主人（附文件+行号+错误原文），主人最高优先修复回执，不经 leader
- 测试净化前缀 env -u TEAM_AGENT_*；tmux 只用自建隔离 socket；杀进程只 scoped kill 自己命名空间（w-fix-statewire 险案）
- 派单通道净化：所有 Team Agent CLI 调用统一走仓库包装器 .team/ta，尤其 add-agent/start-agent/reset-agent；禁止手写 env -u 前缀或直接调用 team-agent，否则 Codex 托管代理会被快照进新席启动串，形成零 token 假 BUSY
- A-31 开工核真：Codex 新席必须在对应 ~/.codex/sessions 当日 JSONL 出现 reasoning 或 custom_tool_call；Working/BUSY、pane 存在、命令 exit 0 均不算真活性
- 代码必须带注释（设计决策写为什么）；禁止 git push；本地不 commit；report_result 恰好一次带 tests

## 5. 现场基（leader 手填取证素材——唯一手填合法区）
- 无现场素材文件
