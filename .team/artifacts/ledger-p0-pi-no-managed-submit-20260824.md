# P0：当前 Pi harness 没有受管后台提交入口，resident driver 无法安全启动

- 报告方：远程Agent安卓 / input-advisor-luna
- 受理方：ledger-orchestration 维护方
- 事件标签：baseline-bundle-v1 resident driver 启动阻塞
- 定级依据：任务要求不能把 resident 命令作为长期阻塞的 bash 调用，也禁止 &、nohup、自 daemonize、真实 tmux；没有受管提交入口时只能停下并报告。

## 一、现象

### 使用方视角

当前 leader 使用的 Pi 工具只有 bash/read/edit/write。bash 调用面没有 background 或 managed-task 参数；因此无法把 ledger-run --drive --resident 提交成一个由宿主托管、可查询和可回收的长期任务。

### 机器视角

安全启动所需的命令本身是前台常驻命令：

~~~sh
exec ledger-run --drive --resident --json "$LEDGER" >> "$OUT" 2>&1
~~~

在当前 Pi bash 中直接执行会长期占住调用；改成 shell 后台、nohup、自 daemonize 或真实 tmux 又违反当前安全边界。仓内现成 watchdog 和监视脚本均没有“提交一个 foreground 命令并返回受管句柄”的入口。

## 二、实际可用 Pi 工具与量具身份

本次使用的量具均为只读调用，未读取 argv 或凭据：

| 量具 | 路径 | md5 | mtime |
|---|---|---|---|
| ledger-run | /Users/alauda/.cargo/bin/ledger-run | 8c1c850bec4c86d230480b99fd6cd671 | 2026-08-20T15:06:13+0800 |
| team-agent | /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/team-agent | d8d2ca74fca5ea4c05a51df9fa364052 | 2026-08-24T02:40:31+0800 |
| nodeprobe | /Users/alauda/.local/bin/nodeprobe | 1c500dfa2933eb69a948d480b4c1536c | 2026-08-20T22:11:12+0800 |
| ps | /bin/ps | c3f64576952facf5397100becf71b305 | 2026-06-25T10:29:03+0800 |
| lsof | /usr/sbin/lsof | 9d610fcec75363a1385993f0e95f7ded | 2026-06-25T10:29:03+0800 |

team-agent --help 的实际命令面包含 quick-start/send/status/collect/results、agent 生命周期、diagnose/recovery 和 provider launcher；没有 run、background 或 managed-task submit 命令。

## 三、现行 skill 要求

/Users/alauda/.agents/skills/ledger-orchestration-trial/phases/01-立格.md 要求的顺序是：

1. ledger-run --preflight
2. ledger-run --dry-run，核对 frontier/excluded
3. 用受管后台任务启动 resident driver
4. 手写 .team/nodes/_driver/<name>.pid，按 cwd 核对 PID 归属
5. 日志使用 _driver 下的直接路径，或同文件系统硬链；不能用软链
6. 首格必须同时核 dispatch-landed 与 nodeprobe 的实际工作态，不能只信 send-ok

同一分册明确记录：普通 nohup & 会被外部 TERM，driver 自 daemonize 也不能推导为其他脚本的安全启动方式。当前 Pi 没有该 skill 假定的宿主提交接口，故不能凭文字把普通 bash 命令称为“受管后台”。

## 四、最小安全复现

下面只运行静态预检、dry-run 和命令面检查，不启动 resident、不创建 PID 文件、不碰 tmux：

~~~sh
cd /Volumes/nvme/Projects/远程Agent安卓
ledger-run --preflight --json .team/ledgers/baseline-bundle-v1.json
# 实际：rc=0，{"ok":true,"preflight_rejected":false,"issues":[]}

ledger-run --dry-run --json .team/ledgers/baseline-bundle-v1.json
# 实际：rc=0，frontier=["t.baseline-bundle.repro"]

team-agent --help
# 实际：命令面没有 run/background/managed-task submit
~~~

期望是：预检通过后有一个可调用的受管提交入口。实际是：只有前台 resident 命令，没有当前 Pi 可调用的托管入口；因此安全终态为 blocked，而不是启动失败或账本失败。

## 五、现有 driver 的 ppid=1 对照

只读字段限定为 pid,ppid,etime,stat,comm：

~~~text
pid=96139 ppid=1 etime=12:11:42 stat=S comm=ledger-run
pid=85754 ppid=1 etime=05:38:31 stat=S comm=ledger-run
~~~

两条父链都直接到 PID 1。对应的运行后留痕是：

- input-full-auto-v1.pid：inode 706858843，links 1，mtime 2026-08-24T12:36:45+0800
- input-full-auto-v1.out：inode 706858842，links 1，mtime 2026-08-24T13:00:59+0800
- perf-regress-v1.pid：inode 708814723，links 1，mtime 2026-08-24T19:09:57+0800
- perf-regress-v1.out：inode 708788902，links 1，mtime 2026-08-24T19:22:07+0800

