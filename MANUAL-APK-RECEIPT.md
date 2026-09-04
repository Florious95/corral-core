# PR72 composition and remote APK receipt

## Immutable composition input

- Base main: `4605951e427f9ba6627375498dcb3c757c05bf36`
- Status-core: local `064bf78b84737e5fca941876f0598d4704d85b2f`; remote PR70 `23e0c4f1529b7b51192d6e65ecc62b3b517e2cf5`
- Session UI: local `12646e0b6f876ca03fd18ac17495d5146decc580`; remote PR69 `322ce01424c35d2afafc693b1485e73efbde3441`
- External status UI: prior local `fb391922aa3178f4f3a988c82fc087f36c153ece`; accepted local `97ec915006021b9b11e877bb472ee56087fa54ce`; remote PR73 final `a872881ce36f4666770bfd332983b9fc74759745`
- Existing PR72 head: `190775f0c23fcfbe97061c6e6f22d156b285db61`
- Cherry-picks, in order: `1c998c4453220d1361a82bcad3b9353bfca66690`, `0ab816b132598db2d76d20d9730e0cfecbec6ad5`, `5c5e6b77b098cf9b489c088bf09aa5ff00fe1047`, `97ec915006021b9b11e877bb472ee56087fa54ce`
- Composed code head built on Grok Bot: `f52db062a004cd52cda963e23bbb35154b8458ee`
- Composed product tree: `8bf386d46f87baedb2df048e6dc68aba527c9558`

The status-core and session ranges are already patch-id/product-tree equivalent in PR72. The four listed commits were cherry-picked onto PR72 with zero conflicts; no complete PR73 merge was used. The eight affected external paths match accepted local `97ec915...`; status/session paths have no additional changes. The documented debug Manifest semantic union remains unchanged. No owning PR history was rewritten and PR72 remains open/unmerged.

## Grok Bot checkout

- Host: `grok-bot`
- Checkout: `/workspace/pr72-status-icons-20260904-a5d520ed`
- Fetch/checkout: remote `pr/status-icons-manual-compose` at `f52db062a004cd52cda963e23bbb35154b8458ee`
- Checkout status: clean
- Android SDK: `/home/box/Android/Sdk` via `ANDROID_HOME`/`ANDROID_SDK_ROOT`

## APK artifacts

| Artifact | Exact path | Bytes | SHA-256 |
|---|---|---:|---|
| Debug | `/workspace/pr72-status-icons-20260904-a5d520ed/app/app/build/outputs/apk/debug/app-debug.apk` | 40359807 | `4eef1ce499e6401f93793beb4e5f3771aad2886771542e034b3757b30a5fb8a4` |
| AndroidTest | `/workspace/pr72-status-icons-20260904-a5d520ed/app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 1154254 | `7066010c07c712b6e3bd33f4b5ecf37a784683e005a3b559ecf742e8aabb989a` |
| Release | `/workspace/pr72-status-icons-20260904-a5d520ed/app/app/build/outputs/apk/release/app-release.apk` | 35598237 | `d947e2cb76bcd62989960079620e2a278f16eb41411b3d67d873c02c2160a335` |

## Checks

- Session focused: `:app:testDebugUnitTest --tests "dev.agentmirror.app.session.*" --rerun-tasks`, exit 0 (`/workspace/pr72-status-icons-20260904-f52db062-evidence/session-focused.log`).
- External focused: `:app:testDebugUnitTest --tests "dev.agentmirror.app.ui.ExternalSessionStatusUiTest" --tests "dev.agentmirror.app.ui.ProviderAssetProvenanceTest" --tests "dev.agentmirror.app.workspace.PiWorkingDtoProjectionTest" --rerun-tasks`, exit 0 (`/workspace/pr72-status-icons-20260904-f52db062-evidence/external-focused.log`).
- Core/protocol: `:core-protocol:test :core-terminal:test :core-conn:test --rerun-tasks`, exit 1 (`/workspace/pr72-status-icons-20260904-f52db062-evidence/core-protocol.log`): 51 tests, 19 failures. All failures are filtered-checkout fixture lookup failures for `server/internal/protocol/testdata`; no product fix was made. Failure names: `testBinaryDeltaFixture`, `testBinaryScrollbackFixture`, `testBinarySnapshotFixture`, `testGoldenAuthAckOk`, `testGoldenAuthAckReject`, `testGoldenAuthFrame`, `testGoldenErrorFrame`, `testGoldenInputAckFail`, `testGoldenInputAckOk`, `testGoldenInputFrame`, `testGoldenInputKeysFrame`, `testGoldenListDeltaFrame`, `testGoldenListFrame`, `testGoldenListingFrame`, `testGoldenResizeFrame`, `testGoldenScrollbackFrame`, `testGoldenSubscribeFrame`, `testGoldenUnsubscribeFrame`, `testPassthroughCompatOmitBytesFieldUnchanged`.
- Full JVM once: `:app:testDebugUnitTest :terminal:test :core-protocol:test :core-terminal:test :core-conn:test --continue --rerun-tasks`, exit 1 (`/workspace/pr72-status-icons-20260904-f52db062-evidence/full-jvm.log`). App: 636 tests, 2 known baseline-identical environment failures (`DiagLogExportDirBoundedTest.exportDir_fileCountCappedAfterManyExports`, `RemapThroughputTest.twoHundredThousandColorForBelowDeclaredCap`). Core/protocol repeats the 19 filtered-checkout fixture failures; core-terminal 76 tests/3 skipped; core-conn 3 tests/0 failures.
- Compile: `:app:compileDebugKotlin :app:compileReleaseKotlin --rerun-tasks`, exit 0 (`/workspace/pr72-status-icons-20260904-f52db062-evidence/compile-debug-release.log`).
- Assemble: `:app:assembleDebug :app:assembleAndroidTest :app:assembleRelease --rerun-tasks`, exit 0 (`/workspace/pr72-status-icons-20260904-f52db062-evidence/assemble-all.log`).

## Provider invariants

- Canonical provider IDs are exactly `claude_code`, `codex`, `copilot`, `grok`, `cursor`, `pi`; Grok maps to the source-backed spiral PNG and does not render the X fallback at runtime.
- Grok PNG: 1718 bytes, SHA-256 `515fd702a733df33e669a431f7d0b465350c8332c344c59672f2782f5ce3ff10`.
- Codex prior-app PNG: SHA-256 `cdfc4f2eecc16469176a3cdfb0decb43646e7e3ac44e894f0cc94d330d897260`; ten-frame Braille spinner is pinned to CLI `0.149.0`.
- Pi PNG SHA-256 remains `9d59066fac0cb0361fb7cf663e87d0f29beb654e49780baa55aab74aa4757b2f`; Copilot PNG remains `49faef29cb14fa7aaa73672ef126acee65ff504c2463a6672d9a9364fa75c54a`.
- Pi display projection prefers real `session_name` over generic `node`; Pi working state maps to the left `busyDot`/working shimmer slot.
- Codex native marker contract is pinned to CLI 0.149.0: ten Braille frames (`⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏`), 1000ms period, and 32ms redraw cadence.

No local compile, device run, Downloads write, SCP/rsync/tar transfer, merge, or production/service change was performed.
