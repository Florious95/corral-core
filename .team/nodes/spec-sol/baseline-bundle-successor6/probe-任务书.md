# successor6 probe — 独立内容身份与槽位判者

只写 future probe WT 的 `PROBE.md`；旧 PROBE/manifest只作 provenance。独立重算 canonical JSON字节与 SHA、两个 exact slot、路径安全性、build_root独立、archive id模板及 manifest前后hash，记录两边操作数但不输出SDK值或APK内容。

必须含：`SUCCESSOR6_LEGACY_PREFIX_REPRO`、`SUCCESSOR6_CANONICAL_IDENTITY`、`SUCCESSOR6_SLOT_PROJECTION`、`SUCCESSOR6_ARCHIVE_PROJECTION`、`projection_keys=source,runtime,artifact,build,equivalence,implementation`、`slot0=build-1`、`slot1=build-2`、`manifest_stable=true`。

破坏齿必须区分：id篡改由canonical拒绝；越界/槽位改名/交换/旧id-scoped路径在重新canonicalize后仍由slot拒绝；fixture/量具缺失2。另核 successor6 impl wrapper不调用 `baseline-bundle-impl.sh`/successor3 impl wrapper，且仍调用SDK fallback、deep、canonical real fixture、controlled bypass。产物齐后只 report_result 一次。
