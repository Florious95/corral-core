# 知识基底 · test-app-android-seams（Android 接缝零测四类补齐）

## 0. 任务（taskbook.yaml#test-app-android-seams）
- 总图纸：docs/scenario-coverage.md §12 P0-6 行与 B6/B8/D9/E2 各行——先读。
- 目标：复用 fix-app-nav 已落地的 Robolectric 基建，补四类零测：
  1. NotificationHelper（app/…/service/NotificationHelper.kt）：渠道创建、通知构建（含深链 PendingIntent 形状——fix-app-nav 后有消费方了）、通知权限缺失时降级不崩；
  2. HttpUrlConnectionUploader：multipart 组包正确性、非 200 响应、JSON 不可解析、path 空、base 未配——每个错误分支都有独立文案，逐一断言（B6 实锤：整类零测）；
  3. SharedPreferencesPairingConfigStore：真持久化 round-trip（存→取→删→取 null；Robolectric 真 SharedPreferences 而非假件）；
  4. StateBadge 语义（Compose rule）：每态颜色/文案可辨；顺带 R-7 当期项——contentDescription 语义标注断言。
- 验收：`bash -lc 'cd app && ./gradlew -q :app:testDebugUnitTest'` 绿，新增 ≥20 用例。
- 红线：只写测试不改生产代码，**唯一例外**：StateBadge 若缺 contentDescription，可加法性补标注（R-7 当期裁定项），行内注释留痕 `// R-7`；其余缺陷即停报 leader 立案。

## 1. 现场基
- Robolectric 基建已就位（fix-app-nav 落）：robolectric:4.16.1 + isIncludeAndroidResources=true 已在 app/app/build.gradle.kts；模板 @Config(sdk=[34])（避开 compileSdk 36 支持面差异）——参考 app/app/src/test/…/MainActivityNavTest.kt。
- Compose 测试：StateBadge 用 createComposeRule（若需 ui-test 依赖可加法性入 build.gradle.kts，版本从既有 BOM 取）。
- **并行环境**：server 侧两席同期施工，与你无写域交集；app 模块当前无他人在写。
- upload/badge/store 的现有测试形状参考 conn/workspace/pairing 各包 *_test（表驱动+具名）。

## 2. 需求基（指针）
1. requirement-base/entries/013（测试体系）
2. requirement-base/entries/017 R-7（无障碍当期=徽章 contentDescription）
3. requirement-base/entries/003（B8 上传失败提示——文案断言的需求锚）

## 3. 经验基
- Robolectric 测试跑 JVM 不需模拟器；HttpUrlConnectionUploader 用本地 HttpServer 假端点（勿打真网）；交件前全量 :app:testDebugUnitTest + tools/gate/run.sh 自查；净化前缀 env -u TEAM_AGENT_*。
