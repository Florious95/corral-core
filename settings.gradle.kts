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

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        val candidateRepo = rootDir.parentFile.resolve("workspace-create-agent-core-maven/.team/nodes/developer/session-longpress-close-core-maven")
        if (candidateRepo.isDirectory) {
            maven { url = candidateRepo.toURI() }
        }
        maven { url = uri("https://raw.githubusercontent.com/Florious95/corral-core/maven/") }
        // Host-routing core artifacts, pinned to the immutable Maven publication commit.
        maven { url = uri("https://raw.githubusercontent.com/Florious95/corral-core/0f365ec019bd4cf537972536eeae0f9588aa89dc/") }
        google()
        mavenCentral()
    }
}

rootProject.name = "corral-app"

include(":app")
