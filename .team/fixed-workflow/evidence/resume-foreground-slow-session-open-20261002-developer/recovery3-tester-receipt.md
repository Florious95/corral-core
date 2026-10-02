# Session snapshot recovery3 final APK receipt

- App source commit: `75c67c4dd29a0722e687542fcc7ca7311cf448a6`
- App tree: `2ed4db647d84c9e65fef48e4e634440c720bfce9`
- App worktree: `/Volumes/nvme/Projects/CorralCore-worktrees/session-snapshot-app`
- Core source commit: `843b18222ea4f9348e87bbc2c611b13a49728d08`
- Core source tree: `12f3bfa9982bc0e039291d8c86bd5adfd0e02b1d`
- Core coordinate: `dev.agentmirror.core:core-conn:20261002.snapshot-recovery3`
- Core publication commit: `28df59b69bb638f32d7eae694a6c9f9174dc742a`
- Core AAR SHA-256: `ceeb3752668d2acd2128265117405faa6e8d40fbe0da508e4f781b7b1f89af75`
- Core JAR SHA-256: `3c22c27b31df6a9c8776e5cbeecb3fd54b5d7e97f33b7bb734751a0483d0c841`
- Golden protocol AAR/JAR: `e6ec508b623d98f15ad0341b405b558fe0e207d433b3d0117ca70812892b3e79` / `0543c87d6806970496ee58e2ce57e8279e5333c7118b64fa4b468f1026b7b855`
- Golden terminal AAR/JAR: `b81934a3ea6d11913a03b89b93aa08be1f755a54a3cdf9ad26e3eaad134c7865` / `7ef686bd346ef08c5de50e948eee8a858f761fad389edcd92d9eb384cb795e6e`

## APK

Build command (from exact frozen worktree; local Maven mirror contains the exact immutable publication and unchanged golden cores):

```sh
cd /Volumes/nvme/Projects/CorralCore-worktrees/session-snapshot-app
ANDROID_HOME=/Users/alauda/Library/Android/sdk ./gradlew --offline --rerun-tasks \
  -I /tmp/snapshot-core-recovery3.init.gradle :app:assembleDebug
```

- Result: `BUILD SUCCESSFUL`, exit `0`, 37 actionable tasks.
- APK: `.team/evidence/session-snapshot-recovery3-tester/app-debug.apk`
- APK SHA-256: `0efb2251cedf618feb67592e65557cb231e04d7cd0ae7bcad3221310501b51fd`
- Size: `41074779` bytes.
- Package: `dev.agentmirror.app`, versionCode `1`, versionName `0.1.0`.
- Frozen source worktree was clean before build and remains source-clean after generated-state cleanup.

## Three-core proof

`dependencyInsight` exit `0` for all three artifacts. `debugRuntimeClasspath` selected:

- `core-conn:20261002.snapshot-recovery3` (exact candidate rule)
- `core-protocol:20260915.close`
- `core-terminal:20260915.close`

The immutable publication URL `https://raw.githubusercontent.com/Florious95/corral-core/28df59b69bb638f32d7eae694a6c9f9174dc742a/` was fetched successfully. Downloaded AAR/JAR hashes match local Maven and the identities above exactly (`remote-three-core-hashes.txt`).

## Same-source regression

From an isolated checkout of the exact App commit using recovery3 and unchanged golden protocol/terminal:

- `ReadySubscriptionNoFirstFrameRedTest`: 5/5 PASS (matching snapshot, wrong-ref late snapshot, unsubscribe cleanup, delayed Listing A/B recovery).
- `Issue40ForegroundResumeRedTest`: 2/2 PASS.
- Listener routing: 2/2 PASS; `%0 -> %166` transition fixture: 1/1 PASS.
- Cache/geometry route: 4/4 PASS.
- SessionViewModel: 47/47 PASS.

Raw Gradle log/exit: `regression.log`, `regression.exit`; JUnit XML and hashes are in `junit/` and `junit-sha256.txt`.

Recovery1/recovery2 APKs are superseded intermediate candidates and must not be installed, uploaded, or used for acceptance.

App-tester previously reported no available Android devices/adb; no UI/device installation or visual acceptance is claimed.
