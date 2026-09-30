# 阶段三 Issue 全量清单（gate-static-analysis 立账）

> 本文档由 `gate-static-analysis` 席位（2026-08-11）一次性生成：**只暴露不修复**。
> 每条 issue 仅记账，不做任何产品代码改动；leader 按「建议归属包」直接派施工席。
> 来源工具：`staticcheck`（Go，BSD-3）、`Android Lint`（AGP，:app:lintDebug）、
> `go vet`、`gofmt`、`go test`、`archwiki --check`。
> 判读以本次 `tools/gate/gate-report.json` 与 `app/app/build/reports/lint-results-debug.xml` 为准。

**总账：38 条** = staticcheck 9 + Android Lint 29。与 gate-report 的失败条目数逐一对应
（server 面 failures=9，app 面 failures=29）。

**按建议归属包分布**：`internal/api` 6、`internal/agentstate` 1、`internal/ws_test` 1、
`cmd/agentmirrord` 1、`app/app` 运行时 12、`app/app` 构建配置 14、`tools/tsnetbind` 3。

**按初判严重度**：P1 = 5（lint 3 + lint 3 条重复对齐/见 G）、P2 = 7、P3 = 26。
> P1 分布见下：PermissionImpliesUnsupportedChromeOsHardware（Error 级）、InlinedApi
> （API 29 字段在 minSdk 26 直用）、Aligned16KB ×3（Android 15 上架硬性）。

---

## A. 建议归属 `internal/api`（server，6 条）

| # | 来源 | 文件:行 | 规则 id | 原文 | 初判严重度 |
|---|------|---------|---------|------|-----------|
| 1 | staticcheck | server/internal/api/server.go:60 | U1000 | `field loopOnce is unused` | P2（死字段，疑删除遗留） |
| 2 | staticcheck | server/internal/api/session.go:98 | U1000 | `func (*sessionCatalog).bridgeFor is unused` | P2（死方法，疑删除遗留） |
| 3 | staticcheck | server/internal/api/connected_idle_economy_test.go:299 | SA4006 | `this value of snap is never used` | P2（测试内被断言后丢弃，属断言缺口） |
| 4 | staticcheck | server/internal/api/state_wiring_test.go:278 | S1000 | `should use a simple channel send/receive instead of select with a single case` | P3（风格简化，非缺陷） |
| 5 | staticcheck | server/internal/api/ws_test.go:114 | U1000 | `func (*wsEnv).readControlTimeout is unused` | P3（测试辅助死代码） |
| 6 | staticcheck | server/internal/api/ws_test.go:171 | U1000 | `func (*wsEnv).readBinary is unused` | P3（测试辅助死代码） |

## B. 建议归属 `internal/agentstate`（server，1 条）

| # | 来源 | 文件:行 | 规则 id | 原文 | 初判严重度 |
|---|------|---------|---------|------|-----------|
| 7 | staticcheck | server/internal/agentstate/rules.go:132 | S1011 | `should replace loop with out = append(out, raw...)` | P3（风格简化，非缺陷） |

## C. 建议归属 `internal/api` 测试辅助（server，1 条）

| # | 来源 | 文件:行 | 规则 id | 原文 | 初判严重度 |
|---|------|---------|---------|------|-----------|
| 8 | staticcheck | server/internal/api/ws_test.go:215 | U1000 | `func modelForSocket is unused` | P3（测试辅助死代码） |

> 注：#5/#6/#8 同属 `ws_test.go`，可合并为一条施工（死测试辅助清理）。

## D. 建议归属 `cmd/agentmirrord`（server，1 条）

| # | 来源 | 文件:行 | 规则 id | 原文 | 初判严重度 |
|---|------|---------|---------|------|-----------|
| 9 | staticcheck | server/cmd/agentmirrord/pidfile.go:26 | U1000 | `const pidfileName is unused` | P2（常量定义未引用，疑死代码） |

---

## E. 建议归属 `app/app` 运行时（Android 源码/manifest/res，12 条）

