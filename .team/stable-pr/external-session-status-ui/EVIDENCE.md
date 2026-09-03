# External session status UI — evidence (HTML extract rework)

- Local worktree: `pr/external-session-status-ui`
- GitHub stacked base: corral-core PR#70 `23e0c4f1529b7b51192d6e65ecc62b3b517e2cf5`
- Owning PR: https://github.com/Florious95/corral-core/pull/71 (OPEN, unmerged)
- Design source: `Agent App Prototype.dc.html` SHA-256 `9c81f24bfdb57fb54fbcfe0a6abf825d6299e0d19482f790ec3f4e057d4bb0fc`
- Provider marks: extracted `icon()` inline SVG fallbacks for `claude_code` `codex` `grok` `cursor`; `copilot`/`pi` absent in source (blank, no guess)
- CLI lamp unchanged: busyDot `alpha 1.0↔0.35`
- Rows: unified 66dp title+path; row is sole gesture owner
- JVM: `:app:testDebugUnitTest --rerun-tasks --no-build-cache` → **613/0/0**
- assembleDebug + assembleRelease: BUILD SUCCESSFUL
- Connected: AVD `pi_ext_status_ui_r2_api35` serial `emulator-5580` API35
  class `ExternalSessionListGestureTest` → **3/0**
- uiautomator `uiautomator-emulator-5580-r3.xml`: HIT Claude Working, Codex Idle, 不在线, /ws/codex-i; MISS 关闭/创建/配置/未知/☆/★
- Mobile MCP: not connected
- No merge, no APK delivery
