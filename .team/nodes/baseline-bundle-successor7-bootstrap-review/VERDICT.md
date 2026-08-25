# successor7 apparatus bootstrap review

This was a fresh, read-only semantic review. No adb, emulator, qemu, product
implementation, old ledger/attempt, or private APK was touched.

The bootstrap has useful positive structure: strict preflight is intended to
precede launch; the fake regression covers fresh AVD, PID/serial binding,
install timeout, ambient-process survival, cleanup=false rejection, and
permanent fixture green/forged/missing states; continuity binds the historical
ledger and retained `wt-maple-core`; and the historical provenance object is
available.

It is nevertheless refuted by source-level contract gaps:

- `baseline-bundle-successor7-owned-emulator.sh` only treats
  `SUCCESSOR7_FIXTURE_MODE=1` as fixture mode and only checks four explicitly
  named test variables. Non-empty `SUCCESSOR7_FIXTURE_MODE` values other than
  `1`, and arbitrary `SUCCESSOR7_TEST_*` variables, are not rejected in the
  production branch. This violates the required fail-closed production
  override boundary.
- `baseline-bundle-successor7-apparatus.py` writes evidence with `0600` but
  its validator never checks the file mode. A changed mode can therefore pass
  the official evidence validator, contrary to the fixed-0600 requirement.
- The timeout helper sends TERM and immediately performs an unbounded `wait`;
  runner shutdown also waits without a deadline. The stated bounded command,
  timeout, and cleanup contract is not enforced for TERM-ignoring children.

The candidate permanent fixture is present at its fixed path and has the
declared provenance inputs, but is currently untracked before the promised
bootstrap commit; the commit must make it durable before final-ledger
generation. Full source and test evidence is in `tests.log`.

verdict: refutes
