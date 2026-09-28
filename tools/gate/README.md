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
| server | `go test ./... -count=1 -json` + `go vet ./...` + `gofmt -l .` + `staticcheck ./...` | `go test -json` 的 Test 事件（pass/fail）计数 | **红**（min_cases=1） |
| app | `./gradlew -q test` + `./gradlew -q :app:lintDebug` | `build/test-results/**/TEST-*.xml` 的 tests 属性求和 | 起步基线为 0（当前 app 无测试类），不判红 |
| archwiki | `python3 tools/archwiki/build_wiki.py --check` | 存在才跑；缺席显式 `skipped` 标注 | — |

### 静态分析（gate-static-analysis 接入，2026-08-11）

- **server 面 staticcheck**：`go vet` 之外的静态分析，BSD-3 许可（与 Apache-2.0 兼容，
  需求 008 全开源约束）。**默认规则集不裁剪**（红线：不许为了让门禁变绿而降规则）。
  二进制不在登录 PATH（`GOPATH/bin` 未导出），gate.py 经 `go env GOPATH` 解析绝对路径；
  找不到即显式红（环境缺失不得静默当 pass）。每条 finding 记一条失败（`staticcheck:<file>:<line>`），
  供四归因 carry-over。
- **app 面 Android Lint**：AGP 自带（零新依赖），跑 `:app:lintDebug`。**默认规则集不裁剪**。
  lint 默认仅 error 使构建失败（AbortOnError），但 gate 把 XML 报告里每条 finding
  （含 warning）都记为失败条目——存量未清前 app 面非绿属预期，四归因由上游标注，不挑不藏。
  报告读取 `app/app/build/reports/lint-results-debug.xml`；报告缺失且 gradle 非 0 时
  记「lint (no report, exit non-zero)」显式红（空清单不算健康）。

## gate 进 acceptance 收口位（约定）

**taskbook 里凡涉及代码改动的条目，acceptance 末尾补一条全量门**，标准 argv：

```bash
bash -lc 'env -u TEAM_AGENT_WORKSPACE -u TEAM_AGENT_ID -u TEAM_AGENT_OWNER_TEAM_ID -u TEAM_AGENT_AGENT_ID bash tools/gate/run.sh'
```

说明：
- taskbook 内部常用简写 `env -u TEAM_AGENT_*`——在 bash 下是未展开的字面量（无文件命中时
  原样传给 `env`，`-u` 不接受通配，实际不净化任何变量）。**真正生效的净化在 gate.py 内部**，
  按上表显式清单逐条 `-u`。标准 argv 用显式清单，与 gate.py `_SANITIZE` 同一份来源。
- 门**允许因新接工具暴露存量问题而非绿**——此时证据里必须逐条说明每条非绿的来源与归属；
  禁止调低棘轮基线或裁剪规则集让它变绿。
- 具体条目的批量补写由 leader 收口时做（gate-static-analysis 只立约定，不逐条改）。

## 豁免清单

默认规则集不裁剪。确有必须豁免的逐条给理由（一行一条，没理由的不许加）：

（当前无豁免——见 `docs/stage3-issue-inventory.md`，`Aligned16KB` 的 tsnetbind AAR
对齐问题记录为 tools/tsnetbind 的真实缺陷，不豁免。）

## 约束

- 门**只读**产品代码：不修改 `server/`、`app/`，只写 `tools/gate/`。
- 构建/测试一律经 `bash -lc`（JAVA_HOME 在 profile），测试带净化前缀
  `env -u TEAM_AGENT_WORKSPACE -u TEAM_AGENT_ID -u TEAM_AGENT_OWNER_TEAM_ID -u TEAM_AGENT_AGENT_ID`。
- 并行席位正在改 server/ 时，个别包瞬态红属可能——门**如实报**，不重试掩盖（归因是上游的事）。
