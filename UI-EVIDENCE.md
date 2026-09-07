# T-A Compose/UI 证据

CI hosted runner run [34090682711](https://github.com/Florious95/corral-core/actions/runs/34090682711) 执行 `ExternalSessionStatusUiTest` 的真实 Compose `SessionRow`：

1. 构造 `Session` DTO，`ref=dto-abnormal-health`、activity/status=`working`、health=`abnormal`。
2. 经 `toL2Entry()`、`toSessionItem(starred=false)`，assert ref/status/health 均正确。
3. `AppTheme { SessionRow(item, "l2", ...) }` 挂到真实 Compose 测试规则。
4. 读取 `l2-motion-dto-abnormal-health` content description：起始为 `working:`；推进 950ms + 一帧后仍为 `working:` 且帧描述改变。

同组还证明 working 灯随虚拟时钟变化、idle 保持 `idle:static`、unknown 无 motion。该证据是 JVM Robolectric/Compose 测试，不是最终 APK、AVD 或真机验收；最终组合 APK 与真实 Grok 循环留给独立验收席。
