# Host gates — implementation compose `dce90469fbfb767bc2a3d2fe1afedc94fda41587`

All commands were single attempts. No failed command was rerun to green. Durable raw logs are under `evidence/logs/` and include SHA/tree/app-tree, environment, command, duration, and exit status.

| Gate | Result | Executed / failed |
|---|---|---|
| Rust nodeprobe locked, single-thread | PASS | 34 unit + 1 integration; 0 failed; 24s |
| Node Pi extension | PASS | 1; 0 failed |
| Go four packages, `-count=1` | PASS | 4 packages; 0 failed; 55s |
| `:core-protocol:test :app:testDebugUnitTest --rerun-tasks --no-build-cache` | PASS | core 51 + app 608; 0 failed/skipped; 43 tasks executed; 46s |
| provider focused `--tests '*Provider*Test'` | FAIL / apparatus invocation | 0 executed; Android SDK location was not exported for this first and only focused attempt; it was not retried. The later required full uncached suite did execute all 7 Provider-name-matched tests and they passed. |
| status frozen provenance/static gate | FAIL / composition scope | It rejects the required Provider drawable as `provider UI changed`; the frozen status-only scope gate is not composition-aware. It was not changed or bypassed. |
| provider asset/license/hash validation | FAIL / receipt invocation | 7 source/license hashes passed before the one-shot command used a wrong relative path for generated PNGs; it was not retried. `RESOURCE-MAP.txt`, package, NOTICE and all assets remain frozen inputs. |
| session UI seven-test inventory | PASS | planned/discovered exactly 7; no device execution |
| assemble debug, uncached/rerun | PASS | 44 tasks executed; 12s |
| assemble release, uncached/rerun | PASS | 57 tasks executed; 54s |

## Full-suite Provider coverage

The uncached full app suite includes 7 test names matching Provider, including the three frozen `ProviderStatusIconsContractTest` cases: canonical providers use pinned presentation; four-axis projection fails closed with abnormal precedence; and right-chip projection agrees with the Provider mark. All 7 passed.

## Joint static surface receipt

`logs/static-joint-surface.log` records the simultaneous presence of accepted four-axis fields/projection, exact `快捷键 / 查看 / 会话` labels, shared `ProviderMark`, and long-press favorite menu wiring. Static presence is not substituted for emulator/MCP acceptance.

## Gate disposition

This is not an all-green host receipt. The frozen strict architecture gate also fails as detailed in `archwiki.md`. These failures are disclosed rather than patched in the composition candidate.
