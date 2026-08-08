# 知识基底 · term-core-android（系统编译产物）——契约级攻坚任务

## 0. 任务（taskbook.yaml#term-core-android，contention: contract，Fable 5 攻坚席）
- 目标：终端模拟内核——客户端最大契约。两步：
  1. **选型裁定**：对比 改造 Android-Terminal-Emulator(Jack Palevich, Apache-2.0) / 改造 ConnectBot 终端核(Apache-2.0) / 自研最小 VT 引擎，落 `docs/decisions/term-core.md`（一页纸：能力矩阵、许可核验、改造成本、结论）。落盘后 send 一句结论给 leader 即可续行施工，若 leader 裁定调整则按裁定返工。
  2. **施工**：`app/terminal/` 独立 Gradle 模块（`:terminal`，纯 Kotlin/JVM，**不依赖 Android 运行时**）：ANSI/CSI/SGR 解析器 + 字符网格（含宽字符/emoji 宽度）+ 本地 scrollback buffer（环形，容量可配）+ 快照重放接口（吃 capture-pane -e 输出整屏重建）+ 增量字节流接口。JVM 单测覆盖解析/网格/滚动/重放。
- 验收（exit 0 = 过）：`bash -lc 'cd /Volumes/nvme/Projects/远程Agent安卓/app && ./gradlew -q :terminal:test'`
- 写范围：`app/terminal/`、`app/settings.gradle.kts`（加 include）、`docs/decisions/term-core.md`。红线：许可必须 Apache-2.0 兼容（Termux 核 GPLv3 **禁用**，连"参考实现抄写"都不行）；模块零 Android 依赖。

## 1. 架构基（内核的消费方约束，决定接口形状）
- 上游数据两种：①订阅首帧 = capture-pane -e 的整屏快照（含 SGR 转义）→ 内核需支持"清屏重建"；②pipe-pane 增量字节流 → 常规解析推进。重连即重放快照（004 无状态）。
- scrollback 由客户端本地持有（006）：服务端按行区间补页（capture-pane -S），内核需支持"向头部插入历史行"。
- 渲染层（term-view 任务）消费内核的网格快照 + 脏区回调；内核不做绘制。
- resize：内核网格重建由服务端 resize 后的整屏快照驱动（005：重排是 CLI 的事，内核只换网格尺寸+重放）。
- 必须正确处理：SGR 全 256 色+真彩、光标移动/清行清屏、宽字符（CJK/emoji 占 2 格）、alternate screen 进出（进入时标记"历史不可用"，见 006 边界）。

## 2. 现场基
- `:app` 模块已就位（Kotlin 2.2.0 / AGP 8.13.0 / wrapper 8.14.3；compileSdk 36 坑见 .team/nodes/app-scaffold/CLAUDE.md 沉淀区，值得先读）。`:terminal` 用 `org.jetbrains.kotlin.jvm` 插件即可，避开 AGP。
- 构建命令一律 `bash -lc`（JAVA_HOME 在 profile）。

## 3. 需求基（指针，按序读）
1. requirement-base/entries/006-秒开与本地滚动.md（内核存在的理由与边界）
2. requirement-base/entries/011-技术路线裁定.md（终端内核行：为什么原生+Apache2）
3. requirement-base/entries/008-生产级定位与开源许可.md（许可红线背景）
4. requirement-base/entries/005-自适应-让CLI自己重画.md（resize 语义）

## 4. 经验基
- 你是攻坚席：**禁止做杂活**——只做选型裁定与 :terminal 模块本身；发现相邻问题（如 :app 配置瑕疵）报 leader 不动手。
- 红测先行：每类转义序列先写红测（esctest/vttest 的经典用例形状可参考）；宽字符与 SGR 边界是历史缺陷高发区。
- 测试净化前缀照旧；注释红线照旧（KDoc 首句一句话职责）。

## 5. 沉淀区（唯一允许你追加写入的区域）

### term-core-android 任务沉淀（2026-08-09）
- **选型关键事实**：任务书"ConnectBot 终端核 Apache-2.0"前提不成立——其核 `de.mud.terminal.vt320` 为 JTA 血统（GPLv2/LGPL-2.1），已核验并写入 docs/decisions/term-core.md；裁定自研，leader 已采纳并登修订记录。
- **:terminal 插件声明坑**：`plugins { id("org.jetbrains.kotlin.jvm") }` **不能写版本号**——KGP 2.2.0 已在根 classpath（根脚本 kotlin.android apply false），子模块再写版本会报"already on classpath"。`jvmToolchain(17)` 经 JAVA_HOME 自动探测即可。
- **接口形状**：`TerminalEmulator.feed`（增量）/`replaySnapshot`（清屏重建，scrollback 保留）/`prependHistory`（头插历史，alt 屏期间忽略）/`snapshot`+`damageListener`（脏行区间，构造后初始脏区=整屏，渲染层首帧必全绘）。擦除走 BCE（空白格带当前背景色）。
- **测试坑**：Kotlin 字符串里裸 ESC/BEL 字节能编译但不可见易碎，一律 `\u001b`/`\u0007` 显式转义；测组合字符要确认源文件里是分解形式（e+U+0301）而非预组合 é。
- 61 个 JVM 单测全绿；验收命令 exit 0（2026-08-09）。
