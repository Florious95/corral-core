# successor6 final ledger result

已生成但未启动 `ledger.baseline-bundle.successor6.v1` revision 1。九格十边保持 `repro → (test ∥ probe ∥ impl) → verify → user-gate → migrate → measure → final`；无自定义 statuses，首 frontier 仅 `t.baseline-bundle.repro`。

## 产物

- DSL 源：`.team/ledgers/src/baseline-bundle-successor6-v1.py`
- 编译账本：`.team/ledgers/baseline-bundle-successor6-v1.json`
- final 连续性任务书：`.team/nodes/spec-sol/baseline-bundle-successor6/final-任务书.md`
- required/legacy-negative：`.team/ledgers/acceptance/baseline-bundle-successor6-{structure,test,probe}.sh`
- successor6 独立 verify/final 组合门：`.team/ledgers/acceptance/baseline-bundle-successor6-{verify,final}.sh`
- 冻结真实实现门继续使用：`.team/ledgers/acceptance/baseline-bundle-successor6-{impl,deep,projection-regression}.sh`、`baseline-bundle-successor6-projection.py` 与 tracked fixtures。

## 冻结身份与历史连续性

全部 task provenance 精确写为 `fdf7f64970351d51e616491850e2c49d03d24b22`；验证时 HEAD=`b2ee4f9d259e0cacffeea4d124a8c658f070d601`，祖先检查 exit0。successor6 impl/deep/projection、successor5 SDK fallback、successor3 canonical/controlled bypass、两组 fixture/control-contract 与四份 bootstrap 任务书共 16 路径均能从 frozen commit 取回。

账本与任务书保留 successor5 的真实 A2 构建、repro/test/probe 三门成功以及 impl 被 legacy bundle_id 路径固定点门反证的历史；旧 ledger/attempt 只读，不清洗、不重放为新证据。final-only 组合/结构脚本须由 leader 与源/编译账本一并提交后再启动；本格没有 commit 或启动。

三枚全新 WT 为 `wt-maple-core`、`wt-indigo-tests`、`wt-falcon-review`，磁盘与 git worktree metadata 均 absent，且并行写格 pairwise distinct。账本 lease 与 driver PID 均 absent。

## 门与不弱化

- test required 精确为 `M.baseline-bundle.successor6-test`；probe 精确为 `M.baseline-bundle.successor6-probe`；impl 精确为 `M.baseline-bundle.successor6-impl,M.baseline-bundle.successor6-bypass`。
- impl wrapper 实际合取 successor5 SDK fallback/regression、successor6 projection regression/真实 projection/deep、successor3 canonical/controlled bypass，并锁 manifest hash；其内不调用 `baseline-bundle-impl.sh` 或 successor3 impl wrapper。
- compiled ledger 内 17 个 ScriptRef 全部存在，均 `cwd=${worktree}`、expect0、unjudgeable2；legacy impl/probe、旧 argv、坏四态破坏齿分别 exit1，候选缺失 exit2，原件 exit0。
- verify/final 改用 successor6 组合门，避免旧固定点从 transitive verify/final 回流；仍机械要求双归档恢复/安装/变异 JSON、用户 gate、迁移、fresh measure 与终审矩阵。
- fresh measure 的三夹具 A/B/A/B、每夹具每段 n>=10、nearest-rank p50/p95、同批 A2/B、B/A<=1.10 和用户真机“秒开无空白”均保留；迁移仍在 verify+user gate 后。

## Fresh 验证

- `final-compile-schema.log`：ledgerdsl 0.1.1 compile exit0，byte recompile cmp exit0，jsonschema PASS，DSL preflight PASS；17 个 ScriptRef 全存在且四态绑定精确。
- `final-preflight-dry-run.log`：`ledger-run --preflight` exit0，`--dry-run` exit0，首 frontier 仅 repro；未运行 drive/once。
- `final-byte-syntax.log`：全部新增/复用 successor6 shell `sh -n` exit0、shellcheck exit0、Python byte compile exit0；projection 红绿/伪造回归 exit0，SDK fallback 回归 exit0且不输出 SDK 路径。
- `final-provenance-worktrees.log`：frozen ancestry、16 路径可取回、三枚 WT 不存在、无 lease/PID。
- `final-structure-teeth.log`：exact=0；legacy impl/probe、旧 argv、坏四态均1；missing candidate=2；九格十边、无 transitions、1.10 与真机金标准均在 compiled ledger。

未修改 App/server/tools/perfbase，未读凭据，未创建 worktree，未执行 ledger-run drive/once，未迁移或停止任何旧账本/attempt。

verdict: pass
