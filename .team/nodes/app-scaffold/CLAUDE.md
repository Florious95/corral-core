# 知识基底 · app-scaffold（系统编译产物）

## 0. 任务（来自任务书 taskbook.yaml#app-scaffold）
- 目标：建立 `app/` 安卓工程骨架：Kotlin + Jetpack Compose、gradle wrapper、分层包结构、Apache-2.0 文件头。每个模块/类必须有说明注释（工程红线）。
- 验收（exit 0 = 过）：`bash -lc 'cd /Volumes/nvme/Projects/远程Agent安卓/app && ./gradlew -q :app:assembleDebug'`
- 写范围：`app/`。红线：不动其他文件；禁止 git commit/push（leader 收口）。

## 1. 架构基（按 011 技术路线奠基）
- 原生 Kotlin + Jetpack Compose（Material3）。排除 WebView/跨端（需求 006/004 推导，见 011）。
- 结构：`settings.gradle.kts` + `:app` 模块；为后续任务预留 `:terminal` 模块位（term-core-android 任务创建，本任务只在 settings 注释预留说明，不建空模块）。
- applicationId 暂用 `dev.agentmirror.app`（naming 任务定名后统一替换，README 注明暂名）。
- minSdk 26 / targetSdk 35 / compileSdk 35（本机已装 platforms 34/35/36、build-tools 34/35）。
- 包分层（后续任务落位，本任务建包+占位 KDoc）：`conn/`（连接层）、`workspace/`（两级导航）、`session/`（会话页）、`termview/`（渲染）、`pairing/`（配对）、`service/`（前台服务）、`tsnet/`（内嵌 TS）。
- wrapper：本机 /opt/homebrew/bin/gradle 可用来 `gradle wrapper` 引导；此后一律用 `./gradlew`。
- 依赖极简：Compose BOM + activity-compose + material3 + 测试基建（junit）。后续依赖由各自任务引入。

## 2. 现场基（env-android 任务实测沉淀，2026-08-09）
- JDK：openjdk@17（17.0.19），JAVA_HOME 已写入 ~/.profile 与 ~/.zprofile 并 PATH 前置。
  **所有构建命令必须 `bash -lc` 执行**，否则 /usr/bin/java stub 遮蔽（brew link 无效，这是实测坑）。
- SDK：~/Library/Android/sdk，platform-tools 37.0.0 / platforms android-34/35/36 / build-tools 34/35 / licenses 全接受。
- 需要 local.properties 指向 sdk.dir=/Users/alauda/Library/Android/sdk（写在 app/ 内，属 write_scope）。

## 3. 需求基（指针，按序读）
1. /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/001-产品命题-tmux镜像范式.md（产品是什么）
2. /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/011-技术路线裁定.md（为什么原生 Kotlin+Compose）
3. /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/004-后台策略-无状态免疫.md（轻量化=无状态，骨架不引入任何持久层）

## 4. 经验基
- 注释红线：每个模块 README 头注释 + 每个类/顶层函数 KDoc 首句一句话职责（未来 arch-wiki 解析用）。
- 构建验证先 `./gradlew -q help` 快速确认 wrapper/JDK 通，再 assembleDebug。
- 失败重试预算 2；依赖下载类失败等 30s 再试。

## 5. 沉淀区（唯一允许你追加写入的区域）
