# successor6 test — 固定点复现与投影红绿齿

只写 future test WT 的 `RED.md`，不改实现/判据/旧账。逐例列输入 manifest、唯一变异、canonical 是否重算、两边操作数、期望 exit与精确原因。

必须含固定 token：`SUCCESSOR6_LEGACY_PREFIX_REPRO`、`SUCCESSOR6_CANONICAL_IDENTITY`、`SUCCESSOR6_SLOT_PROJECTION`、`SUCCESSOR6_ARCHIVE_PROJECTION`、`legacy_constraint=1`、`legal_projection=0`、`bundle_id_tamper=1`、`path_traversal=1`、`slot_tamper=1`、`slot_swap=1`、`legacy_scoped=1`、`missing=2`。

合法 successor5 fixture必须先被旧 id-prefix predicate拒绝1，再被新门接受0；路径类齿先重算 bundle_id/archive以隔离槽位判定，bundle_id齿则只改 id以隔离 canonical判定。另保留 SDK fallback四态、required exact/legacy-negative、APK/运行/签名/报告/archive/provenance齿与禁缓存命令。RED.md不冒充真实产品已绿。产物齐后只 report_result 一次。
