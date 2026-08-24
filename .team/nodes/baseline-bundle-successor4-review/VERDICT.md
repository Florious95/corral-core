# baseline-bundle successor4 启动前零上下文独立审查

## 结论

判定为 `pass`。这是对 successor4 DSL/compiled ledger 与启动前门的独立审查，不是对后续 worker 实现、APK、性能或真机 gate 的通过判定。未启动账本、未创建 WT、未修改被审包。

## 机器核验

- ledger 为 `ledger.baseline-bundle.successor4.v1` revision `1`，9 格均 `planned`，无 attempts、无 `statuses`、无 `missing_status`；10 条依赖保持九格图。
- `ledger-run --preflight --json` exit 0，`ok=true`、`issues=[]`；fresh `--dry-run --json` exit 0，首 frontier 仅 `t.baseline-bundle.repro`，其余 8 格均 `dependency_unsatisfied`。
- 当前 HEAD `ec1145820186b8862949b84fe56f9309c1b0754f`，bootstrap `f0fce0a44182558c23e4bba66d6b0b6ea91bdf1d` 为祖先；固定 successor3 canonical/bypass/helper/fixture 路径仍可从 bootstrap 取回。
- 三个新 WT 精确为 `wt-atlas-42`、`wt-quartz-77`、`wt-nimbus-93`，两两不同，磁盘和 git worktree metadata 均不存在。

## required 精确集合与 legacy negative

compiled ledger 和 fresh structure gate 均核到：

```text
test : [M.baseline-bundle.successor4-test] -> baseline-bundle-successor4-test.sh
probe: [M.baseline-bundle.successor4-probe] -> baseline-bundle-successor4-probe.sh
impl : [M.baseline-bundle.successor4-impl, M.baseline-bundle.successor4-bypass]
       -> baseline-bundle-successor4-impl.sh, baseline-bundle-successor3-bypass.sh
```

不存在 `M.baseline-bundle.impl-bypass` 或 `M.baseline-bundle.probe`，probe 也没有 legacy `source_tree_sha256` 要求。structure 齿副本 fresh 结果：精确集合 rc0；插入 legacy impl required rc1；插入 legacy probe required rc1；把 probe argv 指回旧脚本 rc1。副本未修改候选 ledger。

impl wrapper 仍组合 successor4 SDK/structure 与 successor3 bootstrap 的真实 impl/canonical 门；successor3 fixed `control-contract.json`、真实 `baseline_bundle.py retrieve` canonical 齿和 controlled bypass 仍被后续 required 门挂载。SDK/fixture/IMPL 四态保持原约定：缺环境/目录/工具或 fixture 不可判为 2，误提交 local.properties 为 1，IMPL unjudgeable 为 1，真实 provenance 伪造为 1。

## SDK 前置边界

successor4 SDK gate 只从当前进程 `ANDROID_SDK_ROOT`，为空才取 `ANDROID_HOME`；不读取 provider profile、凭据或生产日志，不输出环境值、`sdk.dir` 值或 `local.properties` 内容。fresh 清空两环境变量时 exit 2 且 stdout 字节数为 0。gate 静默验证 worker 已生成的非版本化 `app/local.properties`、目录一致性及可执行 `apksigner`/`aapt`，并拒绝 tracked local.properties。

任务书和 compiled resources 对六个 Gradle-capable task（test/probe/impl/verify/measure/final）均声明 `app/local.properties` write path，title 明确要求在任何 Gradle/SDK 命令前由 worker 无输出生成并核验；因此“生成”是 worker 开工前置，“successor4-sdk.sh”是静默验证门。缺环境不可被染成产品失败，且 local.properties 不得提交。

## 未弱化的终局

- 任务图仍为 `repro -> (test ∥ probe ∥ impl) -> verify -> user-gate -> migrate -> measure -> final`，并行组 `max_concurrency=3`、`failure_policy=halt`。
- 性能仍先 envcheck，再三夹具四段 A/B/A/B、每夹具每段 n>=10、同批 A2/B、nearest-rank p50/p95、全部 `B/A<=1.10`；没有改变阈值、缓存禁令或 raw 身份要求。
- 真机 gate 仍只认蜂窝网络+广州中转下绑定确切 bundle 的“秒开、没有空白”；agent/模拟器/旧口述不能代判。
- migrate 仍依赖 verify 与 user gate 全绿，要求精确 PID、状态前置、漂移不发信号、历史保留；没有提前开放不可逆动作。
- 历史四本 ledger/attempt 只作 provenance，successor4 不清洗、复制、重派或改写旧证据。

启动前仍须由 leader 提交 successor4 包，使 startup-time current main 可取回本包并保持 pinned provenance 后代关系；本审查没有把当前候选的 logs 当成 worker 产品事实。

verdict: pass
