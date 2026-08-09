# 知识基底 · test-aggregate-status（012 聚合与 server diff 零覆盖补齐）

## 0. 任务（taskbook.yaml#test-aggregate-status）
- 总图纸：docs/scenario-coverage.md D-5/D-6/G-6 三行与 §12 P0-5 行——先读。
- 目标：**仅新增 _test.go** 补齐三块零覆盖：
  1. statePriority/aggregateState/wsAggregate：blocked>done>working>idle 全序、unknown 剔除不计入、全 unknown 才聚合 unknown、空工作区——每条规则一个具名用例（012 与 docs/protocol.md 聚合节是判定权威）；
  2. modelSnapshot.diff()（≈90 行生产代码目前仅"加一条"1 例擦到）：removed/changed/跨 cwd 迁移/噪声抑制（无变化不发帧）；
  3. discovery 失败时 API 行为：api 测试假件 scriptedDiscoverer 的 err 字段从未被赋值（建好从未用过的死接缝）——激活它，断言扫描失败时 listing/delta 的降级行为。
- 验收：`bash -lc 'cd server && go test ./internal/api/...'`，新增 ≥12 具名用例。
- 红线：**只写测试，不改生产代码**。若测出实现与 012/protocol.md 不符的真缺陷：立即停在该处，把红测保留为 t.Skip+缺陷注释，报 leader 立案，不顺手修。

## 1. 现场基
- 聚合与 diff 实现都在 server/internal/api/（grep statePriority / aggregateState / func.*diff）。
- 既有测试形状参考 TestListReturnsFullListing（含"全 unknown"一例，勿重复）。
- **同包并行警示**：feat-input-keys-server 席位同期会动本包 ws_handler.go（生产文件）。你只新增 _test.go，文件不撞；若某时刻 go test 编译失败且报错在你没写的文件，那是别人的半成品——报 leader，不修不动它。

## 2. 需求基（指针）
1. requirement-base/entries/012（聚合规则权威）+ docs/protocol.md 聚合节
2. requirement-base/entries/013（测试体系：具名用例、用例数棘轮、失败四归因）

## 3. 经验基
- 表驱动测试优先；纯逻辑测试不起 tmux（能内存构造 model 就内存构造）；需 tmux 时只用自建隔离 socket+净化前缀 env -u TEAM_AGENT_*；交件前 tools/gate/run.sh 全量门自查。
