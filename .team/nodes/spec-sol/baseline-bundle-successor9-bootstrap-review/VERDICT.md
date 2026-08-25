# successor9 SDK selector bootstrap review

Fresh, read-only review of the selector bootstrap. No production selector,
owned emulator, adb/qemu, final ledger, or device was started; the package was
not modified.

The selector implementation closes the requested source and identity rules:
it gathers non-empty `ANDROID_SDK_ROOT`, `ANDROID_HOME`, strict root-local
`sdk.dir`, and PATH sdkmanager-derived roots; canonicalizes and inode-dedups
them; then independently requires the exact package XML, regular in-root
adb/emulator/avdmanager executables, and sdkmanager
`--sdk_root=<root> --list_installed` reporting the exact package. Invalid
source-local roots do not mask a unique valid sdkmanager root, while two valid
roots are exit 2.

Target handling is fail-closed: only the current Git worktree's existing
untracked regular non-symlink `app/local.properties` is rewritten. Unknown or
duplicate keys, tracked/symlink/non-regular targets, and malformed escaped
values are rejected without rewrite; missing/zero/multiple valid roots are
unjudgeable. The write is atomic, fsync-backed, one-line `sdk.dir`, mode 0600,
and remains untracked. Selector and regression output contain only counts and
boolean status, not paths or SDK values.

Fresh regression returned rc0 across same-root/inode dedup, wrong-root versus
valid sdkmanager, dual-valid ambiguity, escaping, two-line normalization,
unknown key, tracked/symlink/missing target, exact-package, and no-leak teeth.
The future owned-emulator wrapper statically orders strict envcheck → selector
→ successor7 owned apparatus, excludes production `SUCCESSOR9_TEST_*`, and
does not reference a final ledger; successor7 ownership/cleanup gates remain
the delegated downstream contract.

No successor9 DSL or compiled final ledger exists, as required for this
bootstrap phase. All requested bootstrap facts are mechanically closed.

verdict: pass
