# successor3 run1 返修归因

## 结论

这轮红主要是账本 required/资源契约问题，不能归因成产品实现失败：

1. 三棵实际 WT 的 HEAD 都是 `171db68d9a6075ea6b6c4552686c43395de84e9c`，三棵均同时有 successor3 与 legacy acceptance 脚本。启动前审查所说的“三 WT 均不存在”是当时的 prelaunch 快照；它没有证明之后创建的 `wt-provenance-oracle` 会使用 successor3-only 的 required 门，也没有证据证明该路径是旧 WT 物理复用。
2. successor3 probe 的 required 同时包含旧 `M.baseline-bundle.probe` 和新 `M.baseline-bundle.successor3-probe`。先执行的旧门在 `.worktrees/wt-provenance-oracle` 因 `PROBE.md missing source_tree_sha256` rc1；这是旧 PROBE 格式与 successor3 交付契约不一致的形式红。新 probe 门未取得有效执行结果，不能据此判断 probe 实质缺陷。
3. successor3 impl 的 `M.baseline-bundle.successor3-impl` rc2 原因是 `app/local.properties missing`，属于 SDK/外部环境前置不可判；不是 fixture 缺失、实现产物内容红或 repository-root 路径错误。旧 `M.baseline-bundle.impl-bypass` 也在 required 中，因旧 frozen fixture 路径缺失 rc2；新 successor3 bypass 门没有得到有效执行。

## 只读核对

### Ledger resources 与 required 门

live：`.team/ledgers/baseline-bundle-successor3-v1.json`，当前 revision 3；repro/test succeeded，impl 为 `failed_retryable`/不可判，probe 为 `failed_retryable`/冻结。

资源与 required 的关键形状如下：

| task | ledger `worktree_id` | required 门 | 运行 cwd / 结果 |
|---|---|---|---|
| impl | `wt-bundle3-core-f0` | `M.baseline-bundle.successor3-impl`、`M.baseline-bundle.impl-bypass`、`M.baseline-bundle.successor3-impl-bypass` | `.../.worktrees/wt-bundle3-core-f0`；rc2、rc2 |
| probe | `wt-provenance-oracle` | `M.baseline-bundle.probe`、`M.baseline-bundle.successor3-probe` | `.../.worktrees/wt-provenance-oracle`；旧门 rc1 |
| test | `wt-canon-red-lane` | `M.baseline-bundle.test`、`M.baseline-bundle.successor3-test` | `.../.worktrees/wt-canon-red-lane`；两门 succeeded |

因此 impl 不是“漏挂 successor3 门”，而是 successor3 门和旧门并挂；运行先在 successor3 impl rc2 与旧 bypass rc2 处停止，没有为 successor3 bypass 形成可用证据。probe 也是新旧门并挂，旧门先红使新门未被判定。

三项 required 的共同配置还声明 `cwd=${worktree}`、`expected_exit_code=0`、`unjudgeable_exit_codes=[2]`。这些是机器门，不能把 rc2 折成实现失败，也不能用 test 的 grep 形状通过替代 impl/probe 的真实事实。

### WT HEAD 与脚本存在性

只读 `git -C <WT> rev-parse HEAD` 得到：

```text
.worktrees/wt-bundle3-core-f0       171db68d9a6075ea6b6c4552686c43395de84e9c
.worktrees/wt-canon-red-lane        171db68d9a6075ea6b6c4552686c43395de84e9c
.worktrees/wt-provenance-oracle     171db68d9a6075ea6b6c4552686c43395de84e9c
```

三棵 WT 均存在以下脚本：

```text
.team/ledgers/acceptance/baseline-bundle-successor3-impl.sh
.team/ledgers/acceptance/baseline-bundle-successor3-bypass.sh
.team/ledgers/acceptance/baseline-bundle-successor3-probe.sh
.team/ledgers/acceptance/baseline-bundle-impl.sh
.team/ledgers/acceptance/baseline-bundle-bypass-probes.sh
.team/ledgers/acceptance/baseline-bundle-probe.sh
```

`171db68…` 是“冻结并审过基线包 successor3 最终账本”的提交，且 successor3 source 的 provenance revision 为其祖先 `f0fce0a…`；这说明运行 WT 不是由旧基线 checkout 出来的差异 HEAD。名称 `wt-provenance-oracle` 来自当前 successor3 ledger resource，本身不能作为“旧 WT 复用”证据。

但这里有一个启动审查边界：prelaunch 记录的 HEAD 是 `f0fce0a…`，真正运行 WT 的 HEAD 已是其后继 `171db68…`。若 provenance 要求精确 HEAD，启动前审查没有在 WT 创建后重核 pin；若只要求祖先，脚本的 ancestry 检查可接受它。两种语义都不能被“所有 task provenance 字段写了 f0fce0a…”自动代替。

### Worker artifacts 与 results

仓根的三个 landing 目录当前只有 `.gitkeep`；实际交付物在对应 WT：

- impl WT 有 `ROUTE.md`、`IMPL.md`、`BUNDLE-MANIFEST.json`、`INSTALL.md`、`RETRIEVE.md`，但 successor3 impl 门在 SDK 前置处已经 rc2，不能把这些文件当成通过证据。
- probe WT 有 `PROBE.md`，但旧 probe 门要求的 `source_tree_sha256` 不在该 successor3 交付形状中；故旧门 rc1 是形式不兼容。
- test WT 有 `RED.md`，两个 test 门 succeeded；任务书明确 test 门只核交付形状，不执行产品事实，因此不覆盖 impl/probe。

