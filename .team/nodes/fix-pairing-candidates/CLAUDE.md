# 知识基底 · fix-pairing-candidates（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: fix-pairing-candidates
    goal: >
      P0（2026-08-09 二次真机实证：QR 主地址赌错网卡，用户撞「地址不可达」后只能靠人肉猜正确地址）：
      多网卡下正确地址机器不可判定，产品必须把选择权给用户而不是赌。方案：①QR payload 增可选
      candidates 字段（全部候选 ws URL，服务端已会算，qr-host-detect 交付的终端清单同源）；
      ②App 配对失败（不可达/超时）时若有 candidates，展示候选列表逐项一键重试（自动逐个试探
      更佳——3s 超时/个）；③手填表单地址下拉可选候选。协议前向兼容（无 candidates 行为不变）。
      红测先行：解析含 candidates 的 QR、失败后候选可见可点、逐试成功即连。
    acceptance: ["bash -lc 'cd server && go test ./internal/pairing/... ./internal/protocol/...'", "bash -lc 'cd app && ./gradlew -q :app:testDebugUnitTest'"]
    deps: ["fix-qr-host-detect", "fix-pairing-scan-flow"]
    write_scope: ["server/internal/pairing/", "docs/protocol.md", "app/app/src/main/java/dev/agentmirror/app/pairing/", "app/app/src/test/"]
    evidence: ".team/evidence/fix-pairing-candidates.json"
    contention: impl
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：internal/pairing, dev.agentmirror.app.pairing
- 正向依赖（你消费的契约，只读）：kt_dev_agentmirror_app_conn, kt_dev_agentmirror_app_service
- **反向依赖（波及面=回归自查范围）**：go_cmd_agentmirrord, kt_dev_agentmirror_app

### 闭包架构卡内联（职责/导出面/依赖边）

### Go · internal/pairing

- **职责**：Package pairing implements token-based device pairing and QR-code onboarding for the Android app.
- **导出面**：Address, DetectAddresses, EnsureToken, GenerateToken, KindLAN, KindLoopback, KindTailnet, LoadToken, NewPayload, Onboarding, Payload, PayloadVersion, PrimaryHost, PrintOnboarding, PrintOnboardingAll, PrintOnboardingWith, RenderQR, SaveToken, TokenDir, WSURL
- **依赖边**：（无）

### Kotlin · dev.agentmirror.app.pairing

- **职责**：配对：扫码连接（路线 a：QR 载服务端地址 + 配对 token，可选 TS authkey，需求 011）。
- **导出面**：Failed, Pairing, PairingConfig, PairingConfigStore, PairingFailCause, PairingRoute, PairingScreen, PairingViewModel, QrParseException, QrPayload, QrPayloadParser, SharedPreferencesPairingConfigStore
- **依赖边**：dev.agentmirror.app.conn, dev.agentmirror.app.service
- **doc 全文**：配对：扫码连接（路线 a：QR 载服务端地址 + 配对 token，可选 TS authkey，需求 011）。 负责相机扫码、地址解析与配对握手；替代"终端 App + Tailscale App + SSH 配置"三件套 （需求 001 单一 App 原则）。本包为占位骨架，由 pairing 任务落位实现。

### Go · cmd/agentmirrord

- **职责**：Command agentmirrord is the service-side daemon of AgentMirror (product github.com/agentmirror/agentmirror): a sidecar that mirrors the user's existing tmux sessions to the Android app over WebSocket.
- **导出面**：main
- **依赖边**：internal/api, internal/config, internal/pairing, internal/tsnetd

### Kotlin · dev.agentmirror.app

- **职责**：Compose 应用根组合。
- **导出面**：AgentMirrorApp, MainActivity, MainNavState
- **依赖边**：dev.agentmirror.app.pairing, dev.agentmirror.app.service, dev.agentmirror.app.session, dev.agentmirror.app.ui.theme, dev.agentmirror.app.workspace
- **doc 全文**：Compose 应用根组合。 依需求 004「客户端无状态」，本组件只做路由，不持有任何业务状态。 首启路由（pairing-ui 知识基底 §1）： - 无配对配置 → 配对页（扫码/手填，可跳过进空工作区）； - 有配对配置 → 直进工作区列表； - 配对页可从设置/重配入口重进（重新配对）。 会话页跳转沿用 session-ui 挂载的 [SessionRoute]。 导航态（activeSession/showPairing）由 [navState]（D-3 修复）注入：MainActivity 在 onSaveInstanceState 持久化、重建恢复，深链/旋转都不丢导航位置（审计 D-2/D-3）。 工作区 VM（[workspaceViewModel]）由 MainActivity 持有（fix-workspace-wiring 修复， navState 同模式提升），本组件只负责在工作区分支用 [DisposableEffect] 把它接入 [ServiceWire.uiConnector]——配对成功后列表能渲染（此前 VM 裸建从未接线，uiConnector 全仓无调用点，见 fix-workspace-wiring 知识基底）。

## 3. 需求基
- goal 引用条目：（goal 无编号引用）
- librarian 撞库：无回执文件（leader 未查或无命中）
- 修订记录 requirement-base/REVISIONS.md 必读（被推翻结论不回改条目）

## 4. 经验基（通用纪律+先例）
- 红测先行；每次落盘保持整模块可编译（共享编译单元互阻三次实案）；编译被他人半成品阻断→直接 send 文件主人（附文件+行号+错误原文），主人最高优先修复回执，不经 leader
- 测试净化前缀 env -u TEAM_AGENT_*；tmux 只用自建隔离 socket；杀进程只 scoped kill 自己命名空间（w-fix-statewire 险案）
- 代码必须带注释（设计决策写为什么）；禁止 git push；本地不 commit；report_result 恰好一次带 tests

## 5. 现场基（leader 手填取证素材——唯一手填合法区）
- .team/nodes/fix-pairing-candidates/FIELD.md（先完整读；含真机实证/失败现场/裁定）
