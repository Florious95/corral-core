# baseline-bundle successor3 final 启动前独立审查

## 结论

判定为 `pass`（仅限启动前 DSL/compiled ledger 与 provenance 审查；没有启动 ledger、创建 WT、构建/安装 APK、迁移旧 park 或执行用户真机 gate）。

## Fresh 核验结果

- 当前候选 ledger 为 `ledger.baseline-bundle.successor3.v1`，revision `1`，schema `ledger.v2`；9 格均为 `planned`，无 `attempts`、无自定义 `statuses`、无 `missing_status`，旧账本未改。
- source `.team/ledgers/src/baseline-bundle-successor3-v1.py` 用 `/usr/bin/python3` 与固定 ledgerdsl 重新编译，exit 0；stdout 与现有 compiled JSON 字节相等，compiled SHA-256 为 `f6cd694aa7bd811c9331d364885cba3dc84f60436cc7d86e3593e8b47618f4ac`。
- `ledger-run --preflight --json` exit 0，`ok=true`、`issues=[]`；`ledger-run --dry-run --json` exit 0，首 frontier 仅 `t.baseline-bundle.repro`，其余 8 格均为 `dependency_unsatisfied`。
- 三枚预留 WT 为 `wt-bundle3-core-f0`、`wt-canon-red-lane`、`wt-provenance-oracle`；三者均不存在于磁盘或 `git worktree` metadata。successor3 lease、lease.pid、driver PID 也均不存在。
- 所有 9 个 task 的 `Resources.provenance` 都是 `identity=git`、revision=`f0fce0a44182558c23e4bba66d6b0b6ea91bdf1d`；当前 HEAD 正是该 bootstrap commit，祖先关系成立。
- compiled acceptance 的 15 个路径均由 bootstrap commit `f0fce0a44182558c23e4bba66d6b0b6ea91bdf1d` 的 `git cat-file -e` 取回；固定四份 successor3 任务书、真实 fixture helper、`control-contract.json` 也可取回。fixture SHA-256 为 `ffcea3d0d3282618ad91f9db44c7a99616868b6610c88516e022385e59bd3fd9`。

## 门与任务图审计

- 任务图保持 `repro -> (test || probe || impl) -> verify -> user-gate -> migrate -> measure -> final`；10 条边均为 `requires_success`，并行组为 `max_concurrency=3`、`failure_policy=halt`。
- impl acceptance 调用真实 canonical retrieve 与 successor3 controlled bypass；canonical 齿保持 stale `apk_relpath` 红/final path 绿，provenance 伪造由底层 `manifest bundle_id mismatch` 形成 rc2，再由 hardened 门归类为 1。measure bypass 保持合法 root、非空 raw，仅改声明 runner SHA，精确 `runner provenance mismatch` 为红。
- test/probe acceptance 按 successor3 任务书是 RED.md/PROBE.md 的交付形状门，明确不冒充产品事实；真正 canonical/bypass 入口由 impl、verify/final 的 required checks 调用。该边界与任务书及 final logs 一致。
- SDK/IMPL 四态保持：缺 `app/local.properties` 或不可执行 SDK 工具为 exit 2，且不泄露值；`IMPL.md` 末行不是 `implementation: pass`（含 unjudgeable）为 exit 1；固定 fixture 缺失/漂移为 2；受控伪造为 1。
- measure/final 标题与任务书仍冻结三夹具四段 A/B/A/B、n>=10、nearest-rank p50/p95、同批身份、全格 `B/A<=1.10`；用户 gate 仍要求绑定确切 bundle 的蜂窝网络+广州中转真机“秒开、没有空白”。迁移仍要求 verify 与 user gate 全绿后才精确 PID、有限 TERM、lease 收口、历史保留并置旧 park 为 paused。

## 历史连续性与边界

旧三本 ledger 的冻结 SHA/attempt provenance 保持不变；successor3 没有复制、清洗、重写或重放旧 attempts。final candidate 的 DSL/JSON 是 bootstrap 之后的阶段二产物，当前仍是未提交候选；启动前必须由 leader 将其作为后代提交，且不得把当前 untracked 文件当成未来 WT 的输入。

本审查未把 bootstrap 阶段的 canonical/bypass/SDK logs 当成 successor3 产品实现已通过；它们只证明固定门、fixture 和四态设计可执行。真实产品实现、bundle、迁移、性能与真机结论必须留给后续 fresh ledger 格执行。

verdict: pass
