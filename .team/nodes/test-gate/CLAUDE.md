# 知识基底 · test-gate（系统编译产物）

## 0. 任务（taskbook.yaml#test-gate）
- 目标：全量回归门 `tools/gate/run.sh`：
  1. 并行跑三面：server 全量（`go test ./... -count=1` + `go vet ./...` + `gofmt -l .` 空输出）、app 全量（`./gradlew -q test`，`:terminal` 模块存在则含其测试）、archwiki（`python3 tools/archwiki/build_wiki.py --check`，脚本存在才跑，缺席跳过并在报告标注 skipped）。
  2. 输出 `tools/gate/gate-report.json`：每套件 {name, cases, failures:[{test, category: product|harness|baseline|flaky|unclassified}], duration}；总结论 pass/fail。
  3. 基线棘轮 `tools/gate/baseline.json`：记录每套件用例数；本次 < 基线即红（防"删测试变绿"）；增加则更新基线（棘轮上行自动，下行必须 `--accept-baseline` 显式旗标+理由参数，写进报告）。
  4. `--self-test`：用 fixture 验证门本身——含"用例数下降必须红""某套件失败必须整体红""空扫描（0 用例）必须红"三条红测。
- 验收（exit 0 = 过）：`bash -lc 'bash /Volumes/nvme/Projects/远程Agent安卓/tools/gate/run.sh --self-test'`
- 写范围：`tools/gate/`。红线：门只读产品代码，绝不修改 server/ app/；报告必须机器可读。

## 1. 架构基
- 语言：bash 骨架 + 内嵌 python3 做 JSON 汇总（或纯 python3 入口 + run.sh 薄壳，自选）；零第三方依赖。
- Go 用例数来源：`go test -json` 流式解析（Action=pass/fail 的 Test 事件计数）；gradle 用例数来源：`build/test-results/**/TEST-*.xml` 的 tests 属性求和。
- 并行：三面各自后台跑，全部收齐再汇总（wall-clock=最慢一面）。
- 归因字段默认 unclassified，四归因是给人/上游席位填的模板位，门不自动猜。

## 2. 现场基
- server 现有 tsnetd(10 测)+discovery(13 测) 已过；app 尚无测试类（gradle test 0 用例——所以"0 用例即红"只对 server 面启用，app 面在 baseline 里以当前实际值起步，注释说明）；archwiki 在途（缺席跳过路径现在就会用到）。
- 构建命令一律 `bash -lc`（JAVA_HOME 在 profile）；测试净化前缀 `env -u TEAM_AGENT_WORKSPACE -u TEAM_AGENT_ID -u TEAM_AGENT_OWNER_TEAM_ID -u TEAM_AGENT_AGENT_ID`。
- 并行席位正在改 server/，跑全量时个别包瞬态红属可能——门如实报，不重试掩盖（归因是上游的事）。

## 3. 需求基（指针）
1. requirement-base/entries/013-测试体系与回归门禁.md（本任务的裁定原文，权威）
2. requirement-base/entries/010-最终验收与运行方式.md

## 4. 经验基
- 验证不得自证：--self-test 的 fixture 手工构造，不用门自己生成。
- 静默失效猎杀：跳过的套件必须在报告显式 skipped，不得静默当 pass。
- 注释红线照旧。

## 5. 沉淀区（唯一允许你追加写入的区域）
