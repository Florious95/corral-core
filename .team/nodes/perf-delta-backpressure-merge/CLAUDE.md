# 知识基底 · perf-delta-backpressure-merge（tools/basegen.py 编译产物——手工编辑无效，重编请改素材源后重跑）

## 1. 任务信封（taskbook.yaml 原文，机械抽取）
```yaml
  - id: perf-delta-backpressure-merge
    goal: >
      C1：delta 背压合并。服务端 ws_conn.go sendMirror 在 sendCh(cap 256) 满时丢弃 delta
      （契约 004「下一个 snapshot 会对账」）；改为并入待发缓冲，队列排空后 flush 成一个
      大 delta 帧（≤1MiB）。目标是用户主诉「慢链上看得见每个中间状态」——
      合并的是本就在排队的东西，不引入定时器、零延迟代价、零协议改动、纯服务端、
      不碰任何被回退过的 UI 路径。
      **第一关是证伪自己**：从未证实过该队列在真实链路上会满（20KB/s 限速下
      deltas_dropped 实测为 0）。队列不满则合并永不触发，C1 即为修一个不存在的问题。
      故第一步是部署 sendq_metrics.go（已写好未部署）在真实 tailnet 链路上取
      conn.queue_peak / conn.deltas_dropped，**拿到「队列会满」的实证再动代码**。
      真实链路实测基线：应用层 srtt 1762ms、重传 10.4%、DERP 中继 HKG、
      ICMP avg 1221ms（见 docs/cellular-ts-optimization.md）。
    acceptance:
      - "关卡1（前提）：真实 tailnet 链路上取到 conn.queue_peak 与 conn.deltas_dropped；队列峰值接近 256 或 dropped>0 才继续，否则 halt 报 leader 改方向"
      - "关卡2（红测）：合并前后客户端收到的字节流逐字节相同（不依赖网络，纯单测）"
      - "关卡3：cd server && env -u TEAM_AGENT_* go test ./... 全绿"
      - "关卡4：python3 tools/archwiki/build_wiki.py --check --strict-t3 exit 0"
      - "关卡5：deltas_dropped 计数器语义随丢弃路径消失而同步订正（纪律⑨：仪表要说清作用域）"
      - "关卡6（眼见为实）：真机/模拟器上走复现步骤，慢链下中间状态个数下降且不倒退"
    deps: []
    write_scope: ["server/internal/api/", "test/cases/"]
    evidence: ".team/evidence/perf-delta-backpressure-merge.json"
    contention: none
```

## 2. 架构基（build_wiki.py 现算影响闭包）
- write_scope 包：internal/api
- 正向依赖（你消费的契约，只读）：go_internal_agentstate, go_internal_bridge, go_internal_discovery, go_internal_protocol
- **反向依赖（波及面=回归自查范围）**：go_cmd_agentmirrord

### 闭包架构卡内联（职责/导出面/依赖边）

### Go · internal/api

- **职责**：Package api implements the service-side WebSocket API and the image upload endpoint, wiring together discovery and bridge (task ws-api).
- **导出面**：Discoverer, NewServer, NewStateProvider, Options, Server, StateProvider, TokenValidator
- **依赖边**：internal/agentstate, internal/bridge, internal/discovery, internal/protocol

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
