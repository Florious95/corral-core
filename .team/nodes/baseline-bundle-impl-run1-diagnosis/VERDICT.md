# baseline-bundle successor2 impl run1 返修归因

## 裁定

结论是：实现尚未完成，同时存在两个独立的判据/事实前置缺失；不是产品代码真实红，也不是 `wt-b2-mainline` 落点错误。

- `M.baseline-bundle.impl` 的 exit 1 是真实交付缺陷：worker 产物 `IMPL.md` 末行是 `implementation: unjudgeable`，不是 `implementation: pass`。更重要的是，`tmp/retrieve.log` 已记录 `manifest bundle_id mismatch`。这是实现确实未满足 canonical manifest/retrieve 契约的证据，不是把期望码改成 1 的洗红。
- `M.baseline-bundle.impl-bypass` 的 exit 2 是诚实不可判：判据在 WT 内寻找 `.team/nodes/baseline-bundle-prelaunch-review/tmp/impl-bypass/...`，但该冻结 fixture 只存在仓根同路径，不存在 `wt-b2-mainline` 内。它没有进入 bypass 的 legacy/hardened 对照，因此不能判为实现绕过失败。
- `M.impl` 继续往下执行后还会遇到第二个事实前置缺失：判据只读取 `app/local.properties` 的 `sdk.dir`，而该文件在 WT 不存在；worker BUILD/IMPL 记录用环境变量发现 SDK。这个差异应修正为一致的、可审计的 SDK 前置后再测。
- `wt-b2-mainline` 本身正确：HEAD=`2263fd95f`，可达 `a538117cc2`、`ef7a02c1d`、`488a1f25b`；实现文件、报告与私有 APK 均落在该 WT。没有证据表明此次是旧 WT 复用或错误 cwd。
- 没有产品真实红证据：改动集中在 perfbase/bundle 工具和账本产物，未执行 APK 安装、真机或产品运行判定。

## 关键实现根因

`baseline_bundle.py` 在 A2 建包时先用空的 `apk_relpath` 计算一次 bundle id，写入报告哈希后再次计算并移动到新 bundle 目录；移动后又把两个 `independent_builds[].apk_relpath` 改成最终目录，却没有第三次按最终 projection 重算 bundle id。验收脚本随后以最终 manifest projection 重算 canonical id，因而 retrieve 和 `M.impl` 都会拒绝当前 manifest。当前 `BUNDLE-MANIFEST.json`、primary/backup 与两份 build APK 可作为现场证据，但不能作为已通过的 Bundle。

## replay 边界

`att-t.baseline-bundle.impl-seq1-t1787601574226` 是恢复 driver 在 durable result 已存在后重新 dispatch 的 attempt；P0 日志证明它与原 revision-2 case 共用 case_id，先重派后消费旧结果。因此不得把 sampler-dev 的重放、当前 `wt-b2-mainline` 的最终文件状态或 probe/test 的 revision-3/4 writeback 当作本次新的实现证据。该 failed_retryable attempt、旧 delivery、worker 产物和当前 WT 现场必须全部保留。

## 最小返修清单

1. 保留本次 attempt，不清理、不覆盖、不把 task 的 `planned` 或 durable result 改写成成功。待同 case 的重放完全停止并由编排层建立新 case 后，再派唯一一个新的 impl case；不能在原 case 上“补报成功”。
2. 修正 bundle canonicalization：最终 projection（含 report hashes、最终 `independent_builds[].apk_relpath`、archive 绑定字段）冻结后只计算一次 bundle id，再创建/移动内容寻址目录；生成新 manifest，并 fresh 验证 retrieve 从 backup 恢复成功、摘要/路径/manifest 三者相等。
3. 解决 SDK 前置契约：让判据使用明确允许且不含凭据的 SDK 来源，或在该 WT 提供有 provenance 的 `app/local.properties`；worker 的环境变量成功记录不能替代判据自身可读的前置。
4. 让冻结 bypass fixture 在验收 WT 中可寻址且不可变（优先纳入基线可达的只读 fixture，并保留四个固定 SHA；不要用调用方可替换的 `FIXTURE_ROOT`）。当前仓根 fixture 内容可作为 hash-checked 输入沿用，但当前 exit 2 不能当成 bypass 结论。
5. 新 case 交货后 fresh 重跑 `M.baseline-bundle.impl` 与 `M.baseline-bundle.impl-bypass`；两门都必须 exit 0，随后才允许 verify 继续。不得复用本次 acceptance failure 作为新证据。

## 可沿用与必须重做

可沿用但仅作为历史/开发输入：`ROUTE.md` 的 `blocked_missing_baseline`→A2 裁定；A2-EQUIVALENCE/BUILD/ARCHIVE/INSTALL/RETRIEVE 文档的事实索引；已生成两份 A2 APK、primary/backup 和 focused/runner 自测日志（重新核 SHA、source、signer、inode 后才可进入新 manifest）；上游 repro/probe/test 的已写回结果也可保留为历史依赖，但不能宣称是恢复重放产生的新证据。

必须新 case fresh 重做：worker report/result envelope、`IMPL.md` 最终 pass 结论、canonical `BUNDLE-MANIFEST.json`、archive/retrieve 交叉校验、SDK 前置验证、两枚 impl acceptance（尤其 bypass 对照）以及后续 verify 对该新 bundle 的独立验收。当前 failed attempt、当前 manifest 与旧 replay 不得直接转绿。

verdict: refutes
