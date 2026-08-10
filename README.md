# server — 服务端（Go）

AgentMirror（产品名，module `github.com/agentmirror/agentmirror`）的服务端 sidecar 守护进程。

> 产品定名 **agentmirror**（naming 任务裁定，2026-08-09）。服务端二进制名 `agentmirrord`。

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

`internal/` 各包均由 daemon 入口按上述职责装配；`tsnetd` 与 LAN listener 组成同一 API 双栈。

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
| —（禁止 argv） | `TS_AUTHKEY` | 空（LAN-only） | 内嵌 tsnet 节点凭据；非空启用 LAN + tailnet 双栈 |
| — | `TS_CONTROL_URL` | 官方控制面 | 可选自托管控制面（如 headscale）URL |
| `-state-dir` | `AGENTMIRROR_STATE_DIR` | 用户配置目录 | pidfile；tsnet 状态位于其 `tsnet/` 子目录 |

普通配置优先级：flag（显式指定）→ 环境变量 → 默认值。`TS_AUTHKEY` 是例外：它只允许
环境变量，故意不提供 `-ts-authkey`；argv 会暴露在进程列表与 shell history 中。

## LAN / tailnet 双栈运行

不设置 `TS_AUTHKEY` 时，daemon 只监听 LAN，不连接 Tailscale 控制面，也不创建 tsnet
状态目录：

```bash
go run ./cmd/agentmirrord -listen 0.0.0.0:9900
```

启用内嵌 tailnet 时，用终端的静默输入把 key 放进环境，再启动同一个 daemon。不要把 key
写进命令参数、脚本、日志或截图：

```bash
IFS= read -r -s TS_AUTHKEY
export TS_AUTHKEY
go run ./cmd/agentmirrord -listen 0.0.0.0:9900
unset TS_AUTHKEY
```

启动序列会先保留 LAN listener，再等待内嵌节点 Up（最长 60 秒），随后在 tailnet 的同一
端口启动第二个 listener。控制面握手失败、key 无效/过期或 tailnet listener 失败都会明确
退出，不会假装降级成功。配对 QR 的 `candidates` 同时包含 LAN 与 100.64.0.0/10 tailnet
地址；QR 还携带 App 入网所需的凭据，因此二维码本身是秘密，不要分享或截图。

daemon 与 App 会先后用同一 key 注册两个节点：必须使用至少可用两次的 reusable key；建议
设置短有效期、预授权并用 ACL/tag 限权，配对完成后立即吊销。收到 SIGINT/SIGTERM 时，daemon
会关闭 LAN/tailnet listener 与内嵌节点。自托管 headscale 可另设 `TS_CONTROL_URL`；不要为
隔离测试改写 `HOME`，请用 `AGENTMIRROR_STATE_DIR` 指向专用目录。

## 未验证清单

- 尚未使用用户真实 `TS_AUTHKEY` 在真实 Android 设备完成「扫码 → App 内嵌节点 Up →
  tailnet WebSocket READY」端到端；当前自动验收到假后端、SOCKS5 选路与双栈接线单测。
- `TS_CONTROL_URL` 当前只配置 daemon；QR v1 不携带控制面 URL，App 的 headscale
  扫码入网尚未形成产品链路，不应当作已支持或已验证。
- Android Keystore 的真机加密持久化与系统回收后的恢复仍需真机验收；JVM 测试使用注入
  加密器，只锁定「磁盘不写明文 authkey」的存储层语义。

## 日志

标准库 `log/slog` 结构化日志（TextHandler，输出到 stderr）。
tsnet 上游 debug/error 文本会在进入该日志前脱敏。不要启用 `TS_DEBUG_REGISTER`：该上游
调试开关会在本地脱敏钩子之前记录完整注册请求；配置 authkey 时 daemon 会拒绝这种组合。

## 运行

```bash
go run ./cmd/agentmirrord -listen 0.0.0.0:9900
```

收到 SIGINT/SIGTERM 时优雅退出（等待在途组件收尾后返回 0）。

## 许可

Apache-2.0，见根目录 `LICENSE`。
