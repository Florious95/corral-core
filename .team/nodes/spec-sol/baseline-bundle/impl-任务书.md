# t.baseline-bundle.impl — 实现 Baseline Bundle、A2 等价与消费工具

背景与必读：读共享 `任务书.md`、repro、CONTRACT、test/probe（若并行尚未落盘则不等待）、现有 `run-input-ab.sh`/`parse-input-ab.py`、perf-regress 判据。先重跑 repro，确认当前红后才改工具。

精确交付路径：

- `tools/perfbase/baseline-bundle.sh`、`baseline_bundle.py`、`test-baseline-bundle.sh`、`migrate-perf-regress.sh`；
- 必要时最小修改 `run-input-ab.sh`、`parse-input-ab.py`，只把 A 身份从“唯一旧 md5”升级为“已验证 bundle”，不得改三夹具、四段、n、nearest-rank 或 1.10；
- `.gitignore` 仅追加 `.team/private/baseline-vault/`、`.team/private/baseline-backup/` 与 `.team/private/baseline-candidates/`；
- `.team/baseline-bundles/SCHEMA.md`；
- `.team/nodes/baseline-bundle-impl/{ROUTE.md,IMPL.md,A2-EQUIVALENCE.md,BUILD.md,ARCHIVE.md,INSTALL.md,RETRIEVE.md,BUNDLE-MANIFEST.json}`。

实现要求：先查获准来源；精确 A 三身份全中走 exact，否则 ROUTE.md 诚实写 `legacy_status: blocked_missing_baseline` 并走 A2。A2 两次独立干净构建必须无缓存，并把两份真实 APK 留在 `.team/private/baseline-vault/<bundle_id>/builds/` 供判据重算；不能只写报告。归一化算法、manifest 字段、内容寻址、拒覆盖、primary/backup 非链接非同 inode、去掉全部写位并写 `sealed=true`、从 backup 真复制到新路径复核均按共享任务书。fixture 测试必须逐字输出 `BASELINE_BUNDLE_EVIDENCE missing_route=true exact_route=true a2_equivalence=true runtime_mutation=true archive_restore=true migration_precheck=true`。

机械绑定：判据固定从自己的 git worktree 根读取，不接受 `BASELINE_BUNDLE_FIXTURE_ROOT`、manifest root、archive root 等调用方替换；聚焦测试自行把临时件固定写到本格 node 的 `tmp/`。manifest 的 `implementation` 必须记录 `provenance_base=a538117cc2e9832c88754ccfa9d6f9becb6a91b0`，实际工作树 HEAD 必须是该固定基点的后代；还须记录四个真实入口 `baseline-bundle.sh`、`baseline_bundle.py`、`migrate-perf-regress.sh`、`test-baseline-bundle.sh` 与 runner 的仓内相对路径和 64 位 SHA-256。判据自行核 git frozen source closure、APK SHA/md5/size、ZIP 归一化、entry_count、aapt 包/version、apksigner 证书、Gradle wrapper/build-tools、报告摘要和两份 inode，不以工具的 `verify` 自报代替。

破坏齿：实现完成前后都运行 `.team/ledgers/acceptance/baseline-bundle-bypass-probes.sh`。冻结的一字符 digest+stub 工具旧门夹具必须保持 legacy exit 0，而当前门必须 exit 1 或 2；把两边 rc 写入 IMPL.md。删齿、改 fixture 摘要或让新门接收调用方 root 均为失败。

硬约束：实际 APK 只进 gitignored 私有目录，不出现在提交、日志正文或 report_result；不读/复制 keystore 私钥与口令，只读 apksigner 输出的公证书摘要；不改 App/server 产品码。缺旧 A 不是重试失败；缺构建工具/无法形成两次等价证据才 exit 2。

合法出口：完整工具、bundle、报告与 fixture 门绿为 exit 0；算法/身份/归档/测试不满足为 exit 1；SDK/Gradle/apksigner/授权来源不可用为 exit 2。无论哪一态都先落 ROUTE.md 与 IMPL.md，IMPL.md 末行固定 `implementation: pass|fail|unjudgeable`；不可判不要求伪造 manifest。只 `report_result` 一次。
