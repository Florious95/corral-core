# successor10 AVD-create bootstrap review

Fresh, read-only semantic review. No production wrapper, selector, adb,
emulator, qemu, or final ledger was started; the reviewed package was not
modified.

## Contract

- The helper creates only a task-local empty `ANDROID_AVD_HOME` with mode
  `0700`, rejects existing names and unsafe targets before invoking the tool,
  and generates a production name matching the successor10 namespace.
- It uses the exact package
  `system-images;android-35;google_apis;arm64-v8a`, exact `pixel_6` profile,
  exact no-input `avdmanager create avd --force ...` argv, and a bounded owned
  process group. It independently revalidates device, image, ABI, and tag in
  `config.ini`.
- Tool stdout/stderr are captured in node-local mode-0600 files, hashed, then
  removed. External output is limited to reason code, rc, digests, and the
  cleanup boolean; the regression's secret-path checks found no SDK/AVD path
  or raw text leakage.

## Wrapper and failure boundary

The production/fixture selector is fail-closed. In the named launch sequence,
successor9 selector precedes AVD creation, which precedes the successor7
ownership runner (`EMULATOR_LAUNCHER`); any AVD helper/evidence/name failure
exits before that runner. The inherited continuity gate runs as a separate
precondition before the selector and does not launch an emulator. Fixture
overrides require the exact harness marker and node-local temp root; production
rejects `SUCCESSOR10_TEST_*` overrides.

## Fresh evidence

- Python byte compile, POSIX syntax, and shellcheck passed.
- Fake regression passed success, mode/name policy, license/input, device
  missing/mismatch, package missing/mismatch, timeout process-group cleanup,
  no-leak, evidence permission/schema/digest/missing teeth, and zero
  runner/adb/emulator launch checks.
- Immutable predecessor inputs and ownership/selector gates are retrievable
  from HEAD `918b4c06ff93271b0c84bfc187bde1f3a5a93db3`; no successor10 final
  ledger exists. The new bootstrap files remain pre-commit inputs as required
  for this review stage.

The bootstrap contract is closed for a later successor10 ledger; this review
did not authorize or perform production execution.

verdict: pass
