# successor9 final recovery startup review

Fresh read-only review of the successor9 recovery package. No ledger drive,
ADB, emulator, qemu, collect, redispatch, or product modification was done.

## Identity and graph

- The compiled ledger is `ledger.baseline-bundle.successor9.v1`, revision 1,
  with exactly 9 planned tasks. No task has `attempts` or `statuses`.
- The graph has exactly 8 `requires_success` edges. Its first frontier is
  exactly the three command consumers in `wt-maple-core`, `wt-s7-cedar`, and
  `wt-s7-orbit`; their commands and empty write paths are unchanged from
  successor8 r4.
- The three consumers precede the apparatus, followed strictly by
  `verify -> user-gate -> migrate -> measure -> final`.

## Historical continuity and apparatus

- Successor8 r4's three consumers remain succeeded. Its apparatus remains the
  historical `acceptance_pending`, exit 2, `fixed system image unavailable`
  unjudgeable result; the old result was not rewritten.
- Successor9's apparatus command is exactly
  `/bin/sh .team/ledgers/acceptance/baseline-bundle-successor9-owned-emulator.sh`.
  Its required set adds the selector regression while retaining the successor7
  apparatus, fixture, and continuity gates. The wrapper order is strict
  envcheck, selector, then successor7 owned-emulator.
- The inherited downstream acceptance keeps strict environment gating,
  A/B/A/B with n>=10 per segment, nearest-rank p50/p95, every B/A<=1.10, and
  the real-device “seconds-open, no blank” user gate. Migration remains after
  the user gate.

## Provenance and retained worktrees

The five pinned commits are present and ancestry-valid: `61af5e3c4`,
`6dbf110a5`, `bd48271b9`, `0fdee1072`, and `e6c2e2625`. The fixed command pair
is source HEAD `7485102b26ed34eb828e94900902147d5e00e995` with binary MD5
`627f5e6fa5f47a61d23a09b918b50567`.

Retained worktree HEADs are exact: maple=`0fdee1072`, cedar=`25517d808`,
orbit=`25517d808`. Frozen RED/PROBE digests and target-file identity pass the
structure gate. Successor9 lease, driver PID, and lease trace are absent.

## Fresh checks

The fixed pair returned preflight rc 0 (`issues=[]`) and dry-run rc 0 with
exactly the three consumer commands in the frontier; apparatus and all later
tasks were excluded by unsatisfied `requires_success` dependencies. Two fresh
DSL compilations under the fixed source environment were byte-identical to
the compiled ledger. Structure, shell syntax, shellcheck, and ledger identity
checks all returned rc 0.

The review is startup-safe and the package is internally closed.

verdict: pass
