# External session status UI — evidence (HTML extract rework)

- Local worktree: `pr/external-session-status-ui`
- GitHub stacked base: corral-core PR#70 `23e0c4f1529b7b51192d6e65ecc62b3b517e2cf5`
- Owning PR: https://github.com/Florious95/corral-core/pull/71 (OPEN, unmerged)
- Design source: `Agent App Prototype.dc.html` SHA-256 `9c81f24bfdb57fb54fbcfe0a6abf825d6299e0d19482f790ec3f4e057d4bb0fc`
- Provider marks: HTML `icon()` extracts for `claude_code`/`codex`/`grok`/`cursor`; Pi+Copilot exact PNG blobs from `1b12e92d8efb1c0eec41e14a264f9d80ee833ad9` (`R.drawable.provider_pi` / `provider_copilot_color`); no overlay
- CLI lamp unchanged: busyDot `alpha 1.0↔0.35`
- Rows: unified 66dp title+path; row is sole gesture owner
- Focused JVM: ProviderAssetProvenanceTest+ExternalSessionStatusUiTest → **7/0/0**
- Connected: AVD `pi_ext_status_ui_r2_api35` serial `emulator-5580` API35
  class `ExternalSessionListGestureTest` → **3/0** (scroll-to Pi/Copilot marks)
- uiautomator `uiautomator-emulator-5580-r3.xml`: HIT Claude Working, Codex Idle, 不在线, /ws/codex-i; MISS 关闭/创建/配置/未知/☆/★
- Mobile MCP: not connected
- No merge, no APK delivery
