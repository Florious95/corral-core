# successor4 test — required-list 回流齿与 SDK 四态

## 输入与范围

只写 test WT 的 `.team/nodes/baseline-bundle-test/RED.md`；不改实现、判据、旧账。先按总任务书安全生成非版本化 `app/local.properties` 并运行静默 SDK 前置；环境缺失2，误提交1，不打印值、不读凭据。

## 输出结构与验收

除 successor3 的 canonical/SDK/fixed-fixture/IMPL-unjudgeable 独立红测外，新增结构破坏齿并固定写入：

- `SUCCESSOR4_REQUIRED_EXACT`
- `SUCCESSOR4_LEGACY_NEGATIVE`
- `SUCCESSOR4_SDK_PREFLIGHT_NO_VALUE_DISCLOSURE`
- `impl_required=successor4_impl,successor4_bypass`
- `probe_required=successor4_probe`
- `legacy_impl_bypass=absent`
- `legacy_probe=absent`
- `missing_local_properties=2`

用例必须机械修改一份账本夹具：插入 legacy impl-bypass、插入 legacy probe、交换/增加 required、把 mechanical argv 指回旧脚本时均 exit1；精确 successor4 集合为0；候选账本缺失/不可读为2。不能把真实账本改坏。保留 `--rerun-tasks --no-build-cache` 与 `-count=1`。RED.md 只证明设计交付，verify/final 才证明产品事实。

产物齐后只 report_result 一次。
