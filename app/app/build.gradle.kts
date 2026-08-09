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
    defaultConfig {
        // applicationId = dev.agentmirror.app（014 落账裁定）：与 namespace、源码包三方对齐。
        // 无 agentmirror.com 域名，com.* 名不副实；.dev 为开源惯例。naming 曾偏离改 com.*，
        // leader 回炉裁定改回（2026-08-09）。
        applicationId = "dev.agentmirror.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
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
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // conn 层：协议控制帧 JSON 编解码（kotlinx-serialization-json，Apache-2.0）。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // 终端内核：ANSI/CSI 解析 + 字符网格 + 本地 scrollback（:terminal，term-core-android 任务交付，纯 JVM 零 Android 依赖）。
    implementation(project(":terminal"))
    // 配对：OkHttp WebSocket 真实传输（conn 层 WebSocketTransport 接口的 service 实现，
    // 清偿传输欠账①，leader 裁定 A）+ MockWebServer 单测（均 Apache-2.0）。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 配对扫码：CameraX 相机分析流 + ZXing core（均 Apache-2.0，零 GMS 依赖，008 开源自托管精神）。
    // 注意：google maven 无 androidx.camera:camera-bom（1.4.x/1.3.x 实测 404，BOM 从未随该系列发布），
    // 各 camera artifact 的 POM 自带同版本依赖管理（1.4.1 互 pin），故显式按版本声明、不引 BOM。
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.zxing:core:3.5.3")
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
}
