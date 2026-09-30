# 知识基底 · fix-upload-auth（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: fix-upload-auth
    goal: >
      P0 安全缺口（test-api-user-scenarios-perf 套件 2026-08-10 实测发现，证据见
      e2e/artifacts/test-api-user-scenarios-perf/baseline.json 的 deviations）：`POST /upload`
      端点当前**没有任何鉴权检查**——任何能连到 :9900 的人都能往用户主机的上传目录写文件，
      而 WS 侧是有配对 token 校验的，等于绕过配对直写磁盘。修复：上传端点复用现有配对 token 校验
      （与 WS 握手同一套凭据来源，不新发明 header 契约前先在 docs/protocol.md 补该端点的鉴权契约
      再实现）；未授权请求返回明确错误码与原因（红线5 失败可见），token 不落日志。
      红测先行：未带凭据/带错凭据/带对凭据三分支断言，并在 API 场景套件里加一条对应场景。
      同时处置 D-13：上传目录大小上限、超限拒绝或轮转、README 说明（红线3 资源有界）。
    acceptance:
      - "bash -lc 'env -u TEAM_AGENT_* bash -lc \"cd server && go test -count=1 ./internal/api/... ./internal/pairing/...\"'"
      - "bash -lc 'env -u TEAM_AGENT_* bash e2e/api-user-scenarios.sh'"
    deps: ["test-api-user-scenarios-perf"]
    write_scope: ["server/internal/api/", "server/internal/pairing/", "server/internal/config/", "docs/protocol.md", "server/README.md", "e2e/api-user-scenarios.sh", "e2e/artifacts/fix-upload-auth/"]
    evidence: ".team/evidence/fix-upload-auth.json"
    contention: impl
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：internal/api, internal/pairing, internal/config
- 正向依赖（你消费的契约，只读）：go_internal_agentstate, go_internal_bridge, go_internal_discovery, go_internal_protocol
- **反向依赖（波及面=回归自查范围）**：go_cmd_agentmirrord

### 闭包架构卡内联（职责/导出面/依赖边）

### Go · internal/api

- **职责**：Package api implements the service-side WebSocket API and the image upload endpoint, wiring together discovery and bridge (task ws-api).
- **导出面**：Discoverer, NewServer, NewStateProvider, Options, Server, StateProvider, TokenValidator
- **依赖边**：internal/agentstate, internal/bridge, internal/discovery, internal/protocol

### Go · internal/pairing

- **职责**：Package pairing implements token-based device pairing and QR-code onboarding for the Android app.
- **导出面**：Address, DetectAddresses, EnsureToken, GenerateToken, KindLAN, KindLoopback, KindTailnet, LoadToken, NewPayload, Onboarding, Payload, PayloadVersion, PrimaryHost, PrintOnboarding, PrintOnboardingAll, PrintOnboardingWith, RenderQR, SaveToken, TokenDir, WSURL
- **依赖边**：（无）

### Go · internal/config

- **职责**：Package config loads the daemon configuration from command-line flags and environment variables.
- **导出面**：Config, Load
- **依赖边**：（无）

### Go · cmd/agentmirrord

- **职责**：Command agentmirrord is the service-side daemon of AgentMirror (product github.com/agentmirror/agentmirror): a sidecar that mirrors the user's existing tmux sessions to the Android app over WebSocket.
- **导出面**：main
- **依赖边**：internal/api, internal/config, internal/pairing, internal/tsnetd

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
