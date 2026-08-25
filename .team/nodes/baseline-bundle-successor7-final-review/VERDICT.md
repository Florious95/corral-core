# successor7 compatible-pair post-commit review

Scope was limited to the committed package and the explicitly supplied
command-executor binary. Previously passed semantic items were not re-run or
reclassified.

The supplied binary is auditable: it is the arm64 executable at
`/Volumes/nvme/cargo-target-w7-builder-b/debug/ledger-run` with SHA-256
`3090060248410c463a2194d5a4840b7782494a2c1415062f71431571735172cc`; its
source worktree `/Volumes/nvme/Projects/无等编排/.worktrees/wt-cmd-executor`
HEAD is exactly `7485102b26ed34eb828e94900902147d5e00e995` (`feat: add command
task executor`).

With that compatible pair, fresh `--preflight --json` and `--dry-run --json`
both return 0. Dry-run reports exactly the three initial task IDs:
`continuity`, `apparatus-test`, and `apparatus-probe`. The compiled apparatus
task retains `executor=command` and the exact owned-emulator argv; it is not
dropped by the compatible validator. The default installed binary returning
2 is only a dialect contrast, not evidence against this package pair.

The immutable lineage checks also pass: commit `79cd08f0f` yields all 50
successor7 paths through NUL-safe `git cat-file`; the ledger is revision 1
with nine planned tasks and no attempts/statuses; `wt-s7-cedar` and
`wt-s7-orbit` plus successor7 lease/PID are absent, while retained
`wt-maple-core` remains present. No drive, dispatch, WT creation, or device
launch occurred.

Minimal follow-up is operational: launch only with the supplied compatible
binary/source pair, preserving the committed ledger and prior semantic gates.

verdict: pass
