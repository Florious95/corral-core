# successor7 apparatus bootstrap second review

Fresh, read-only review; no adb, emulator, qemu, product implementation, old
ledger/attempt, or private APK was touched.

The three prior refutes are closed in the current source:

- `SUCCESSOR7_FIXTURE_MODE` accepts only empty/unset production or exact `1`
  isolated mode; other non-empty selectors fail before runner setup.
- Production counts `SUCCESSOR7_TEST_*` overrides before apparatus setup;
  isolated mode requires the fixed harness and node-local fixture root.
- The apparatus validator now requires official evidence to be regular,
  non-symlink, and exact mode `0600`; the derived handoff is created with the
  same mode and the 0644 mutation tooth is recorded as exit 1.
- All bounded commands and runner cleanup follow deadline → TERM grace →
  known-PID KILL grace → bounded reaping. Bound serial disappearance and owned
  qemu disappearance are checked; forced cleanup remains exit 2 and cannot
  write pass evidence.

Permanent fixture four-state evidence is green=0, forged=1, missing=2 and is
bound to the historical provenance object. Continuity binds the four
successor6 tasks, retained `wt-maple-core`, manifest/archive identity, and the
expected commit. The final successor7 ledger is absent as required before
bootstrap submission; the fixture is explicitly pending that bootstrap commit.

The source checks and existing bootstrap logs support the repaired bootstrap.

verdict: pass
