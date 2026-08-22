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
        google()
        mavenCentral()
    }
}

rootProject.name = "corral-app"

includeBuild("../corral-core") {
    dependencySubstitution {
        substitute(module("dev.agentmirror:core-protocol")).using(project(":core-protocol"))
        substitute(module("dev.agentmirror:core-terminal")).using(project(":core-terminal"))
        substitute(module("dev.agentmirror:core-conn")).using(project(":core-conn"))
        substitute(module("dev.agentmirror:terminal")).using(project(":terminal"))
    }
}

include(":app")
