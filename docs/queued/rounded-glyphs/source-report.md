# fix(termview): preserve rounded box-drawing glyph geometry

## 状态

这是待提交的 PR 说明，不是已经创建的 GitHub PR。本次会话没有推送分支、创建 PR、合并、发布或修改服务端。

## 问题与根因

用户截图里的输入框不只是“不够圆”：上方圆角变成直角，底部两个角向外伸，底边与角之间断开。

`BoxBlockGeometry.boxSpec` 把 Unicode 圆弧字符与普通直角字符放在同一分支，用填充矩形代替原始圆弧；其中两个底角方向写反：

| 码点 | 字符 | 应有连接 | 原实现 |
|---|---|---|---|
| U+256D | ╭ | 右、下，圆弧 | 右、下，直角 |
| U+256E | ╮ | 左、下，圆弧 | 左、下，直角 |
| U+256F | ╯ | 左、上，圆弧 | 右、上，直角 |
| U+2570 | ╰ | 右、上，圆弧 | 左、上，直角 |

Unicode 定义：https://www.unicode.org/charts/nameslist/n_2500.html 。

`GlyphFallbackPolicy.resolve` 已将这类字符送入几何路径，`TermSurfaceView.drawCentered` 再调用 `drawBoxBlock`。因此只换字体、全局开启抗锯齿或给 App 容器添加圆角，都不能修复这个错误映射。

## 修改

- 为 U+256D–U+2570 单独生成圆弧路径参数，恢复四个角的正确连接方向。
- 描边中心与现有 `─`、`│` 的整数像素带对齐；格边保留至少一像素直线段，避免曲线抗锯齿影响接缝。
- `TermSurfaceView` 使用可复用 `Path` 和独立抗锯齿描边 `Paint` 绘制圆角；极小字格保留正确方向，不把空圆弧静默跳过。
- 普通直线、直角与块元素继续使用原来的非抗锯齿整数矩形；不全局开启抗锯齿。
- 保留调用方已经解析的 ARGB 颜色。无字体、主题、网格大小、手势、滚动、终端解析或网络协议改动。
- 添加纯 JVM 几何检查、JUnit 包装及调用生产 Canvas 入口的 Robolectric native graphics 测试。

本补丁不把圆角路径虚计为矩形绘制次数；现有 `geomRectCount` 仍仅统计矩形。

## 仓库边界

主补丁目标是 `corral-app` 当前编译的本地渲染代码：

- `app/src/main/java/dev/agentmirror/app/termview/BoxBlockGeometry.kt`
- `app/src/main/java/dev/agentmirror/app/termview/TermSurfaceView.kt`

`corral-core` 的 `app/app/src/main/.../termview` 中已有一份同源渲染代码，本交付另附对应仓库的同步补丁。两个仓库的几何原文件 blob 相同；这不是在壳里新复制一套逻辑，也不混入一次仓库架构重构。

`core-terminal` Maven 产物是纯 JVM 解析/网格内核，不含这里的 Android Canvas 渲染器。仅更新它的版本不能修复 App 内这份本地代码。`corral-serve` 不在本补丁修改范围；这不等于完成了服务端所有路径的端到端排除。

## 已执行验证

1. 原始几何源码重建后，通过 Git blob SHA 校验：`aeb409f9e00ee8e69f0291c6663fe84b713083b7`，9902 字节。
2. 在该原版上编译、运行独立复现程序：两上角与直角完全相同，两下角左右连接反向，四项均复现。
3. 修复后的生产几何源码在 Kotlin/JVM 编译、运行五组检查，全部通过：Unicode 四角方向、1–64 × 1–64 字格的像素带端点对齐、切线/描边边界、小尺寸与平移、非圆角路由。
4. 153 个非圆角几何字符 × 25 种尺寸，共 3825 组结果，修复前后逐字节一致。输出 SHA-256 均为 `ea8da2e0bcb7bf3b893f221048a7b9a04f306702eeeff9085ce5a316c14c8ba5`。
5. 两个补丁的语法与源码上下文片段应用检查通过。几何文件使用完整、校验过的原文件；View 使用从各仓库读取的三个精确上下文片段。**不是完整仓库构建或完整 checkout 上的应用验证。**

环境：Kotlin 1.9.0 / OpenJDK 21。执行的是生产几何源码，不是用另一种语言重写后的模拟断言。项目 Gradle/JUnit 与 Android 测试运行情况见下节。

## 尚未执行 / 合并前门槛

- 完整仓库 Gradle 编译、JUnit runner、Robolectric native Canvas 三项测试、APK 构建、GitHub CI。
- Android 真机与同一主机同一合成字符夹具对照，多个字号/密度/浅深主题/反显颜色。
- 项目既定性能门和真机“秒开无空白”基线，不得把纯 JVM 绿判为性能验收通过。
- Native 测试断言已写入，但这里没有 Android SDK 和依赖运行环境，**不能声称 Canvas 像素测试通过**。

建议在 `corral-app` 根目录执行：

```sh
./gradlew :app:testDebugUnitTest \
  --tests 'dev.agentmirror.app.termview.RoundedCornerGeometryTest' \
  --tests 'dev.agentmirror.app.termview.RoundedCornerRenderingTest'
```

Core 同步补丁在 `corral-core/app` 目录执行相同命令。

`source-geometry-before-after.png` 是编译后的 Kotlin 几何参数经 SVG 绘制的合成夹具，不是 Android 或主机终端截图，也不证明 native Canvas 栅格器的最终像素表现。

## “与主机一模一样”的边界

本补丁修复已定位的四种圆角语义和接缝错误，不以“字体可能不同”替代修复。但没有同屏配对截图和主机渲染参数，不能宣称整屏像素级一致。

当前旧实现中，虚线与实线、部分粗细混合/单双线字符也存在合并映射；本次没有顺手重写它们。App 的字体与主题策略同样不是主机像素投送。四圆角问题修好，不代表所有 Unicode 终端图形和所有终端样式已经等价。

## 追溯

- Core 原几何引入提交：`6afcd049fe4d19a63e52e171e34207e8b23b67b9`，parent `c775fee057555b2f20c0cfa6dbbf577280aeaf2f`。GitHub commit→PR 查询未返回关联 PR。
- App 随旧 PR #1 迁入：https://github.com/Florious95/corral-app/pull/1 ，引入提交 `e4b58ab174bf9f0f8404baf6f5bb26e90337010c`，parent `6b68a52dc96256ec18808c3f68e9c9b2716c7095`。
- 以上 #1 是历史迁入 PR，不是本次新建 PR。不能整体回退迁入提交，否则会删除远超本问题范围的 App 壳代码。

## 读取基线

| 仓库 | main commit | View blob |
|---|---|---|
| corral-app | `277c3f383b93e2c555ec9cf01a8c16b6c63101fe` | `bf477ecd0631440e18b0b75d4d939681d1d54882` |
| corral-core | `2835984e2aa13f603e51ad675efc8717c9e1636c` | `87879210578d0fb8abb5ee3794605fcce67ae105` |

两个几何文件的 blob 均为 `aeb409f9e00ee8e69f0291c6663fe84b713083b7`。提交前必须重新检查基线，不能覆盖正在进行的其他修改。
