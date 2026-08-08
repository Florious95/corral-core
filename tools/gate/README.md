# tools/gate —— 全量回归门

一键并行三面回归 + 机器可读报告 + 用例数棘轮（对应需求 013「回归门禁」）。

```bash
bash tools/gate/run.sh                        # 全量门：并行 server/app/archwiki → gate-report.json
bash tools/gate/run.sh --self-test            # 用手工 fixture 验证门本身（含三条红测）
bash tools/gate/run.sh --accept-baseline=<理由>  # 显式接受用例数下行（棘轮下行必须此旗标）
```

退出码：`0`=过，`1`=红，`2`=参数错误。

## 产出

- `gate-report.json` —— 每套件 `{name, cases, failures:[{test, category, ...}], duration}`，
  总结论 `pass`/`fail`，外加 `issues[]` 与 `summary`。机器可读，判读以 JSON 为准。
- `baseline.json` —— 每套件用例数棘轮：本次 < 基线即红（防删测试变绿）；增加自动上行更新。
- `last-run/*.json` + `*.log` —— 三面原始结果与日志（已 gitignore，排查用）。

## 失败四归因（模板位，门不自动猜）

`failures[].category` 默认为 `unclassified`。需要归因时由人/上游席位在
`gate-report.json` 中直接改该字段为四类之一，下一轮运行会自动 **carry-over** 保留
（按「套件名+测试名」配对，`unclassified` 才覆盖）。四类：

- `product`    —— 产品代码缺陷（需修复）。
- `harness`    —— 门/工具链自身问题（需修门）。
- `baseline`   —— 既有失败，非本轮新增回归（标尺=0 新回归）。
- `flaky`      —— 复跑可通过的抖动。

> 判错归因比失败本身危害更大（需求 013）：门绝不自动猜归因。

## 三面行为

| 面 | 命令 | 用例数来源 | 空扫描(0 用例) |
|----|------|-----------|----------------|
| server | `go test ./... -count=1 -json` + `go vet ./...` + `gofmt -l .` | `go test -json` 的 Test 事件（pass/fail）计数 | **红**（min_cases=1） |
| app | `./gradlew -q test` | `build/test-results/**/TEST-*.xml` 的 tests 属性求和 | 起步基线为 0（当前 app 无测试类），不判红 |
| archwiki | `python3 tools/archwiki/build_wiki.py --check` | 存在才跑；缺席显式 `skipped` 标注 | — |

app 面基线以当前实际值起步（知识基底 §2 现场基）：棘轮自动上行，后续只增不减。
`:terminal` 等后续模块若被 include，其测试结果自动计入。注意 `./gradlew test` 会同时跑
`testDebugUnitTest` 与 `testReleaseUnitTest` 两个变体，两套 XML 都计入求和（规范 §1 原文即
「TEST-*.xml 的 tests 属性求和」，未去重）——同一测试类可能以 debug/release 两态各计一次，
棘轮度量口径保持一致即可。

## 约束

- 门**只读**产品代码：不修改 `server/`、`app/`，只写 `tools/gate/`。
- 构建/测试一律经 `bash -lc`（JAVA_HOME 在 profile），测试带净化前缀
  `env -u TEAM_AGENT_WORKSPACE -u TEAM_AGENT_ID -u TEAM_AGENT_OWNER_TEAM_ID -u TEAM_AGENT_AGENT_ID`。
- 并行席位正在改 server/ 时，个别包瞬态红属可能——门**如实报**，不重试掩盖（归因是上游的事）。
