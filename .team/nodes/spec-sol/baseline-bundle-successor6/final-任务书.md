# successor6 final ledger — 冻结 bootstrap 后的审计连续性

## 身份与历史

本账本为 `ledger.baseline-bundle.successor6.v1` revision 1，以 `fdf7f64970351d51e616491850e2c49d03d24b22` 为 immutable provenance；启动时 main 必须是其后代。它承接但不改写旧 baseline-bundle 与 successor1–5 ledger/attempt：successor5 已真实构建 A2、repro/test/probe 三门通过，impl 最终因旧 `bundle_id` 路径固定点门 rc1；这些只读事实不得重放或包装成 successor6 新成功。

三枚新 worktree 为 `wt-maple-core`、`wt-indigo-tests`、`wt-falcon-review`。开跑前必须机械证明磁盘与 git worktree metadata 均不存在；repro/impl/verify/user-gate/migrate/measure/final 只在依赖串行的 core WT 写，test 与 probe 各用独立 WT。leader 提交本 final 包并复核 main 包含 frozen bootstrap 后才可启动；本格不启动、不建 WT。

## 图与 required

图固定为 `repro → (test ∥ probe ∥ impl) → verify → user-gate → migrate → measure → final`，九格十边，无自定义 statuses，开发/测试/审查三格并行且 `failure_policy=halt`。

- test required 只允许 `M.baseline-bundle.successor6-test`，机械门核 RED.md、successor6 投影红绿齿、successor5 SDK fallback 与本账本 exact required。
- probe required 只允许 `M.baseline-bundle.successor6-probe`，机械门核 PROBE.md 独立操作数、同一投影/SDK齿与 exact required。
- impl required 只允许 `M.baseline-bundle.successor6-impl` 与 `M.baseline-bundle.successor6-bypass`。前者合取 successor5 SDK fallback、successor6 projection regression/真实 projection/deep、successor3 canonical/controlled bypass 并锁 manifest hash；后者固定 provenance 防伪。
- `baseline-bundle-impl.sh` 与 `baseline-bundle-successor3-impl.sh` 不得成为任何 mechanical argv，也不得借 verify/final wrapper 回流。verify/final 使用 successor6 专用组合门，仍核原要求的结构化证据。

所有 ScriptRef 均 `cwd=${worktree}`、expect 0、unjudgeable 2。0=事实完整且门绿；1=实现/证据被有效反证；2=SDK、fixture、资产、环境或量具不可判；互斥未派发才是不适用。

## 不得弱化的终局

impl 必须独立证明 canonical `bundle_id` 与非循环稳定槽位 projection，两份 build、APK/运行内容/签名/报告/provenance、primary+backup archive 与恢复安装均真实；SDK 优先有效环境，否则走 successor5 白名单 fallback，缺失2且不输出值、不提交 local.properties。

measure 前过 SDK、资产取回/摘要/安装与 envcheck；只接受 fresh 同批三夹具 A/B/A/B、每夹具每段 n>=10、非空 raw、同批 A2/B 身份、nearest-rank p50/p95 与每格 B/A<=1.10。有效样本超阈1，环境/身份/样本不足2，不得调阈值或重跑碰运气。

user-gate 仍只认用户在蜂窝+广州中转、绑定确切 bundle 的真机“秒开、没有空白”；agent/模拟器不得代判。migrate 必须在 verify 与 user-gate 全绿后，以精确 PID、lease、ledger 状态机械前置停止/paused 旧 perf-regress，漂移时不发信号、不清历史。final 严格合取 bundle、双归档恢复、迁移、fresh 1.10 与同 bundle 真机裁决。

不得改 App/server，不得公开分发 debug 签名 APK，不得读取或输出凭据。本账本每格产物齐后只 `report_result` 一次。

verdict: pass
