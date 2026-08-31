# REWORK-003 host/static evidence
- Frozen digest: `203a1372af12a71bcb2506d18a0ab48695b81fdff16acc971ec9df8c25e25320`
- Local head at execution start: `eee45b0a861c49c2293fe7ca884084adc14ae012`; final head recorded in PR result.
- `bash tools/test-session-ui-emulator.sh --list-tests`: rc=0, exact planned_count=18 and discovered_count=18, exact names equal.
- `bash -n tools/test-session-ui-emulator.sh`: rc=0.
- Host: `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:compileDebugAndroidTestKotlin :app:assembleRelease --rerun-tasks --no-build-cache`: rc=0, 85 tasks executed.
- No emulator/device was started or touched per apparatus freeze. Normal harness execution remains NOT_RUN_NO_DEVICE; no executed count, APK acceptance, or MCP tree claimed.
- Persistent debug fixture now exposes real shell controls; release build excludes debug source/manifest by variant.
