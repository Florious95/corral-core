# 知识基底 · tsnet-embed（系统编译产物）

## 0. 任务（taskbook.yaml#tsnet-embed）
- 目标：服务端内嵌 tsnet：配置 TS authkey 后同时在 LAN 与 tailnet 监听；无 authkey 纯 LAN 降级。
- 验收（exit 0 = 过）：`bash -lc 'cd /Volumes/nvme/Projects/远程Agent安卓/server && go test ./internal/tsnetd/...'`
- 写范围：`server/internal/tsnetd/`（可在 go.mod 加 tailscale.com 依赖）。红线：无 authkey 时不得触网 tailscale 控制面；tsnet 状态目录放用户配置目录（~/.config/agentmirror/tsnet，可配）。

## 1. 架构基
- 依赖 `tailscale.com/tsnet`：`tsnet.Server{Hostname, AuthKey, Dir}` → `Listen("tcp", ":<port>")` 得 tailnet listener；与 LAN 的 `net.Listen` 并联，两个 listener 喂同一个 http/ws handler（接线在 ws-api 任务，本包只产出 listener 组）。
- 配置来源：flag/env（TS_AUTHKEY、监听端口）；authkey 空 ⇒ 返回仅 LAN listener 的降级组合，日志明示"tailnet 未启用"。
- 单测面：配置分支（有/无 authkey 的构造路径）、降级行为、状态目录创建；**不做**真实 tailnet 连通（无凭据环境跑不了）——真连通性归 e2e 老化手册，测试里对 tsnet.Server 只构造不 Up。

## 2. 现场基
- go1.26.1；tailscale.com 模块较大，首次 go get 下载时间长属正常（重试预算内等待）。

## 3. 需求基（指针）
1. requirement-base/entries/007-联网模型-tsnet与扫码.md（内嵌 TS 的裁定与理由）
2. requirement-base/entries/011-技术路线裁定.md（联网行）

## 4. 经验基
- 红测先行（无 authkey 构造必须成功且不含 tailnet listener 的红测）。
- 测试净化前缀；注释红线照旧；"算不出/连不上"如实报 unknown 不猜。

## 5. 沉淀区（唯一允许你追加写入的区域）

- 2026-08-09：`tsnet.Server.Close()` 在 server **从未启动**（未触发 `initOnce`/`start()`）时
  直接 panic——`close()` 第 668 行访问 `s.sys.Bus` 而 `s.sys` 尚未赋值。构造态 server 只能靠
  不调用 Close 来收尾，或跟踪 started 标志。本包用 `Group.started` 区分"构造 vs 已启动"，
  Close 只在 started 时调 ts.Close（红线约束下 Close 恰好只会发生在启动失败路径）。
- 2026-08-09：`go get tailscale.com` 会把 go 指令升到 `go1.26.5`（v1.102.2 要求 >= 1.26.5），
  属 go.mod 必要变更；随后 `go mod tidy` 补齐约 230 行 go.sum（tsnet 依赖 gvisor、aws-sdk、wireguard-go 等）。
  本包单测实现上未触发 tsnet 的 Up/网络路径，仅在 `TestLANListenerAccepts` 走真实 TCP 回环。
