# Baseline-bundle second prelaunch verdict

## Scope and freshness

先读取上一轮 `PRELAUNCH-VERDICT.md` 与 `tests.log`，再只审修订后的 `.team/ledgers/src/baseline-bundle-v1.py`、编译 JSON、spec-sol 全部任务书及 `baseline-bundle-*.sh`。未采信 spec-sol 的 pass/旧日志；未执行迁移、停 driver、APK 构建/安装、emulator、产品变更或凭据读取。

Fresh DSL 输出与落盘 JSON `cmp=0`，SHA-256 为 `f03eb469071580d1a64fc3f8d8949f74e88b436af903ae5f4aea97c879671b0a`；schema、`ledger-run --preflight`、`--dry-run` 均 exit 0。dry-run frontier 仅 `t.baseline-bundle.repro`，下游均由 `requires_success` 排除。11 个 `baseline-bundle-*.sh` 全部 `sh -n=0`、ShellCheck `-s sh=0`，全部内嵌 Python `py_compile=0`。

## 三态真实链

- 真实主仓 `baseline-bundle-real-chain-probe.sh` fresh exit 1：`ledger.perf-regress.v1|4|running|failed_retryable`，`frontier=[]`，impl=`state_not_dispatchable`，verify=`dependency_unsatisfied`，`measurement: unjudgeable`，lease/pidfile 与窄 `comm=ledger-run` 均核过。
- 同一真实 repro wrapper 先打印该行为红，再因尚无 `REPRO.md` exit 1；不是旧一轮的“缺 focused script”假红。
- 隔离缺现场副本 fresh exit 2；脚本的 paused 分支静态核验要求 lease 消失、migration `desired_state=paused`/`pid_dead`/`history_preserved`、64 位 bundle id 且 migration 绑定同一 bundle。未执行会改变现场的迁移动作，因此根治后 exit 0 仅保留为后续现场态。

## Bundle/provenance 与永久破坏齿

`baseline-bundle-impl.sh` 已固定真实 git root、provenance base、frozen source closure、APK/ZIP/aapt/apksigner、固定 archive 路径、inode/写位/backup restore、两次 A2 构建、工具/报告 SHA 与 canonical bundle id；不接受调用方 fixture/archive/manifest root。隔离运行永久 bypass probe：短 digest+stub manifest 旧门 `0`、当前门 `2`；调用方 root 替换命中 `repository root mismatch`。同一 probe 已挂 impl/verify/measure/final。

## Measure 判据矩阵

用 review-only 隔离 git fixture 实际运行 hardened measure：完整非空 raw、严格单调四事件、三夹具、10 对 ABAB、固定 A/B/runner/batch 身份、由 raw 重算的 nearest-rank p50/p95 与 ratio，exit 0；输出的 p50/p95 为 10/20/30/60ms，ratio=1.000000。

逐项变异结果：空 raw exit 2；错段/顺序 exit 2；n<10 exit 2；跨批 identity exit 2；错 p50 摘要 exit 1；有效 nearest-rank 比例超过 1.10 exit 1。永久空 raw+伪造 JSON 齿也实际为旧门 `0`、新门 `2`。因此当前门不信任调用方 arrays、`order.valid` 或 raw 文件名计数。

## 迁移、用户 gate 与编排形状

迁移任务书逐项保留 exact ledger/revision/state、measurement、lease/pidfile、窄 comm、无 active task、verify/user gate 前置，要求 TERM 精确 PID、有限等待、ledgerdsl plan/apply、历史保留；acceptance 只核收口事实。迁移脚本/真实动作未运行，符合本轮禁止副作用边界；静态扫描未发现 argv/ps args、凭据路径、无限重试、`pkill`/`killall`。

用户 gate 明确要求 `reported_by.kind=user`、同一 bundle/APK/signer、蜂窝+广州中转、真实 alt-screen CLI、秒开、无 blank frame；当前缺用户证据时 fresh exit 2。final 机械链实际调用 user gate；全包无 `AllSucceeded`、自定义 statuses 或 `missing_status`，任务书正常收工均仅 `report_result`，并行三席 write_paths 无交集、同席任务均依赖有序。

第二轮已消除上一轮三个可复现 bypass；剩余真实 migration/用户/真机态按要求未执行，不能冒充已完成。

verdict: pass
