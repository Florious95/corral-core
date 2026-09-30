# 知识基底 · feat-daemon-scoped-discovery（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: feat-daemon-scoped-discovery
    goal: >
      给 agentmirrord 增加「只扫指定 tmux socket 目录」的配置出口，使测试可以起一个
      真正隔离的 daemon。现状：discovery.DefaultSocketDirs() 扫「$TMUX_TMPDIR 覆盖树
      **加上**平台默认目录」，故设 TMUX_TMPDIR 也无法排除宿主真实 socket；
      api.Discoverer 内部已有 socketDirs 字段（注释明写 set-but-empty means scan none），
      但 cmd/agentmirrord 未将其暴露为 flag/env。
      后果（2026-08-12 实证）：w-nav-recover 自建隔离 daemon 后，其 discovery 仍扫到宿主
      真实 socket，UI dump 出现用户真实工作区与会话名，触碰「绝不触碰用户真实 tmux」红线并 halt。
      这卡死整条模拟器测试流水线：要么碰真实 tmux，要么 blocked。
      本任务只做配置出口接线，不改扫描算法、不改默认行为（不传即维持现状全扫）。
    acceptance:
      - "bash -lc 'cd server && env -u TEAM_AGENT_* go test ./...'"
      - "红测：给定隔离 socket 目录时，Discover 结果不含该目录之外的任何 socket"
      - "默认行为不变：未配置时仍等价于 DefaultSocketDirs()"
    deps: []
    write_scope: ["server/cmd/agentmirrord/", "server/internal/api/"]
    evidence: ".team/evidence/feat-daemon-scoped-discovery.json"
    contention: none
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：cmd/agentmirrord, internal/api
- 正向依赖（你消费的契约，只读）：go_internal_agentstate, go_internal_bridge, go_internal_config, go_internal_discovery, go_internal_pairing, go_internal_protocol, go_internal_tsnetd
- **反向依赖（波及面=回归自查范围）**：无

### 闭包架构卡内联（职责/导出面/依赖边）

### Go · cmd/agentmirrord

- **职责**：Command agentmirrord is the service-side daemon of AgentMirror (product github.com/agentmirror/agentmirror): a sidecar that mirrors the user's existing tmux sessions to the Android app over WebSocket.
- **导出面**：main
- **依赖边**：internal/api, internal/config, internal/pairing, internal/tsnetd

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
- .team/nodes/feat-daemon-scoped-discovery/FIELD.md（先完整读；含真机实证/失败现场/裁定）
