# successor5 impl rc1 只读归因

## 结论

本轮 `ledger.baseline-bundle.successor5.v1` revision 4 的 `t.baseline-bundle.impl`
冻结为 `failed_retryable`。账本记录的红是
`M.baseline-bundle.successor5-impl`：`/bin/sh
.team/ledgers/acceptance/baseline-bundle-successor5-impl.sh`，cwd 为
`.worktrees/wt-cedar-main`，期望 0、实际 1。driver 只留下空的 `stderr_tail`，没有
保存叶门的错误行；下面的叶门及操作数由现行脚本和 manifest 的只读对照确定。

这不是 SDK、fixture、provenance 或 canonical `bundle_id` 计算失败。直接原因是
successor5 impl wrapper 第 9 行传递调用 `baseline-bundle-successor3-impl.sh`，后者
又调用旧的 `baseline-bundle-impl.sh`。旧门第 161 行要求每个独立 APK 的路径以
`.team/private/baseline-vault/{bundle_id}/builds/` 开头；successor5 worker 交付的
projection-aware 路径不含 bundle id。旧门因此在第一个 independent build 的
`safe_rel` 比较处返回 1。

## 精确操作数

来源：`.worktrees/wt-cedar-main/.team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json`
以及同 WT 的
`.team/ledgers/acceptance/baseline-bundle-impl.sh:125-168,185-187`。

| 比较 | 期望/重算值 | manifest 实际值 | 结果 |
|---|---|---|---|
| canonical projection → `bundle_id` | `baf29dacb5653c7c4f74c193e4f506a5315c238a741f084b9f31e68ca044e13c` | 同值 | 相等 |
| archive primary | `.team/private/baseline-vault/baf29dacb5653c7c4f74c193e4f506a5315c238a741f084b9f31e68ca044e13c/baseline.apk` | 同值 | 相等 |
| archive backup | `.team/private/baseline-backup/baf29dacb5653c7c4f74c193e4f506a5315c238a741f084b9f31e68ca044e13c/baseline.apk` | 同值 | 相等 |
| old gate build-1 prefix | `.team/private/baseline-vault/baf29dacb5653c7c4f74c193e4f506a5315c238a741f084b9f31e68ca044e13c/builds/` | `.team/private/baseline-vault/builds/build-1.apk` | 不匹配，首个叶红 |
| old gate build-2 prefix | 同上 | `.team/private/baseline-vault/builds-second/build-2.apk` | 同样不匹配，首个红后未到达 |

旧门随后才会检查文件存在、独立 inode、签名/运行内容、报告摘要及最终
`bundle_id`。因此本轮 rc1 不能被解释为这些后置事实失败。source-derived 的首个
错误形状是 `safe_rel` 的 “unsafe or noncanonical path” 分支；该文字不在 driver
的截断证据中，不能冒充已保存的 stderr 原文。

## 归因边界

- **canonical bundle_id/path projection：** manifest 的六段 projection 重算结果与
  `bundle_id` 相等；`build.independent_builds[].apk_relpath` 确实参与 hash。把 build
  路径再放进 `{bundle_id}` 目录会要求先知道 hash，而 hash 又依赖该路径，正是旧门
  与 successor5 设计的固定点冲突。archive 路径含 id 则与当前 projection 不冲突，且
  manifest 已相符。
- **SDK：** worker 的 `IMPL.md` 记录 successor5 SDK gate/regression 已通过；当前
  红发生在 successor3 wrapper 的真实 bundle 深门之后。没有 SDK 缺失或凭据内容证据，
  不把 rc1 改判为 rc2。
- **fixture：** fixed `control-contract.json` 摘要与 canonical/controlled-bypass
  入口属于 successor3 bootstrap 门；worker 记录其控制齿为绿。它不是本次旧深门的
  首个比较操作数。新 case 仍须重新执行 bypass，因为本轮 required acceptance 没有
  单独留下 `M.baseline-bundle.successor5-bypass` 的成功回执。
- **provenance：** worker WT HEAD 为 `b2af6fd182d4bad766a6b62a82994565baeab9ab`；
  ledger resource provenance 为 `2f76349afdb42d4d0dcdc97e8ccc6e02868ec263`，现行脚本
  实际使用祖先门而非精确 HEAD 相等门。脚本已走到路径校验，driver 没有记录
  provenance mismatch；不能把它升级成 provenance 红。
- **diff/交付形状：** WT 的 tracked diff 只有 `.gitignore`，bundle 工具、manifest
  和 `.team` 产物是 worker 的未跟踪交付。该形状不是本次 rc1 的叶门判据；判据直接
  读取这些路径。manifest 的 `source.commit`/`dirty=false` 是冻结来源身份，不是 WT
  当前工作树洁净声明。

