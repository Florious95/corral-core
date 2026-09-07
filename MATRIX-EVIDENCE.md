# T-A 四轴矩阵证据

测试：`ExternalSessionStatusUiTest.fourAxisProjectionUsesOnlyOnlineAndActivity`。

| online | activity | normal | unknown | abnormal |
|---|---|---|---|---|
| true | working | Working | Working | Working |
| true | idle | Idle | Idle | Idle |
| true | unknown | None | None | None |
| false | working | None | None | None |
| false | idle | None | None | None |
| false | unknown | None | None | None |

每格同时断言 health 原值仍留在 `SessionItem.health`；motion 调用不再传入 health。红测在精确 C73 的 abnormal working 格命中旧 veto，绿测同一完整矩阵通过。

附加 DTO 证据：

- `Session(activity="working", status="working", health="broken")` → `toL2Entry()` → `toSessionItem(false)`：`status=Busy`、`health="unknown"`、online 时 `Working`。
- `health="abnormal"` 的同 ref DTO 经过真实 Compose `SessionRow`：working content description 存在且虚拟时钟前后帧不同。
- activity/status 冲突与非法 activity 的 fail-closed 解析逻辑未改；本次只移除 health 对已解析 activity 的否决。
