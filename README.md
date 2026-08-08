# AgentMirror — 手机上的 tmux 舰队

> **产品定名 agentmirror**（naming 任务裁定，2026-08-09）。`github.com/agentmirror` org 待发布时注册。
> 代码仓库：`github.com/agentmirror/agentmirror`（模块路径）、App `dev.agentmirror.app`（014 裁定）、服务端二进制 `agentmirrord`。

**AgentMirror 是一面手机镜子，映出你主机上所有 tmux 里的 Agent 舰队。**

主机是唯一运行时，手机只是显示器 + 键盘。服务端是一个 **sidecar**——attach 到用户**已经在跑**的
tmux 会话上，不要求任何东西为它重启：启动那一刻扫描主机上所有 tmux server（含 team-agent 私有
socket），存量 Agent CLI 自动纳管，零迁移成本（需求 001）。

一句话定位：**herdr 的多 agent 感知 + moshi 的真实终端保真 + kittylitter 的扫码即连，但以"不动
用户的 tmux"为第一原则。** 产品面是投屏，技术面是 sidecar。

## 为什么自研

1. **非侵入**：不接受"agent 必须跑在我的客户端里才受控"（herdr 范式）。用户几十个 agent 已在
   tmux 里活着，"再开一遍"不可接受。
2. **单一 App**：不接受"终端 App + Tailscale App + SSH 配置"三件套凑功能（moshi 范式）。联网能力
   内置于本产品 App 与服务端。
3. **舰队视角**：现有产品都是单 agent 陪伴式交互；用户面对的是大量 agent（多工程 × 每工程一队），
   需要聚合导航。

## 架构总览

架构维基从源码现算（`tools/archwiki/build_wiki.py`，勿手改）：见 [docs/wiki/README.md](docs/wiki/README.md)
——依赖图、判据结果、每包架构卡。

```
┌────────────┐        WebSocket         ┌───────────────────────────────┐
│  Android    │ ◄──────────────────────► │  agentmirrord  (主机 sidecar)  │
│  App        │    JSON 控制帧 + 终端流    │  ├─ tsnetd   LAN + tailnet     │
│  com.agent… │                          │  ├─ api       WS + upload      │
└────────────┘                          │  ├─ bridge    单 pane 终端桥    │
                                        │  ├─ discovery tmux 多 socket 扫描│
                                        │  ├─ agentstate  per-agent 状态   │
                                        │  ├─ pairing    token + QR       │
                                        │  └─ config     flag + env       │
                                        └───────────────────────────────┘
                                            ▲ attach（不重启、不重构）
                                            ▼
                                        tmux server（含 team-agent 私有 socket）
```

- **镜像层零适配**：终端镜像任何 CLI 天然支持，永不因 CLI 升级而坏。
- **状态层严格隔离**：状态判不出降级 `unknown`，绝不影响镜像与输入的可用性（需求 008）。

## 快速开始

### 服务端：一条命令起

```bash
cd server
go run ./cmd/agentmirrord -listen 0.0.0.0:9900
```

可选联网 flag（tailnet 直达，免局域网发现）：`-ts-authkey <key>`（见 `server/README.md` 配置表）。

### App：扫码即连

1. 服务端 stdout 打印配对二维码（ANSI half-block，无图片管线）。
2. Android 端装 App，扫码——二维码载服务端地址 + 配对 token。
3. 已配对连接直接进舰队视图：按工程导航、状态一屏尽收、点 pane 进终端。

> 服务端无配置文件：全部配置来自 flag + 环境变量（sidecar 单二进制哲学）。

## 许可

Apache-2.0，见 [LICENSE](LICENSE)。
