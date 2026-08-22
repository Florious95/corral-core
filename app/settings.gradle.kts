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
 * AgentMirror App 工程设置（根）。
 *
 * 仅含 :app 模块；:terminal 模块位预留给 term-core-android 任务创建（见需求 011 技术路线裁定），
 * 本任务不在 settings 里 include 空模块，仅在下方注释中预留说明。
 */
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 所有模块统一走根级仓库，禁止模块自行声明仓库。
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "agentmirror-app"

// :app —— 主应用模块（本任务建立）
include(":app")

// :terminal —— 门面，api 转发 :core-terminal（源码已改名并入，未另抄）。
include(":terminal")

// :core-terminal —— 终端模型（由 :terminal 改名复用）
include(":core-terminal")

// :core-protocol —— 帧编解码 / 协议类型
include(":core-protocol")

// :core-conn —— 连接状态机
include(":core-conn")