这是当前已脱离原调用者的运行态对照，不是历史启动来源的证明，也不是 Pi 已有 managed-task 提交器的证明。ppid=1 只能说明当前父进程关系已断开或被系统收养，不能替代受管任务句柄与 cwd 归属证据。

## 六、为什么现有替代都不合法

- .team/watchdog-supervisor.sh 是 watchdog 的循环守护，源码入口明确使用 setsid nohup ... &。它监视 watchdog，不提交 ledger driver；并且该启动形态正是本任务禁止的形态。
- .team/watchdog.sh、.team/artifacts/orch-watch.sh 和 skill 的 stall-alert.sh 都是判停/判活监视器。它们读取既有 PID、日志和 nodeprobe，触发时退出或告警，没有提交长期 foreground 命令的 API。
- 普通 & 只把进程交给当前 shell 的后台作业表，不能给 Pi 返回受管任务句柄，也不能保证工具调用结束后存活。
- nohup 只改变挂断信号处理，不提供 cwd 归属、日志接管、状态查询或安全回收；本仓已有经验也明确该形态曾被外部 TERM。
- 自 daemonize 会让进程脱离调用者，现有 driver 的 ppid=1 正是这种“当前已脱离”形状的对照；它不能证明提交时的工作区、日志和 PID 由宿主受管。
- 真实 tmux 属于用户会话/生产运行时；它不是 Pi 的后台任务提交 API，且本任务明确禁止触碰真实 tmux。把命令塞进真实 tmux 还会混淆会话所有权和 cwd 证据。

## 七、原因分析及边界

### 已确认事实

1. 当前 Pi 可调用面没有 background/managed-task 参数。
2. team-agent --help 没有受管命令提交入口。
3. 仓内 watchdog、stall-alert 和 orch-watch 都是监视器，不是 launcher。
4. 现有两个 driver 的当前进程父链都到 PID 1。
5. ledger-run 本身的静态预检与 dry-run 正常，账本不是因为结构非法而阻塞。

### 原因判断

当前使用方的 Pi 通道与 ledger skill 所假定的“受管后台任务宿主”之间缺少接入层。Pi 可以调用一个前台命令，却没有能持有该命令生命周期并返回句柄的接口；因此 skill 要求无法落到实际可调用命令上。

### 判断边界

本报告只判断当前仓、当前 Pi 工具面和当前可见 CLI/脚本；没有断言其他 provider、其他工作区或框架内部可能存在的未暴露宿主能力。没有为框架新增复现阶梯、没有修改框架代码，也没有实际启动 resident 去制造现场。

## 八、框架所需最小修复

二选一即可解除当前阻塞：

1. 给 Pi 暴露真实的 managed-task submit API/参数：至少接收工作区 cwd、前台命令参数、stdout/stderr 目标路径，返回可查询/可停止的任务句柄及 PID；句柄还应能让调用方核对 cwd 和日志归属。
2. 提供一个已注册且受管的本机服务入口，承接同一组参数并返回同等生命周期句柄。

无论采用哪一项，提交器都必须保持命令前台运行，由宿主负责托管；不能要求使用方自行补 &、nohup、自 daemonize 或真实 tmux。补齐后才可继续 pid 文件、硬链/直接日志和首格 dispatch-landed/nodeprobe 核验。

## 九、我方未做的危险动作

- 未启动 ledger-run --drive --resident
- 未使用 &、nohup、自 daemonize 或真实 tmux
- 未写入 baseline-bundle-v1.pid 或 driver 日志
- 未向任何已有 driver 发信号，未重启、重投、替换席位或修改账本
- 未读取进程 argv、provider profile、凭据或生产日志
- 未为框架额外做复现、取证阶梯或代码修复

## 十、当前账本、commit 与预检状态

- 账本：.team/ledgers/baseline-bundle-v1.json
- ledger id：ledger.baseline-bundle.v1
- revision：1
- 账本 SHA-256：f03eb469071580d1a64fc3f8d8949f74e88b436af903ae5f4aea97c879671b0a
- 源账本字段没有写入 desired_state/workflow_state；只读 dry-run 求值为 desired_state=running、workflow_state=active
- preflight：通过，rc=0，preflight_rejected=false，issues=[]
- dry-run：通过，rc=0，frontier=t.baseline-bundle.repro，其余依赖格 excluded
- commit：main / 9468854e1f1a1fdef50edb859d9d309461a597d8（编排基线资产丢失死锁根治链）
- 当前账本相对 HEAD 无 scoped 修改
- 本报告及先前的 COMMAND.md 均为当前工作树新增/更新、尚未纳入 commit；这是交付留痕，不是账本改写

verdict: pass
