# baseline-bundle prelaunch refutes 返修结果

本轮只修订 baseline-bundle 编排包，没有启动账本、停止旧 driver、构建/安装 APK、启动 emulator、修改 App/server 或读取凭据。

## Refutes 逐条关闭

1. 真实行为链：新增 `baseline-bundle-real-chain-probe.sh`。它固定观察主仓 `ledger.perf-regress.v1`，从 git worktree metadata 定位原 impl 现场，联结 ledger revision/state、FIXED-MEASURE 末行、lease/pidfile、窄 `comm` 与真实 `ledger-run --dry-run`。fresh 结果为旧链 exit 1：`failed_retryable + frontier=[] + verify dependency_unsatisfied + measurement unjudgeable`；迁移完成后同一脚本只在 paused、lease 收口且 migration 绑定有效 bundle 时 exit 0；事实漂移为 exit 2。repro wrapper 先执行并打印该真实红，随后才检查执行格的 REPRO.md。
2. impl 门：固定自己的 git worktree 根与 provenance base `a538117…`，拒绝任何调用方 input/archive/manifest root。判据不调用工具自报 verify，而是独立重算 frozen source closure、真实 APK SHA-256/md5/size、ZIP 运行内容摘要与 entry count、aapt package/version、apksigner signer、Gradle wrapper/build-tools、两份 A2 APK、工具入口 SHA、报告 SHA、canonical bundle id、无写位 seal、primary/backup inode，并实际从 backup 复制取回复核。
3. measure 门：固定真实 bundle A 与私有 candidate B 文件，从 `raw/order.tsv` 和每个非空 raw 的 batch/runner/A2/B/fixture/sequence/package 身份头重建唯一同 open_id 严格单调四事件链。逐夹具验证严格 A/B/A/B、连续序号、每包 n>=10，从 raw 重算四段数组、nearest-rank p50/p95 与 B/A，再和 JSON 逐项对账；空 raw、缺链/顺序/身份为 exit 2，有效超 1.10 或证据矛盾为 exit 1。
4. 永久破坏齿：`baseline-bundle-bypass-probes.sh` 冻结判者旧门与 fixture 摘要。fresh 结果：短 digest+stub manifest `legacy=0 hardened=2`，且新门命中固定仓根拒绝；60 空 raw+伪造 JSON `legacy=0 hardened=2`，并在补齐 git/APK/runner/batch/order前置后精确命中 `empty raw log`。该 Check 已挂到 impl、verify、measure、final；final 另 required 同一真实链探针 exit 0。
5. 编排：`ledger.baseline-bundle.v1` revision 1，无 custom statuses；frontier 仅 repro，之后 impl/test/probe 三席隔离并行，再 verify→user gate→migrate→measure→final。所有 required Check 均 expect 0、unjudgeable 2。

## Fresh 验证

- DSL 构造与二次生成 cmp：0；jsonschema：0；`ledger-run --preflight`：0；`ledger-run --dry-run`：0。
- 全部 11 个 `baseline-bundle-*.sh`：`sh -n` 0；ShellCheck POSIX sh：0；impl/measure 内嵌 Python 编译：0。
- 真实旧链探针：预期/实际 1；repro 执行格未产出 REPRO.md 前 wrapper：预期/实际 1，且先输出真实链操作数；永久 bypass 合取：预期/实际 0。
- fresh 日志：`compile.log`、`schema.log`、`preflight.log`、`dry-run.log`、`sh-n.log`、`shellcheck.log`、`embedded-python.log`、`real-chain-probe-fresh.log`、`repro-wrapper-fresh.log`、`bypass-probes-fresh.log`、`structure.log`、`seat-check.log`、`scope.log`。

verdict: pass
