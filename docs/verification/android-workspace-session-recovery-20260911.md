# Android workspace session recovery

## Scope and base

Base: `05e234374a05aea092de6aabd9f928b3f9ddbbfc` in `Florious95/corral-core`.

This change is limited to the Android client under `app/`, regression tests and this report. It does not change `server/`, `corral-serve`, nodeprobe, terminal input/rendering, deployment, protocol formats or the independent `corral-app` Maven dependencies. No merge, release or device installation was performed.

The report concerns source-level defects compatible with the reported symptom (directories remain visible while their session list becomes empty). No phone incident trace was supplied, so it does not claim to identify the exact sequence on the user's installed APK.

## Root causes

1. `MainActivity` creates a fresh `WorkspaceViewModel` on recreation and sets the saved-state refresh suppression flag. The fresh model has no Level2 snapshot; the ServiceWire replay contains READY and Listing, not Level2. Suppressing the first subscription can therefore leave an empty model receiving only row-less heartbeats.
2. The server has one Level2 slot per connection. Favorites and visible directory/session owners previously manipulated this slot independently. A late unsubscribe for B could close the newer A slot, and a late favorite response could advance the old queue and replace A with C. A set of replay intents in ConnectionManager also incorrectly represented multiple concurrent Level2 streams.
3. Manual pull-to-refresh on a directory requested only a Level1 listing. In-flight foreground refresh suppression had no deadline, and a heartbeat was treated as a full snapshot acknowledgement.
4. Ignoring authoritative empty snapshots in the cache could resurrect deleted rows on reentry. Navigation placeholders and actual empty snapshots must be distinguished.

## Fix

- Suppress a redundant Level2 request only when the same retained model has both the relevant snapshot and subscription. A new empty model requests its first full snapshot, including after saved-state restoration.
- Coordinate the client-side single wire slot. Cancel outgoing favorite queues before a visible owner takes over, restore retained visible intent after favorite fetch completion/timeout, and return generation leases to page effects so stale disposal cannot release a newer owner, including same-directory reentry.
- Inactive/precomposed home tabs do not acquire a subscription or run their fetch timer. Session takeover keeps the existing same-directory stream without another scan.
- ConnectionManager records only the latest Level2 intent and does not send a global unsubscribe for a superseded workspace. Connection loss resets pending refresh bookkeeping.
- Directory pull-to-refresh requests Level2. Listing, unrelated workspace frames and heartbeats do not finish a pending visible Level2 refresh.
- Pending snapshot recovery is bounded: one retry after 40 seconds, then a stable generic failure message after another 40 seconds. Healthy established streams are not polled. Foreground-edge coalescing expires after 40 seconds without rebuilding a healthy socket. The existing UI-driven clock is reused; no new recurring network loop is added.
- Cache complete snapshots including empty ones, but never cache an unreceived navigation placeholder. A real final-session deletion stays empty after reentry.

## Validation actually executed

This environment has Kotlin 1.9.0 and JDK 21, but no Android SDK, Gradle/dependency cache or working outbound DNS. A complete Android build and the repository's Gradle/JUnit/Robolectric suite were NOT run.

An isolated compiler/runner compiled the actual modified `WorkspaceViewModel.kt` and `ConnectionManager.kt` with local adapters for Android/platform logging, model/favorite dependencies, protocol/Connection/FrameCodec and JUnit assertions. The three test source files in this PR were executed by a reflection runner against those production classes. Coroutines StateFlow used the installed Kotlin coroutine library. The protocol adapter is NOT the real wire codec; these results establish control-flow behavior only, not Android, Compose, real transport or end-to-end acceptance.

Results:

| Run | Test sources | Result |
| --- | --- | --- |
| Exact parent production classes with the same baseline-compatible test bytes | WorkspaceSessionRecoveryTest (10), ConnLevel2RecoveryTest (7) | 17 executed, 12 failed, 5 passed; expected red, exit 1 |
| Modified production classes | Above 17 plus WorkspaceSessionOwnershipTest (9) | 26 executed, 0 failures; exit 0 |

Original production sources were checked against their Git blob hashes before modification:

- WorkspaceViewModel: `603253ada3a9a76ef4f4bf1ab57c7e6d34fc5d30`
- ConnectionManager: `1426d2242f3408d369295da6db1e52f3789cae2c`
- WorkspaceScreen: `a04452227c39100b8c70868e60a783e5dd0791f1`
- ThreePane: `b30b70cb8ac7fb710e0dc50a00bde942d57cb106`

The local adapters are not shipped in production or installed in the repository test source set. The committed tests use the real repository classes/helpers when run through Gradle. Local isolated validation is not a substitute for that run.

## Required verification before merge

Run with the real project toolchain:

```sh
cd app
./gradlew :app:testDebugUnitTest --rerun-tasks \
  --tests 'dev.agentmirror.app.workspace.WorkspaceSessionRecoveryTest' \
  --tests 'dev.agentmirror.app.workspace.WorkspaceSessionOwnershipTest' \
  --tests 'dev.agentmirror.app.conn.ConnLevel2RecoveryTest'
```

Then run the existing workspace, connection, foreground-resume and navigation suites and `:app:assembleDebug`. These checks are pending, not passed.

Device acceptance, using synthetic sessions rather than real pane content:

1. Open a directory with sessions; recreate the Activity while the server's directory contents remain unchanged. Confirm a full snapshot restores rows without waiting for a session mutation.
2. Switch between Favorites and directories A/B while responses and outgoing disposal are delayed. Confirm the final visible directory remains subscribed and old favorite replies do not start another fetch.
3. Reenter the same directory before the previous page disposes; open a session from Favorites; return to the directory. Verify both ordering variants and state indicators.
4. Drop/reconnect during a favorite fetch and after A→B→A. Confirm only the latest required wire workspace is replayed.
5. Hold a Level2 snapshot while allowing Listing/heartbeat messages. Verify refresh remains pending, only one recovery retry occurs, failure is visible and manual refresh recovers it. Repeat with a previously populated cache; the error must not disappear on the next quiet check.
6. Delete the final synthetic session and receive an authoritative empty frame. Reenter and verify deleted rows do not resurrect. For healthy idle streams, verify no added periodic list/subscription traffic.

No performance numbers, APK acceptance, production deployment or complete phone-incident reproduction are claimed.
