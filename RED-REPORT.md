# T-A C73 红测收据

## 结论

精确 C73 产品未改时，具名 `online=true/activity=working/health=abnormal` 场景在 GitHub hosted runner 上真实失败；失败点是完整 Gradle/JVM 测试断言，不是编译、依赖或装置缺口。

## 精确对象

- 产品红测提交：`95d28308824272b8c9e07a5d57704b7fefab349b`（仅 runner keystore 修复；产品仍为 C73 `1642902e7b9fe002f72a0a16a6963be2ef865141`）
- PR：[#73](https://github.com/Florious95/corral-core/pull/73)
- CI：[#34089083105](https://github.com/Florious95/corral-core/actions/runs/34089083105)
- Runner：`ubuntu-latest`，Temurin Java 21，Android SDK；临时公开 debug keystore 由 `keytool` 生成，无 secrets/真实会话。

## 实际命令与结果

```text
cd app
./gradlew --no-daemon --rerun-tasks \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest \
  --tests 'dev.agentmirror.app.ui.ExternalSessionStatusUiTest.fourAxisProjectionUsesOnlyOnlineAndActivity'
```

- 退出码：`1`
- Gradle：`BUILD FAILED in 3m 1s`
- 执行数：`41 actionable tasks: 41 executed`
- 测试结果：`1 test completed, 1 failed`
- 失败集：`ExternalSessionStatusUiTest > fourAxisProjectionUsesOnlyOnlineAndActivity`
- 证据关键行：`tmp/actions-red-2/run.log:1615-1632`
- 原始日志 SHA256：`c38fcb27e63c5e8829e6d6a4f4e433e03c7dce4d0b2810d674e75995d036b0ec`
- 运行 JSON SHA256：`f5b18403effb33348e933a3ae1df25e9cce49653e602893a0ad4034cf9a00d98`

## 装置前置尝试

初始 test-only 提交 `3bd7ba0138d2d3e169686327538728ed6be24411` 的 run `34088882213` 只因 hosted runner 缺少 `/home/runner/.android/debug.keystore` 在 Gradle 配置阶段失败；随后在 `95d283...` 加入临时公开 debug keystore 生成并重跑，才得到上述产品红。该装置失败不计作产品红。
