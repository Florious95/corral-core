# 知识基底 · server-scaffold（系统编译产物）

## 0. 任务（来自任务书 taskbook.yaml#server-scaffold）
- 目标：建立 `server/` Go module 骨架：`cmd/agentmirrord` 入口、`internal/` 分层目录、结构化日志、配置加载、根 `LICENSE`(Apache-2.0 全文)、`server/README.md`。
- 验收（exit 0 = 过）：`bash -lc 'cd /Volumes/nvme/Projects/远程Agent安卓/server && go build ./... && go vet ./...'`
- 写范围：`server/`、`LICENSE`。红线：不动其他任何文件；禁止 git commit/push（leader 收口）。

## 1. 架构基（本任务即奠基，按 011 技术路线）
- Go ≥1.26（本机 go1.26.1 darwin/arm64 已装）。module 名暂用 `github.com/remote-agent/agentmirror`（naming 任务定名后统一替换，README 注明暂名）。
- 目录骨架（后续任务的落位，先建包+doc.go 占位说明注释，不写实现）：
  - `cmd/agentmirrord/` 入口（flag 解析、优雅退出）
  - `internal/discovery/`（tmux 多 socket 枚举，任务 tmux-discovery）
  - `internal/bridge/`（pane 快照/流/注入/resize，任务 term-bridge）
  - `internal/protocol/`（WS 帧类型，任务 protocol-spec）
  - `internal/api/`（WS 服务，任务 ws-api）
  - `internal/agentstate/`（状态适配器，任务 state-parser）
  - `internal/pairing/`（token+QR，任务 pairing-security）
  - `internal/tsnetd/`（tsnet 监听，任务 tsnet-embed）
- 日志用标准库 `log/slog`；配置：flag + 环境变量，无配置文件依赖（sidecar 单二进制哲学，见需求 001）。
- 第三方依赖本任务只允许零个或极少（骨架不需要）；tsnet/qr 等由各自任务引入。

## 2. 需求基（指针，按序读）
1. /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/001-产品命题-tmux镜像范式.md（产品是什么）
2. /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/011-技术路线裁定.md（为什么是 Go/这些模块）
3. /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/008-生产级定位与开源许可.md（Apache-2.0、注释红线背景）

## 3. 经验基
- **代码必须有注释**：每个包 doc.go 写包职责与边界（这些注释未来会被 arch-wiki 任务解析成架构图，格式：普通 Go doc 注释，首句一句话职责）；每个导出符号带注释。gofmt 干净。
- 红线自查：`go vet` 零告警；不引入未使用依赖。
- 测试命令一律净化前缀：`env -u TEAM_AGENT_WORKSPACE -u TEAM_AGENT_ID -u TEAM_AGENT_OWNER_TEAM_ID -u TEAM_AGENT_AGENT_ID go ...`

## 4. 沉淀区（唯一允许你追加写入的区域）
