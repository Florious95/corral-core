# PR72 composition and remote APK receipt

## Immutable composition input

- Base main: `4605951e427f9ba6627375498dcb3c757c05bf36`
- Status-core: local `064bf78b84737e5fca941876f0598d4704d85b2f`; remote PR70 `23e0c4f1529b7b51192d6e65ecc62b3b517e2cf5`
- Session UI: local `12646e0b6f876ca03fd18ac17495d5146decc580`; remote PR69 `322ce01424c35d2afafc693b1485e73efbde3441`
- External status UI: local `fb391922aa3178f4f3a988c82fc087f36c153ece`; remote PR73 `5dd6c9f1ac00168b3bb25c3b5065b6e3ac6e8588`
- Existing PR72 head: `889404b0a8eedacc7aa40e693bcdd38c9ce4cfdd`
- Composed code head built on Grok Bot: `a5d520ed1845c527624ddaa900514cfbc7b9b8b0`
- Composed product tree: `32a285fff0e4544696ab3c09208766443468c9cd`

The status-core and session ranges are already patch-id/product-tree equivalent in PR72. The appended commit is the accepted PR73 delta only. The sole add/add conflict was `app/app/src/debug/AndroidManifest.xml`; its existing semantic union retains both debug fixture activities and is unchanged in the composed tree. No owning PR history was rewritten and PR72 remains open/unmerged.

## Grok Bot checkout

- Host: `grok-bot`
- Checkout: `/workspace/pr72-status-icons-20260904-a5d520ed`
- Fetch/checkout: remote `pr/status-icons-manual-compose` at `a5d520ed1845c527624ddaa900514cfbc7b9b8b0`
- Checkout status: clean
- Android SDK: `/home/box/Android/Sdk` via `ANDROID_HOME`/`ANDROID_SDK_ROOT`

## APK artifacts

| Artifact | Exact path | Bytes | SHA-256 |
|---|---|---:|---|
| Debug | `/workspace/pr72-status-icons-20260904-a5d520ed/app/app/build/outputs/apk/debug/app-debug.apk` | 40357423 | `c616deb326954947ccabbaa51b394a1267099c5c866ee4b301ff6b04576fcb85` |
| AndroidTest | `/workspace/pr72-status-icons-20260904-a5d520ed/app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 1151366 | `17e7128a9daeac152886b2f11d087de5c9aa7ed24435f883db8dfc59b4320ec8` |
| Release | `/workspace/pr72-status-icons-20260904-a5d520ed/app/app/build/outputs/apk/release/app-release.apk` | 35596121 | `ed65bd2a3ebd1aff7c9474c081bfee84fb5d54292c799ca5367cbd18774df040` |

## Checks

- Session focused: `:app:testDebugUnitTest --tests "dev.agentmirror.app.session.*" --rerun-tasks`, exit 0 after SDK environment setup.
- External focused: `:app:testDebugUnitTest --tests "dev.agentmirror.app.ui.ExternalSessionStatusUiTest" --tests "dev.agentmirror.app.ui.ProviderAssetProvenanceTest" --tests "dev.agentmirror.app.workspace.PiWorkingDtoProjectionTest" --rerun-tasks`, exit 0.
- Core/protocol: `:core-protocol:test :core-terminal:test :core-conn:test --rerun-tasks`, exit 1: 51 tests, 19 failures. All failures are filtered-checkout fixture lookup failures for `server/internal/protocol/testdata`; no product fix was made. Failure names: `testBinaryDeltaFixture`, `testBinaryScrollbackFixture`, `testBinarySnapshotFixture`, `testGoldenAuthAckOk`, `testGoldenAuthAckReject`, `testGoldenAuthFrame`, `testGoldenErrorFrame`, `testGoldenInputAckFail`, `testGoldenInputAckOk`, `testGoldenInputFrame`, `testGoldenInputKeysFrame`, `testGoldenListDeltaFrame`, `testGoldenListFrame`, `testGoldenListingFrame`, `testGoldenResizeFrame`, `testGoldenScrollbackFrame`, `testGoldenSubscribeFrame`, `testGoldenUnsubscribeFrame`, `testPassthroughCompatOmitBytesFieldUnchanged`.
- Full JVM once: `:app:testDebugUnitTest :terminal:test :core-protocol:test :core-terminal:test :core-conn:test --continue --rerun-tasks`, exit 1. App: 636 tests, 2 known baseline-identical environment failures (`DiagLogExportDirBoundedTest.exportDir_fileCountCappedAfterManyExports`, `RemapThroughputTest.twoHundredThousandColorForBelowDeclaredCap`). Core/protocol repeats the 19 filtered-checkout fixture failures; core-terminal 76 tests/3 skipped; core-conn 3 tests/0 failures.
- Compile: `:app:compileDebugKotlin :app:compileReleaseKotlin --rerun-tasks`, exit 0.
- Assemble: `:app:assembleDebug :app:assembleAndroidTest :app:assembleRelease --rerun-tasks`, exit 0.

## Provider invariants

- Canonical provider IDs are exactly `claude_code`, `codex`, `copilot`, `grok`, `cursor`, `pi`; Grok maps to the source-backed spiral PNG and does not render the X fallback at runtime.
- Grok PNG: 1718 bytes, SHA-256 `515fd702a733df33e669a431f7d0b465350c8332c344c59672f2782f5ce3ff10`.
- Pi PNG SHA-256 remains `9d59066fac0cb0361fb7cf663e87d0f29beb654e49780baa55aab74aa4757b2f`; Copilot PNG remains `49faef29cb14fa7aaa73672ef126acee65ff504c2463a6672d9a9364fa75c54a`.
- Pi display projection prefers real `session_name` over generic `node`; Pi working state maps to the left `busyDot`/working shimmer slot.
- Codex native marker contract is pinned to CLI 0.149.0: `•`, 2000ms period, 32ms redraw cadence, five-cell cosine band.

No local compile, device run, Downloads write, SCP/rsync/tar transfer, merge, or production/service change was performed.
