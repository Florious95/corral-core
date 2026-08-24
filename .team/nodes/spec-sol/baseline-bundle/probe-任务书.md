# t.baseline-bundle.probe — 并行设计等价、归档与迁移破坏齿

背景与必读：读共享 `任务书.md`、repro、CONTRACT、现有 perf-regress 任务书/判据和已知 apksigner 归档；只写探针报告。

精确交付：`.team/nodes/baseline-bundle-probe/PROBE.md`。列清 `source_tree_sha256`、`normalized_runtime_sha256`、APK SHA-256/md5/size、`signer_certificate_sha256`、primary/backup inode/写位、恢复摘要、实现入口+runner SHA、raw batch/A2/B 身份头、安装 rc、envcheck rc、旧 ledger id/revision/lease pid/pidfile pid/active states 的两边操作数。分别说明 `recover_exact_artifact` 与 `rebaseline_with_equivalence_proof` 的可证伪条件，并审计真实链探针为何旧现场 exit 1、根治迁移后 exit 0、中间漂移 exit 2。

硬约束：破坏齿只写性质，由最终独立判者选址；至少包括改一个 classes.dex 字节、换 signer 摘要、把 backup 变同 inode、篡改 lease pid、把一格 B/A 改到 1.1001。齿必须红、还原必须绿且不改真实私有 APK。

合法出口：操作数与齿齐全为 exit 0；相关性冒充等价或漏安全边界为 exit 1；必需输入不可读为 exit 2。只 `report_result` 一次。
