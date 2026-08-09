# 知识基底 · fix-daemon-idle-cpu（真机验收缺陷 C 修复）

## 0. 任务（taskbook.yaml#fix-daemon-idle-cpu）
- 目标/验收/写范围见任务书（三件套：空闲降耗/单实例守卫/e2e 泄漏）。红测先行。
- 红线：镜像与列表的正确性行为不变（有客户端时的语义零变化）；协议不改。

## 1. 现场基（leader 取证，2026-08-09）
- 孤儿实证：`ps -p 59998` → 父进程 1、`./e2e/bin/agentmirrord -listen 0.0.0.0:9902 -upload-dir /tmp/e2e-l2/uploads -log-level debug -list-interval 500ms`、09:19 起跑、CPU 时间 53 分钟、17.4%。共 4 实例（另一个 -list-interval 未知的手起实例同样 ~17%）。已全部 pkill。
- CPU 空烧机理（验证它）：api 层列表轮询按 ListInterval 无条件 tick → 每 tick Discover 扫全部 socket（每 socket 多次 tmux exec）+ 状态采样（capture-pane / ps）→ 500ms 下每秒派生几十子进程。**注意默认 2s 也只是慢性病，不是治愈**——治愈=零客户端时不 tick。
- 设计要点：连接计数门控（authed conns >0 才轮询；从 0→1 立即做一次全量扫描保首屏新鲜）；tick 内合并 tmux 调用（list-panes -a 一次拿全 server 的 pane 行已是现状？核实 discovery 实现后合并 per-socket 调用数）；pidfile 放 token 同级状态目录（~/.config/agentmirror/agentmirrord.pid），写前校验旧 pid 活性（kill -0 + 进程名核对，防 pid 复用误判）。
- e2e/layer2.sh：找到 daemon 启动处，trap 里补 kill + wait；run.sh 收尾统一 pkill -f e2e/bin/agentmirrord 兜底。

## 2. 需求基（指针）
1. requirement-base/entries/001-产品命题-tmux镜像范式.md（sidecar 应安静）
2. requirement-base/entries/004-后台策略-无状态免疫.md（轻量化哲学同样约束服务端）
3. requirement-base/entries/016-生产级验收定义修正.md（本缺陷即"未验证清单"的实证）

## 3. 经验基
- 红测先行；fake clock/注入 runner 断言 tick 与子进程派生数，不做真实 CPU 百分比断言（CI 不稳）；真实空闲 CPU 数字在交件 notes 里报实测值。
- 最小修复；注释红线；净化前缀；交件前全量门自查（含 e2e 脚本语法 bash -n）。

## 4. 沉淀区（唯一允许你追加写入的区域）

- **验收结论**：acceptance 命令绿；空闲 CPU 实测（缺陷同参 -list-interval 500ms、2 隔离 socket fleet、真机本机）：
  有客户端(2 并发 auth 连接) 平均 10.6% CPU + 扫描日志增长（正常功能未被饿死）；
  零客户端稳态 0% CPU、0 次 tmux 子进程派生、扫描日志不再增长。缺陷现场为 4 孤儿各 ~17.5%（合计 ~70% 空烧）。
- **①空闲降耗**：`Server.listingLoop` 改为连接计数门控——`authed atomic.Int64`（handleAuth +1 / teardown -1），
  零客户端时 loop park（不 tick、零子进程）；`wakeCh`(cap 1) 在 0→1 时唤醒立即全量扫描保首屏新鲜。
  discovery 本就每 socket 一次 `tmux list-panes -a`（子进程派生已按 socket 合并，本件未改）。
- **②单实例守卫**：flock 守卫（非 kill -0 方案）——`cmd/agentmirrord/pidfile.go` acquirePidfile，
  flock 内核在进程死亡时自动释放，无陈旧锁/无 pid 复用误判；pidfile 放 pairing token 同级
  （resolveStateDir 支持 `AGENTMIRROR_STATE_DIR` 覆盖，e2e/harness 与 layer2 均隔离使用）。
  进程级实证：一启持锁→二启明确报错 exit=1→一启 SIGTERM 退出后三启成功。
- **③e2e 泄漏修复**：layer2.sh trap cleanup 补 `kill + 轮询 wait + 超时强杀 + wait 收尸`；
  run.sh 收尾 `pkill -f $E2E_ROOT/bin/agentmirrord` 兜底（仅清 e2e 自己的二进制，净化红线）。
- **经验**：macOS 无 `timeout` 命令（用后台+kill）；zsh 里 `PPID` 是只读保留变量（probe 脚本换名）；
  go module 根在 server/，仓库根直接 `go build` 会报 "cannot find main module"。
