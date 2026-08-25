# successor7 fresh measure 任务书

目标：对已验证 A2 bundle 与新 B 做唯一 fresh 性能实验，不复用 r13/raw，不重跑同 B 抽绿。

输入：APPARATUS/VERIFY/USER/MIGRATION、bundle manifest/双备份、CONTRACT、runner/parser 与真实 A2/B APK。Gradle 前使用已冻结的安全 SDK fallback；测量前必须 `sh tools/perfbase/envcheck.sh --gate`=0。

交付：`.team/nodes/baseline-bundle-measure/{MEASURE.md,perf-ab-bundle.json,PRE-MEASURE.json,raw/order.tsv,raw/A/,raw/B/}`；raw/order/结果与两 APK 的 batch、runner、bundle、revision、sha256/md5 必须全等。

验收：`baseline-bundle-successor7-measure.sh` 合取 SDK、permanent fixture 与 raw 独立重算门。三夹具、A/B/A/B 四段、每段 n>=10，每样本同 open_id 四事件唯一严格单调；nearest-rank p50/p95 与所有 B/A<=1.10 才为0。有效样本任一超 1.10 为1；env/adb/身份/order/raw 缺事实为2。Go/Gradle 禁缓存，自建 emulator 只清本次绑定 PID。
