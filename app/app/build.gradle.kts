/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * AgentMirror 主应用模块（:app）。
 *
 * 原生 Kotlin + Jetpack Compose（Material3），applicationId 为 dev.agentmirror.app
 * （014 裁定落账；naming 曾误改 com.agentmirror.app 已回炉改正，2026-08-09）。
 * namespace 与 Kotlin 源码包路径一致（均为 dev.agentmirror.app），三方对齐。
 * 依赖极简，仅 Compose BOM + activity-compose + material3 +
 * kotlinx-serialization-json（conn 层控制帧编解码，Apache-2.0，见 docs/protocol.md §4）+
 * 测试基建（junit），后续模块由各自任务引入（见需求 011 技术路线裁定、004 无状态策略）。
 *
 * 注意：kotlinx-serialization 的 @Serializable 需要编译器插件（与 Kotlin 同版本 2.2.0），
 * 插件是加该依赖的必要组成部分；除它外不引入任何额外构建逻辑。
 */
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.0"
}

android {
    // namespace = R 包与资源符号域。保留 dev.agentmirror.app（与源码包路径一致，
    // 见文件头注释：迁包是源码级改动，不在 naming 单点范围）。
    namespace = "dev.agentmirror.app"
    // compileSdk 36：BOM 2025.12.01 依赖链（androidx.core 1.18.0）要求 API 36+，本机已装 platforms 36。
    // targetSdk 保持 35，不改变运行时行为。
    compileSdk = 36
    // 仅供本地 debug vs release 性能对比，⛔ 不可用于分发。
    // 口令是 Android SDK 公开的 debug keystore 固定值（storePassword/keyAlias/keyPassword），不是秘密。
    val debugKeystore = file(System.getProperty("user.home") + "/.android/debug.keystore")
    signingConfigs {
        create("releasePerfCompare") {
            require(debugKeystore.isFile) {
                "release 签名失败：未找到 Android debug keystore（${debugKeystore.absolutePath}）。请先用 SDK 生成该文件，或跑一次 debug 构建；⛔ 不许静默跳过签名。"
            }
            storeFile = debugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    defaultConfig {
        // applicationId = dev.agentmirror.app（014 落账裁定）：与 namespace、源码包三方对齐。
        // 无 agentmirror.com 域名，com.* 名不副实；.dev 为开源惯例。naming 曾偏离改 com.*，
        // leader 回炉裁定改回（2026-08-09）。
        applicationId = "dev.agentmirror.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("releasePerfCompare")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // Robolectric 基建（fix-app-nav 引入，后续 seams 任务共用）：在 JVM 上模拟
            // Activity 生命周期（深链/旋转重建导航态），需加载合并后的 manifest 与资源。
            isIncludeAndroidResources = true
        }
    }
}

// 红测（fix-app-network-manifest）前置依赖：ManifestNetworkPolicyTest 同时断言 debug 与
// release 两个变体的 merged manifest（INTERNET + 明文放行）。单测任务默认只走 debug 依赖链，
// release 的最终 merged manifest 产物须显式前置 :app:processReleaseManifest 才能保证存在。
// 注：AGP 的单测任务在 android 配置阶段后延后注册，故用 configureEach 延迟挂依赖
// （dependsOn 传任务名字符串由 Gradle 延迟解析，不要求此刻已注册）。
tasks.configureEach {
    if (name == "testDebugUnitTest") {
        dependsOn("processReleaseManifest")
    }
}

dependencies {
    // Compose BOM 统一管理 Compose 系版本。
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // conn 层：协议控制帧 JSON 编解码（kotlinx-serialization-json，Apache-2.0）。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // 终端内核：ANSI/CSI 解析 + 字符网格 + 本地 scrollback（:terminal，term-core-android 任务交付，纯 JVM 零 Android 依赖）。
    implementation(project(":core-terminal"))
    implementation(project(":core-protocol"))
    implementation(project(":core-conn"))
    implementation(project(":terminal"))
    // 配对：OkHttp WebSocket 真实传输（conn 层 WebSocketTransport 接口的 service 实现，
    // 清偿传输欠账①，leader 裁定 A）+ MockWebServer 单测（均 Apache-2.0）。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 配对扫码：CameraX 相机分析流 + ZXing core（均 Apache-2.0，零 GMS 依赖，008 开源自托管精神）。
    // 注意：google maven 无 androidx.camera:camera-bom（1.4.x/1.3.x 实测 404，BOM 从未随该系列发布），
    // 各 camera artifact 的 POM 自带同版本依赖管理（1.4.1 互 pin），故显式按版本声明、不引 BOM。
    implementation("androidx.camera:camera-core:1.6.1")
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")
    implementation("com.google.zxing:core:3.5.4")
    // 内嵌组网：tsnet 用户态节点 gomobile 绑定（本地构建产物，非 maven——
    // libtailscale 无任何预构建 artifact，实测 Maven Central 0 命中；由 tools/tsnetbind
    // 重建，见 libs/README.md）。tailscale 系 BSD-3，Apache-2.0 兼容，零 GMS。
    implementation(files("libs/tsnetbind.aar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Robolectric 基建（fix-app-nav 引入，后续 seams 任务共用）：Activity/生命周期级单测。
    // 版本 4.16.1（maven 实测存在，2026-08-09；4.17-beta 不取）；测试以 @Config(sdk=[34])
    // 跑，避开 compileSdk 36 的 Robolectric 支持面差异（fix-app-nav 知识基底 §3 小样验证）。
    testImplementation("org.robolectric:robolectric:4.16.1")
    // Compose UI 测试（test-app-android-seams 引入）：StateBadge 语义用 createComposeRule
    // 在 Robolectric JVM 上跑（不起模拟器）。BOM 约束 testImplementation 需单独声明
    // （implementation 的 platform 约束不传播到 test 配置）；ui-test-manifest 的
    // ComponentActivity 宿主按变体合并进 manifest（debugImplementation 进 debug 主 manifest，
    // releaseImplementation 进 release 主 manifest，见下方 fix-release-test-host 注释）。
    testImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // release 单测变体的 Compose 测试宿主（fix-release-test-host 清偿门债：testReleaseUnitTest
    // 原 13 红，统一 Unable to resolve ComponentActivity）。
    // 机制（小步实验实测，非猜测）：Robolectric 单测的活动解析读的是 packageReleaseUnitTestForUnitTest
    // 产物（apk-for-local-test.ap_）内嵌的**变体主 manifest**——不是 processReleaseUnitTestManifest
    // 的 XML。因此 testImplementation 只让 unit-test XML 含宿主、资源 APK 仍缺（实测仍红）；
    // 必须用 releaseImplementation 把 ui-test-manifest 并入 release 主 manifest，资源 APK 才带上
    // ComponentActivity 宿主（实测转绿）。
    // 副作用评估：release APK 因此多一个 ComponentActivity 测试宿主。该 activity 是
    // ui-test-manifest 官方设计的 test-only 宿主（无 intent-filter、不可隐式启动，仅
    // createComposeRule/ActivityScenario 显式 launch 使用），对本应用主流程零影响；
    // debug 变体本就经 debugImplementation 带同一宿主，双变体行为自此对称。
    releaseImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
