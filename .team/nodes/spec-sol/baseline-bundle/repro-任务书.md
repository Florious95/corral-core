# t.baseline-bundle.repro — 先红复现“精确 A 丢失导致旧性能格无限重试”

背景与必读：先读同目录 `任务书.md`、当前 `.team/ledgers/perf-regress-v1.json`、`.team/ledgers/acceptance/perf-regress.sh`、真实 wt-pr-impl 的 `FIXED-MEASURE.md` 与 `.team/ledgers/acceptance/baseline-bundle-real-chain-probe.sh`。只运行 `ledger-run --dry-run` 观察真实旧链；不得启动/改变 ledger、emulator、tmux 或 daemon。

精确交付：只写 `.team/nodes/baseline-bundle-repro/REPRO.md`。连续两次运行真实链探针并记录完整窄字段：ledger id/revision、impl state、FIXED-MEASURE 末行、lease pid=pidfile pid、`comm=ledger-run`、dry-run `frontier=[]`、impl `state_not_dispatchable`、verify `dependency_unsatisfied` 与两次 rc=1。不得以未来文件不存在、打印预期字符串或自造 ledger 副本代替真实行为。

硬约束：不得触碰真实 lease/PID/JSON，不修改门槛或旧 A 身份；探针约定是旧死锁现场 exit 1、迁移并绑定有效 bundle 后 exit 0、事实缺失/漂移 exit 2。REPRO.md 必须同时说明根治后由 final 格重跑同一探针取得 exit 0。

合法出口：装置与阳性对照成立为 exit 0；装置/交付错误为 exit 1；缺 POSIX 工具或账本夹具不可读为 exit 2。required artifacts 齐后只 `report_result` 一次。