| # | 来源 | 文件:行 | 规则 id | 原文 | 初判严重度 |
|---|------|---------|---------|------|-----------|
| 10 | Android Lint | app/app/src/main/AndroidManifest.xml:40 | **PermissionImpliesUnsupportedChromeOsHardware** | `Permission exists without corresponding hardware <uses-feature android:name="android.hardware.camera" required="false"> tag` | **P1（Error 级，唯一 error；补 uses-feature required=false 即解）** |
| 11 | Android Lint | app/app/src/main/java/dev/agentmirror/app/service/MirrorForegroundService.kt:91 | **InlinedApi** | `Field requires API level 29 (current min is 26): ServiceInfo#FOREGROUND_SERVICE_TYPE_DATA_SYNC` | **P1（API 29 字段在 minSdk 26 直用；低版本机型潜在崩溃，需 API 守卫）** |
| 12 | Android Lint | app/app/src/main/java/dev/agentmirror/app/pairing/PairingConfigStore.kt:131 | ApplySharedPref | `Consider using apply() instead; commit writes its data to persistent storage immediately` | P2（性能/UI 线程；commit→apply 语义差异需人工确认） |
| 13 | Android Lint | app/app/src/main/java/dev/agentmirror/app/pairing/PairingConfigStore.kt:74 | UseKtx | `Use the KTX extension function SharedPreferences.edit instead` | P3（风格） |
| 14 | Android Lint | app/app/src/main/java/dev/agentmirror/app/pairing/PairingConfigStore.kt:88 | UseKtx | `Use the KTX extension function SharedPreferences.edit instead` | P3（风格） |
| 15 | Android Lint | app/app/src/main/java/dev/agentmirror/app/pairing/PairingConfigStore.kt:126 | UseKtx | `Use the KTX extension function SharedPreferences.edit instead` | P3（风格） |
| 16 | Android Lint | app/app/src/main/java/dev/agentmirror/app/pairing/PairingScreen.kt:187 | UseKtx | `Use the KTX extension function String.toUri instead` | P3（风格） |
| 17 | Android Lint | app/app/src/main/java/dev/agentmirror/app/service/NotificationHelper.kt:52 | ObsoleteSdkInt | `Unnecessary; SDK_INT is never < 26` | P3（minSdk 已 26，判据恒真，清理） |
| 18 | Android Lint | app/app/src/main/res/mipmap-anydpi-v26 | ObsoleteSdkInt | `folder configuration (v26) unnecessary; minSdkVersion is 26. Merge resources` | P3（资源目录整理） |
| 19 | Android Lint | app/app/src/main/res/values/colors.xml:18 | UnusedResources | `The resource R.color.brand_primary appears to be unused` | P2（实测零引用；Theme.kt 硬编码 0xFF1B2A4A，与注释「同值」不一致） |
| 20 | Android Lint | app/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml:16 | MonochromeLauncherIcon | `adaptive icon missing a monochrome tag` | P3（Android 13+ 主题图标；产品视觉补缺） |
| 21 | Android Lint | app/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml:16 | MonochromeLauncherIcon | `adaptive roundIcon missing a monochrome tag` | P3（同上） |

## F. 建议归属 `app/app` 构建配置（依赖版本 / 工程卫生，14 条）

| # | 来源 | 文件:行 | 规则 id | 原文 | 初判严重度 |
|---|------|---------|---------|------|-----------|
| 22 | Android Lint | app/app/build.gradle.kts:50 | OldTargetApi | `Not targeting the latest versions of Android; compatibility modes apply` | P2（targetSdk 35 < 最新；属排期决策） |
| 23 | Android Lint | app/gradle/wrapper/gradle-wrapper.properties:3 | AndroidGradlePluginVersion | `A newer version of Gradle than 8.14.3 is available: 8.14.5` | P3（构建工具升级） |
| 24 | Android Lint | app/app/build.gradle.kts:95 | GradleDependency | `A newer version of androidx.compose:compose-bom than 2025.12.01 is available: 2026.06.01` | P3（依赖升级） |
| 25 | Android Lint | app/app/build.gradle.kts:96 | GradleDependency | `A newer version of androidx.core:core-ktx than 1.18.0 is available: 1.19.0` | P3（依赖升级） |
| 26 | Android Lint | app/app/build.gradle.kts:112 | GradleDependency | `A newer version of androidx.camera:camera-core than 1.4.1 is available: 1.6.1` | P3（依赖升级） |
| 27 | Android Lint | app/app/build.gradle.kts:113 | GradleDependency | `A newer version of androidx.camera:camera-camera2 than 1.4.1 is available: 1.6.1` | P3（依赖升级） |
| 28 | Android Lint | app/app/build.gradle.kts:114 | GradleDependency | `A newer version of androidx.camera:camera-lifecycle than 1.4.1 is available: 1.6.1` | P3（依赖升级） |
| 29 | Android Lint | app/app/build.gradle.kts:115 | GradleDependency | `A newer version of androidx.camera:camera-view than 1.4.1 is available: 1.6.1` | P3（依赖升级） |
| 30 | Android Lint | app/app/build.gradle.kts:132 | GradleDependency | `A newer version of androidx.compose:compose-bom than 2025.12.01 is available: 2026.06.01` | P3（依赖升级） |
| 31 | Android Lint | app/app/build.gradle.kts:34 | NewerVersionAvailable | `A newer version of org.jetbrains.kotlin.plugin.serialization than 2.2.0 is available: 2.4.10` | P3（插件升级） |
| 32 | Android Lint | app/app/build.gradle.kts:103 | NewerVersionAvailable | `A newer version of org.jetbrains.kotlinx:kotlinx-serialization-json than 1.9.0 is available: 1.11.0` | P3（依赖升级） |
| 33 | Android Lint | app/app/build.gradle.kts:108 | NewerVersionAvailable | `A newer version of com.squareup.okhttp3:okhttp than 4.12.0 is available: 5.4.0` | P3（依赖升级） |
| 34 | Android Lint | app/app/build.gradle.kts:116 | NewerVersionAvailable | `A newer version of com.google.zxing:core than 3.5.3 is available: 3.5.4` | P3（依赖升级） |
| 35 | Android Lint | app/app/build.gradle.kts:122 | NewerVersionAvailable | `A newer version of com.squareup.okhttp3:mockwebserver than 4.12.0 is available: 5.4.0` | P3（依赖升级） |

