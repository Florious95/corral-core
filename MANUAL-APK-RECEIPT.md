# PR72 composition and remote APK receipt

## Immutable composition input

- Base main: `4605951e427f9ba6627375498dcb3c757c05bf36`
- Status-core: local `064bf78b84737e5fca941876f0598d4704d85b2f`; remote PR70 `23e0c4f1529b7b51192d6e65ecc62b3b517e2cf5`
- Session UI: local `12646e0b6f876ca03fd18ac17495d5146decc580`; remote PR69 `322ce01424c35d2afafc693b1485e73efbde3441`
- External status UI: prior local `fb391922aa3178f4f3a988c82fc087f36c153ece`; accepted local `97ec915006021b9b11e877bb472ee56087fa54ce`; new accepted remote PR73 `51ea20b6a04629e5eb25bfad11c3f0e1696e002e`
- Existing PR72 head before this update: `48d2dbb6ea1bf1a6b233a174ec5f0d601a8e1562`
- Cherry-picks, in order: `1c998c4453220d1361a82bcad3b9353bfca66690`, `0ab816b132598db2d76d20d9730e0cfecbec6ad5`, `5c5e6b77b098cf9b489c088bf09aa5ff00fe1047`, `97ec915006021b9b11e877bb472ee56087fa54ce`, `51ea20b6a04629e5eb25bfad11c3f0e1696e002e`
- Increment `PR73 a872881ce... -> 51ea20b6...`: one commit, patch-id `d752317d76f8c224a32acf1822468b1aada2f7b3`; cherry-pick had zero conflicts.
- Composed code head tested on Grok Bot: `548414b73f945a1f6e918ee1b35fff5d3b5f3595`
- Composed product tree: `f0ba3bc504b9f9d0ab258d87b4119e27b8644bf2`
- Final receipt commit follows the tested code head; final remote PR72 head is reported with the immutable release metadata below. Product code was not manually patched.

The status-core and session ranges are already patch-id/product-tree equivalent in PR72. The five listed commits were cherry-picked onto PR72 with zero conflicts; no complete PR73 merge was used. The new PR73 increment was applied as its single exact commit, with no manual product patch. The documented debug Manifest semantic union remains unchanged. No owning PR history was rewritten and PR72 remains open/unmerged.

## Grok Bot checkout

- Host: `grok-bot`
- Checkout: `/workspace/pr72-status-icons-20260905-548414b73`
- Fetch/checkout: remote `pr/status-icons-manual-compose` at `548414b73f945a1f6e918ee1b35fff5d3b5f3595`
- Checkout status: clean
- Android SDK: `/home/box/Android/Sdk` via `ANDROID_HOME`/`ANDROID_SDK_ROOT`

## APK artifacts

