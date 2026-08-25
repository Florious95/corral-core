# successor7 migrate 任务书

目标：在 fresh verify 与同 bundle 用户 gate 全绿后，安全停止旧 `ledger.perf-regress.v1` 的无限 park，保留所有 attempts。

输入：上游证据、旧 ledger 源/JSON/lease/pidfile 与 `.team/nodes/spec-sol/baseline-bundle/migrate-任务书.md`。

交付：`.team/nodes/baseline-bundle-migrate/{PRECHECK.json,MIGRATION.json,MIGRATION.md,ledgerdsl-plan.log,ledgerdsl-apply.log}`；只将旧链设为 paused 并引用迁移报告，不清 attempt、不重派。

验收：`baseline-bundle-successor7-migrate.sh` 先核 ledger id/revision/state、末行 unjudgeable、lease=pidfile、comm=`ledger-run`、无 active 任务与两上游 pass，再 TERM 精确 PID、有限等待、ledgerdsl plan/apply。安全迁移为0；杀错/丢历史为1；任一现场漂移不发信号且为2。禁止 pkill/killall/KILL 起步。
