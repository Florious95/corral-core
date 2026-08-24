# P0：Pi 前台 `ledger-run --drive` 超时终止，已落地派单仍在飞

## 1. 既有 P0 与本次新形状

本报告引用并承接：

- `.team/artifacts/ledger-p0-pi-no-managed-submit-20260824.md`：当前 Pi 没有可调用的 managed/background submit 入口；普通前台命令不能安全变成长期受管任务。
- `.team/artifacts/ledger-p0-send-timeout-but-landed-20260823.md`：外部投递超时不等于派单未落地，不能据此自动重投。
- `.team/artifacts/ledger-p0-ledgerdsl-plan-rejects-parallel-20260824.md`：旧 `baseline-bundle-v1` 的 `Task.parallel` plan 所有权阻塞；successor2 是经过独立审查的新账本路径，不是对旧 live 的手写复位。

本次是同一生命周期缺口的新形状：在无 managed submit 时，前台 `ledger-run --drive` 确实完成了首格并派出了下一波，但 Pi bash tool 在运行 3600s 后终止了调用，driver PID `28697` 已不存在。它不是产品判据红，也不是首格未落地；当前 `sampler-dev-luna2` 仍由 nodeprobe 判为 `working`，因此不能重启制造第二次派单。

## 2. 量具身份与证据边界

沿用既有 P0 已核身份（本次没有重新采集进程或命令行）：

| 量具 | 路径 | md5 | mtime |
|---|---|---|---|
| ledger-run | `/Users/alauda/.cargo/bin/ledger-run` | `8c1c850bec4c86d230480b99fd6cd671` | `2026-08-20T15:06:13+0800` |
| team-agent | `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/team-agent` | `d8d2ca74fca5ea4c05a51df9fa364052` | `2026-08-24T02:40:31+0800` |
| nodeprobe | `/Users/alauda/.local/bin/nodeprobe` | `1c500dfa2933eb69a948d480b4c1536c` | `2026-08-20T22:11:12+0800` |
| ps | `/bin/ps` | `c3f64576952facf5397100becf71b305` | `2026-06-25T10:29:03+0800` |
| lsof | `/usr/sbin/lsof` | `9d610fcec75363a1385993f0e95f7ded` | `2026-06-25T10:29:03+0800` |

本次安全证据只使用：既有 `.team/nodes/_driver/baseline-bundle-successor2-v1.out`、`.team/ledgers/baseline-bundle-successor2-v1.json`、successor2 启动审查，以及 leader 提供的窄字段进程结论和 nodeprobe `working` 结论。没有读 argv、命令行参数、凭据或生产日志，也没有重新运行 `ps`、`lsof` 或 nodeprobe。

## 3. 时间线

以下时间来自已落盘 driver 日志；最后一项是本次收到的 Pi tool 事件，时间按开工时间加 3600s 表示为约值：

| 时间（UTC） | 事实 |
|---|---|
| 2026-08-24 18:39:12Z | `ledger-run` 开工，账本 `ledger.baseline-bundle.successor2.v1` revision 1；墙钟上限 115200s，派单死线 90s。 |
| 18:39:31Z | 首格 `t.baseline-bundle.repro` dispatch；`send-ok` 后又出现 `dispatch-landed`，开始等待原 case。 |
| 18:43:37Z | repro waiter 收到结果，进入 acceptance。 |
| 18:43:39Z | repro 两条 required check 通过；原子写回 revision 1→2，随后自动派出 `t.baseline-bundle.impl`、`t.baseline-bundle.probe`、`t.baseline-bundle.test`。三格日志均有 `dispatch-landed`；driver 开始等待 impl case。 |
| 约 19:39:12Z | 前台 Pi bash 调用运行满 3600s 后被 tool timeout 终止；这不是账本内的 seat deadline。leader 提供的安全进程结论为 driver PID `28697` 已不存在，而 `sampler-dev-luna2` 仍是 `working`。 |

## 4. driver、ledger、nodeprobe 的安全对照

### Driver

`.team/nodes/_driver/baseline-bundle-successor2-v1.out` 明确记录了：

```text
[ledger-run 2026-08-24T18:43:39Z] 原子写回 writeback-atomic | task=t.baseline-bundle.repro state=succeeded + revision+1 ... (revision=2)
[ledger-run 2026-08-24T18:43:39Z] 派单落地 dispatch-landed | task=t.baseline-bundle.impl 席位 sampler-dev-luna2 的收件箱已多出这条派单
[ledger-run 2026-08-24T18:43:39Z] 派单落地 dispatch-landed | task=t.baseline-bundle.probe 席位 sampler-review-luna2 的收件箱已多出这条派单
[ledger-run 2026-08-24T18:43:39Z] 派单落地 dispatch-landed | task=t.baseline-bundle.test 席位 sampler-test-luna2 的收件箱已多出这条派单
[ledger-run 2026-08-24T18:43:39Z] 等待 wait | task=t.baseline-bundle.impl ...
```

`send-ok` 不是本次的唯一依据；三格都有后续 `dispatch-landed`。PID `28697` 已死只说明前台 driver 的调用生命周期结束，不能反推三个席位已停止或结果不存在。

### Ledger

