# session-ui-shell rework evidence

- Frozen Goal digest: `203a1372af12a71bcb2506d18a0ab48695b81fdff16acc971ec9df8c25e25320`
- Base: `4605951e427f9ba6627375498dcb3c757c05bf36`
- Rework local head under test: `92e492bff63035aa885a3778639d3d709536c211`.
- Sessions dock is a same-row replacement: menu has exactly 快捷键/查看/会话; sessions state uses a bounded horizontally scrolling chip row plus 返回菜单. Candidate source is always reconciled favorite rows filtered by current ref; no all-session/level2 fallback.
- Input is one line unfocused and bounded multiline only while focused. Existing controlled TextFieldValue and attachment callbacks remain wired.
- Real Compose instrumentation now renders SessionShellScreen, clicks dock/hotkeys/chips, checks semantics, callback counts, current exclusion, offline disabled, empty filtering, and scheme changes. JVM-only smoke replacement removed. A debug-only persistent `SessionUiAcceptanceActivity` is present only in debug source/manifest.
- Fresh compile: `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:compileDebugAndroidTestKotlin --rerun-tasks --no-build-cache` succeeded (35 tasks executed).
- Fresh strict architecture command: exit 1; output `archwiki-strict-t3.log`. Remaining names are baseline violations; PR-local declarations are not listed.
- Fresh harness static syntax: `bash -n tools/test-session-ui-emulator.sh` passed. Emulator harness remains deferred: no selected running emulator; previous fresh run exit 2 (`expected exactly one Android emulator, found 0`) and no planned/discovered/executed counts, APK, or mobile-tree evidence are claimed. Raw output `emulator-fresh.log`.
