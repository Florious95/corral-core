# baseline-bundle successor3 stage2 final result

## 结论

已从独立语义通过并提交的 bootstrap commit `f0fce0a44182558c23e4bba66d6b0b6ea91bdf1d` 生成可审计但尚未启动的 final successor3 DSL/compiled ledger。验证时当前 HEAD 与 bootstrap commit 完全相同；全部任务资源把该完整 SHA 冻结为 immutable provenance，所有 acceptance、真实 fixture helper、control contract 和 bootstrap 路径均可从该 commit 直接取回。

## 最终账本

- DSL 源：`.team/ledgers/src/baseline-bundle-successor3-v1.py`
- 编译账本：`.team/ledgers/baseline-bundle-successor3-v1.json`
- ledger id：`ledger.baseline-bundle.successor3.v1`
- revision：`1`
- compiled SHA-256：`f6cd694aa7bd811c9331d364885cba3dc84f60436cc7d86e3593e8b47618f4ac`
- 状态：9 tasks 全 planned、0 attempts、无自定义 statuses、无 transitions、未 drive

图保持完整：

```text
repro -> (test || probe || impl) -> verify -> user-gate -> migrate -> measure -> final
```

10 条边全为 `requires_success`；并行组仅 impl/test/probe，`max_concurrency=3`、`failure_policy=halt`。test/probe 除 bootstrap 专用判据外仍保留原 test/probe 门；impl/measure 专用 wrapper 内部保留原 base gate，并与原 bypass、新 hardened bypass 合取；verify/user-gate/migrate/final/real-chain 旧门均保留。measure 与 final 仍机械绑定 fresh 三夹具四段 A/B/A/B、每夹具每段 n>=10、nearest-rank p50/p95、同批身份和全格 `B/A<=1.10`；用户 gate 仍只认绑定确切 bundle 的蜂窝+广州中转真机“秒开、没有空白”。

## 全新 WT 与隔离

- core 顺链：`wt-bundle3-core-f0`
- test：`wt-canon-red-lane`
- probe：`wt-provenance-oracle`

三者在磁盘、`git worktree` metadata 和历史跟踪中的既有计数均为 0；impl/test/probe 三格写隔离，core WT 只由依赖串行的 repro/impl/verify/user-gate/migrate/measure/final 复用。lease、driver PID 均不存在，没有启动账本或创建 WT。

## Provenance 与两阶段连续性

- `git merge-base --is-ancestor f0fce0a44182558c23e4bba66d6b0b6ea91bdf1d HEAD` exit0。
- 24 个 compiled/transitive acceptance、helper、fixture、任务书路径在 bootstrap SHA 和 HEAD 均可寻址。
- 固定 `control-contract.json` SHA-256 为 `ffcea3d0d3282618ad91f9db44c7a99616868b6610c88516e022385e59bd3fd9`。
- 旧三本账本 SHA 分别仍为 `89ba716e...a6b5723`、`d0e11364...ebf58be`、`39bb9fcf...c0e2a9c0`；旧 attempts 未复制、清理、重写或重派。
- final 源/编译账本当前可提交但未提交；leader 提交本阶段产物后，未来 ledger-run 从其当前 main 后代创建的新 WT 即可直接读取 bootstrap 已跟踪 acceptance/fixture，不依赖主工作区 untracked 输入。

## Fresh 验证

- `final-compile.log`：DSL compile exit0，结构/旧门/新门/1.10/真机 gate 合取审计通过。
- `final-byte-recompile.log`：二次编译与候选 `cmp -s` exit0，SHA-256 完全一致。
- `final-schema.log`：jsonschema 与 ledgerdsl full gate exit0。
- `final-preflight.log`：`ledger-run --preflight --json` 返回 `ok=true`、issues=[]。
- `final-dry-run.log`：exit0，frontier 精确为 `t.baseline-bundle.repro`，其余 8 格仅因依赖未满足排除。
- `final-provenance.log`：bootstrap ancestry、24 路径 reachability、fixture 摘要通过。
- `final-worktree.log`：三枚新 WT 不存在、并行隔离成立、无 runtime/启动痕迹。
- `final-continuity.log`：旧账本 byte identity/attempt 历史保持，未改产品或旧运行态。
- `final-consistency.log`：RESULT 落盘后再次完整重编、schema/preflight/dry-run 与未启动不变量全绿。

本阶段未执行 `ledger-run --drive/--once`，未创建 worktree，未改 App/server/tools/perfbase，未迁移或停止任何旧账本/driver。

verdict: pass
