# baseline-bundle successor5 启动前零上下文独立审查

## 结论

判定为 `pass`。这是 successor5 SDK fallback、DSL/compiled ledger 与启动前结构的 fresh 审查，不是对后续 worker 实现、APK、性能或真机 gate 的通过判定。未启动或修改被审包。

## 机器核验

- ledger 为 `ledger.baseline-bundle.successor5.v1` revision `1`，9 格均 `planned`，无 attempts、无 `statuses`、无 `missing_status`；10 条依赖仍为九格图。
- source 重新编译 stdout 与 compiled JSON 字节相同，SHA-256=`c58fc612c8032c35e7acb3919b95ca1aac9d442c193271f75a738e63e6bdce80`；`ledger-run --preflight --json` exit 0、issues 空；fresh dry-run exit 0，首 frontier 仅 `t.baseline-bundle.repro`，其余 8 格为 `dependency_unsatisfied`。
- 当前 HEAD=`2f76349afdb42d4d0dcdc97e8ccc6e02868ec263`，固定 bootstrap `f0fce0a44182558c23e4bba66d6b0b6ea91bdf1d` 为祖先。successor3 bootstrap canonical/bypass/helper/fixture 路径均可由该 bootstrap commit 取回。
- 三个新 WT 精确为 `wt-cedar-main`、`wt-ruby-lab`、`wt-owl-audit`；三者均不在磁盘或 git worktree metadata 中，且两两不同。

## SDK fallback 安全边界

fresh `baseline-bundle-successor5-sdk-regression.sh` exit 0，验证了：

| 情形 | 结果 |
|---|---:|
| 有效 `ANDROID_SDK_ROOT` 优先 | 0 |
| 无效环境回退主仓唯一 `app/local.properties` | 0 |
| 无环境/额外键/重复 `sdk.dir`/无效 SDK 目录 | 2 |
| 目标 `app/local.properties` 被 Git 跟踪 | 1 |
| 成功输出 | stdout/stderr 均为空 |
| 目标文件 | 仅一行 `sdk.dir=...`、权限 0600、未跟踪 |

wrapper 只从当前进程的 `ANDROID_SDK_ROOT`，为空或无效才尝试 `ANDROID_HOME`；两者无有效目录时通过 Git common dir 定位主仓，交给 helper 白名单解析。源文件仅接受空行、注释和恰好一行行首 `sdk.dir=<非空值>`；不 source、不原样复制、不打印路径或文件原文。helper 原子写目标并设 0600；所有失败文案不含 SDK 路径。六个可能运行 Gradle/SDK 的 task（test/probe/impl/verify/measure/final）在任务书/title/write_paths 中均要求 worker 在任何 Gradle/SDK 操作前执行该前置，目标不提交。

## required 精确集合与 legacy negative

当前 compiled ledger 与 fresh structure gate 均核到：

```text
test : [M.baseline-bundle.successor5-test] -> baseline-bundle-successor5-test.sh
probe: [M.baseline-bundle.successor5-probe] -> baseline-bundle-successor5-probe.sh
impl : [M.baseline-bundle.successor5-impl, M.baseline-bundle.successor5-bypass]
       -> baseline-bundle-successor5-impl.sh, baseline-bundle-successor3-bypass.sh
```

不存在 `M.baseline-bundle.impl-bypass`、`M.baseline-bundle.probe`、successor4 impl/probe required，也没有旧 probe `source_tree_sha256` 门。结构副本齿 fresh 结果：精确集合 rc0；插入 legacy impl required、legacy probe required、successor4 impl id、旧 probe argv 分别 rc1；候选 ledger 未改。

successor5 impl wrapper 组合 successor5 SDK、SDK regression、structure 和 bootstrap successor3 impl/canonical 真实门；probe/test wrapper 分别挂 successor5 SDK regression 与 structure 门；bootstrap `control-contract.json` 与真实 canonical/bypass 门仍保留。SDK/fixture/真实 provenance 的四态没有被改成“缺证据即绿”。

## 未弱化的产品终局

- 任务图仍为 `repro -> (test ∥ probe ∥ impl) -> verify -> user-gate -> migrate -> measure -> final`，并行组 `max_concurrency=3`、`failure_policy=halt`。
- 缺冻结 A 仍只允许 `recover_exact_artifact` 或 `rebaseline_with_equivalence_proof`；canonical、运行内容、双归档恢复和真实 bootstrap 门继续保留。
- fresh 性能仍先 envcheck，再三夹具四段 A/B/A/B、每夹具每段 n>=10、非空 raw、同批 A2/B、nearest-rank p50/p95，全部 `B/A<=1.10`；无缓存参数未弱化。
- 真机 gate 仍要求蜂窝网络+广州中转下绑定确切 bundle “秒开、没有空白”；agent、模拟器和旧口述不能代判。
- migrate 仍需 verify 与 user gate 全绿后，按精确 PID/ledger 状态前置处理旧 park；漂移不发信号，历史保留。

当前 successor5 DSL/compiled ledger、脚本和任务书属于待启动候选；leader 必须先提交本包，使 startup current main 能取回它并保持 pinned provenance 后代关系。该条件不等于已启动，本审查没有执行 ledger drive/once、创建 WT、构建/安装 APK、迁移、daemon 或凭据操作。

verdict: pass
