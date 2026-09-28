# 独立场景红证据（消息触发核验）

run34146253960 / job101818819725 / head29eee88e1f0e7fedfbc1fe7425859a0d22506087，artifact10027772328。测试源码由test-only分支提供，产品仍为冻结base。实际具名测试已执行，20秒仍未断连接；同时出现race，不能只记预期断连红而隐去race。

```text
2026-09-07T17:07:54.3929220Z === RUN   TestOverflowDisconnectReplayStaticOracle
2026-09-07T17:07:55.9650680Z WARNING: DATA RACE
2026-09-07T17:07:55.9650900Z Read at 0x00c0000e4348 by goroutine 53:
2026-09-07T17:07:55.9653970Z       /Users/runner/work/corral-serve/corral-serve/internal/api/ws_conn.go:245 +0x58
2026-09-07T17:07:55.9678680Z       /Users/runner/work/corral-serve/corral-serve/internal/api/sendq_metrics.go:86 +0xe0
2026-09-07T17:08:14.5213980Z     overflow_recovery_test.go:257: slow connection remained open: failed to get reader: context deadline exceeded
2026-09-07T17:08:14.5336930Z     testing.go:1712: race detected during execution of test
2026-09-07T17:08:14.5339380Z --- FAIL: TestOverflowDisconnectReplayStaticOracle (20.14s)
2026-09-07T17:08:15.7038730Z Artifact perf16-recovery-34146253960.zip successfully finalized. Artifact ID 10027772328
2026-09-07T17:08:15.7041330Z Artifact perf16-recovery-34146253960 has been successfully uploaded! Final size is 1021 bytes. Artifact ID is 10027772328
```
