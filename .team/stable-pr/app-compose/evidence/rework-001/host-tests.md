# REWORK-001 host barrier

No failed command was rerun on the same exact candidate. Preserved red receipts remain beside explicitly authorized superseding receipts.

## Passed gates

| Gate | Bound candidate/tree | Result |
|---|---|---|
| Rust nodeprobe locked/single-thread | `50e3798ce`, unchanged nodeprobe inputs through final | 34 unit + 1 integration, 0 failed |
| Node Pi extension | `50e3798ce`, unchanged input through final | 1, 0 failed |
| Go four packages `-count=1` | `d9a553119`, server tree identical to final `54d28cf…` | four packages PASS; 54s |
| Provider focused JVM uncached | `d9a553119`, main/JVM app sources unchanged through final | 7 parsed tests, 0 failed/skipped; 41 tasks executed |
| core/app JVM uncached | `d9a553119`, main/JVM app sources unchanged through final | core 51 + app 608, 0 failed/skipped; 43 tasks executed |
| Provider assets/license/hash | `50e3798ce`, asset blobs unchanged through final | 6 SVG + 6 generated + LICENSE hashes, 6 mappings, package and NOTICE PASS |
| Provider instrumentation compile | final code candidate `ac84f22ef` | `assembleDebugAndroidTest` PASS; 55 tasks executed |
| Release assemble | final code candidate `ac84f22ef` | PASS; 57 tasks executed |
| incremental architecture attribution | strict run at `50e3798ce`; scanned production surface unchanged | zero new normalized T1/T3 findings |
| coordinate barrier | frozen inputs | `3 → 24 → 9`, 36 one-to-one range mappings |

The status-only scope gate remains **N/A** after composition, not PASS. CI with no checks remains `unknown`.

## Preserved invalid/red receipts

- initial Provider focused invocation: zero tests due missing SDK;
- initial asset invocation: wrong generated-resource relative path;
- `50e3798ce` Go four-package red: predecessor ack 1 accepted while waiting for ack 2;
- `d9a553119` instrumentation compile red: two invalid imports;
- strict global baseline debt remains exit 1.

Each superseding run was made only after explicit leader authorization on a new exact candidate. Raw logs and `.rc` files are under `logs/`.
