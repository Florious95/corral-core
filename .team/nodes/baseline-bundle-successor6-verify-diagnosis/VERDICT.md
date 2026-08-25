# successor6 verify rc2 只读归因

## 结论

账本 .team/ledgers/baseline-bundle-successor6-v1.json 为 revision 5；repro、impl、probe、test 均已 succeeded，verify 的 M.baseline-bundle.successor6-verify 在 wt-maple-core 返回 rc2。artifact_refs 精确记录：

    acceptance_failure.acceptance_id=M.baseline-bundle.successor6-verify
    acceptance_failure.argv=["/bin/sh", ".team/ledgers/acceptance/baseline-bundle-successor6-verify.sh"]
    acceptance_failure.cwd=/Volumes/nvme/Projects/远程Agent安卓/.worktrees/wt-maple-core
    acceptance_failure.exit_code=2
    acceptance_failure.expected_exit_code=0
    stderr_tail=UNJUDGEABLE baseline-bundle-successor6-verify: independent verifier could not judge

直接原因不是 bundle 实现红，而是 worker 的 VERIFY.md 末行是 verdict: unjudgeable。baseline-bundle-successor6-verify.sh:15-24 先检查该报告，在第 19 行把 unjudgeable 映射为 exit 2；因此后续 VERIFY.json 机械检查尚未成为本次 rc2 的独立叶门。worker 的 VERIFY.json 与配套记录给出了两个确切缺口：没有 adb/owned emulator，且 permanent bypass probe 的冻结 impl-bypass fixture 缺失。

## 判据与操作数

baseline-bundle-successor6-verify.sh 的顺序是：

1. 先调用 baseline-bundle-successor6-impl.sh；本轮 impl 已在 ledger 中以 M.baseline-bundle.successor6-impl 和 M.baseline-bundle.successor6-bypass 成功写回 revision 3。
2. 检查 verify worker 产物 VERDICT.md、RETRIEVE.md、INSTALL.md、MUTATION.md、VERIFY.json。
3. 末行只有 verdict: pass 才继续；unjudgeable 必须 exit 2，不能降级为绿。
4. 只有上述步骤通过后，才以 Python 检查 VERIFY.json 的固定布尔操作数：

    restore_pass=true
    install_exit=0
    envcheck_gate_exit=0
    independent_inode=true
    owned_qemu_cleanup=true
    mutation_red=true
    mutation_restored_green=true

worker 实际记录为：

    restore_pass=true
    install_exit=2
    install_status=unjudgeable_no_adb_or_emulator
    envcheck_gate_exit=0
    owned_qemu_cleanup=false
    owned_qemu_cleanup_status=not_executed_no_qemu_started
    independent_inode=true
    mutation_red=true
    mutation_restored_green=true
    permanent_bypass_probe_exit=2
    permanent_bypass_probe_status=unjudgeable_missing_frozen_fixture
    verdict_basis=apparatus_unavailable

已判明的绿事实包括 canonical projection、两个独立槽位、primary/backup regular file、sealed/distinct inode、backup retrieve/digest、aapt/apksigner、SDK fallback、manifest mutation red/restore green 和 envcheck。缺的是设备安装/清理与冻结旁路夹具，不是这些 bundle 事实。

## WT、本地产物、SDK 与 provenance

- verify WT HEAD 为 87ce64f0361a3f14fbfa04595cd6a3425ccd6f6a；ledger verify resource provenance 为 548572dfd7d8ee2e3f602a274268e8bd881ef8b2，该 provenance 是当前 HEAD 的祖先。successor6 deep gate 需要的 ancestry 也已由 worker 通过，未出现 provenance mismatch。
- verify 与 impl 共用 worktree_id=wt-maple-core，且 impl 先于 verify 成功；.team/nodes/baseline-bundle-impl/ROUTE.md、IMPL.md、BUNDLE-MANIFEST.json、INSTALL.md、RETRIEVE.md 和 .team/private/baseline-vault/、.team/private/baseline-backup/ 在 verify WT 均存在。因此本轮不是“需要复制 impl 产物但未复制”。
- verify WT 的 app/local.properties 存在；worker 记录 SDK fallback 为一行、0600、untracked，且 aapt/apksigner 可用。没有 SDK 值缺失或泄露证据，SDK 前置不是 rc2 原因。
- probe/test 各自位于独立 WT，verify gate 不读取其 PROBE.md/RED.md，而是消费 impl WT 的 bundle 和 verify WT 自身的报告。因此不存在本次 rc2 的跨 WT 证据不可达；probe/test 结果可作为已完成的上游 acceptance 证据，但不能替代 verify 的设备门。
- 当前 git status 的 .gitignore、bundle 工具、.team 产物及 local.properties 是本地交付形状；verify 脚本只要求这些路径可读，并未把它们误判成当前 rc2。

