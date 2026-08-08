# 知识基底 · state-ident-wrapper（系统编译产物）

## 0. 任务（taskbook.yaml#state-ident-wrapper）
- 目标：wrapper 场景 agent 识别：进程树下钻（pane_pid → 后代 argv 匹配 claude/codex）与/或 pane 标题启发式，产出 AgentKind 提示供注册表分派，让舰队 pane（pane_current_command=bash）不再全降 unknown。
- 验收（exit 0 = 过）：`bash -lc 'cd /Volumes/nvme/Projects/远程Agent安卓/server && go test ./internal/agentstate/...'`
- 写范围：`server/internal/agentstate/`。红线：**只做加法性 API 变更**（ws-api 席位在途消费现有 API，破坏性改名=阻塞他人）；每次落盘 `go build ./...` 必须绿；识别器允许有界 IO（总超时 ≤500ms，超时/失败必回 unknown），**Decide 判定路径保持纯函数零 IO**（008 隔离铁律）。

## 1. 架构基
- 现有结构（先读代码）：`sample.go`（Sample/Adapter/DefaultRegistry 按 PaneCommand 分派）、`adapters.go`（Claude/Codex 规则表）、`track.go`、`ansi.go`。
- 设计方向（细节你定）：新增 `Identify(ctx, IdentifyInput{PanePID, PaneTitle, PaneCommand}) AgentKind`——进程树：macOS `ps -axo pid=,ppid=,command=` 一次采样内存建树，pane_pid 后代 argv 含 claude/codex 判 kind（注意 claude 实为 node 跑的脚本，匹配 argv 全串不是 comm）；标题启发式作旁证。Registry 增加按 AgentKind 分派入口（保留按 command 的旧入口=加法）。
- 消费方接线：ws-api 在途，其 StateProvider 目前恒 unknown；你交件后接线归 ws-api/后续任务——**本任务不改 api 包**。

## 2. 现场基（state-parser 席位实测沉淀，权威参考 .team/nodes/state-parser/CLAUDE.md §5）
- 舰队 pane `pane_current_command`=bash（wrapper）；直接 pane 才是 claude/codex。
- pane 标题可见 braille spinner（`✳`=idle/`⠐`=working）但 capture-pane 拿不到标题——标题要 `list-panes -F '#{pane_title}'` 单独取（该 IO 属识别器输入装配，同样有界）。
- 本机可只读取样真实舰队 pane 验证进程树（ps 即可，无需碰 tmux socket）；测试用 fake 进程表驱动（表→树→匹配纯逻辑可测），真实 ps 路径一条冒烟即可。

## 3. 需求基（指针）
1. requirement-base/entries/008-生产级定位与开源许可.md（隔离铁律：识别失败=unknown，永不影响镜像）
2. requirement-base/entries/001-产品命题-tmux镜像范式.md（舰队是核心场景——本任务存在的理由）
3. requirement-base/entries/003-对话体验四标准.md（blocked 推送=第四标准，识别是它的数据源）

## 4. 经验基
- 红测先行：fake 进程树"bash→node(claude argv)"必须判 claude、无匹配后代必须 unknown、超时必须 unknown（阳性对照防静默）。
- 注释红线、净化前缀照旧。

## 5. 沉淀区（唯一允许你追加写入的区域）

### 实测纪要（2026-08-09，真实进程表 + 真实 wrapper 链取样）

- **真实 argv 形状**：claude 是原生二进制，argv[0]=`claude`（`claude --dangerously-skip-permissions`）；
  codex 是链 `sh → node /opt/homebrew/bin/codex … → …/vendor/aarch64-apple-darwin/bin/codex`。
  匹配必须按"可执行 + node 包装器的直接启动目标"（argv[0:2] 路径基名）判定，**不能扫全串**：
  ps 把 argv 重连成无引号空格串，`-c developer_instructions="… codex …"` 里的裸词会误报 codex（红测抓到，已修）。
- **真实 wrapper 端到端命中**：临时测试在真实 ps 表上找到 wrapper 祖先（argv=`team-agent claude …`，
  fields[1]=`claude`）并判出 AgentKindClaude——wrapper 场景真实闭环。
- **快速路径**：直接 pane 的 `pane_current_command` 已命名 CLI 时 Identify 零 IO 直接判（PanePID=0 也安全）；
  进程树路径单次 `ps -axo pid=,ppid=,command=`，500ms 预算，超时/解析失败/无 PID 一律 unknown。
- **标题启发式只作旁证**：`pane_title` 基名匹配 claude/codex，仅在进程树判不出时兜底；
  树匹配权威，标题冲突不覆盖（`TestIdentifyTreeBeatsTitle`）。
- **加法契约已验**：`DetectForKind` 经 `AgentKind.Command()` 复用同一规则表，`DetectForKind==Detect(command)` 
  有对等测试；旧 API（`Detect`/`DefaultRegistry`/`Track`）未动，ws-api 在途消费无破坏。
- **后代遍历 BFS + 部分表容错**：ps 快照与 pane 可能竞态（进程中途退出→PPID 缺失），缺失父节点即止该支，
  不清表；根 argv 不匹配（wrapper 启动文本可含 agent 路径，后代才决定）——`TestIdentifyFromTableIgnoresRootArgv`。
