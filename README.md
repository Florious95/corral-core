# server — 服务端（Go）

remote-agent agentmirror（暂名，naming 任务定名后统一替换）的服务端 sidecar 守护进程。

> **模块名暂用 `github.com/remote-agent/agentmirror`**。产品命名任务（naming）定名后统一替换
> module 名与二进制名，本骨架不做假设。

## 定位

产品命题（需求 001）：主机是唯一运行时，手机只是显示器 + 键盘。服务端是一个 **sidecar**——
attach 到用户**已经在跑**的 tmux 会话上，不要求任何东西为它重启。服务端启动时扫描主机上所有
tmux server（含 team-agent 私有 socket），存量 Agent CLI 自动纳管，零迁移成本。

## 技术路线（需求 011 裁定）

- Go 单静态二进制，零依赖安装。
- 传输：WebSocket（JSON 控制帧 + 二进制终端流帧）。
- 联网：LAN 直连 + 内嵌 Tailscale（tsnet）。
- 状态解析：per-agent 适配器，判不出降级 `unknown`，与镜像/输入层严格隔离（需求 008）。

## 目录结构

```
server/
├── cmd/agentmirrord/     # 守护进程入口：flag 解析、结构化日志、优雅退出
└── internal/
    ├── config/           # 配置加载（flag + 环境变量，无配置文件）
    ├── discovery/        # tmux 多 socket 枚举 → 两级工作区模型（任务 tmux-discovery）
    ├── bridge/           # 单 pane 终端桥：快照/增量流/注入/resize（任务 term-bridge）
    ├── protocol/         # WS 帧类型（任务 protocol-spec）
    ├── api/              # WS 服务 + 图片上传（任务 ws-api）
    ├── agentstate/       # per-agent 状态适配器（任务 state-parser）
    ├── pairing/          # token + QR 配对（任务 pairing-security）
    └── tsnetd/           # tsnet 内嵌监听（任务 tsnet-embed）
```

`internal/` 下除 `config/` 外的包当前为占位声明（doc.go 说明职责与边界），实现由各自任务落位。

## 构建与验证

```bash
cd server
go build ./...
go vet ./...
```

## 配置

无配置文件依赖。全部配置来自 flag + 环境变量（sidecar 单二进制哲学）：

| flag | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `-listen` | `AGENTMIRROR_LISTEN` | `0.0.0.0:9900` | WebSocket 服务监听地址 |
| `-qr-listen` | `AGENTMIRROR_QR_LISTEN` | 空（禁用） | 配对 QR 页监听地址 |
| `-log-level` | `AGENTMIRROR_LOG_LEVEL` | `info` | 日志级别 `debug\|info\|warn\|error` |

优先级：flag（显式指定）→ 环境变量 → 默认值。

## 日志

标准库 `log/slog` 结构化日志（TextHandler，输出到 stderr）。

## 运行

```bash
go run ./cmd/agentmirrord -listen 0.0.0.0:9900
```

收到 SIGINT/SIGTERM 时优雅退出（等待在途组件收尾后返回 0）。

## 许可

Apache-2.0，见根目录 `LICENSE`。
