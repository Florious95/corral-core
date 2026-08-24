# successor3 prelaunch verdict

结论：`refutes`。该包的账本结构本身通过 fresh DSL/schema/preflight/dry-run，任务图、四态、1.10、真机 gate、迁移前置和旧历史保留声明完整；但不能启动验收。

1. 致命的可启动性缺陷：`HEAD=6575cefc084871738f9817236220eb91e3173a6e` 中不存在 successor3 DSL、编译账本、专用 acceptance 脚本和固定 fixture（`git cat-file -e HEAD:<path>` 对四条路径均失败，当前均为 untracked）。因此 ledger-run 从 HEAD 创建 `wt-b3-*` 后，首格及其后继无法寻址任务契约/判据/fixture。任务书声称“current main 含本编排包”与事实冲突。

2. canonical 破坏齿不真实：`.team/ledgers/acceptance/baseline-bundle-successor3-impl.sh:41-75` 仅对 synthetic projection 计算两次 SHA-256；没有调用 `baseline_bundle.py`、没有写/读 manifest、没有把 stale `bundle_id` 送入真实拒绝路径。`stale == identity(final)` 与 `correct == identity(final)` 的检查是恒等式，不能证明旧行为确实红或实现已转绿。

3. bypass 齿形状不足：fresh 运行总体 exit 0，但 hardened impl 的 exit 1 原因是隔离目录 `repository root mismatch`，hardened measure 的 exit 1 原因是 synthetic `empty raw log`；这不是固定伪造 manifest 被 successor3 canonical 门拒绝的实现级证据。缺失 fixture=2、摘要漂移=2 的前置检查本身成立。

4. test/probe 专用 gate 只 grep RED.md/PROBE.md token，不执行对应场景；fresh 时两份 required artifact 不存在，各自 exit 1。它们可以作为任务书交付形状检查，不能作为独立事实覆盖证明。

正面核验：fresh compile/schema/preflight/dry-run 均 exit 0，首 frontier 仅 repro；九格 planned、无 attempts，三枚 WT 磁盘/metadata 均不存在；旧三本账本哈希保持 `89ba716e...a6b5723`、`d0e11364...ebf58be`、`39bb9fcf...c0e2a9c0`；sh -n/ShellCheck 全过；未执行 drive、创建 WT、迁移、停 driver、构建 APK 或产品变更。

最小可证伪修法：先把 successor3 DSL、candidate、全部 acceptance/fixture 和任务书纳入 provenance base 的实际 commit，并 fresh 从该 commit 创建全新 WT；再把 canonical red/green 改成真实调用实现/manifest 的 stale-id reject 与 final-id accept，且让 bypass 在真实新 WT 内因固定伪造内容得到明确 exit 1，而不是 root/empty-raw 旁路红。修复后必须 fresh 重跑四道账本门与所有三态齿，保留旧三本 ledger/attempt。

verdict: refutes
