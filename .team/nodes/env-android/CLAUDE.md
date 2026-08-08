# 知识基底 · env-android（系统编译产物）

## 0. 任务（来自任务书 taskbook.yaml#env-android）
- 目标：在本机建立可无人值守的安卓构建环境：安装 JDK 17+（brew）、Android platform-tools（adb）、确认 cmdline-tools/sdkmanager 可用并接受 licenses。
- 验收（exit 0 = 过）：`bash -lc 'java -version && ~/Library/Android/sdk/platform-tools/adb --version'`
- 写范围：无仓库写入，仅系统环境。红线：只装缺的，不动已有 SDK 组件与系统配置。

## 1. 现场基（leader 实测，2026-08-09）
- macOS (Darwin 25.5.0, arm64)，Homebrew 可用（/opt/homebrew）。
- `java` 不存在（Unable to locate a Java Runtime）→ 建议 `brew install --cask temurin@21` 或 `brew install openjdk@17` 并处理 PATH/link（brew 输出会给指引；验收用 `bash -lc`，确保登录 shell 能找到 java）。
- `~/Library/Android/sdk/` 已存在：build-tools、cmdline-tools、emulator、licenses、ndk 在；**platform-tools（adb）缺失**，`platforms/` 未见。
- gradle 在 /opt/homebrew/bin/gradle（但项目将用 wrapper，不依赖它）。
- 用 `~/Library/Android/sdk/cmdline-tools/*/bin/sdkmanager` 安装：`platform-tools`、`platforms;android-34`（供后续 app 构建）、必要 build-tools 若缺；`--licenses` 全接受。

## 2. 需求基（指针）
- /Volumes/nvme/Projects/远程Agent安卓/requirement-base/entries/010-最终验收与运行方式.md（验收=安卓 APP，本任务是全部安卓任务的前置）

## 3. 经验基
- 每步安装前先探测（which/ls），已存在即跳过并在 summary 记"已存在"。
- brew 安装 JDK 后常需 `sudo ln -sfn ...` 或 export JAVA_HOME——优先选不需要 sudo 的路径（temurin cask 装到系统目录无需 link；若无法 sudo 则用 openjdk + 写 ~/.zprofile 的 JAVA_HOME/PATH，并在 report 里说明改了哪一行）。
- 失败重试预算 2；网络类失败等 30s 再试。

## 4. 沉淀区（唯一允许你追加写入的区域）
