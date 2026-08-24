# baseline-bundle successor run1 返修归因

## 裁定

根因已被现场直接证明：successor 复用了旧 ledger 已占用的 `worktree_id=wt-bundle-core`，所以 dispatch/acceptance 的 cwd 是旧 worktree，而不是包含 successor 新判据的当前仓版本。当前 run1 不是产品失败；它是“旧 worktree 造成的判据/任务契约不一致”，其中一个判据是形式失败，另一个判据根本不可执行。

## 证据链

### 1. 不是 result waiter 910s 超时

`.team/nodes/_driver/baseline-bundle-successor-v1.out` 显示：

- 17:57:55 dispatch、send-ok、dispatch-landed，随后进入 case waiter，预算为 3600s；
- 18:13:03 `wait-signaled`，明确已经收到 worker result；
- 18:13:03--18:13:04 才开始 required acceptance；
- 18:13:04 因判据不可判停机，墙钟 910s，`AwaitingHuman`。

因此 910s 是 worker 结果到达前的实际等待加判据执行耗时，不是 result waiter 到预算边界未收到结果。driver 没有在 waiter 阶段 alarm，也不是无结果/错 waiter key。

### 2. 旧 worktree 与新判据的确定性错配

successor ledger 的 `t.baseline-bundle.repro.resources.worktree_id` 是 `wt-bundle-core`，两个 required checks 的 cwd 都是 `${worktree}`；driver 实际记录的 cwd 是 `/Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-bundle-core`。

只读检查得到：

- 仓根 HEAD=`ef7a02c1d23d85eaf08c1093efafd50376fa4db5`，包含返修提交 `488a1f25bc8b0a35afb62df85e3afd5ce666bf8e`；
- `wt-bundle-core` HEAD=`d400b8b47adbb84e860c6a442b99386dcd747766`，是旧 worktree，未包含 488a1f25b；
- root 的 `baseline-bundle-repro.sh` 是新 JSON translator 版本，SHA-256 `788c1b7e...`；WT 中同名文件是旧 magic-token grep 版本，SHA-256 `1f69a1e6...`；
- root 中 `baseline-bundle-repro-translate.sh` 与 `baseline-bundle-repro-regression.sh` 均存在，WT 中两者均不存在；
- WT 中 worker 的 `REPRO.json`、`REPRO.md` 和两份 tmp probe record 均已落盘，说明 worker 交付到了旧 worktree，而非缺席。

这正好解释 driver 的两条 acceptance 诊断：

1. `M.baseline-bundle.repro` 在旧脚本上 exit 1：`REPRO.md omits recover_exact_artifact`。这是旧形式门看新 worker 产物的契约错配，不是新 translator 对真实旧红的判断，也不是产品行为红。
2. `M.baseline-bundle.repro-regression` exit 127：`/bin/sh: .team/ledgers/acceptance/baseline-bundle-repro-regression.sh: No such file or directory`。这是判据文件在 WT 不存在，严格属于不可判。

### 3. 真实旧链不是本次产品失败

WT worker 产物本身记录了两次真实 probe exit 1、`ledger.perf-regress.v1` revision 4、impl `failed_retryable`、measurement `unjudgeable`、frontier `[]`、`state_not_dispatchable` 与 `dependency_unsatisfied`。这些是 repro 要保留的旧链先红证据。root 新 translator 已在此前独立审查中要求两次真实 rc=1、固定 shape/provenance 才把它转成 acceptance 0；本次未执行到该新 translator，因为它不在被复用的 WT。

### 4. ledger 写回事实

当前 `.team/ledgers/baseline-bundle-successor-v1.json` 的任务 `state` 仍是 `planned`，但只读 JSON 显示已写入一条 attempt `att-t.baseline-bundle.repro-seq1-t1787594275571`，其中包含 M.repro exit 1 与 M.repro-regression exit 127 的 `acceptance_failure` refs。driver 日志也记录了“判据失败诊断写入 attempts[]”和“不可判停机”。所以“全 planned”成立；“无 attempts 写回”不成立，不能在交接中把这条运行事实抹掉。它是已留痕的失败/不可判结果，不是 worker 结果丢失。

## 最小可继续修法（不改 framework）

保留三层历史：旧 `ledger.baseline-bundle.v1` 及其首红 attempt、当前 `ledger.baseline-bundle.successor.v1` run1 的 acceptance failure/worker 产物与 driver 日志，均不清理、不覆盖、不重派成“同一轮成功”。

创建新的 successor ledger/source（新 ledger id、revision 1、九格 planned、无 runtime attempts），将所有原来使用 `wt-bundle-core` 的 successor 任务改为全新且未占用的 `worktree_id`，例如 `wt-bundle-successor-core`；保持原 task graph、write_paths、required checks、expected code=0、四态与只读历史 provenance 不变。编译后 fresh 运行 schema/preflight/dry-run，确认首 frontier 仍只有 repro。

由该新 worktree 从包含 488a1f25b 的当前仓版本创建/运行，使 WT 同时具备新 `baseline-bundle-repro.sh`、translator、regression 与 real-chain probe；worker 再在新 worktree 生成新的 REPRO.json/REPRO.md，acceptance 才能实际核验新契约。这样不需要改 framework，也不需要把旧 WT 的文件复制进去，更不能通过把 expected code 改成 1 或删掉 regression check 洗绿。

## 结论边界

当前 successor run1 的终态是 acceptance 形式红 + acceptance 不可判，driver 的 AwaitingHuman 是后者触发的诚实停机；没有证据支持产品实现失败。立即继续的安全钥匙是新 successor ledger + 新 worktree_id，同时保留旧 ledger、当前 successor run1 和两轮红证据。

verdict: refutes