## fixture 与设备边界

worker 的 MUTATION.md 记录先调用 permanent baseline-bundle-bypass-probes.sh，该门返回 exit 2，因为它要求的 frozen fixture 路径组不存在/不可用，包括：

    .team/nodes/baseline-bundle-prelaunch-review/tmp/impl-bypass/
      .team/ledgers/acceptance/baseline-bundle-impl.sh
      .team/nodes/baseline-bundle-impl/BUNDLE-MANIFEST.json
    .team/nodes/baseline-bundle-prelaunch-review/tmp/measure-bypass/
      .team/ledgers/acceptance/baseline-bundle-measure.sh
      .team/nodes/baseline-bundle-measure/perf-ab-bundle.json

这是固定输入缺失的不可判，不是产品失败。它与账本中另一个 M.baseline-bundle.successor6-verify-bypass -> baseline-bundle-successor3-bypass.sh 不是同一个入口；由于主 verify 门先 rc2，后者没有产生本轮成功 artifact_ref，不能把它默认为已通过。

设备缺口同样明确：adb 在该 WT 环境不可用；没有新鲜 serial、owned qemu PID、boot observation、adb install 或 trap cleanup。没有启动 qemu，也没有碰已有 emulator、生产 daemon 或私有 APK payload。这是 verify 阶段的机器 apparatus 缺失，不是后续 user-gate 所要求的蜂窝网络/广州中转真机裁决；user-gate 尚未开始。

判据四态没有错误：缺设备或冻结 fixture 必须 rc2，不能把 verdict: unjudgeable 改写为 pass。唯一需要框架/任务设计裁定的是 permanent old bypass fixture 是否仍应为 successor6 verify 的前置；若 successor6 已用 projection regression 与 successor3 controlled fixture 取代它，则应在新 case 前修正任务契约，不能静默接受缺失。

## 最小继续方案

本席未清 attempt、未重派、未修改文件。后续由 leader 在外部前置补齐后，用新 verify case/revision 继续：

1. 保留当前四格成功，不重跑 repro/impl/probe/test。先确认 verify WT 仍可读 impl manifest、两份归档、SDK local.properties 和 provenance；本轮这些已成立。
2. 选择并固定 permanent bypass 的合法契约：若它仍是必需门，按脚本要求恢复同一 frozen fixture 并核对固定摘要；若 successor6 契约已替代 legacy fixture，则让 verify 任务只要求 successor6 的 projection/controlled-bypass 入口，并明确记录这是任务契约修正，不把缺口判绿。
3. 在不使用已有 emulator/qemu 的前提下提供可辨认的 adb 与 fresh owned emulator，取得 fresh serial，完成实际 install、绑定包身份、进程/启动观察和精确 trap cleanup；目标操作数必须为 install_exit=0、owned_qemu_cleanup=true。本席不启动它。
4. 用新的 verify case 让 M.baseline-bundle.successor6-verify 重新消费；按现行 wrapper 它会再次执行 successor6 impl 组合门，这是脚本结构的既定成本。随后才执行 M.baseline-bundle.successor6-verify-bypass，两者都得到独立 artifact_ref 后才可放行 user-gate。

## 可沿用四格证据

以下是 revision 5 ledger 已落下的 acceptance refs，可作为 verify 新 case 的上游证据索引，不代表 verify 已绿：

    repro  writeback_revision=2
      M.baseline-bundle.repro
      M.baseline-bundle.repro-regression
    impl  writeback_revision=3
      M.baseline-bundle.successor6-impl
      M.baseline-bundle.successor6-bypass
    probe writeback_revision=4
      M.baseline-bundle.successor6-probe
    test  writeback_revision=5
      M.baseline-bundle.successor6-test

这些四格是 durable ledger evidence；verify 的设备安装、owned cleanup 与旁路 fixture 仍必须补齐，不能用四格成功或 worker 自报替代。

本次只读核对 ledger、driver artifact_refs、verify worker 报告、判据脚本、WT HEAD 与本地产物存在性；没有启动 adb/qemu、没有读取 SDK 值/凭据、没有读取私有 APK 内容、没有重跑判据、清 attempt 或重派。

verdict: pass
