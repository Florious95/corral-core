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
 * 连接状态机核（:core-conn）：auth / 订阅 / 重连 / 按 ref 分发。纯 Kotlin/JVM，零 Android。
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
    api(project(":core-protocol"))
    testImplementation("junit:junit:4.13.2")
}