因此准确归属是：**successor5 新 projection 语义与仍被 successor5 wrapper
传递调用的 legacy implementation acceptance contract 漂移/不相容**。这不是证明
bundle 工具本身已通过；它是当前 required 门无法对该交付作有效绿判的实质 apparatus
红。

## 最小新 case 返修清单

以下仅为返修设计，本席没有清 attempt、改 ledger、重派或运行任何门：

1. 由 leader 为 impl 生成 fresh case_id，并保留 revision 4 的 repro/probe/test
   成功证据；不要沿用冻结 impl case。先在判据/账本侧明确 successor5 的路径契约：
   build 产物可位于固定、非 id 目录（如现有 `builds/` 与 `builds-second/`），archive
   仍是 `{bundle_id}` 内容寻址目录。
2. 修 successor5 acceptance adapter（或其 successor5 专用深门）使其验证上述安全
   前缀、两个独立 build root、APK/运行/签名/报告摘要和 canonical hash；不得继续把
   legacy `baseline-bundle-impl.sh` 的 id-scoped build 前缀作为 successor5 绿门。
   直接把 build 文件移动到 id-scoped 路径不是最小修法：在当前 projection 下会重建
   bundle-id/path 固定点，改变语义才能解除矛盾。
3. 保持 required 名称与 successor5 账本语义不漂移：新 case 依次重跑
   `M.baseline-bundle.successor5-impl` 与 `M.baseline-bundle.successor5-bypass`；
   SDK 前置仍先走、无值泄露。不要删除 canonical、archive、A2 equivalence 或
   provenance 破坏齿来换取 0。
4. 新 gate 绿后，独立确认 manifest projection 重算等于
   `baf29…e13c`（或新 case 新产物对应的新值）、两份 build 路径各自存在且互异、
   archive primary/backup 为不同 regular inode 且 sealed，再让后续 verify 消费。

## 可沿用证据与必须新增的探针

可沿用但不冒充 impl 绿：

- `.worktrees/wt-owl-audit/.team/nodes/baseline-bundle-probe/PROBE.md`，ledger
  revision 3 的 `M.baseline-bundle.successor5-probe` succeeded；可沿用 successor5
  required 精确集合、SDK fallback 四态、fixed contract 操作数和
  `source_tree_sha256=not_required` 边界。
- `.worktrees/wt-ruby-lab/.team/nodes/baseline-bundle-test/RED.md`，ledger revision
  4 的 `M.baseline-bundle.successor5-test` succeeded；可沿用 required legacy-negative、
  SDK `extra/duplicate/invalid=2`、tracked target=1、canonical stale/final/forged
  红绿设计及无缓存命令约束。
- 两份证据只覆盖结构/探针设计与测试红测；不能覆盖本轮 impl 深门，也不能把
  worker `IMPL.md` 的 `implementation: pass` 当作 acceptance success。

必须新增一组直接锁定本次根因的红绿齿：

1. **successor5-path green：** 用真实 successor5 manifest，保留当前
   `builds/build-1.apk` 与 `builds-second/build-2.apk` projection，重算
   `bundle_id`，要求 successor5 专用深门 rc0；同时核 archive id-scoped 路径。
2. **legacy-prefix incompatibility red：** 仅把第一个 `apk_relpath` 变为旧门要求的
   `.team/private/baseline-vault/{bundle_id}/builds/build-1.apk`，固定同一 id 后应由
   canonical hash 或文件路径前置明确拒绝，并报告“旧门契约不适用”，不能变成产品
   失败或静默绿。更直接的 gate spy 应证明 successor5 impl 不再传递调用旧
   `baseline-bundle-impl.sh` 的 id-scoped check。
3. **canonical stale red / final green：** 只改
   `build.independent_builds[0].apk_relpath` 为 stale/PENDING 而不改 id，retrieve
   应 rc2 且 `manifest bundle_id mismatch`；恢复 final projection 应 rc0 且输出
   字节一致。
4. **provenance forgery red：** 只改 `implementation.bundle_py.sha256` 或
   `runner_sha256`，底层 rc2、hardened controlled gate rc1；真实 root/raw 合法时
   不得落到 `repository root mismatch` 或 `empty raw log` 旁路。
5. **A2/archive green+red：** 两个 no-cache build 的 normalized runtime、签名、包名、
   版本全等为绿；任一 APK 运行内容、symlink、共享 inode、可写位或 backup 摘要变异为
   红/不可判，保持 exit 语义不变。

本次未做：未读取凭据/SDK 值/私有 APK 内容，未重跑判据或 Gradle，未清 attempt，未
重派、未杀席、未修改产品码、账本或现行判据。

verdict: pass
