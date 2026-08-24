# successor5 probe — 独立重算安全 fallback 与真实门

只写 probe WT 的 `PROBE.md`；旧 PROBE/source_tree_sha256 门只作失败 provenance。任何 Gradle/SDK 操作前运行 successor5 SDK gate；不读取/输出值，目标只含 sdk.dir、0600、未跟踪。

独立重算真实 bundle_id、independent_builds、apk_relpath、fixed control-contract、canonical 红绿、controlled provenance 与 SDK fallback 两边布尔操作数。必须含：

- `SUCCESSOR5_REQUIRED_EXACT`、`SUCCESSOR5_LEGACY_NEGATIVE`
- `SUCCESSOR5_SDK_SOURCE_WHITELIST`
- `SUCCESSOR5_SDK_FALLBACK_NO_OUTPUT`
- `SUCCESSOR5_SDK_EXTRA_KEY_REJECTED`
- `SUCCESSOR5_SDK_NOT_TRACKED`
- `SUCCESSOR5_CANONICAL_REAL`、`SUCCESSOR5_FIXED_FIXTURE`
- `probe_required=successor5_probe`、`legacy_probe=absent`、`source_tree_sha256=not_required`

证明 probe required/mechanical 只为 successor5 probe 且 WT=`wt-owl-audit`；旧 id/argv/四态绑定回流1，账本或量具缺失2。SDK fallback 的额外/重复键与无效目录2、被跟踪1、成功0且无输出；不得泄露路径。PROBE.md 不冒充实现绿。产物齐后只 report_result 一次。
