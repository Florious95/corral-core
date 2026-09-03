# External session status UI — evidence

- Local base: `064bf78b84737e5fca941876f0598d4704d85b2f`
- GitHub stacked base: corral-core PR#70 `23e0c4f1529b7b51192d6e65ecc62b3b517e2cf5`
- CLI working lamp: existing StatusChip busyDot frames (`alpha 1.0↔0.35`, `Motion.statusDotPulse/2`, `emphasized`, Reverse)
- Provider marks: `@lobehub/icons-static-svg@1.94.0` exact SHA-256s in `third_party/lobehub-icons-static-svg-1.94.0/PROVENANCE.txt`
- JVM: `./gradlew :app:testDebugUnitTest --rerun-tasks --no-build-cache` → **612/0/0**
- Connected: API35 qemu serial `emulator-5578` AVD `pi_ext_status_ui_api35`
  `./gradlew :app:connectedDebugAndroidTest --rerun-tasks --no-build-cache` class `ExternalSessionListGestureTest` → **2/0**
- Same-serial element tree: `uiautomator-emulator-5578.xml` (Mobile MCP not connected)
  HIT: Claude Working, Codex Idle, 不在线, 收藏 (page title), Online Fav
  MISS: 关闭, 创建, 配置, 未知, ☆, ★
- ArchWiki: Kotlin UI paths empty/partial unjudgeable (no Rust YAML fences)
- Debug fixture Activity is debug-only; release merged manifest has no `ExternalSessionListAcceptanceActivity`
- No merge, no release APK
