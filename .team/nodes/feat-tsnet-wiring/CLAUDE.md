# 知识基底 · feat-tsnet-wiring（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: feat-tsnet-wiring
    goal: >
      P0（用户验收标准原文即含 TS——单一 App 内嵌 tsnet 是三痛点之一，007/011 裁定；死件家族
      第七例：tsnetd 服务端在、app-tsnet gomobile 绑定在，App 配对页 authkey 输入框是死占位
      「即将推出」，连接层从未走 tsnet 拨号）：全链接线——①App：authkey 填入/QR 携带（011 预留
      字段）→ 起内嵌 tsnet 节点 → ConnectionManager 拨号经 tsnet SOCKS5（有 tsnet 走隧道，无则
      LAN 直连，运行时可判定）；连接状态区分展示 LAN/tailnet；authkey 安全存储不进日志。
      ②服务端：daemon 起 tsnetd 双栈的操作路径文档化（authkey 环境变量/flag，README 补节）。
      ③QR：candidates 与 tailnet 地址共存语义（tailnet 地址入候选）。红测先行（假 tsnet 接缝：
      拨号路由选择/authkey 存取/QR 解析）；真实 tailnet 端到端留用户真机验收（需用户 authkey，
      模拟器自验到 SOCKS5 拨号路由正确为止，未达部分显式列未验证清单）。
    acceptance: ["bash -lc 'cd app && ./gradlew -q :app:testDebugUnitTest'", "bash -lc 'cd server && go test ./internal/tsnetd/... ./internal/pairing/...'"]
    deps: ["app-tsnet", "tsnet-embed", "fix-pairing-candidates"]
    write_scope: ["app/app/src/main/java/dev/agentmirror/app/", "app/app/src/test/", "server/internal/pairing/", "server/internal/tsnetd/", "server/cmd/", "docs/protocol.md", "server/README.md"]  # 扩权留痕：internal/config 纯加法 TSAuthKey（flag+env+单测，leader 裁定 2026-08-09）
    evidence: ".team/evidence/feat-tsnet-wiring.json"
    contention: contract
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：dev.agentmirror.app, internal/pairing, internal/tsnetd, cmd/agentmirrord
- 正向依赖（你消费的契约，只读）：go_internal_api, go_internal_config, kt_dev_agentmirror_app_pairing, kt_dev_agentmirror_app_service, kt_dev_agentmirror_app_session, kt_dev_agentmirror_app_ui_theme, kt_dev_agentmirror_app_workspace
- **反向依赖（波及面=回归自查范围）**：kt_dev_agentmirror_app_service

### 闭包架构卡内联（职责/导出面/依赖边）

### Kotlin · dev.agentmirror.app

- **职责**：Compose 应用根组合。
- **导出面**：AgentMirrorApp, MainActivity, MainNavState
- **依赖边**：dev.agentmirror.app.pairing, dev.agentmirror.app.service, dev.agentmirror.app.session, dev.agentmirror.app.ui.theme, dev.agentmirror.app.workspace
- **doc 全文**：Compose 应用根组合。 依需求 004「客户端无状态」，本组件只做路由，不持有任何业务状态。 首启路由（pairing-ui 知识基底 §1）： - 无配对配置 → 配对页（扫码/手填，可跳过进空工作区）； - 有配对配置 → 直进工作区列表； - 配对页可从设置/重配入口重进（重新配对）。 会话页跳转沿用 session-ui 挂载的 [SessionRoute]。 导航态（activeSession/showPairing）由 [navState]（D-3 修复）注入：MainActivity 在 onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。 工作区 VM（[workspaceViewModel]）由 MainActivity 持有（fix-workspace-wiring 修复， navState 同模式提升），本组件只负责在工作区分支用 [DisposableEffect] 把它接入 [ServiceWire.uiConnector]——配对成功后列表能渲染（此前 VM 裸建从未接线，uiConnector 全仓无调用点，见 fix-workspace-wiring 知识基底）。

### Go · internal/pairing

- **职责**：Package pairing implements token-based device pairing and QR-code onboarding for the Android app.
- **导出面**：Address, DetectAddresses, EnsureToken, GenerateToken, KindLAN, KindLoopback, KindTailnet, LoadToken, NewPayload, Onboarding, Payload, PayloadVersion, PrimaryHost, PrintOnboarding, PrintOnboardingAll, PrintOnboardingWith, RenderQR, SaveToken, TokenDir, WSURL
- **依赖边**：（无）

### Go · internal/tsnetd

- **职责**：Package tsnetd embeds Tailscale networking (tsnet) so the daemon's WebSocket service is reachable over the tailnet as well as the LAN.
- **导出面**：DefaultDir, ErrTailnetDisabled, Group, New, Options
- **依赖边**：（无）

### Go · cmd/agentmirrord

- **职责**：Command agentmirrord is the service-side daemon of AgentMirror (product github.com/agentmirror/agentmirror): a sidecar that mirrors the user's existing tmux sessions to the Android app over WebSocket.
- **导出面**：main
- **依赖边**：internal/api, internal/config, internal/pairing, internal/tsnetd

### Kotlin · dev.agentmirror.app.service

- **职责**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。
- **导出面**：MirrorForegroundService, NoopTransportFactory, NotificationHelper, OkHttpTransportFactory, OkHttpWebSocketTransport, ServiceWire, StateWatcher
- **依赖边**：dev.agentmirror.app, dev.agentmirror.app.conn
- **doc 全文**：前台服务：常驻连接 + 通知栏（需求 004 Android 前台服务路线）。 分层（fg-service 知识基底 §1）： - [StateWatcher]：纯 JVM 核心逻辑（验收单测全打这里），消费 conn 层 listing/list_delta 流，检测会话状态沿变化（→blocked/→done）→ 通知；同状态重复抑制；unknown 不通知。 - [NotificationHelper]：通知渠道（常驻/状态两条）+ 常驻通知与状态通知 + 会话页深链 PendingIntent。 - [MirrorForegroundService]：薄 Android 层，startForeground（dataSync）+ 生命周期绑定 [ConnectionManager]（经 [ServiceWire]）；断连静默重连归 conn 层，本服务只反映状态。 - [ServiceWire]：接线点——传输工厂（默认 [NoopTransportFactory]）、UI 监听桥、连接配置注入。 电量策略（004 裁定）：仅在有活跃订阅或用户开启后台守望时运行前台服务；服务被系统杀 → 冷启动重连即恢复（客户端无状态，没有丢失可言）。UI/配对层经 [ServiceWire] 控制启动/停止。

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
- .team/nodes/feat-tsnet-wiring/FIELD.md（先完整读；含真机实证/失败现场/裁定）
