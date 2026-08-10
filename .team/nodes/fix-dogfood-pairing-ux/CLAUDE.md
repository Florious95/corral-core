# 知识基底 · fix-dogfood-pairing-ux（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: fix-dogfood-pairing-ux
    goal: >
      dogfood 缺陷波次一（P1+P2，来源 e2e/artifacts/dogfood/REPORT.md）：
      D-07 配对 token 明文上屏（协议 §9 铁律：QR 是唯一合法出口，token 不上屏不落日志）；
      D-14 配对完成后再无重配入口，且代码注释谎称"设置里有重配按钮"（注释与实现不符，红线：
      注释即契约）——补设置页单档重配流程并修正注释；D-11 017 R-4 token 吊销与 `-token` flag
      的文档欠账（README/protocol 补齐吊销与轮换说明）。红测先行：token 不出现在任何可见文本节点
      的断言、重配入口可达性断言。
    acceptance:
      - "bash -lc 'env -u TEAM_AGENT_* bash -lc \"cd app && ./gradlew -q :app:testDebugUnitTest\"'"
      - "bash -lc 'env -u TEAM_AGENT_* bash -lc \"cd server && go test -count=1 ./internal/pairing/...\"'"
    deps: ["test-app-dogfood"]
    write_scope: ["app/app/src/main/java/", "app/app/src/test/", "server/internal/pairing/", "server/README.md", "docs/protocol.md", ".team/evidence/fix-dogfood-pairing-ux.json"]
    evidence: ".team/evidence/fix-dogfood-pairing-ux.json"
    contention: impl
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：internal/pairing
- 正向依赖（你消费的契约，只读）：无
- **反向依赖（波及面=回归自查范围）**：go_cmd_agentmirrord

### 闭包架构卡内联（职责/导出面/依赖边）

### Go · internal/pairing

- **职责**：Package pairing implements token-based device pairing and QR-code onboarding for the Android app.
- **导出面**：Address, DetectAddresses, EnsureToken, GenerateToken, KindLAN, KindLoopback, KindTailnet, LoadToken, NewPayload, Onboarding, Payload, PayloadVersion, PrimaryHost, PrintOnboarding, PrintOnboardingAll, PrintOnboardingWith, RenderQR, SaveToken, TokenDir, WSURL
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
