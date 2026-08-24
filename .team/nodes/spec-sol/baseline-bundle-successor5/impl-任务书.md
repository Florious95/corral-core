# successor5 impl — 安全 SDK fallback 与真实 Baseline Bundle 门

## 输入与范围

必读本目录总任务书、原 baseline-bundle impl、successor3 bootstrap impl、successor4 impl 与 fresh successor5 repro/test/probe。旧账/WT/IMPL/manifest/result 只读。只在 ledger write_paths 内修 bundle 工具与 focused 测试，不改 App/server。

任何 Gradle/SDK 操作前先运行 successor5 SDK gate：有效环境优先，否则由 gate 从主仓根白名单解析唯一 sdk.dir，在当前 WT 只生成单行、0600、未跟踪 `app/local.properties`。不得手工复制根文件、输出路径/内容或提交目标。额外/重复/缺失键、无效目录、量具缺失均2；被 Git 跟踪为1。

## 输出与机械验收

fresh 交 `ROUTE.md`、`IMPL.md`、`BUNDLE-MANIFEST.json`、`INSTALL.md`、`RETRIEVE.md`。最终 projection 冻结后计算 canonical bundle_id；真实 create/retrieve/backup restore 一致。`IMPL.md` 仅在 successor5 SDK+regression+structure、bootstrap canonical/impl 真实门与 controlled bypass 全绿后末行为 `implementation: pass`。

required 只允许 successor5 impl 与 successor5 id 绑定的已跟踪 bootstrap bypass，禁止 legacy/successor4 required。fixture 缺失/漂移2，单变量伪造1，合法绿控0。测试禁缓存；产物齐后只 report_result 一次。
