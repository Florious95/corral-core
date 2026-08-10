# 现场基 · fix-ts-state-dir-e2e（裁定席取证，2026-08-10）

## 阻塞事实

- `feat-ts-wire` 双验收通过，但自建 headscale + 隔离 daemon + 模拟器实链未执行；状态只能是 `blocked`，不是功能失败。
- `server/cmd/agentmirrord/main.go:128-134` 构造 `tsnetd.Options` 时传入 ListenAddr/Hostname/AuthKey/ControlURL，未传 `Dir`。
- `server/internal/tsnetd/tsnetd.go:130-167` 在 `Options.Dir` 为空时调用 `os.UserConfigDir()` 并创建用户真实配置目录下的 tsnet 状态。
- `server/cmd/agentmirrord/main.go:68` 已经把 `cfg.StateDir` 解析成有效 `stateDir`；它目前只供 pidfile 使用。
- 安全验收禁止赋值、重定向或复用 `HOME`，禁止触碰用户真实 tsnet；因此不得再用前席的 HOME 旁路。

## 最小实现边界

- 复用 cmd 已解析的有效 `stateDir`，把独立子目录（建议 `<stateDir>/tsnet`）传给 `tsnetd.Options.Dir`；不新增第二套 state-dir flag/env。
- 默认行为保持为同一用户配置根下的 agentmirror 状态；显式 `-state-dir`/`AGENTMIRROR_STATE_DIR` 时 pidfile 与 tsnet 状态一起进入隔离根，但目录分层。
- 红测必须锁定消费方：只给 Config 增字段或只测路径函数不算交付，`tsnetd.New` 的 Options.Dir 必须能被测试指认。
- tailnet 未启用时不得创建 tsnet 子目录。

## E2E 与密钥卫生

- 使用自建 headscale、隔离高端口、隔离 daemon state、模拟器 `emulator-5554`；不读 profile `.env`，不碰生产 daemon/用户真实 tmux/真实 Tailscale。
- ephemeral TS authkey 只通过 `TS_AUTHKEY` 环境进入 daemon，禁止 `-ts-authkey` argv；不得写日志、截图或 shell trace。服务端向 App 的唯一合法出口是 QR。
- 必须实证：daemon 与 App 两节点均加入自建控制面；App 通过 SOCKS5 拨 daemon 的 100.64/10 地址并进入工作区；018 逐图目检截图不得含 authkey 明文。
- runner 必须 `trap` 精确清场；验收后核对 task-owned PID/端口/状态目录/open files 全零，并 presence-only 报告 argv 中 authkey flag/key-shape 是否出现。

## 前席残留教训

- 终审曾发现前席孤儿 PID 19738 agentmirrord、PID 88840 headscale 与 `/private/tmp/tswire-e2e2` 敏感目录；已精确 SIGTERM 并删除，生产 PID 46081/:9900 未触碰。
- 所以“本席没启动”不等于任务域零残留；验收要覆盖整条任务命名空间，而非只查本席新预留端口。
