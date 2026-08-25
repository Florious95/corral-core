# P0：direct result 静默后只能全局 collect

## 现象

direct 审查任务 `msg_fc4fdb12caf0` 的席位已经 idle，结果
`res_e21c78cc677f` 已存在于 durable store，但 leader 通知没有到达。为继续流程，
执行了一次 `team-agent collect --json`。该 CLI 没有按 message/result 过滤能力，实际
全局收走 708 个结果，返回 `uncollected=0`。

这不是“结果未产生”，而是“结果已存储、通知未唤醒、恢复接口只能全局取走”的新形状。
本报告只记录消息中给出的事实，不对其他 708 个结果做额外取证或归属推断。

## 量具身份与风险

- direct 任务身份：`msg_fc4fdb12caf0`。
- durable result 身份：`res_e21c78cc677f`，状态为已在 store；席位状态为 idle。
- 恢复量具：Team Agent CLI `collect --json`；当前接口无 message_id/result_id 选择器。
- 本次操作结果：全局 collect，708 个结果被收走，`uncollected=0`。

全局 collect 可能抢走 active ledger waiter 本应消费的 durable result，使 waiter 永远
等不到自己的 key，或把结果从原 case 的相关链路中移走。后果包括 active case 假性
停滞、错误 alarm、重复派单和跨任务/跨账本结果污染；它也直接违反“direct 结果可
collect、active ledger 结果不可 collect”的接口边界。`uncollected=0` 只表示全局
收取接口没有留下未收项目，不表示目标 direct result 已被 leader 正确唤醒或 active
ledger 已安全消费。

## 最小框架修复

1. 为 collect 增加服务端选择性参数，例如 `--message-id msg_fc4fdb12caf0` 或
   `--result-id res_e21c78cc677f`，并在服务端按 result 的 case/ledger/ownership 做
   原子 claim；默认拒绝 active ledger waiter 持有的结果。CLI 无选择器时应 fail-closed，
   不得静默执行全局收取。
2. 修复 durable store 到 waiter 的可靠通知：以稳定的
   `ledger case_id ↔ dispatch message_id ↔ result_id` 关联唤醒正确 waiter，带重试、
   ack 和可观察的 delivery 状态。通知失败时应明确返回“已存储、未呈现”，而不是让
   使用者靠全局 collect 恢复。
3. 若保留全局 collect 作为显式运维工具，必须要求强制 emergency 标志并显示影响
   范围；它不能成为 direct result 静默时的合法继续路径，也不能绕过 active-case
   保护。

## 我方边界与继续策略

我方不再执行 collect，不清理或重派 active case，不把 `uncollected=0` 当作 leader
已读证明；任务继续依赖各自 durable result 与 ledger waiter 的正常消费链。未为该
框架缺陷额外复现、取证或修改框架。

verdict: pass
