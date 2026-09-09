# Queued: rounded box-drawing glyph correction

Status: documentation-only Draft PR; implementation pending. Queue order: Issue15, Issue16, then this task. This PR does not modify product code and does not fix rendering.

The original [source report](source-report.md) is preserved verbatim. Its “修改” and “已执行验证” sections describe the report author's external work; the referenced patches and test sources were not available during PR preparation. Those historical results are not validation of this PR.

Repository identity checked during preparation:

- corral-app main: `277c3f383b93e2c555ec9cf01a8c16b6c63101fe`
- main tree: `7b3ce79c6ac2512261fde9e1475d51f503ced522`
- BoxBlockGeometry.kt blob: `aeb409f9e00ee8e69f0291c6663fe84b713083b7`
- TermSurfaceView.kt blob: `bf477ecd0631440e18b0b75d4d939681d1d54882`

Both source blobs match the report. The rounded code points still share straight-corner branches in this checked baseline. No replacement implementation was reconstructed from prose.

When this task reaches the queue head, recover the referenced corral-app patch and tests, recheck main, then define and run independent acceptance. The report lists JVM/JUnit, native Canvas, APK, device comparison and performance gaps; none were executed during this documentation-only preparation. Existing Issue15 candidates and worktrees are separate.

Primary implementation target: corral-app's local Android renderer. corral-core renderer synchronization is a recorded dependency, not a second PR or an authorized implementation in this preparation. No service changes, merge or release are included.
