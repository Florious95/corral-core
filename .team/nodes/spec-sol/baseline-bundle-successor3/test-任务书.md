# successor3 test — 三项返修独立红测设计

只写 future test 格的 `RED.md`，逐例列前置、动作、两边操作数、期望 exit、旧红/新绿证据；旧 RED.md 只作索引。必须含固定 token：

- `SUCCESSOR3_CANONICAL_PROJECTION_RED_GREEN`
- `SUCCESSOR3_SDK_PREFLIGHT_NO_VALUE_DISCLOSURE`
- `SUCCESSOR3_BYPASS_FIXED_PROVENANCE`
- `SUCCESSOR3_IMPL_UNJUDGEABLE_REJECTED`
- `missing_local_properties=2`、`missing_fixture=2`、`forged_fixture=1`、`unjudgeable_report=1`

canonical 用例必须调用真实 retrieve，证明 stale path 精确 `manifest bundle_id mismatch`、final path 取回绿；SDK 用例证明缺失/不可执行2且输出无值；fixture 用例证明 control contract 缺失/漂移2、canonical 底层 rc2 精确原因被 hardened 齿转为1、runner provenance 单变量伪造1，并排除 root/empty-raw 旁路；IMPL unjudgeable 为1。

本格 grep gate只验交付形状，RED.md 不等于事实通过；verify/final 才执行真实门。不改实现/判据/旧账，不读凭据。测试禁缓存。产物齐后只 report_result 一次。
