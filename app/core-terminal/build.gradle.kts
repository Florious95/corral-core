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
 * 终端模拟内核模块（:core-terminal）。由 :terminal 改名复用，未另抄一份。
 *
 * 纯 Kotlin/JVM，零 Android 依赖（工程红线，选型依据 docs/decisions/term-core.md：自研最小 VT 引擎）。
 * 被 :app 渲染层（term-view 任务）消费；本模块只做解析与网格状态，不做绘制。
 * Kotlin 插件版本由根构建脚本 classpath 统一提供（2.2.0），此处不写版本号。
 */
plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
}

group = "dev.agentmirror.core"
version = "20260822.0"
apply(from = rootProject.file("gradle/core-maven-publish.gradle"))

kotlin {
    jvmToolchain(17)
}

dependencies {
    // 与 :app 测试基建一致，命中本地缓存。
    testImplementation("junit:junit:4.13.2")
}