ledger attempts 的 `artifact_refs` 与 driver 诊断分别记录了：

```text
M.baseline-bundle.successor3-impl: rc2, app/local.properties missing
M.baseline-bundle.impl-bypass: rc2, frozen bypass fixture missing .../baseline-bundle-impl.sh
M.baseline-bundle.probe: rc1, PROBE.md missing source_tree_sha256
```

没有把 worker 的自报或上述已落盘形状 artifact 当作产品通过；没有清 attempts 或重新派单。

## 各红的归属

### impl rc2：SDK 前置，不是产品/fixture/产物红

`.team/ledgers/acceptance/baseline-bundle-successor3-impl.sh` 在 canonical/fixture/产物门之前明确检查 `app/local.properties`、`sdk.dir`、SDK 目录、`apksigner` 与 `aapt`。它本次首个 rc2 是 `app/local.properties missing`。三棵 WT 都缺该非版本化本地文件，而仓根有它；运行 cwd 和 WT root 均可解析，日志没有 `repository root mismatch`。

所以这个 rc2 的最小归因是“新 WT 未得到 SDK 本地前置”，属于资源/环境供给缺口。实现无需因该 rc2 修改；恢复路径应在派 impl 前显式提供或核验不含敏感值的 local SDK 前置，缺失则保持 exit 2。

旧 `M.baseline-bundle.impl-bypass` 的 rc2 则是另一个账本问题：该旧门寻找旧 frozen fixture 路径，当前 successor3 资源并未保证该旧 fixture；它不能替 successor3 controlled bypass。新 `M.baseline-bundle.successor3-impl-bypass` 在本轮没有有效结果。

### probe rc1：旧 WT 名称下的旧门形式红，不是实质 probe 缺陷

运行 cwd 虽是 `wt-provenance-oracle`，但该 WT 同样是当前 successor3 HEAD，且 `PROBE.md` 已存在。rc1 来自旧 `baseline-bundle-probe.sh` 的硬编码字段 `source_tree_sha256`；successor3 probe 任务书要求的是 `SUCCESSOR3_*` token、`bundle_id`、`independent_builds` 等新契约，不要求旧字段。故这是 legacy required gate 与新 artifact schema 的形式不相容。

新 `M.baseline-bundle.successor3-probe` 是否通过本轮不可判，因为 acceptance 在旧门 rc1 后停止；不能把旧门红升级成 successor3 probe 的实质缺陷，也不能把它当成 successor3 新门已通过。

## 最小连续修法（不在本次执行）

### 需要修账本 required/resources

1. successor3 impl 的 required 移除旧 `M.baseline-bundle.impl-bypass`，只保留 `M.baseline-bundle.successor3-impl` 与 `M.baseline-bundle.successor3-impl-bypass`；相应从 successor3 impl 的资源声明中移除仅为旧 bypass 服务的旧 fixture/read path，避免旧门再次执行。
2. successor3 probe 的 required 移除旧 `M.baseline-bundle.probe`，只保留 `M.baseline-bundle.successor3-probe`；旧 `baseline-bundle-probe.sh` 可留作历史对照，但不能作为 successor3 的成功门。
3. 在 impl 派发前把 `app/local.properties`/SDK 可达性声明为外部环境前置并作无值泄露的存在性核验；若 ledger `resources` 只表达读范围而不表达供给，则应在受管 WT bootstrap/command 格注册该前置，或让 gate 在缺失时明确维持 unjudgeable。不要把该文件内容写入结果。
4. 在创建 WT 后、首格 acceptance 前，核验每棵实际 WT 的 HEAD 与 ledger provenance 语义（精确 pin 或明确祖先规则），并逐项核验 required argv 在该 WT 内存在；不能只用 prelaunch 时的仓根 `git cat-file` reachability 代替运行时 WT 检查。

### 不需要先改实现

本轮没有足够证据证明 bundle 实现、probe 内容或 successor3 canonical/bypass 实质有错。test/probe 任务书也把 grep 门限定为交付形状；实现性判断必须等 SDK 前置满足、旧 required 移除后，由新 successor3 gates 在正确 WT 中实际运行。不得为了把 rc2/rc1 变成 0 而放宽四态或删除判据。

## 上一轮启动审查漏检项

上一轮 `baseline-bundle-successor3-final-review` 的 prelaunch 结论正确覆盖了当时“三个 WT 不存在”、DSL/schema/preflight/dry-run 和 bootstrap commit 的 reachability，但漏了以下运行时交叉检查：

1. 没有逐项审计 successor3 task 的 `acceptance.required` 是否残留 legacy 门；因此“15 个路径可达”掩盖了旧 `baseline-bundle-probe.sh` / `baseline-bundle-bypass-probes.sh` 仍会先执行。
2. 没有在 WT 创建后重核实际 cwd、HEAD pin、required argv 和非版本化 `app/local.properties`；prelaunch 的不存在快照不能证明后续 runtime WT 的输入完整。
3. 没有用一个实际创建的 WT 做“新 required 门全能运行、旧门不会抢先”的 smoke shape check；结果新 WT 中旧门照样被 ledger required 调用。
4. 没有把 SDK 本地前置作为 launchability gate；successor3 impl script 虽然有正确的 rc2 语义，启动审查没有先确认三 WT 都具备该前置。

## 本次安全边界

只读 ledger resources/required、driver acceptance refs、WT HEAD/脚本和 worker artifacts；未修改账本、source、判据或产品码，未清 attempt，未重派，未杀席，未读取凭据或 argv。

verdict: pass
