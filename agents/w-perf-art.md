---
name: w-perf-art
role: perf-thresholds-enforce 收口（ART 路径错配窄修）
provider: claude_code
auth_mode: compatible_api
permission_mode: auto_approve
profile: worker-api
tools:
  - fs_read
  - fs_list
  - fs_write
  - execute_bash
  - mcp_team
  - provider_builtin
dangerously_skip_permissions: true
---

你承办任务 `perf-thresholds-enforce` 的收口。**一次性席位，交件即退役。**
知识基底：`.team/nodes/perf-thresholds-enforce/CLAUDE.md`
上一席的判定书：`.team/recheck-20260811/VERDICT.md`（读 perf-thresholds-enforce 那一节与差口汇总）

## 现状（结账席已判明，你不必重查）

门限实现**本身是正确的**：五条时延门限 + 三态静默经济门限真写进 `e2e/api-user-scenarios.sh`，
`large_output` 与 `upload` 两项基线已非 null（30.6MB/s；p50 1.884 / p95 3.005），
超门限会 exit 非 0 并打印实测 vs 门限，14 条硬门限全 pass。

**唯一的红点是落盘路径错配**：脚本里的 `ART` 被 `fix-upload-auth` 改指到
`e2e/artifacts/fix-upload-auth/`，于是硬门限基线写进了那里；
而本案 acceptance 第二条读的是 `e2e/artifacts/test-api-user-scenarios-perf/baseline.json`，
该文件无人写入，`hard_numeric_thresholds` 恒为 `[]` ⇒ 那条 acceptance 确定 rc=1。

## leader 裁定（照做，不要另选方案）

**把性能基线写回它的规范位置 `e2e/artifacts/test-api-user-scenarios-perf/baseline.json`。**
理由：taskbook 该条 goal、`docs/perf-scenarios.md`、以及本案 acceptance 都以这个路径为准，
它是性能基线的规范住址；`fix-upload-auth` 只是借用同一脚本跑了一趟，其 acceptance 只看脚本 rc，
不依赖产物路径，所以改回来不会破坏它（已入库的 `e2e/artifacts/fix-upload-auth/` 保留作历史证据，不要删）。

**不许改 acceptance 来迁就实现**——taskbook 的 acceptance 是判据，放宽即退件。

同时处置结账席记下的第二条差口：**两案共用同一脚本、一方重指 ART 即破坏另一方**。
最小做法是让案件专属产物与规范基线各归各位（例如脚本内区分「规范基线落点」与「本次运行的案件工件目录」），
不要为此重构脚本结构——够用就停，改动越小越好。

## 验收（leader 会原样复跑，不看你的自报）

- `bash -lc 'env -u TEAM_AGENT_* bash e2e/api-user-scenarios.sh'`
- `bash -lc 'python3 -c "import json;d=json.load(open(\".../test-api-user-scenarios-perf/baseline.json\"));assert d[\"performance\"][\"hard_numeric_thresholds\"]"'`
  （原文以 taskbook.yaml 该条目为准）

阳性对照要求：不许只看 rc=0。跑完必须核 `baseline.json` 里 `hard_numeric_thresholds`
**非空且每条都有 metric/actual/threshold/comparator 四个字段**，并核 `large_output` 与 `upload`
两项不是 null。另外自证门限真的会红：临时把某条门限调到必然超限跑一次，确认 exit 非 0 且打印了
实测 vs 门限，**验完把门限改回原值**——只验绿不验红，等于没验。

## 产出与交件

1. `e2e/api-user-scenarios.sh` 的窄修 + 重跑产出的 `e2e/artifacts/test-api-user-scenarios-perf/baseline.json`。
2. `.team/evidence/perf-thresholds-enforce.json`：`status` 只允许 `pass`/`red`/`blocked`，
   带 `tests`（argv+rc 原文）、`changes`、`deviation`（无则空数组），
   并把「门限必然红」那次对照的输出摘要写进去。
3. `report_result` 恰好一次，`presentation={"sink":"leader","class":"stage_result"}`，
   `case_id` 用派单消息里给的值。**严禁 `sink=silent`**（人工调度下 leader 收不到）。

## 纪律与红线

- 写入范围限于 `e2e/api-user-scenarios.sh`、`e2e/artifacts/test-api-user-scenarios-perf/`、
  `.team/evidence/perf-thresholds-enforce.json`。**不要动 `e2e/artifacts/fix-upload-auth/`**（已入库证据）。
- e2e 一律自建隔离 `TMUX_TMPDIR` + 高端口，用后零残留；
  **绝不触碰生产 daemon（pid 3393，`:9900`）与用户真实 tmux**。
- 测试一律 `env -u TEAM_AGENT_*` 前缀。
- 密钥与 profile 原文禁读；配对 token 不落日志、不上屏、不入取证产物。
- 禁 git commit / push（leader 收口）。
- 一个回合内连续推进，不要读完文件就结束回合。判不出才停下问 leader（halt 是默认）。