> 依赖升级类（#23-#35）13 条为 `GradleDependency`/`NewerVersionAvailable`/`AndroidGradlePluginVersion`，
> 属**基建排期**，不具即时风险，建议归入依赖升级专项而非逐条施工。

## G. 建议归属 `tools/tsnetbind`（Android 原生库对齐，3 条同一 AAR 重复告警）

| # | 来源 | 文件:行 | 规则 id | 原文 | 初判严重度 |
|---|------|---------|---------|------|-----------|
| 36 | Android Lint | app/app/libs/tsnetbind.aar → jni/arm64-v8a/libgojni.so | **Aligned16KB** | `The native library arm64-v8a/libgojni.so is not 16 KB aligned` | **P1（Android 15+ 对未对齐 native 库强制 16KB 要求；需在 gomobile 构建链加对齐）** |
| 37 | Android Lint | 同上 | **Aligned16KB** | 同上（重复告警） | P1 |
| 38 | Android Lint | 同上 | **Aligned16KB** | 同上（重复告警） | P1 |

> #36-#38 指向 `tools/tsnetbind/` 的 gomobile 构建产物，是**真实发布缺陷**（16KB 对齐为
> Android 15 硬性上架要求），不属 lint 规则可豁免的生成物目录，故不豁免、记真实缺陷。

---

## 附录

### 阳性对照（自证工具真在跑）

| 工具 | 故意引入 | 结果 | 规则 id | 撤销自证 |
|------|---------|------|---------|---------|
| staticcheck | `internal/api/zz_positive_control.go` 未使用变量 `positiveControlUnused` | staticcheck 退出 1，报 `file:4:5: var positiveControlUnused is unused` | **U1000** | 文件已删；再跑 staticcheck 该构造消失，`git diff` 无残留 |
| Android Lint | `strings.xml` 加未引用字符串 `zz_positive_control_unused_string` | `:app:lintDebug` 报 `strings.xml:20: Warning: UnusedResources`（文本+XML 双报告命中） | **UnusedResources** | 已删；`git diff app/app/src/main/res/values/strings.xml` 干净 |

### 空扫描自证

- **staticcheck**：非 0——扫到 9 条（77 个 .go 文件，exit 1）。真在跑。
- **Android Lint**：非 0——扫到 29 条（1 error + 28 warnings）。真在跑。
- **archwiki T3 全 PASS（0 违规）**：arch-criteria-t3/t3-contract 既有判据在 19 包上的报告模式
  结果；fixtures 已由该任务挂进 `test_check.py`（必红/必绿四格防「没扫到」当「干净」），本次
  扫描 2176 个仓库文件基名索引 / 138 个 @contract 符号，覆盖量非零。

### 棘轮说明

gate 用例数棘轮**未下行**：server 276→315（+39）、app 547→757（+210）、archwiki 1→1。
棘轮红线未触发，未调用 `--accept-baseline`。app 面 757 = debug 376 + release 376 + terminal 5
（`./gradlew test` 双变体各计一次 + :terminal 的 test-results 计入求和，README §三面行为已说明）。

### 阳性对照误伤（已撤销）

app 面套件运行期间，我曾把阳性对照字符串用 `cat >>` 追加到 `</resources>` 之后（XML 非法），
触发一次 `mergeDebugResources` 失败、导致 app 套件多记一条 `gradle test (exit 1)` 构建级失败。
该误伤已撤销（strings.xml 恢复 HEAD），复跑后 app 套件该条消失、29 条 lint 之外零失败。
此条**不计入总账**，仅留档供排查。

### 非绿来源与归属（本轮 gate 结论 fail 的逐条说明）

gate 结论 `fail` 的全部来源：
- **server 面 9 条 staticcheck**——见 A/B/C/D 分组；
- **app 面 29 条 lint**——见 E/F/G 分组；
- **archwiki 面绿**（exit 0）。

无棘轮下行、无 `no result`、无工具崩溃。全部 38 条均可归因到具体包，leader 可直接派席。
