# External session status UI — evidence (HTML extract rework)

- Local worktree: `pr/external-session-status-ui`
- GitHub stacked base: corral-core PR#70 `23e0c4f1529b7b51192d6e65ecc62b3b517e2cf5`
- Owning PR: https://github.com/Florious95/corral-core/pull/71 (OPEN, unmerged)
- Design source: `Agent App Prototype.dc.html` SHA-256 `9c81f24bfdb57fb54fbcfe0a6abf825d6299e0d19482f790ec3f4e057d4bb0fc`
- Provider marks: HTML `icon()` extracts for `claude_code`/`codex`/`grok`/`cursor`; Pi+Copilot exact PNG blobs from `1b12e92d8efb1c0eec41e14a264f9d80ee833ad9` (`R.drawable.provider_pi` / `provider_copilot_color`); no overlay
- Rework head: `b3714a8c1982222faedcd65b4eeeeaedf35780eb`
- Rework tree: `b3487d3c9ddda0da05d1224f292f384ffb45e85a`
- CLI working marker authority: installed `codex-cli 0.149.0`; public upstream
  release `rust-v0.149.0`, commit
  `758ef40f50c1a458425c7cfbf1eb12cbc07af0b0`.
- Native render chain: `codex-rs/tui/src/status_indicator_widget.rs:758-777`
  calls `motion::activity_indicator` and `shimmer_text`; the former is defined
  at `codex-rs/tui/src/motion.rs:156-197`, and the source shimmer is
  `codex-rs/tui/src/shimmer.rs:101-160`.
- Native frames/spec: one `•` Unicode cell; true-color mode applies a cosine
  band `0.5*(1+cos(pi*distance/5))` when distance from the sweep position is
  at most 5 cells, with 10 leading pad cells and a 21-cell period. The sweep
  is process-start synchronized, continuous over 2000ms, and the widget
  requests redraws every 32ms. Each frame is bold and blends 90% toward
  default background from default foreground. Non-true-color fallback is a
  `•`/dim `◦` blink with 600ms half-period. Android preserves the existing
  centered 8dp leading slot and uses the list foreground/background colors.
- Idle is static `◦`; unknown/abnormal/offline have no working animation.
- Pi live title: canonical `pi` with non-empty DTO `session_name` wins over
  generic `window_name`/`name`; provider remains right-side mark only.
- Rows: unified 66dp title+path; row is sole gesture owner
- Focused JVM: ProviderAssetProvenanceTest+ExternalSessionStatusUiTest → **7/0/0**
- Connected: AVD `pi_ext_status_ui_r2_api35` serial `emulator-5580` API35
  class `ExternalSessionListGestureTest` → **3/0** (scroll-to Pi/Copilot marks)
- uiautomator `uiautomator-emulator-5580-r3.xml`: HIT Claude Working, Codex Idle, 不在线, /ws/codex-i; MISS 关闭/创建/配置/未知/☆/★
- Mobile MCP: not connected
- Grok Bot JVM: `./gradlew --no-daemon --rerun-tasks :app:testDebugUnitTest`
  targeted `ExternalSessionStatusUiTest`, `ProviderAssetProvenanceTest`, and
  `PiWorkingDtoProjectionTest` — **BUILD SUCCESSFUL**, 12 tests executed.
- No merge, no APK delivery
