# successor7 frontier recovery verdict

观察时间：`2026-08-25T13:32:39+0800`。本格只读；没有 collect、再重派、账本修改或
driver/resident 启动。三前格旧 ledger 仍为
`.team/ledgers/baseline-bundle-successor7-v1.json`，`ledger_id=
ledger.baseline-bundle.successor7.v1`、`revision=1`，SHA-256
`447d8a6fb608c2ab520c0a4f3a3d7bb5ab69f9818d2e008bb049096d103320dd`；其三格仍
`planned`，没有可安全 collect 的持久 attempts。

## 三个 required acceptance

按任务声明的 worktree 逐个只读运行：

| 前格 | worktree | required acceptance / command | rc | 结果 |
|---|---|---|---:|---|
| continuity | `wt-maple-core` | `baseline-bundle-successor7-continuity.sh` | 0 | `evidence_commit=3528c2ad5`、四格 succeeded、`manifest_bound=true`、`archives_bound=true` |
| apparatus-test | `wt-s7-cedar` | `baseline-bundle-successor7-test.sh` | 0 | structure、fake apparatus 四态、permanent fixture 与 bounded cleanup 全通过 |
| apparatus-probe | `wt-s7-orbit` | `baseline-bundle-successor7-probe.sh` | 0 | 独立坐标、ownership/cleanup、permanent fixture 与 structure 全通过 |

账本中 continuity 的 `acceptance.required` 实际为空；它的可执行门是 task-level
command 的 exit 0。test/probe 的 required 分别是
`M.baseline-bundle.successor7-test` 与 `M.baseline-bundle.successor7-probe`。

## 产物新鲜度与 r1 绑定

- test 产物：
  `.worktrees/wt-s7-cedar/.team/nodes/baseline-bundle-successor7-test/RED.md`，mtime
  `2026-08-25T12:04:00+0800`，SHA-256
  `04cdbd661548a4b3261c88d491cf80c48f98dcbe3c080e710fb7d12bbe6c105a`；未被本次
  acceptance 执行改写。它声明 successor7 structure/continuity 的 r1 语义锚点；其
  required test gate 又对当前 compiled ledger 机械核出 `revision=1`、9 格、8 依赖、
  exact frontier，故为 gate-correlated 的 r1 证据。
- probe 产物：
  `.worktrees/wt-s7-orbit/.team/nodes/baseline-bundle-successor7-probe/PROBE.md`，mtime
  `2026-08-25T12:05:40+0800`，SHA-256
  `88868a1a1979d3f1504e5efd6876dc5ca8ed5cc6b45a2eb6f6dd23b8e5176cf7`；文件直接声明
  `ledger_id=ledger.baseline-bundle.successor7.v1`、`revision=1`、provenance pin
  `0df3562b7f7479ce4a2683f8c98546fab69bcf1c`、`wt-s7-cedar`/`wt-s7-orbit` 与
  `first_frontier=continuity+apparatus-test+apparatus-probe`。
- continuity 输出把 retained `wt-maple-core`、`3528c2ad5` 四格证据、manifest 与双
  归档绑定；没有旧结果消费或复制动作。本轮 scratch 已清理，两个产物 inode/hash
  在 acceptance 前后不变。

唯一的形式性注意：RED.md 没有自行打印完整 r1 ledger SHA，而是由同一 required
  structure gate 对当前 ledger 绑定；新账本消费它时必须把上述 ledger SHA、两份产物
  SHA 和 worktree 坐标写进 `read_paths`/command guard，不能仅凭文件名当新证据。

## 最小连续方案（不修改当前 r1）

用 sol 席按 DSL 新建一个 successor7 frontier-recovery ledger source（不要手写 live
JSON），父事实固定为上述 r1 ledger SHA。只保留四个 command task：

1. `continuity.consume`：cwd `${worktree}`→`wt-maple-core`，argv
   `/bin/sh .team/ledgers/acceptance/baseline-bundle-successor7-continuity.sh`，
   expected 0、unjudgeable `[2]`、required 为空；
2. `apparatus-test.consume`：cwd→`wt-s7-cedar`，argv
   `/bin/sh .team/ledgers/acceptance/baseline-bundle-successor7-test.sh`，同样以
   command rc 作为机械门，消费现有 `RED.md`；
3. `apparatus-probe.consume`：cwd→`wt-s7-orbit`，argv
   `/bin/sh .team/ledgers/acceptance/baseline-bundle-successor7-probe.sh`，同样以
   command rc 作为机械门，消费现有 `PROBE.md`；
4. 保留原 apparatus command：cwd→`wt-maple-core`，argv
   `/bin/sh .team/ledgers/acceptance/baseline-bundle-successor7-owned-emulator.sh`，
   只在前三项 `requires_success` 后放行，保留原三条 apparatus/fixture/continuity
   required acceptance。

新 ledger 的前三项分别指向原 apparatus；三条边汇合后才指向 apparatus。command task
   不 `team-agent send`、不等 agent `report_result`；每项带 r1 ledger SHA、RED/PROBE
   SHA、worktree 与 bounded budget 的 read-only guard。使用已验证的 command pair：

```sh
/Volumes/nvme/cargo-target-w7-builder-b/debug/ledger-run --preflight --json <recovery.json>
/Volumes/nvme/cargo-target-w7-builder-b/debug/ledger-run --dry-run --json <recovery.json>
# 审核通过后才允许由 leader 决定是否 --drive；本次未执行 --drive
```

这条路径消费现有三格的机械事实并让 apparatus command 继续，不 collect 当前 driver
的 waiter 结果，也不以旧 agent 自报冒充 command 证据。

verdict: pass
