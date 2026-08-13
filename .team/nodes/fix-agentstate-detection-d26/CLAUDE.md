# 知识基底 · fix-agentstate-detection-d26（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: fix-agentstate-detection-d26
    goal: >
      修复 D-26「Agent 工作状态检测准确率低」（用户报过三次失败）。
      直接原因（用户 2026-08-12 截图实证）：Claude Code 新版把「工作中」指示符改成了 `◐` 这类
      半填充圆动画，而本工程的 agentstate 检测仍在匹配旧的转圈 / 左右移动点的字形，**指示符换了检测没跟上**。
      但只补新字形是治标：**按具体字形匹配，上游每改一次就失灵一次，且上游不会通知我们。**
      因此本任务要两层一起做：
      ① 立即修：补齐当前 Claude Code / Codex 等 CLI 的工作指示字形，让检测立刻恢复准确；
      ② 治本：改成不依赖具体字形的判据 —— 例如「同一区域在短窗口内高频变化」这类
      与字形无关的活性信号，使上游换图标不再导致误判。两层都要有测试。
    acceptance:
      - "bash -lc 'cd server && go test ./internal/agentstate/...'"
      - "红测：给定含新版 ◐ 类指示符的 pane 采样，判定为 working；给定静止 pane 判定为 idle"
      - "红测：字形无关判据 —— 用一组从未见过的指示字形，仍能判出 working"
    deps: []
    write_scope: ["server/internal/agentstate/"]
    evidence: ".team/evidence/fix-agentstate-detection-d26.json"
    contention: none
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：internal/agentstate
- 正向依赖（你消费的契约，只读）：go_internal_protocol
- **反向依赖（波及面=回归自查范围）**：go_internal_api

### 闭包架构卡内联（职责/导出面/依赖边）

### Go · internal/agentstate

- **职责**：Package agentstate maps per-agent CLI output and process trees to a normalized state (working/idle/blocked/done), degrading to unknown when undecidable.
- **导出面**：Adapter, AgentKind, ClaudeCodeAdapter, CodexAdapter, Confidence, DefaultRegistry, Identify, IdentifyInput, Proc, Registry, Sample, State, Track
- **依赖边**：internal/protocol

### Go · internal/api

- **职责**：Package api implements the service-side WebSocket API and the image upload endpoint, wiring together discovery and bridge (task ws-api).
- **导出面**：Discoverer, NewServer, NewStateProvider, Options, Server, StateProvider, TokenValidator
- **依赖边**：internal/agentstate, internal/bridge, internal/discovery, internal/protocol

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
- .team/nodes/fix-agentstate-detection-d26/FIELD.md（先完整读；含真机实证/失败现场/裁定）
