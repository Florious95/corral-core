# successor4 impl — 真实门、required 精确集合与 SDK 前置

## 输入与范围

必读本目录总任务书、原 baseline-bundle impl 任务书、successor3 bootstrap impl 任务书、fresh successor4 repro/test/probe 与 run1 诊断。旧四本账、旧 WT、旧 IMPL/manifest/result 只读；不改 App/server。

开工先按总任务书从 `ANDROID_SDK_ROOT`/`ANDROID_HOME` 无输出生成本 WT 的非版本化 `app/local.properties`，确认 `sdk.dir` 目录与 `apksigner`/`aapt` 可用。缺失写恢复条件并 exit 2；不得打印路径/文件内容、不得提交、不得读取禁读凭据。

## 输出与机械验收

在账本既定 write_paths 内修 canonicalization、真实 bundle/retrieve/archive 流程与 focused 测试，fresh 交 `ROUTE.md`、`IMPL.md`、`BUNDLE-MANIFEST.json`、`INSTALL.md`、`RETRIEVE.md`。最终 path projection 冻结后计算 canonical bundle_id；真实 create/retrieve/backup restore 一致。`IMPL.md` 仅在全部真实门绿后末行为 `implementation: pass`，unjudgeable/其它值为1。

required 必须精确为 successor4 impl+bootstrap controlled bypass 两项；不得出现 `M.baseline-bundle.impl-bypass`。`baseline-bundle-successor4-impl.sh` 组合 successor4 SDK/structure 与 bootstrap canonical/impl 真实门；`baseline-bundle-successor3-bypass.sh` 保留固定 provenance 控制变量齿。fixture 缺失/漂移2，伪造1，合法绿控0。测试禁缓存。

产物齐后只 report_result 一次。
