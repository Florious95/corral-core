# successor4 creation result

Created but did not start `ledger.baseline-bundle.successor4.v1` revision 1. The package keeps the complete nine-task graph, B/A<=1.10, the real-device user gate and the migrate mechanical prerequisite. It uses three new absent WT identities and immutable provenance `ec1145820186b8862949b84fe56f9309c1b0754f`, a descendant of tracked bootstrap `f0fce0a44`.

Closed run1 findings:

- impl required is exactly successor4 impl plus the tracked bootstrap controlled-bypass gate; legacy `M.baseline-bundle.impl-bypass` is absent.
- probe required is exactly the successor4 probe gate; legacy `M.baseline-bundle.probe` and its source_tree_sha256 form gate are absent.
- every Gradle-capable task is authorized and instructed to generate a non-versioned `app/local.properties` from `ANDROID_SDK_ROOT`/`ANDROID_HOME` before Gradle without printing values. The silent SDK judge maps success to0, a committed file to1, and missing environment/apparatus to2.
- test/probe wrappers run the exact-required structural judge. Fresh controlled mutations prove exact=0, legacy impl=1, legacy probe=1 and old argv=1.
- canonical/path-projection and fixed-fixture gates remain the Git-tracked successor3 bootstrap real gates; downstream archive, fresh A/B/A/B, 1.10 and user gates are unchanged.

Fresh checks all passed: deterministic compile, ledgerdsl 0.1.1 schema/preflight, ledger-run preflight/dry-run with only repro frontier, sh -n, shellcheck, provenance ancestry and reachability, old-ledger SHA freeze, new-WT absence, exact required sets, SDK four-state and legacy negative teeth. No ledger drive, old ledger/attempt edit, worktree creation, product-code edit, commit or credential read occurred.

Startup remains conditional on leader review and committing this successor4 package so startup-time current main can retrieve it while descending from the pinned provenance.

verdict: pass
