# T-A 测试计划与执行结果

## 目标

在精确 C73 `1642902e7b9fe002f72a0a16a6963be2ef865141` 先执行具名改前红，再修改 `sessionRowMotion` 与唯一 Compose caller；Grok 资源门未满足，实际执行改由标准 GitHub hosted runner 承担。

## 红门（产品修改前）

```text
cd app && ./gradlew --no-daemon --rerun-tasks :app:testDebugUnitTest \
  --tests 'dev.agentmirror.app.ui.ExternalSessionStatusUiTest.fourAxisProjectionUsesOnlyOnlineAndActivity'
```

实际结果：CI run `34089083105` 在产品未改提交 `95d28308824272b8c9e07a5d57704b7fefab349b` 上退出 `1`，`1 test completed, 1 failed`，命中 `fourAxisProjectionUsesOnlyOnlineAndActivity`；Gradle `41 actionable tasks: 41 executed`。

## 绿门

```text
cd app && ./gradlew --no-daemon --rerun-tasks :app:testDebugUnitTest \
  --tests 'dev.agentmirror.app.ui.ExternalSessionStatusUiTest' \
  --tests 'dev.agentmirror.app.workspace.L2UnknownStatusTest' \
  --tests 'dev.agentmirror.app.workspace.PiWorkingDtoProjectionTest'
```

矩阵：online × activity(`working`,`idle`,`unknown`) × health(`normal`,`unknown`,`abnormal`)；在线行只由 activity 映射 Working/Idle/None，离线全 None。另测 malformed health → `unknown` 且合法 working 不变、activity/status 冲突 → unknown。Compose 断言从 DTO → `toL2Entry` → `toSessionItem` → 同 ref 真实 `SessionRow`，working 连续帧变化、idle 静止。

## 执行结果

绿门由 CI run `34090682711` 完成：退出 `0`，`BUILD SUCCESSFUL`，`41 actionable tasks: 41 executed`，JUnit `20/0/0/0`（tests/failures/errors/skipped）。完整命令、哈希与失败集合见 `A73-RECEIPT.json`、`RED-REPORT.md`、`GREEN-REPORT.md`。

## 未执行

未启动新的 Grok Bot sync/build；本机未执行 Gradle/Go/Rust；未构建 APK、启动 AVD 或做最终真机/组合验收。
