# successor10 final recovery startup review

Fresh, read-only review of the successor10 final recovery package. No
`--drive`, ledger execution, ADB/emulator/qemu launch, collect, or package
modification was performed.

## Ledger and frontier

- Identity is `ledger.baseline-bundle.successor10.v1`, revision 1: exactly 9
  planned tasks, 8 `requires_success` edges, no statuses, and no successor10
  lease.
- The first frontier is exactly the three command consumers on the retained
  successor9 r4 WT coordinates: maple continuity, cedar RED, and orbit PROBE.
  Their commands, empty write paths, and expected command exits are preserved.
- The fixed compatible binary returned preflight rc 0 with no issues and
  dry-run rc 0 with exactly those three frontier commands. Apparatus and all
  downstream tasks were dependency-unsatisfied.

## Apparatus and downstream contract

- Apparatus is exactly `/bin/sh
  .team/ledgers/acceptance/baseline-bundle-successor10-owned-emulator.sh`.
  Its five required gates are precisely successor10 AVD-create, successor9
  selector, successor7 ownership apparatus, successor7 permanent fixture, and
  successor7 continuity.
- The wrapper sequence is strict envcheck → selector → AVD preflight/create and
  evidence → retained bundle resolve → successor7 owned PID+serial runner →
  install → owned cleanup/recovery. AVD/evidence/name failure is gated before
  runner/emulator/qemu/install.
- `verify → user-gate → migrate → measure → final` remains chained by
  `requires_success`, with the inherited real-device gate, strict environment
  gate, A/B/A/B n>=10, nearest-rank p50/p95, and B/A<=1.10 unchanged.

## Provenance and historical continuity

The immutable pins `efed31310`, `918b4c06`, `9ea73dff`, and `ad7468f7` are
ancestry-valid and the structure gate retrieves the successor9 r4 ledger/driver,
diagnosis, successor10 bootstrap, and review paths from Git. Successor9 r4's
three consumers remain succeeded; its apparatus remains the preserved exit-2,
`acceptance_pending`, fresh-AVD-creation unjudgeable attempt.

Retained WT heads are maple=`9ea73dff8`, cedar=`25517d808`, and
orbit=`25517d808`; frozen RED/PROBE digests and command pair source
`7485102b26ed34eb828e94900902147d5e00e995` match. The fixed binary MD5 is
`627f5e6fa5f47a61d23a09b918b50567`.

The structure mutation matrix rejects old apparatus, dropped AVD/selector
gates, consume-agent substitution, weakened measure, statuses, dropped edge,
dropped artifact, and wrong provenance; a missing ledger is unjudgeable.

verdict: pass
