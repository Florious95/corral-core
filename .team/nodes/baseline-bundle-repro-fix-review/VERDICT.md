# baseline-bundle repro-fix 候选独立审查

## 裁定范围

本 verdict 只审 detached candidate 的 schema、三态转译和反伪造行为；没有 apply、dispatch、retry、attempt 清理或任何真实现场变更。因此 `pass` 不表示 candidate 已进入 live ledger，也不代替后续 revision/plan/apply 审批。

## Fresh 证据

- `ledger-run --preflight --json .team/nodes/spec-sol/baseline-bundle-repro-fix/baseline-bundle-v1.candidate.json` fresh exit 0，`preflight_rejected=false`、`issues=[]`。该引擎门包含 ledger.v2 JSON-schema 校验。
- 同一 candidate `ledger-run --dry-run --json` fresh exit 0，revision=1、desired_state=running，唯一 frontier 是 `t.baseline-bundle.repro`，下游按 `requires_success` 排除。
- 独立连续运行 `.team/ledgers/acceptance/baseline-bundle-real-chain-probe.sh` 两次，均真实 exit 1。两次均产生 `REAL_CHAIN_PROBE_JSON`：`ledger.perf-regress.v1` revision 4、running、impl `failed_retryable`、measurement `unjudgeable`、frontier `[]`、impl exclusion `state_not_dispatchable`、verify exclusion `dependency_unsatisfied`、lease/pidfile=85754、comm=`ledger-run`。允许变化的 lease SHA 按任务书仅是 heartbeat provenance。
- 把这两次 fresh record 与候选 `REPRO.json` 交给真实 `baseline-bundle-repro-translate.sh`，返回 exit 0：`input=expected_legacy_red probe_runs=2 acceptance_exit=0`。candidate 的 `M.baseline-bundle.repro` 和 regression gate 均仍 `expected_exit_code=0`，没有借改期望码洗红。
- rc=2 输入 fresh 返回 2；伪造 probe rc 返回 1；伪造 `impl_state=succeeded` shape 返回 1；替换为错误但格式合法的 64-hex ledger provenance 返回 1。`baseline-bundle-repro-regression.sh` fresh exit 0，证明无旧 `revision: 4`/`frontier: []`/恢复路线 magic token 的人读文本仍可通过语义门，而伪造 rc/shape 和缺 provenance 的四态保持区分。
- 四个修订脚本 fresh `sh -n=0`、ShellCheck `-s sh=0`。

## 旧红与 live attempt

当前 live `.team/ledgers/baseline-bundle-v1.json` 仍是 revision=1；`t.baseline-bundle.repro` 仍为 `failed_retryable`，且仍保留唯一首轮 attempt `att-t.baseline-bundle.repro-seq1-t1787590762873`，其 acceptance observed=1/expected=0 和 `REPRO.md omits revision: 4` 原始失败证据未被清除。候选 detached JSON 的 `state=planned` 和没有运行时 `attempts` 字段没有覆盖 live；本轮未 apply，故没有丢失旧 attempt。

## 关键语义核对

修订后的任务书把 `REPRO.json` 定为机器权威、`REPRO.md` 定为非空人读说明，并要求两条真实 probe record 的 canonical digest、固定 shape、实际 provenance、稳定字段一致。translator 只在“两次真实 rc=1 + 完整 shape/provenance + REPRO.json 对账”这一合取成立时返回 0；rc=2/缺失事实为不可判 2；rc=0、其它 rc、伪造 rc、字段矛盾或摘要/provenance 不符为 1。旧 magic token 不再参与判定。

这保留了首轮“缺精确 A → failed_retryable → 空 frontier”的红证据，同时阻止一份自写 JSON 或自然语言报告冒充真实旧红。后续 `final` 仍必须用同一 probe 取得迁移后的 exit 0，不能由本 repro 的 acceptance 0 替代。

## 验证边界

按文档路径启动 standalone `ledgerdsl` 失败于本机缺少 `pydantic`，所以没有把该未执行模块当作绿证据；candidate 的 fresh schema/preflight 结论来自实际 `ledger-run --preflight` 内置 schema gate，并由其 exit 0、无 issues 支持。此前 `plan-report.md` 记录的 `Task.parallel` field-ownership 阻塞仍是后续安全 apply 的独立前置；本审查没有绕过它。

## 结论

候选的机器契约、真实旧红转译、不可判透传、伪造拒绝、无 magic-token 假绿、candidate preflight/dry-run 和旧 attempt 保留均满足本次审查要求。结论为 candidate review pass；安全落账仍须由 leader 按既有 plan/apply 守卫处理。

verdict: pass
