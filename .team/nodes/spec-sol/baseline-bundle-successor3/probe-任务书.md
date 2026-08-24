# successor3 probe — 三项返修独立操作数与破坏齿

只写 future probe 格的 `PROBE.md`；旧 PROBE/replay 只作 provenance。必须含固定 token：

- `SUCCESSOR3_CANONICAL_PROJECTION_RED_GREEN`
- `SUCCESSOR3_SDK_PREFLIGHT_NO_VALUE_DISCLOSURE`
- `SUCCESSOR3_BYPASS_FIXED_PROVENANCE`
- `SUCCESSOR3_IMPL_UNJUDGEABLE_REJECTED`
- `bundle_id`、`independent_builds`、`apk_relpath`、`app/local.properties`、`apksigner`、`aapt`
- `control-contract.json`、`missing_fixture=2`、`forged_fixture=1`

齿的性质：真实 retrieve 上 stale `apk_relpath` 红/final path 绿；只改 implementation provenance 后底层 rc2 精确 bundle mismatch，hardened 只把该伪造形状转为1；measure 保持合法 root、非空 raw 与其它身份相同，只改声明 runner SHA 后精确 runner provenance mismatch；fixture 缺失/漂移2；IMPL unjudgeable=1。记录两边操作数，排除 root/empty-raw 旁路。

本格 grep gate只验交付形状；实现后由独立 verify/final 选具体齿位并执行真实门。不改实现/判据/旧账，不读敏感值。产物齐后只 report_result 一次。
