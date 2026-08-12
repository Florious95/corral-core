# 现场基 · feat-daemon-scoped-discovery

## 由来：一次红线触碰暴露的产品洞（2026-08-12 实证）

`w-nav-recover` 席位为做侧滑实测，按纪律自建了隔离 daemon。结果它上报：

> 「新起隔离 daemon 的默认 tmux discovery 仍扫描到了宿主真实 socket
>  （UI dump 出现真实工作区/会话名），违反了『不碰真实 tmux（只读也不行）』边界。
>  未点击任何真实会话、未执行侧滑、未改 pane；仅 daemon listing 读到了名称。」

该席位 halt 并主动登记，处置正确。**问题不在席位，在产品缺少配置出口。**

## leader 已查实的代码事实

- `server/internal/discovery/scan.go:39`
  `Discover()` = `DiscoverWithDirs(ctx, logger, DefaultSocketDirs())`
- `scan.go:85-86` `DefaultSocketDirs()` 注释：
  扫「`$TMUX_TMPDIR` 覆盖树**加上**平台默认目录」
  → **所以设 `TMUX_TMPDIR` 也排除不掉宿主 socket，这条路走不通。**
- `scan.go:42-49` `DiscoverWithDirs()` 已存在且注释明写用途：
  「isolated TMUX_TMPDIR trees here so **no real socket is ever touched**」
- `server/internal/api/discoverer.go:20` `Discoverer` 已有 `socketDirs` 字段，
  注释：「set-but-empty means scan none」
- `server/cmd/agentmirrord/main.go`：**未把该字段暴露为任何 flag / env**

结论：**能力齐备，只差一条接线。**

## 影响面（为什么这条优先级高）

模拟器测试要么起 daemon（就会扫到用户真实 tmux），要么不起（就测不了端到端）。
今晚已因此卡住：D-23/D-32 侧滑实测 blocked。
且这不只是测试问题——**任何为测试起的 daemon 都会枚举用户全部 tmux 会话名**。

## 边界（严格限制范围，不要扩大）

- ✅ 只做配置出口接线：`cmd/agentmirrord` 读 flag/env → 传给 `api.Discoverer.socketDirs`
- ❌ 不改扫描算法、不改 `DefaultSocketDirs()` 语义
- ❌ **不改默认行为**：不配置时必须仍等价于今天的全扫（生产语义是「镜像主机上所有 Agent CLI」，那是特性不是缺陷）
- 命名与形态自行按既有 flag/env 风格定，与仓内现有配置项一致即可

## 收工判据

1. 红测：给定隔离 socket 目录时，Discover 结果**不含**该目录之外的任何 socket
2. 默认行为不变的对照测试：未配置时等价于 `DefaultSocketDirs()`
3. `cd server && env -u TEAM_AGENT_* go test ./...` 全绿
4. 交件时给出**一行可复制的启动命令示例**，说明测试席今后如何起一个只看自己 socket 的 daemon
   —— 这行命令是本任务对下游的真正交付物

## 纪律

- **不许碰用户真实 tmux 与生产 daemon（pid 39489）**，只读也不行。本轮已有四席在此越界。
- 自测一律用 Go 单测 + 临时目录，不需要起真 daemon
- 不 commit、不 push
- 主干含多条未提交在途改动（D-35 termview / D-22 session+service），**不要碰**
