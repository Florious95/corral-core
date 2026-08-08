# 知识基底 · state-parser（系统编译产物）

## 0. 任务（taskbook.yaml#state-parser）
- 目标：per-agent 状态适配器（首批 Claude Code、Codex，架构预留扩展）：从 pane 输出与进程信息判定 working/idle/blocked/done，判不出降级 unknown。参考 herdr（Apache-2.0）状态检测思路并合规借鉴（保留声明、标注出处）。
- 验收（exit 0 = 过）：`bash -lc 'cd /Volumes/nvme/Projects/远程Agent安卓/server && go test ./internal/agentstate/...'`
- 写范围：`server/internal/agentstate/`。红线（008 隔离铁律）：本包任何失败/超时/panic 不得影响镜像与输入——纯函数式判定，不做 IO 阻塞调用；每个判定必须能在无数据时返回 unknown。

## 1. 架构基
- 输入契约（由 ws-api 侧喂给你，你只定义接口）：`Sample{ PaneCommand string; RecentOutput []byte; LastOutputAge time.Duration }`——pane_current_command 来自 discovery，RecentOutput 是 pane 近期输出的尾部窗口（如 4KiB），LastOutputAge 是距最后输出的时长。
- 输出：`State`（five 值，对齐 protocol.AgentState）+ `Confidence`；识别不了 agent 类型或规则不命中 → unknown（一等公民）。
- 结构：`Adapter` 接口 + 注册表（按 PaneCommand 匹配：claude→ClaudeCodeAdapter、codex→CodexAdapter）；规则=对 RecentOutput 去 ANSI 后的模式匹配 + 时序辅助：
  - Claude Code 经验规则（写成表驱动，便于随 CLI 改版更新）：working=存在 spinner/"esc to interrupt"/"Thinking"；blocked=权限确认框（"Do you want"、"y/n"选择器）、输入提示符空闲但上一轮是问句；idle=提示符 "❯ " 且无 spinner；done 的近似=从 working 转入 idle 的沿触发（由上层状态机记忆前值，你提供 `Track(prev State, sample) State`）。
  - Codex 同理（提示符/审批框模式不同）。
  - 规则来源可研读 herdr 源码（github.com/herdrdev/herdr，Apache-2.0）的 agent 状态检测部分；借鉴写明出处于文件头注释（008 合规）。
- ANSI 剥离：自写小函数（几十行状态机），不引依赖。

## 2. 现场基
- 测试fixture：自造真实形状的输出样本（含 ANSI 转义的 Claude Code spinner 帧、权限框、空闲提示符；Codex 相应样本）。**阳性对照必配**：每个 adapter 至少一条"该判出 X 却判成 unknown"的红测防规则失效静默。
- 本机就有真实 Claude Code pane 可 capture 参考样本（`tmux -S /private/tmp/tmux-501/ta-* capture-pane -e -p -t %N`）——**只读 capture 取样可以**，严禁 send-keys/写操作。

## 3. 需求基（指针）
1. requirement-base/entries/008-生产级定位与开源许可.md（五值+隔离铁律+herdr 合规）
2. requirement-base/entries/012-工作区聚合状态规则.md（你产出的状态如何被聚合消费）
3. requirement-base/entries/003-对话体验四标准.md（blocked/done 驱动推送——第四标准的数据源）

## 4. 经验基
- 表驱动规则 + 每条规则带注释（对应 CLI 的哪个 UI 元素、何时会失效）；CLI 改版失效是预期内维护，规则表要好改。
- 红测先行；净化前缀照旧；注释红线照旧。

## 5. 沉淀区（唯一允许你追加写入的区域）
