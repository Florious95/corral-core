# T-A 修复绿测收据

## 结论

health veto 已删除；活动灯只由已解析 `SessionStatus` 与显式 `isOnline` 决定。精确 DTO→`toL2Entry()`→`toSessionItem()`→真实 `SessionRow` Compose 测试通过，异常 health 保留为独立元数据且不否决合法 working activity。

## 实际命令与结果

```text
cd app
./gradlew --no-daemon --rerun-tasks \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest \
  --tests 'dev.agentmirror.app.ui.ExternalSessionStatusUiTest' \
  --tests 'dev.agentmirror.app.workspace.L2UnknownStatusTest' \
  --tests 'dev.agentmirror.app.workspace.PiWorkingDtoProjectionTest'
```

- 修复产品提交：`c33317d7846b6fafc73718b5397780de3e7185c1`
- 最终 CI/receipt 提交：`cfb8d32a11cd3a97d2a096d57b09d7bc184924d9`
- CI：[#34090682711](https://github.com/Florious95/corral-core/actions/runs/34090682711)
- Runner：`ubuntu-latest`，Temurin Java 21，Android SDK
- 退出码：`0`
- Gradle：`BUILD SUCCESSFUL in 2m 55s`
- 执行数：`41 actionable tasks: 41 executed`
- JUnit：`20` tests，`0` failures，`0` errors，`0` skipped
- 日志收据行：`PR73_RESULT command_exit=0 executed_task_lines=67 up_to_date_task_lines=9 junit_tests=20 junit_failures=0 junit_errors=0 junit_skipped=0`
- 说明：命令显式带 `--rerun-tasks`；Gradle 的无动作生命周期项仍报告 9 行 `UP-TO-DATE`，但 41 个 actionable tasks 均已执行。
- 原始日志 SHA256：`ec59dc72d8ad08e4d44e558561eafe8a88b0e4d751dcd83531e7b58f3336faa5`
- 运行 JSON SHA256：`ad423433fba4f60e30413c72d91941fe0a65d4ebb9b44e1a2d953ffa67d3ceee`

## 覆盖

- `ExternalSessionStatusUiTest`：online × activity(working/idle/unknown) × health(normal/unknown/abnormal) 完整矩阵；offline 全 None。
- 畸形 health DTO 归 `unknown`，合法 working activity 保留并映射 Working。
- DTO→L2→SessionItem→Compose `SessionRow` 同 ref 的 abnormal-health working 真实行，连续虚拟帧变化。
- working 连续帧变化、idle 静止；其他 provider/收藏/点击回归测试同组通过。
