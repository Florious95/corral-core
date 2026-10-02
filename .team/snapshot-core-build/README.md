# Golden Core source recovery (not the subscription fix)

The app golden baseline is `83ba5cc363ad7a27ce60e21f539d8a8bc1e89edf`; its Maven Core is `20260915.close`. That historical publication has no surviving source commit. `origin/main` lacks parts of it.

This baseline recovers Connection/ConnectionManager from the existing `issue40-core-fix-composite` worktree on `c2ad5ec4d23e05cb843ec0633643ba35ef94f484`, plus the Listing handler's `foregroundRecoveryArmed = false` seen in the golden JAR. It is deliberately separate from the subsequent subscription fix.

Golden core-conn AAR SHA-256: `723b1f73c72f7530c63685d93b29b85772f4bc7c374f196ec8124dfba3ab0b82`.
Golden JAR SHA-256: `0c2aab7c59ef67d2775074036070f05ef734dba258ed0343345e96d0ee15f66c`.
Rebuilt JAR SHA-256: `ba451ef7358c8ccc7a8031c85e1ae9861d6042dbb8c2c04f83c5c5506b39b264`.

A ZIP-entry comparison found **zero differences in every class and Kotlin metadata entry** (33 entries inspected; archive timestamps/MANIFEST excluded). This verifies the recovered connection source against the actual golden executable classes, not a historical claim.

Build using Java 17 and the original golden Maven repository (whose `dev/agentmirror/core/core-protocol/20260915.close` JAR must be available):

```sh
./app/gradlew -p .team/snapshot-core-build -PgoldenCoreRepo=/absolute/path/to/golden-maven-repository :core-conn:jar :core-conn:test --no-daemon --max-workers=2
```

The standalone build intentionally uses the **unchanged golden protocol binary**, rather than rebuilding unrelated protocol/terminal sources. The virtual protocol project supplies that binary during compilation and preserves its original Maven dependency coordinate for publication.

Baseline build/tests: exit 0, 3 tests, zero failures/errors. Original evidence: `远程Agent安卓/.team/fixed-workflow/evidence/resume-foreground-slow-session-open-20261002-developer/{golden-rebuild.log,golden-rebuild.exit,golden-classes-comparison.json}`.
