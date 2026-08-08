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
 * 原生 Kotlin + Jetpack Compose（Material3），applicationId 暂用 dev.agentmirror.app
 * （naming 任务定名后统一替换）。依赖极简，仅 Compose BOM + activity-compose + material3 +
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
    namespace = "dev.agentmirror.app"
    // compileSdk 36：BOM 2025.12.01 依赖链（androidx.core 1.18.0）要求 API 36+，本机已装 platforms 36。
    // targetSdk 保持 35，不改变运行时行为。
    compileSdk = 36
    defaultConfig {
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
    testImplementation("junit:junit:4.13.2")
}
