# 知识基底 · study-herdr-agent-state（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: study-herdr-agent-state
    goal: >
      调研 herdr 如何正确检测 Agent CLI 的运行状态，产出可落地的替代方案，
      终结本工程「靠刮屏幕字符判状态」的路线。
      **用户 2026-08-12 直接裁定**：「你假如说认为这两种，一个是完成态，一个是工作中态，
      还在通过这样的字符串形式去确定它的工作状态，那就**完全走偏了**。
      并且**这两个实际上都是完成的状态**。你可以通过 **herdr** 这个仓库去确定
      如何正确的检测 Agent CLI 的状态。」
      背景实证：Claude Code 完成态输出 `Brewed for 42m 3s`、也输出 `Churned for 3m 37s`，
      两者**都是完成态**，且前导为同类星号字形 —— 字形与字符串层面根本无法分辨状态。
      leader 此前让 D-26 往 spinnerFrames 白名单补 `✳` 等字形，方向错误，
      上线后只会让「已停止工作却显示工作中」的误检更严重（用户已实测报告该误检）。
      herdr 自述是 "the runtime your coding agents live on" —— 它托管 agent 进程，
      因此可能以进程/PTY/运行时协议等结构性信号判定状态，而非刮屏。本任务查清其机制。
    acceptance:
      - "产出 docs/herdr-agent-state-detection.md：herdr 判定 agent 状态的实际机制（数据来源/信号/判据）+ 出处（仓库/文件/函数）+ 许可证 + 我方能否复现"
      - "明确回答：不刮屏的前提下，我方 daemon 能获得哪些结构性信号（进程状态/PTY 活性/子进程/CLI 自身协议或钩子）"
      - "给出替代方案与代价，并说明 D-26 现有 rules.go 字形白名单应如何退场"
    deps: []
    write_scope: ["docs/"]
    evidence: ".team/evidence/study-herdr-agent-state.json"
    contention: contract
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
- .team/nodes/study-herdr-agent-state/FIELD.md（先完整读；含真机实证/失败现场/裁定）
