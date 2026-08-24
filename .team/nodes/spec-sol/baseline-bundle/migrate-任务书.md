# t.baseline-bundle.migrate — 机械前置后停止旧 park 并迁移到 bundle 基线

背景与必读：读共享任务书、bundle verify、USER-GATE、当前 perf-regress 源/JSON/lease/pidfile。实际目标是主工作区 `/Volumes/nvme/Projects/远程Agent安卓`，不是本格 worktree 副本。

精确交付：`.team/nodes/baseline-bundle-migrate/{PRECHECK.json,MIGRATION.json,MIGRATION.md,ledgerdsl-plan.log,ledgerdsl-apply.log}`；最小修改主工作区 `.team/ledgers/src/perf-regress-v1.py` 与编译 JSON，使旧链 `desired_state="paused"` 并引用迁移报告，不删历史 attempt；保留变更前 JSON 到本格节点目录。

不可逆动作前置：逐项核 id=`ledger.perf-regress.v1`、当前 revision、impl=`failed_retryable`、末行 `measurement: unjudgeable`、lease pid=pidfile pid、窄字段 comm=`ledger-run`、没有 active task 状态、bundle verify pass、user gate pass。全部为真才 TERM 精确 PID；有限等待 PID 消失后才处理 lease 并运行 ledgerdsl plan/apply。任一不符不发信号，exit 2。禁止 `pkill`/`killall`、KILL 起步、collect、重派或删历史。

合法出口：精确 PID 停止、lease 收口、源/JSON paused、备份与审计齐为 exit 0；脚本越权/历史丢失/杀错对象为 exit 1；现场漂移或无法证明安全为 exit 2。只 `report_result` 一次。

