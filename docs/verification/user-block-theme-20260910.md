# 验证：用户文本块底色随终端主题变化

## 范围与根因

`TermPalette.assembleSlot()` 为每个终端主题装配色板时，始终从外壳
`TerminalPaletteLight/Dark.userBlockBackground` 读取固定绿色；
`asTerminalPalette()` 又单独导出同一套固定背景和文字色。
因此换主题、重建缓存甚至重开会话都不能消除这个固定取值。
`48;5;254`、既有近白索引和高亮 RGB 背景最终汇入这个 `userBlockBg`。

同一文件在 corral-core 的 `app/app/src/main/` 与 corral-app 的 `app/src/main/`
均直接参与各自 Android app 的编译，原始 blob 都是
`b99e69afcedfed4cad36fac2b2747e0e3a2ab3cb`。core-terminal 发布产物不包含此主题层。
两个仓库使用同一修复；不需要改 corral-serve 或升级协议。

## 修复规则

1. 使用所选终端主题的 selection 作为候选背景，透明色先合成到该主题的背景上。
2. 候选必须与纸色不同，且默认文字对比度至少为 `min(4.5, 原主题前景/背景对比度)`。
3. 缺少 selection、候选等于纸色或对比不足时，从主题背景向前景混入最多 12%，
   以 1% 步长减少到满足可读性；最终允许退回主题背景，不退回固定绿。
4. Compose 导出读取同一个 Scheme 的用户块底色和默认文字色，不另拼外壳颜色。
   目录异常时的 Light/Dark 兜底也经过同一规则。

规则只依赖实际主题原色，不依赖该主题被放在“浅槽”还是“深槽”。
合法的绿色主题仍可以有绿色背景；修的是“所有主题都被固定为绿色”，不是删除绿色。
用户块对比度保护针对默认文字，不声称所有 ANSI 文本都达到 WCAG AA。
对于原本低对比的主题，保留原文字而不继续降低对比，因此个别用户块可以与纸色相同。

不修改 ANSI 16 色、SGR 识别条件、RGB 投影算法、反显逻辑、终端网格/历史、输入发送、
WebSocket/tmux、字号/字体/几何。没有新增计时器或逐格混色；新计算仅发生于 Scheme 装配。
这不等于已完成设备性能基线验证。

## 已执行（隔离 JVM，不是 Android 验收）

环境：Kotlin/JVM 1.9.0、OpenJDK 21。直接编译原版和修改后的 `TermPalette.kt`；
原版源码的 `git hash-object` 与上述远端 blob 相同。
Compose sRGB Color、日志、TerminalColor 数据类型和测试注解由最小适配器替代，
反射驱动调用提交的测试方法。使用从真实目录读取的 5 个主题族、8 份原始色板：
Alabaster、Afterglow、Vesper、Dracula、Solarized Light/Dark、Catppuccin Latte/Mocha。
**这不是完整目录运行，也不是 JUnit/Robolectric、Android Canvas 或 APK 构建结果。**

| 项目 | 结果 |
|---|---|
| 原版，12 个兼容旧 API 的测试方法 | 3 通过，9 失败，复现固定底色、导出文字色和同源跨模式不一致 |
| 修复版，同样 12 项 + 7 项背景策略测试 | 19 通过，0 失败 |
| 策略边界矩阵 | 13 种背景 × 13 种前景 × 8 种 selection = 1,352 组合通过不透明性/对比度断言 |
| 原/新版映射对比 | 11,980 个输入；580 个既有用户块角色结果变化，其余 11,400 个按位一致；0 个意外变化 |

映射对比遍历 5 族的两个槽，全部 256 索引的前景/背景，以及
RGB 各通道 `{0,32,64,128,220,228,255}` 的笛卡尔积前景/背景。
索引、真彩缓存分别在首次、重复读取、非默认 againstBg、主题 A→B→A 中覆盖。

### 实际取色例子（ARGB，不是真机截图）

| 主题 | 原浅槽 / 原深槽 | 新浅槽 / 新深槽 |
|---|---|---|
| Dracula | `FFE6F5F2` / `FF10241F` | `FF44475A` / `FF44475A` |
| Vesper | `FFE6F5F2` / `FF10241F` | `FF2D2D2D` / `FF2D2D2D` |
| Solarized | `FFE6F5F2` / `FF10241F` | `FFFDF6E3` / `FF052F3A` |
| Catppuccin | `FFE6F5F2` / `FF10241F` | `FFDBDEE4` / `FF333446` |

## 合并前必须补齐，当前未执行

提交的三个测试类共有 21 个测试方法。前两个类的 19 项已在上述隔离环境执行；
`UserBlockThemeIntegrationTest` 的 2 项使用真正 SharedPreferences 与 TerminalEmulator，
仅已编写，尚未执行。Gradle 下前两个类会遍历实际随包全量目录，不使用本地八色板替身。

在已配好项目要求的 Android SDK、Gradle、依赖产物与 debug keystore 的机器上：

```bash
# corral-app：仓库根目录；corral-core：先 cd app
bash gradlew :app:testDebugUnitTest :app:testReleaseUnitTest \
  --tests 'dev.agentmirror.app.ui.theme.UserBlock*' \
  --no-daemon
```

当前环境没有 Android SDK/Gradle，网络 DNS 不可用，未运行上述命令，未编译 APK，
未跑完整 JUnit/Robolectric、Canvas 像素/截图测试或真机性能门；不得标为通过。

设备验收：用合成终端输入创建 `48;5;254` 和 `48;2;228;228;228` 的用户块，
旁边放 `42m` 的正常绿色文本背景作为对照。分别在深浅外观中切换 Dracula、Vesper、
Solarized、Catppuccin；返回同一会话、查看已有历史、重开 App 后仍应使用保存的主题。
同一份 Dracula/Vesper 放在两个槽时用户块颜色须相同；正常绿色 ANSI 背景仍保留。
确认文字可读、没有重连/清空历史、首帧与滚动性能不低于项目既有基线。

仅修改分支并提 PR，不合并或发布。回滚方式是 revert 本修复提交；无需数据迁移或服务端回滚。
