# baseline-bundle repro 返修归因

## 结论

这是一个可判定的形式契约红，不是产品真实红，也不是框架无法驱动。四类归因中，若必须归到单项，归为 worker 产物未满足验收脚本的字面格式；严格根因是 acceptance 脚本与 repro 任务书之间的未声明格式不一致。它不是“把预期先红直接接成验收失败”：内层真实旧链的 exit 1 正是本格要证明的事实，wrapper 的 `case 1)` 也正确把它转成继续核验。

## 证据

- `.team/nodes/spec-sol/baseline-bundle/first-red.log`：真实 `baseline-bundle-real-chain-probe.sh` 的 observed/expected exit 都为 1。
- `.team/nodes/spec-sol/baseline-bundle/repro-wrapper-fresh.log`：wrapper 先观察到真实链 exit 1，输出 `BASELINE_MISSING_DEADLOCK_REPRO`，随后仅因当时没有 `REPRO.md` 以 exit 1 收尾；这证明 rc=1 没有被当成 wrapper 的失败路径。
- `.team/nodes/_driver/baseline-bundle-v1.out:2-15`：同一 case 被正常 dispatch、wait-signaled，判据在声明的 cwd `/Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-bundle-core` 执行；失败唯一原因是 `REPRO.md omits revision: 4`，随后正常写回 `failed_retryable`。
- `.team/ledgers/baseline-bundle-v1.json` 的 `t.baseline-bundle.repro`：attempt 的 argv、cwd、observed=1、expected=0 与 stderr 尾部一致；没有证据表明 driver 运行了错误脚本、错误 case 或错误 worktree。
- 实际交付物 `.worktrees/wt-bundle-core/.team/nodes/baseline-bundle-repro/REPRO.md` 记录了两次 probe exit 1，以及 ledger revision=4、`failed_retryable`、`measurement: unjudgeable`、`frontier=[]`、`state_not_dispatchable`、`dependency_unsatisfied`、lease/pidfile 相同和 `driver comm=ledger-run`。因此旧链红的语义证据齐全。
- 但 acceptance 第 26--27 行要求未在任务书中声明的字面 token：`revision: 4`、`frontier: []`、`recover_exact_artifact`、`rebaseline_with_equivalence_proof`。交付物采用 Markdown 表格（例如 `ledger revision | 4`、`dry-run frontier | []`），且用自然语言说明了等价的恢复步骤，故 grep 形式失败。

因此，`failed_retryable` 是验收脚本的真实形式红；旧 perf-regress 的 `failed_retryable` 是被复现的旧行为，不是本 baseline-bundle 产品的红。worker 没有伪造 ledger，也没有把未来根治状态写成当前事实。该问题也不是框架问题：driver 已唤醒结果、在正确 cwd 执行并原子写回失败原因。

## 最小可证伪修法

保持 `M.baseline-bundle.repro` 的 `expected_exit_code=0`，绝不把期望码改成 1。修正验收契约的最小安全形状是：

1. 让任务书与判据共同规定机器可读的两条 evidence record（固定键名和值），或让判据解析现有表格的等价字段；不要用未在任务书出现的 magic token 作为唯一门槛。至少核验两条记录各自有 `ledger_id/revision`、`impl_state=failed_retryable`、`measurement: unjudgeable`、lease/pidfile、`comm=ledger-run`、`frontier=[]`、impl exclusion、verify exclusion 和 `probe_exit=1`。
2. 判据必须实际运行真实 probe（最好连续两次，或核验由 probe 产生的逐次原始输出），并要求每次真实返回 1；只读 dry-run 的输出、操作数与原始 stdout/stderr 要原样保存在 `REPRO.md`，作为后续 impl 前不可覆盖的先红证据。不能只看报告里手写的“expected”字符串。
3. 在上述旧红证据齐全时，wrapper 将 probe 的预期旧态 `1` 转译为 repro acceptance `0`；probe `2` 仍为不可判 `2`；probe `0`（旧红已消失但尚未有后继根治证明）仍拒绝为 repro pass。`REPRO.md` 同时保留“根治后由 final 重跑同一 probe 得 0”的后继要求。

当前最快的形式修补可以把交付物补成判据明定的键值记录（包括实际 `revision: 4` 和 `frontier: []`），但不能只添加几个字符串而删掉两次真实 probe 证据；更稳妥的是同步收窄脚本到任务书规定的语义字段并增加两次真实 rc=1 的独立核验。两种修法都不改 expected code，也不清除本次 failed attempt；先红日志和本 REPRO 应保留到 impl 前后可追溯。

## 现阶段裁定

当前格不能据此直接成功，也不能把它判成产品缺陷。应修正该形式契约后，以新 attempt 重跑 acceptance；旧 attempt 的红证据必须先留档，不能清洗。

verdict: refutes
