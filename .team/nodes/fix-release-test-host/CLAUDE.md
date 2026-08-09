# 知识基底 · fix-release-test-host（release 单测变体 Compose 宿主缺失——门债）

## 0. 任务（taskbook.yaml#fix-release-test-host）
- 现象：`:app:testReleaseUnitTest` 13 红——StateBadgeTest 9 / WorkspaceWiringTest 3 / PairingScreenClockPumpTest 1，统一 `Unable to resolve activity ... cmp=dev.agentmirror.app/.MainActivity`（gate-report.json 有全列表）。debug 变体同测试全绿。
- 根因（w-input-keys-app 上报定性）：`androidx.compose.ui:ui-test-manifest` 仅 `debugImplementation`（app/app/build.gradle.kts:133，行上注释明示"test host 由 debug 变体合并进 manifest"），release 单测变体合并的 manifest 无 Compose 测试宿主 Activity → createComposeRule/ActivityScenario 必红。
- 目标：双变体全绿。修复方向**先实验再定**（不猜 Gradle 行为）：候选 ①ui-test-manifest 提供给 release 单测变体（testImplementation / testReleaseImplementation 实测哪个能让 Robolectric 合并进 manifest）；②Robolectric @Config 显式 manifest/宿主声明；③测试改用自注册宿主。选实测最小可行者，注释写清为什么。
- 验收：`bash -lc 'cd app && ./gradlew -q :app:testDebugUnitTest :app:testReleaseUnitTest'` exit 0，且 `bash tools/gate/run.sh` 结论 pass（用例数棘轮只升不降）。
- 红线：不得删测试、不得跳过（@Ignore）测试来"转绿"；不得降低 debug 变体行为。

## 1. 现场基
- app/app/build.gradle.kts:126-133（testOptions + ui-test-manifest 注释与依赖）。
- 涉事测试：app/app/src/test/kotlin/…/StateBadgeTest.kt、workspace/WorkspaceWiringTest.kt、app/app/src/test/java/…/pairing/PairingScreenClockPumpTest.kt——只允许动其 @Config/基建声明，不许动断言语义。
- Robolectric 4.16.1 + @Config(sdk=[34]) 模板（MainActivityNavTest.kt）。
- gate 报告：tools/gate/gate-report.json（app 面 failures 全列表）。
- 工作区当前无他席施工。

## 2. 需求基（指针）
1. requirement-base/entries/013（测试体系：0 新回归、棘轮）
2. taskbook#test-app-android-seams 证据（这批测试的来历与设计）

## 3. 经验基
- 小步实验：每改一次立即跑 testReleaseUnitTest 快速反馈；两个方向 15 分钟内无进展就换路；净化前缀 env -u TEAM_AGENT_*；交件前全量门自查。
