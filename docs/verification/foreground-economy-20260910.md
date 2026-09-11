# 前台终端减负：不降帧率、不削减显示效果

## 范围与事实

用户反馈是手机 App 在前台较热。本修改减少已定位的重复 CPU/分配/文件访问，不把源码热点等同于已证明的整机发热根因。

审查与开发基线：corral-app `eeea1ed8e4984c8e55f6ae5431953b60fda24c67`；corral-core `055dfc6fd61318198157597255949cba49032247`。两者的 Android 工程分别直接编译 `app/src/main` 和 `app/app/src/main`，所以用两个配套 PR 保持同步。core 的鼠标输入与 key-echo 埋点差异完整保留。

相关：corral-app #8、#9。不是后台暂停 #7、整屏行快照/保留绘制 core #93 或服务端批量消息 #28 的完整实现，不自动关闭这些任务。

## 改动

### 1. 绘制不再读取诊断控制文件

删除 `TermSurfaceView.doFrame/onDraw/onTouchEvent` 的 `refreshBurst` 调用和 View 内文件读写。`TermDrawControlWatch` 在有可见终端时共享一个目录 FileObserver，监听两个精确文件名的 CLOSE_WRITE/MOVED_TO；首次接入异步读一次已有设置，此后只响应事件，不启动定时轮询。

`TermDrawControlSession` 将重复事件合并为最多一个读/主线程投递加一个 pending 位。最后一个 View 退出时关闭会话，迟到结果不能应用于后来的 View。工作线程不持有 Activity，空闲可退出。每次读取最多 65 字节，超过 64 的命令拒绝；burst 先移动到临时文件领取，避免旧读者删除新替换命令。保留既有 opt/burst 文件接口及 Meter 采样语义，不新增导出组件/权限/网络控制接口。

这是保留现有调试功能的事件化实现，未按早期 issue 建议移除 release 的诊断入口，也未修改 Meter 15 秒期限/取消策略。尚需设备确认真实 FileObserver 文件创建/原子替换/转场行为。

### 2. 相同 presenter / 字号赋值幂等

setter 使用引用/值相等守卫，防止无关 AndroidView 更新重测字体、清宽度缓存、请求整帧。新 presenter 仍完整绑定、度量和播种；所有权检查避免旧 View 解绑时清除新 View 的回调。没有删除 update 的新会话、主题或输入回调传递。

几何 store 由 View 复用，保存前比较共享偏好中的完整值，不使用 View 私有 lastSaved；A→B→A 可恢复正确几何。保持原 apply、字号算法和尺寸上报规则。

### 3. 框线几何缓存，而非像素缓存

`BoxBlockGeometryCache` 为每个 View 在当前字格大小下缓存最多 160 项局部几何，包含矩形和圆角参数。实际支持 157 个码点；字格改变/退出时清缓存。不缓存颜色、会话、历史或 GPU 图像。

原 `BoxBlockGeometry` 的几何算法未改；绘制仍逐帧按当前颜色、原坐标、原顺序调用相同 Canvas 原语。圆角 Path、阴影 alpha、抗锯齿保持。未设置新的帧率上限、消息延迟、丢帧/丢 ANSI 策略，也没有改网络、输入回显、工作态动画、字体 fallback、主题或滚动语义。

## 已执行验证（不是 Android 全套测试）

执行环境：Kotlin 1.9.0、OpenJDK 21；没有 Android SDK/Gradle，容器 DNS 不可用。未编译 APK、未调用生产服务或读取真实会话。

1. `BoxBlockGeometryCacheTest` 4 项与 `TermDrawControlSessionTest` 8 项：**12 个实际测试方法已编写**。使用注解兼容声明和反射运行真实 check() 断言，不是 JUnit/Robolectric runtime。新增 teardown 屏障覆盖 burst 已领取但尚未交付时的异步退休；本次修复后需由可用 kotlinc/独立测试席执行脚本确认。
2. 纯几何 50,240 组合：所有支持码点、多种小/正常字格、正负坐标，缓存平移后与原实现完全相同。157,000 次稳态命中不再建立几何计划。
3. 隔离录制 Canvas：编译两仓库实际原版/候选 View 源码，仅为 Android/其他协作者提供最小适配器，比较 **52,752 组绘制命令、坐标、颜色、画笔参数，全部一致**。这不是 Android 像素/GPU/真实显示验证。重复绑定及逐帧诊断文件消费的原版负对照均命中，候选通过。
4. 独立 JVM 几何构造微基准，预热后 ABBA，各 314,000 次，同 checksum `8728524470000`：

| 路径 | 线程分配字节 | 耗时 ns |
|---|---:|---:|
| 原几何构造 | 123472160 | 33185541 |
| 缓存命中 | 0 | 2371481 |
| 缓存命中 | 0 | 2353083 |
| 原几何构造 | 123472000 | 30934312 |

只测几何构造/查找，不包含网络、ANSI、网格快照、Canvas/GPU或显示屏。不能据此声称整机零分配、十几倍加速或某个温降百分比。原耗时存在环境波动，不设固定毫秒 CI 门槛。

## 可执行验证

仓库根目录，JDK 和 kotlinc 可用：

```bash
bash tools/verify-foreground-economy.sh
bash tools/verify-foreground-economy.sh --bench
```

完整 Android 环境，corral-app 在根目录；core 先 `cd app`：

```bash
bash gradlew :app:testDebugUnitTest :app:testReleaseUnitTest \
  --tests 'dev.agentmirror.app.termview.BoxBlockGeometryCacheTest' \
  --tests 'dev.agentmirror.app.termview.TermDrawControlSessionTest' \
  --tests 'dev.agentmirror.app.termview.TermForegroundEconomyTest' \
  --tests 'dev.agentmirror.app.termview.TermSurfaceSessionBindingRegressionTest' \
  --no-daemon
```

**`TermForegroundEconomyTest` 的 7 项 Robolectric/native-graphics 测试仅已编写，当前未执行。** 覆盖重复绑定/字号变化、新 presenter 播种、回调所有权、实际 doFrame/draw 不读文件、共享偏好 A→B→A、缓存开关的像素一致性。新增 teardown 屏障测试仍是纯 JVM 文件读取/队列测试，不能替代真实 Android FileObserver 转场验证。不能把隔离适配器结果当作这些 Android 测试已绿。保留并执行仓库已有 rounded-corner、CJK、fallback、滚动、重连与几何测试，不能通过降低断言或跳过失败来过关。

## 合并前设备门禁

- 同设备、亮度/刷新率、网络、初始温度、电量、非充电条件，固定合成输出分别对照原版/候选；包含静态页、单行进度、满屏框线与大量中文输出。
- 比较 CPU 时间、分配/GC、长帧率、输入回显 P50/P95、实际终端刷新频率、功耗/温度曲线；暖机与重复顺序对调，不能把初始温度差当作优化。
- 逐项确认圆角、CJK/组合字、阴影块、主题切换、字号、IME、历史滚动、会话切换、回前台均无可见退化。
- 原有诊断操作：文件先于 attach、可见时创建/覆盖/原子替换、两个 View 短暂重叠、退出后迟到 I/O；没有命令时不得出现周期文件任务。

本 PR 不包含发布/安装/数据迁移。回滚只需 revert 对应提交；服务端和核心库发布版本不变。真实性边界：已证明少做这些重复工作，尚未证明用户手机上温度下降多少或剩余最大热点在哪里。
