# Amendment-003 test migration
- Goal digest: `203a1372af12a71bcb2506d18a0ab48695b81fdff16acc971ec9df8c25e25320`
- Authorized files changed: OverlayDismissOnOutsideTapTest.kt, OverlayMenuTest.kt, OverlayOpensFromSessionTopRightTest.kt, ViewMenuSourceTest.kt, ConsoleChromeTest.kt, LandTermTest.kt, VzVerifyRoundTest.kt.
- Fresh command: `./app/gradlew -p app :app:testDebugUnitTest --rerun-tasks --no-build-cache`
- Result: `604 tests completed`, `0 failed`, rc=0; JUnit XML sums tests=604, skipped=0, failed=0, ignored=0.
- Frozen 14 method names remain present and executed; no delete/ignore/filter/rename.
- Functional gate remains unsafe: load1=37.84; no device started/touched.
