# P0：foreground drive 恢复先重派、后消费 durable result

## 1. 既有 P0 引用与新形状

本报告承接以下已落盘报告：

- `.team/artifacts/ledger-p0-pi-no-managed-submit-20260824.md`：Pi 没有可调用的受管后台提交入口。
- `.team/artifacts/ledger-p0-pi-foreground-drive-timeout-20260824.md`：前台 `ledger-run --drive` 运行 3600s 后被 Pi tool 终止，原 driver 死亡时席位仍在飞；当时明确要求等待原 result 入 store，不重启、不 `collect`。
- `.team/artifacts/ledger-p0-send-timeout-but-landed-20260823.md`：外部投递结果不确定或已落地时，不能直接重投。
- `.team/artifacts/ledger-p0-ledgerdsl-plan-rejects-parallel-20260824.md`：旧账本的 `Task.parallel` plan 所有权阻塞；本事件发生在经独立审查的 successor2 live 链上，非旧账本手写复位。

本次是上述生命周期缺口的进一步新证据：恢复 driver 在 durable results 已经存在时，先把三个同一 revision/case 的任务再次 dispatch-landed，数秒后才消费旧结果。`sampler-dev-luna2` 与 `sampler-review-luna2` 因而实际收到并重放；probe/test 随后写回的成功是旧 result 的消费，不是重放产生的新证据。

## 2. 量具和证据边界

沿用上一份 P0 的既有量具身份，本次只读取已落盘证据，没有重新为框架取证：

| 量具 | 路径 | md5 | mtime |
|---|---|---|---|
| ledger-run | `/Users/alauda/.cargo/bin/ledger-run` | `8c1c850bec4c86d230480b99fd6cd671` | `2026-08-20T15:06:13+0800` |
| team-agent | `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/team-agent` | `d8d2ca74fca5ea4c05a51df9fa364052` | `2026-08-24T02:40:31+0800` |
| nodeprobe | `/Users/alauda/.local/bin/nodeprobe` | `1c500dfa2933eb69a948d480b4c1536c` | `2026-08-20T22:11:12+0800` |

直接证据是 `.team/nodes/_driver/baseline-bundle-successor2-v1.out` 与 `.team/ledgers/baseline-bundle-successor2-v1.json`，并以上一份 P0 的 Pi timeout/nodeprobe 安全结论作上下文。没有读取 argv、凭据或生产日志；没有重新运行 driver、nodeprobe、ps、lsof 或 team-agent。

## 3. revision 2→4 时间线

| 时间（UTC） | 账本/driver 事实 |
|---|---|
| 18:43:39Z | 原 driver 在 repro acceptance 成功后原子写回 revision 2，并首次 dispatch-landed impl/probe/test；三格进入原 case 的等待。 |
| 约 19:39Z | 前台 Pi tool timeout 终止原调用，PID 28697 已不存在；三个席位/原 case 仍在飞。上一份 P0 已记录这一状态和“不重启、不 collect”的安全策略。 |
| 19:59:33Z | 旧 lease 被恢复进程以 `pid=32119 stole from pid=28697 reason=holder pid 28697 不存在` 接管；恢复 driver 以 revision 2 开工。 |
| 19:59:34Z | 恢复 driver 先对 impl/probe/test 各执行一次新的 dispatch，并且三次都报告 `dispatch-landed`；这些新投递复用了各自原 revision2 case_id。 |
| 19:59:35Z | 恢复 driver 对 impl/probe/test 依次 `wait-signaled`，说明 durable store 中已有结果被新 driver 消费，而不是等待新的席位工作完成。 |
| 19:59:36Z | impl 旧结果进入 acceptance；判据分别为 exit 1 与 exit 2，随后被记为不可判/失败诊断。该结果不能证明新的 impl 重放已经交货。 |
| 19:59:38Z | probe 消费旧结果并 acceptance-success，原子写回 revision 2→3。 |
| 19:59:39Z | test 消费旧结果并 acceptance-success，原子写回 revision 3→4；driver 随后因 impl 状态停在 AwaitingHuman。 |

## 4. 相同 case_id 对照

原始 dispatch 与恢复 dispatch 的 attempt/delivery 不同，但 case_id 相同：

| task | 原 dispatch（18:43:39Z） | 恢复时再次 dispatch（19:59:34Z） | 相同 case_id |
|---|---|---|---|
| impl | `att-t.baseline-bundle.impl-seq2-t1787597019088` / `del-t.baseline-bundle.impl-seq2-t1787597019088` | `att-t.baseline-bundle.impl-seq1-t1787601574226` / `del-t.baseline-bundle.impl-seq1-t1787601574226` | `ledger_baseline-bundle_successor2_v1__t.baseline-bundle.impl__r2` |
| probe | `att-t.baseline-bundle.probe-seq3-t1787597019370` / `del-t.baseline-bundle.probe-seq3-t1787597019370` | `att-t.baseline-bundle.probe-seq2-t1787601574491` / `del-t.baseline-bundle.probe-seq2-t1787601574491` | `ledger_baseline-bundle_successor2_v1__t.baseline-bundle.probe__r2` |
| test | `att-t.baseline-bundle.test-seq4-t1787597019633` / `del-t.baseline-bundle.test-seq4-t1787597019633` | `att-t.baseline-bundle.test-seq3-t1787601574763` / `del-t.baseline-bundle.test-seq3-t1787601574763` | `ledger_baseline-bundle_successor2_v1__t.baseline-bundle.test__r2` |

