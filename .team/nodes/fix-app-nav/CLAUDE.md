# 知识基底 · fix-app-nav（实锤缺陷 D-2+D-3 合并修复）

## 0. 任务（taskbook.yaml#fix-app-nav）
- 总图纸：docs/scenario-coverage.md §0.4 D-2/D-3 行（代码坐标 NotificationHelper.kt:148-158、MainActivity.kt、AgentMirrorApp.kt:49-52）与 §12 P0-2/3 行——先读。
- 目标：①深链消费：MainActivity 读 launch intent + onNewIntent，ACTION_OPEN_SESSION+EXTRA_SESSION_REF 直达会话页（singleTask/launchMode 自查裁量，注释说明）；②activeSession/showPairing 改 rememberSaveable（自定义 Saver 若类型需要）；③引入 Robolectric 基建（依赖版本实测存在再写入——camera-bom 幻觉版本前车之鉴；后续 seams 任务共用此基建）。
- 验收：`bash -lc 'cd app && ./gradlew -q :app:testDebugUnitTest --tests "*Nav*"'`（Robolectric：深链启动/onNewIntent 断言 activeSession=对应 ref；Activity 重建后仍在会话页；修前红）。
- 写范围：app/app/src/main/、src/test/、build.gradle.kts。红线：不动 conn/termview/session 业务逻辑，只动导航壳与通知消费侧。

## 1. 现场基
- fg-service 沉淀必读（.team/nodes/fg-service）：NotificationHelper 深链 PendingIntent 已构造好 ACTION/EXTRA，只缺消费方。
- session-ui 沉淀：SessionRoute 由 activeSession 驱动；ServiceWire.uiConnector 单槽约束勿破坏。
- 共享编译单元纪律照旧（fix-idlecpu 在 server 侧，:app 当前你独占，仍保持落盘可编译）。

## 2. 需求基（指针）
requirement-base/entries/003（第四标准：点通知直达=被唤醒的完成态）、004（重建恢复哲学）。

## 3. 经验基
- 红测先行；Robolectric sdk 与 compileSdk 36 兼容性先小样验证再铺开；净化前缀；交件前全量门自查。
