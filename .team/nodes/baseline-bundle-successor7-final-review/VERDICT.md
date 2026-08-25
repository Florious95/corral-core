# successor7 post-commit lineage review

Scope was limited to the committed package at `79cd08f0f`; prior semantic
pass items were not re-run or reclassified.

The immutable lineage checks pass:

- `git cat-file` can retrieve the successor7 DSL, compiled ledger, all
  successor7 acceptance wrappers, path-level taskbooks, bootstrap fixture
  files, and review/package logs. A NUL-safe sweep covered 50 successor7
  paths with zero missing objects.
- The compiled ledger is
  `ledger.baseline-bundle.successor7.v1`, revision `1`, with nine planned
  tasks, zero task `attempts` fields, and no `statuses` field. Its 12 compiled
  script references all resolve in the same commit.
- `wt-s7-cedar` and `wt-s7-orbit` are absent from worktree metadata; retained
  `wt-maple-core` is present. Successor7 lease and driver PID paths are
  absent. The pre-existing perf-regress lease/PID are not successor7 state.

The post-commit live mechanical check does not pass: using the real
`ledger-run` against the committed ledger, both `--preflight --json` and
`--dry-run --json` exit `2` with:

`/tasks/t.baseline-bundle.apparatus Additional properties are not allowed ('command', 'executor' were unexpected)`

Thus the requested fresh first-frontier check is not executable, and the
previous logged frontier cannot substitute for it. Minimal repair boundary:
freeze and commit a compatible DSL compiler/validator dialect pair, then
regenerate and re-cat the compiled ledger; do not alter the already-passed
semantic gates.

verdict: refutes
