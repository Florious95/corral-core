# t.baseline-bundle.test — 并行产出根治场景红测设计

背景与必读：读共享 `任务书.md`、repro 产物、CONTRACT、现有 runner/parser 与全部 baseline-bundle 判据；不实现工具。

精确交付：只写 `.team/nodes/baseline-bundle-test/RED.md`。逐项给出真实入口、修前预期红、修后预期绿、命令与无缓存参数，至少覆盖 `BaselineBundleMissingArtifactDeadlockTest`、`BaselineBundleExactRecoveryTest`、`BaselineBundleA2EquivalenceTest`、`BaselineBundleArchiveRestoreTest`、`BaselineBundleMigrationPrecheckTest`；另覆盖运行条目变异、签名不符、backup 同 inode/仍可写、不可安装、envcheck 非 0、B/A>1.10 与用户 gate 缺失。必须把 prelaunch review 的两枚 bypass 纳入永久回归：短 digest+stub manifest 和 60 个空 raw+伪造全 1 JSON 在旧门是绿、在新门必须拒绝。

硬约束：Go 命令写 `-count=1`，Gradle 写 `--rerun-tasks --no-build-cache`；性能测量前逐字写 `sh tools/perfbase/envcheck.sh --gate`；不得写产品/工具实现、不得启动 emulator。

合法出口：RED.md 完整为 exit 0；缺用例/缓存禁令/阳性对照为 exit 1；输入不可读为 exit 2。落盘后只 `report_result` 一次。