| Artifact | Exact path | Bytes | SHA-256 |
|---|---|---:|---|
| Debug | `/workspace/pr72-status-icons-20260905-548414b73/app/app/build/outputs/apk/debug/app-debug.apk` | 40360203 | `f19f196b59194d851a4a34675fa1e7d3eec6a630a3ef58ccb6fe83e827d84fdc` |
| AndroidTest | `/workspace/pr72-status-icons-20260905-548414b73/app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 1154258 | `d7bfd2833c139bc55b3f0c5bbb41b0834227ee1fe857658f737a2aa5045a2443` |
| Release | `/workspace/pr72-status-icons-20260905-548414b73/app/app/build/outputs/apk/release/app-release.apk` | 35615021 | `b24f4e42dd73b0b23e41ed7824bb261b8751ec3a90561e33b617b4547fdef071` |

## Checks

- Session focused: `:app:testDebugUnitTest --tests "dev.agentmirror.app.session.*" --rerun-tasks`, exit 0 (`/workspace/pr72-status-icons-20260905-548414b73-evidence/session-focused.log`).
- External focused: `:app:testDebugUnitTest --tests "dev.agentmirror.app.ui.ExternalSessionStatusUiTest" --tests "dev.agentmirror.app.ui.ProviderAssetProvenanceTest" --tests "dev.agentmirror.app.workspace.PiWorkingDtoProjectionTest" --rerun-tasks`, exit 0 (`/workspace/pr72-status-icons-20260905-548414b73-evidence/external-focused.log`).
- Core/protocol: `:core-protocol:test :core-terminal:test :core-conn:test --rerun-tasks`, exit 1 (`/workspace/pr72-status-icons-20260905-548414b73-evidence/core-protocol.log`): 51 tests, 19 failures. All failures are filtered-checkout fixture lookup failures for `server/internal/protocol/testdata`; no product fix was made. Failure names: `testBinaryDeltaFixture`, `testBinaryScrollbackFixture`, `testBinarySnapshotFixture`, `testGoldenAuthAckOk`, `testGoldenAuthAckReject`, `testGoldenAuthFrame`, `testGoldenErrorFrame`, `testGoldenInputAckFail`, `testGoldenInputAckOk`, `testGoldenInputFrame`, `testGoldenInputKeysFrame`, `testGoldenListDeltaFrame`, `testGoldenListFrame`, `testGoldenListingFrame`, `testGoldenResizeFrame`, `testGoldenScrollbackFrame`, `testGoldenSubscribeFrame`, `testGoldenUnsubscribeFrame`, `testPassthroughCompatOmitBytesFieldUnchanged`.
- Full JVM once: `:app:testDebugUnitTest :terminal:test :core-protocol:test :core-terminal:test :core-conn:test --continue --rerun-tasks`, exit 1 (`/workspace/pr72-status-icons-20260905-548414b73-evidence/full-jvm.log`). App: 637 tests, 2 known baseline-identical environment failures (`DiagLogExportDirBoundedTest.exportDir_fileCountCappedAfterManyExports`, `RemapThroughputTest.twoHundredThousandColorForBelowDeclaredCap`). Core/protocol repeats the 19 filtered-checkout fixture failures; core-terminal 76 tests/3 skipped; core-conn 3 tests/0 failures.
- Compile: `:app:compileDebugKotlin :app:compileReleaseKotlin --rerun-tasks`, exit 0 (`/workspace/pr72-status-icons-20260905-548414b73-evidence/compile-debug-release.log`).
- Assemble: `:app:assembleDebug :app:assembleAndroidTest :app:assembleRelease --rerun-tasks`, exit 0 (`/workspace/pr72-status-icons-20260905-548414b73-evidence/assemble-all.log`).

## Provider invariants

- Canonical provider IDs are exactly `claude_code`, `codex`, `copilot`, `grok`, `cursor`, `pi`; Claude/Codex/Cursor use the extracted HTML BRAND paths, while Grok/Pi/Copilot retain confirmed source-backed assets. Grok does not render the X fallback at runtime.
- Grok PNG: 1718 bytes, SHA-256 `515fd702a733df33e669a431f7d0b465350c8332c344c59672f2782f5ce3ff10`.
- HTML BRAND SVG SHA-256s: Claude `907175cc3a05492c941144633eedc8def950e345f41705a571ec087b5e6b9183`, Codex `6fe42dc2de268e438b2f3cc32eaa07a4b34f17eafc9320bc3381a5e475c3dfe0`, Cursor `64c552bfae500e7b1d80cb3a7186553817c188504537fea7faa096c161ca200e`.
- Pi PNG SHA-256 remains `9d59066fac0cb0361fb7cf663e87d0f29beb654e49780baa55aab74aa4757b2f`; Copilot PNG remains `49faef29cb14fa7aaa73672ef126acee65ff504c2463a6672d9a9364fa75c54a`.
- Right mark slot/content are 28dp/22dp with light `#667085` and dark `#F0F2F5`.
- Left marker is a fixed 20dp Canvas 2x3 dot matrix with ten frames, 100ms/frame, 1000ms period; only normal-health working activity animates. Idle/unknown/abnormal retain the slot but draw no dots.
- Pi display projection prefers real `session_name` over generic `node`; Pi working state maps to the left `busyDot` slot.

## Immutable draft prerelease

- Release: `pr72-status-icons-20260905-dda78a97`
- URL: https://github.com/Florious95/corral-core/releases/tag/untagged-1f45a6b274b3bdabd82b
- State: draft + prerelease; release target and remote PR72 head at publication: `dda78a97c9700df521c0855e8588f74849feeebf`
- Assets are the three Grok-built APKs listed above; no APK was committed to product history.

No local compile, device run, Downloads write, SCP/rsync/tar transfer, merge, or production/service change was performed.
