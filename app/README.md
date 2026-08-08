<!--
Copyright 2026 AgentMirror Project Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# AgentMirror App 工程

远程 Agent CLI 的「手机镜子」Android 客户端。本仓库为 monorepo（需求 011）：`app/` 为 Android
客户端，`server/` 为 Go 侧 sidecar（见各目录 README）。

- 技术栈：原生 Kotlin + Jetpack Compose（Material3），排除 WebView/跨端（需求 011）
- 客户端无状态：仅渲染器 + 输入框，所有状态在主机（需求 004）
- 工程结构：`settings.gradle.kts` + `:app` 模块；`:terminal` 模块位预留给 term-core-android 任务

## 暂名说明

本工程当前名称为**暂名「Agent Mirror」**，applicationId 为 `dev.agentmirror.app`。
naming 任务定名后统一替换（含应用名、包名、目录名）。

## 构建

前置：JDK 17（`JAVA_HOME` 需指向 openjdk@17）、Android SDK（`local.properties` 中的
`sdk.dir`）。

```bash
cd app
./gradlew -q :app:assembleDebug   # 产物 app/build/outputs/apk/debug/app-debug.apk
```
