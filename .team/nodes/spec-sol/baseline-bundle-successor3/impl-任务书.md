# successor3 impl — canonical、SDK 与 controlled bypass

## 背景与必读

必读本目录总任务书、原 `.team/nodes/spec-sol/baseline-bundle/impl-任务书.md`、impl run1 诊断、fresh successor3 repro/test/probe。旧 impl attempt/manifest/WT 只读，不是新证据。

## 精确交付

只在 future final ledger 的既定 `write_paths` 内修 bundle 工具/focused 测试并 fresh 重建原 impl 全套报告。最终 projection（含报告摘要与最终 `independent_builds[].apk_relpath`）冻结后计算 canonical `bundle_id`，真实 create/retrieve/backup restore 必须一致。focused 测试保留：

```text
BASELINE_BUNDLE_SUCCESSOR3_EVIDENCE canonical_projection_red_green=true
```

机械门另行通过真实 `baseline_bundle.py retrieve` 执行 stale-path red/final-path green；不得用测试内自写 hash 代替。

## 硬约束与合法出口

- `app/local.properties` 仅核存在/可读/非空、SDK 目录及 `apksigner`/`aapt` 可执行；不得输出值。缺失或工具不可执行 exit 2。
- 固定 fixture 只认 bootstrap commit 中的 `control-contract.json` 及内置摘要；缺失/漂移 2，不接受 `FIXTURE_ROOT`。
- canonical provenance 伪造必须由真实 retrieve 以底层 rc2 精确报 `manifest bundle_id mismatch`，hardened 齿只把这一伪造形状归类为 1；measure runner SHA 伪造必须在合法 root、非空 raw 的绿色控制旁精确判 1。
- 不改 App/server/旧账，不复用旧 result，不改 1.10；Gradle `--rerun-tasks --no-build-cache`，Go `-count=1`；不读凭据、不公开分发私有 APK。

全部真实门闭合才让 `IMPL.md` 末行为 `implementation: pass`。末行 unjudgeable/其它值为产品交付 1；SDK/fixture/入口缺失为 2 并写恢复条件。产物齐后只 report_result 一次。