`.team/ledgers/baseline-bundle-successor2-v1.json` 当前可读状态是 `ledger.baseline-bundle.successor2.v1`、`revision=2`，repro 已 `succeeded`。impl/probe/test 的持久 task 投影仍是 `planned`，而三格已派出的事实只在 driver 等待链和席位工作态中可见；这正是重启危险：新 driver 不能凭 live JSON 区分“从未派出”与“已经派出且仍在飞”，也没有旧 driver 的内存 wait key 可接管。

successor2 的启动审查已证明初始 revision 1 的 compile/schema/preflight/dry-run 均 exit 0，初始 frontier 只有 repro；本次 revision2 的推进来自 driver 日志中的真实 acceptance/writeback，不是手工修改 live JSON。

### Nodeprobe

判活只认当前 Pi 私有 socket 上的 nodeprobe，不认 `team-agent worker_state`、PID 存活或 `send-ok`。本次 leader 提供的现行 nodeprobe 安全结论是 `sampler-dev-luna2=working`；因此在飞 impl 不能被视为可重派。nodeprobe 的量具身份和“只读 pane 标题判 working”规则均已在上表及既有 P0 中留痕。本次没有为报告重新 attach、扫描真实 tmux 或重采 nodeprobe。

## 5. 为什么此刻不能重启

现在重启 `ledger-run --drive` 会丢失原 driver 的等待状态。live revision2 的 impl/probe/test 投影尚未写成已完成/已消费结果，而三格已在席位收件箱且至少 impl 所属 `sampler-dev-luna2` 仍 working。新 driver 若按 frontier 重新求值，最小风险是对这些 case 再发一次；严重时会让同一席位同时处理两份相同根因任务，污染 worktree、产物和 acceptance 归属。

不能用以下动作规避：

- `collect` 会抢走 active ledger 应由 waiter 消费的 durable result，违反现行 N10 规则；
- 手工 `team-agent send` 会绕过账本 case/delivery 关联；
- 清 attempts、改 state 或手写 live JSON 会制造“未派出”假象并洗掉 in-flight 事实；
- 仅凭 PID 已死、`send-ok` 或 inbox 正文判断可重派，均不能证明原席位已停止。

## 6. 最小框架修复

满足以下任一方案即可消除本次 P0 的宿主生命周期缺口：

1. 暴露真正的 managed-task handle：提交 `cwd`、前台 `argv`、stdout/stderr 路径后由 Pi harness 持有 child 生命周期，返回可查询、可续期、可安全停止的句柄和 PID；tool 调用结束不能杀掉受管任务。句柄还应能绑定 ledger/revision，避免重启误判。
2. 暴露可续期的 foreground-task API：前台 `ledger-run --drive` 运行期间由 Pi tool 定期续租/续期，tool 超时或断开时将任务转入可恢复状态，而不是直接终止 child；恢复时必须保留 case/delivery wait key。

修复必须覆盖“已 dispatch-landed、driver 失活、席位仍 working”的对照，并保证恢复后消费原 case，不重复 `send`。它不是把 `--drive` 改成 `&`、`nohup`、自 daemonize 或真实 tmux；这些都没有受管句柄，也不能解决本次归属与续期问题。

## 7. 我方继续策略

当前不 `collect`、不重启、不重投、不改账本。让 impl/probe/test 继续使用已经落地的原 case；等待在飞席位各自以标准 `report_result` 将 result 写入 durable store。收到 durable result 后，再由正式的 drive/recovery 路径按原 `ledger_id`、revision、task_id、case_id、attempt_id、delivery_id 对账并消费，随后才允许继续 acceptance 和下一轮派单。

在 managed handle 或可续期 foreground task 修复提供之前，若现有 `ledger-run` 无法在新进程中消费这些已存 result 而不先重派，则该消费能力本身也属于框架最小修复的一部分；我方不以人工 collect/send 顶替它。其它有独立合法执行入口的产品任务可继续，不能把本 P0 变成全局停工。

## 8. 本次未做的危险动作

未重启或停止 driver，未向任何席位发信号，未重派 impl/probe/test，未 `collect`，未修改 successor2 live/source/attempt/判据，未创建或删除 PID/lease，未读取 argv、凭据或生产日志，未触碰真实 tmux，未额外运行取证命令。

## 9. 当前账本与提交状态

- 账本：`.team/ledgers/baseline-bundle-successor2-v1.json`，`ledger_id=ledger.baseline-bundle.successor2.v1`，revision 2。
- 账本状态：repro 已成功写回；impl/probe/test 已由同一 driver 在 revision2 派出并落地，尚无本报告时点可消费的完成结果。
- successor2 来源审查的基线 commit：`ef7a02c1d23d85eaf08c1093efafd50376fa4db5`；本报告未改变该 commit 或产品代码。
- 初始 preflight/dry-run：均 exit 0；初始 frontier 为 repro，后继依赖格被排除。revision2 的自动派单证据见 driver log，而非人工改账本。
- 直接证据文件：`.team/nodes/_driver/baseline-bundle-successor2-v1.out`、`.team/ledgers/baseline-bundle-successor2-v1.json`、`.team/nodes/baseline-bundle-successor2-review/VERDICT.md`、`.team/nodes/baseline-bundle-successor2-review/tests.log`。

verdict: pass
