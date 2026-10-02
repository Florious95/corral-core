# Foreground liveness candidate (not independent App acceptance)

Source: `0280307959ca9875aed21904e3913668bf9d4422`, tree `d8865e6bbb42848ea64519ac55665f1825181992`, parent `843b18222ea4f9348e87bbc2c611b13a49728d08` (snapshot recovery3).
Coordinate: `dev.agentmirror.core:core-conn:20261002.background-liveness1`.

A successfully decoded protocol frame on the current physical Connection clears its foreground probe. Listing-only response delay is not connection silence. A per-socket decoded-frame counter is recorded before downstream callbacks; an atomic probe and deadline-side counter check prevent false close when a decoded callback is still in flight. No public Pong callback or OkHttp reflection is fabricated.

The 5-second foreground silence budget and the independent first-subscription-SNAPSHOT retry/timeout at 2/4 seconds are unchanged. No threads, separate WebSocket, WakeLock or foreground-service type changes are introduced.

The tester uniquely published these binaries from the frozen source using the existing `.team/snapshot-core-build` harness against the unchanged golden protocol JAR. Developer independently verified their hashes, all checksum sidecars, the POM/module dependencies, and AAR `classes.jar` equality with the standalone JAR.

SHA-256:
- JAR: `148f8d96a3bb99beb9f84b212f64e70e2e139e170405348ccff20a3d055cea6c`
- AAR: `6579a72870d6b85514dbda67743b0cca407535a0b217478acd723deca0ea2bc1`
- POM: `a96162d39603261853de0cf22d32805f0f46b7c7afeb8e2947fef701ca404bcc`
- Gradle module: `4ba10392d95d289a435934c4c0ad9699f462c9b2e96f9aa9306dcefceb1d58a2`

Developer red: 19 executed, 6 assertion failures, 0 errors/skips. Fixed Core full suite: 22/22 passed, also verified by tester. Cases include DELTA, SNAPSHOT, Level2, heartbeat, control replies, decoded-but-pending callbacks, invalid data, prior-edge/stale-socket data, genuine silence, and unchanged first-SNAPSHOT recovery.

All earlier coordinates and Maven metadata are unchanged. Protocol/terminal remain `20260915.close`; they were not rebuilt. Consume via this publication's immutable Git commit URL. Unit success/publication does not imply simulator R1/R2 acceptance; that evidence is recorded separately for the exact App APK.
