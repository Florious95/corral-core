# successor6 impl — 内容身份与独立槽位真实门

## 输入与范围

必读总任务书、successor5 impl 诊断、原 baseline-bundle impl 任务书、successor5 SDK任务书及 future fresh successor6 repro/test/probe。旧 manifest/IMPL/WT只读 provenance，不得复用为新交付。只在 future final ledger 的 write_paths 内重建 bundle/报告；不改 App/server、旧判据、旧 ledger/attempt。

## 精确交付

开工先走 successor5 SDK fallback，静默生成仅 sdk.dir、0600、未跟踪的 WT local.properties。fresh 交原 impl 全套 `ROUTE.md`、`IMPL.md`、`BUNDLE-MANIFEST.json`、`A2-EQUIVALENCE.md`、`BUILD.md`、`ARCHIVE.md`、`INSTALL.md`、`RETRIEVE.md`。

manifest 的 canonical projection必须重算等于 bundle_id；两个 independent build按固定非 id槽位落盘、文件存在且互异，build_root互异；archive primary/backup仍按 bundle_id内容寻址。不要通过把 build移进 `{bundle_id}/builds` 来凑旧门。若现有工具已交合法 projection，不做投机产品改动，只 fresh 重建并交证据。

机械门依次执行 successor6 projection fixture/真实门、完整 deep 摘要门、successor3 canonical/bypass 防伪门，并锁 manifest hash不变。bundle_id篡改、越界、槽位改名/交换、旧id路径、APK/摘要/签名/报告/archive任一变异必须1或诚实2；不得旁路。测试禁缓存。

只有全部门绿才让 IMPL.md 末行为 `implementation: pass`；SDK/fixture/量具缺失写恢复条件并 exit2。产物齐后只 report_result 一次。
