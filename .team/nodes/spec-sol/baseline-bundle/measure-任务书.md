# t.baseline-bundle.measure — 用已验证 A/A2 对新候选做 fresh A/B/A/B

背景与必读：verify、user gate、migrate 必须先绿。读 bundle manifest、CONTRACT、runner/parser 和 perf-regress 任务书；不得复用 r13 raw 或旧 B。

精确交付：`.team/nodes/baseline-bundle-measure/{MEASURE.md,perf-ab-bundle.json,raw/order.tsv,raw/A/,raw/B/,PRE-MEASURE.json}`。测量前重新执行 bundle retrieve/摘要/signer/package/安装恢复门，再执行 `sh tools/perfbase/envcheck.sh --gate`；A 为已激活 bundle，B 为新候选 `daca6170aa58a8054aa3d20537a61e64` 或具有新源码变化和新 md5 的后继候选。B 的真实 APK 固定留在 gitignored `.team/private/baseline-candidates/<sha256>/candidate.apk` 供判据复核，不能只在 JSON 自报摘要。

硬约束：同批 A/B/A/B、三夹具四段、每包每格 n>=10、raw 全留、nearest-rank p50/p95、每格 B/A<=1.10；同一 revision+md5 只测一批；不得调阈值、删 outlier、重跑旧 B 抽绿。自建 emulator 绑定唯一 qemu PID+adb serial，trap 只清理本次 PID。

raw 证据契约：`order.tsv` 必须先写 `# key=value` 元数据，包含 `batch_id`、`runner_sha256`、`baseline_bundle_id`、A2/B 的 SHA-256+md5 与 B revision；随后每夹具严格按 `fixture<TAB>sequence<TAB>A`、紧接同 sequence 的 B，连续序号从 1 开始。每个 `<fixture>-NN.log` 非空，顶部重复同一组元数据并加 fixture/sequence/package；正文必须有同一 `open_id` 的 `tap→route_enter→first_frame_recv→first_draw` 唯一完整严格单调链。runner、PRE-MEASURE、order、raw、结果 JSON 与两份真实 APK 的摘要必须全等。结果每段须写 raw A/B 数组、n、nearest-rank p50/p95 与 `ratio_b_over_a`；机械门从 raw 重建并逐项对账，禁止信任调用方 arrays 或 `order.valid=true`。

破坏齿：永久运行 `.team/ledgers/acceptance/baseline-bundle-bypass-probes.sh`，冻结的 60 个空 log+全 1 JSON 旧门夹具须保持 legacy exit 0、当前门必须 exit 1 或 2。空 raw、缺链、缺顺序、APK/批次/runner 身份不足均 exit 2；有效样本任一 nearest-rank 比例超 1.10 才 exit 1。

合法出口：身份/环境/样本完整且全格<=1.10 为 exit 0；有效样本任一超 1.10 为 exit 1；bundle/env/adb/order/样本不可判为 exit 2。MEASURE.md 末行 `measurement: pass|fail|unjudgeable`，只 `report_result` 一次。