日志中的关键原文形状为：恢复时先出现三条新的 `派单 dispatch`/`dispatch-landed`，随后同一 task 的 `wait-signaled`；因此新 delivery 的落地不能被解释成“旧结果尚不存在”。当前 ledger revision 4 也只证明 probe/test 的 acceptance/writeback 已完成，不证明对应席位在恢复后又完成了新的实现或探针工作。

## 5. 原因：恢复顺序和去重缺陷

这是恢复顺序缺陷，而非产品判据结论：

1. 恢复 driver 从 revision2 计算 frontier，将 impl/probe/test 当作可派任务。
2. 它在查询/消费 durable result store 之前执行了外部 dispatch。
3. dispatch 生成了新的 attempt/delivery，却沿用了原逻辑任务的 revision2 case_id；席位因此收到第二份相同逻辑任务。
4. 数秒后 waiter 按同一 case_id 找到已经存在的旧 result，执行 acceptance 并写回 revision3/4。

正确的恢复顺序应当是“先以 ledger/revision/task/case/attempt/delivery 维度查询并消费 durable result，再对仍无结果的任务求 frontier 并 dispatch”。如果结果存在但绑定不唯一，应进入 `needs_reconcile`，绝不能先发外部动作。

## 6. 风险

- 同一个 worktree/写范围可能同时承受原席位和恢复重放，造成文件、分支、产物和提交互相污染。
- 旧结果被新 dispatch 包裹后写回，看起来像恢复后的新执行成功；probe/test 的 revision3/4 结果因此不能作为重放有效性的证据。
- 同一 case_id 下 attempt/delivery 不同，若没有严格绑定，可能错消费、重复 acceptance 或把迟到结果写到错误 revision。
- impl 的旧结果被立即判据为 exit 1/2，说明恢复顺序错误还会把旧现场的判据问题与新重放混在一起，增加返修和归因风险。
- 重复 dispatch 的席位可能继续修改共享工作树，即使 driver 已快速消费旧 result 并退出，重放副作用仍可能在后台发生。

## 7. 框架最小修复

恢复路径至少需要以下原子顺序和去重保证：

1. 在 frontier dispatch 前，按 `ledger_id + ledger_revision + task_id + case_id` 查询 durable result；若账本已声明 attempt/delivery，则继续校验 `attempt_id + delivery_id` 的精确绑定。
2. 先消费并完成 acceptance/writeback，再计算下一 frontier；已有 result 的 task 不得再次 dispatch。
3. 加入 case-level 幂等/去重：active dispatch、durable result、waiter claim 必须共享唯一 case key；恢复重试要么复用同一受管 attempt 而不再次发送，要么使用全新的 case_id 并明确记录为新尝试，不能“新 delivery + 旧 case_id”无条件重投。
4. 查询、claim、dispatch 决策需要原子锁或等价 compare-and-set；查询期间若状态不确定，进入 `needs_reconcile`，不做外部派单。
5. 回归必须覆盖本次形状：driver 死亡后席位仍 working、旧 result 已 durable、恢复；期望是零新 dispatch、消费旧 result、只产生一次 acceptance/writeback。对照还应覆盖 result 未存在时才允许一次 dispatch，以及同 case 的重复恢复不增派单。

## 8. 我方处置与继续策略

我方没有杀席、没有向任何席位发信号、没有 `collect`、没有人工重投、没有修改 live/source/判据，也没有把 probe/test 的 revision3/4 写回当作新的产品或重放证据。重放已经由框架造成，不能由我方再补一轮以“验证”它。

后续只按账本现有 durable result 和框架对账路径处理：保留 revision4、旧 attempt/delivery/case 证据及 acceptance 诊断；不抢 active result，不手工清 attempts，不绕过判据。框架修复并通过上述恢复/去重回归前，不再用恢复 driver 触发新的派单。

## 9. 当前状态与原始证据

- live：`.team/ledgers/baseline-bundle-successor2-v1.json`，`ledger_id=ledger.baseline-bundle.successor2.v1`，`revision=4`。
- writeback：probe 为 revision2→3，test 为 revision3→4；impl 没有成功写回，driver 以 AwaitingHuman 停止。
- 主要日志：`.team/nodes/_driver/baseline-bundle-successor2-v1.out`，包含旧 dispatch、恢复 dispatch、相同 case_id、wait-signaled 与 acceptance/writeback 顺序。
- 既有启动/安全审查：`.team/nodes/baseline-bundle-successor2-review/VERDICT.md`、`.team/nodes/baseline-bundle-successor2-review/tests.log`。
- 本报告未改变账本、框架、产品代码、席位或提交。

verdict: pass
