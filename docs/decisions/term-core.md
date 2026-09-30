# 裁定：终端模拟内核选型（term-core-android）

- 状态：已裁定（攻坚席裁定，leader 可复核调整）
- 日期：2026-08-09
- 结论：**自研最小 VT 引擎**（纯 Kotlin/JVM `:terminal` 模块），不改造现成核。

## 能力矩阵

| 维度 | 改造 ATE (jackpal) | 改造 ConnectBot 核 | 自研最小 VT 引擎 |
|---|---|---|---|
| 许可 | Apache-2.0 ✅ | **核不可用 ❌**（见下） | Apache-2.0 ✅（零外来代码） |
| 纯 JVM（零 Android 依赖，工程红线） | ❌ 核与 EmulatorView/android.* 纠缠，需大拆 | ❌ 同样与 Android 渲染纠缠 | ✅ 天然满足 |
| SGR 256 色 + 真彩 | 256 色有；真彩无（truecolor 是 Termux GPLv3 分支后加的，不可取） | vt320 时代序列集，真彩无 | ✅ 按需实现 |
| 宽字符/emoji 占 2 格 | 部分（UnicodeTranscript，2014 年 Unicode 表） | 弱 | ✅ 现代 wcwidth + emoji 规则 |
| 快照重放（capture-pane -e 清屏重建）/ 历史头部插入（006） | 无，需自加 | 无，需自加 | ✅ 一等接口设计 |
| 维护状态 | 已归档（archive，~2015 停更） | 核为 1996-2005 遗产代码 | 自有代码，随需求演进 |
| Kotlin 化 | 需 Java→Kotlin 重写 | 同 | ✅ 直接 Kotlin |

## 许可核验

- **ConnectBot**：应用外壳 Apache-2.0，但其终端仿真核 `de.mud.terminal.vt320` 源自
  JTA（Java Telnet Application），文件头为 GPLv2（JTA 官方对 de/mud/terminal 包另有
  LGPL-2.1 授权口径）。无论按哪种口径，均**不符合本工程 Apache-2.0 红线**（008）。
  任务书中"ConnectBot 终端核 Apache-2.0"的前提经核验不成立，此路线出局。
- **ATE (jackpal/Android-Terminal-Emulator)**：整仓 Apache-2.0，许可干净。但注意其著名
  下游 Termux 核为 GPLv3——只能参考 ATE 原仓，任何 Termux 系代码（含摹写）禁入。
- **自研**：零外来代码，Apache-2.0 纯净，无需版权声明搬运。

## 改造成本对比

- ATE 改造 = 从 Android 渲染层剥离核心（TerminalEmulator/UnicodeTranscript）→ 去
  android.* 依赖 → Java→Kotlin → 补真彩、现代 emoji 宽度、快照重放、头部插入 scrollback。
  剥离后剩下的可复用部分（转义状态机骨架）恰是自研中最小的一块；等于抬着 2014 年架构做一次准重写。
- 自研的序列集是**有界的**：上游数据只来自 tmux（capture-pane -e 快照 + pipe-pane 增量），
  tmux 输出的转义子集明确（SGR、光标移动、清行清屏、alt screen 等），无需实现完整 VT500 兼容矩阵。
  重排由 CLI 侧负责（005），内核不做 reflow，进一步收窄范围。
- 自研可按 006 的接口需求（快照重建、历史头部插入、脏区回调）做一等设计，而非在旧核上打补丁。

## 结论

自研最小 VT 引擎。范围：ANSI/CSI/SGR 解析（含 256 色+真彩）、字符网格（宽字符/emoji
占 2 格）、环形 scrollback（容量可配、支持头部插入历史）、快照重放接口、增量字节流接口、
alternate screen 进出（进入即标记"历史不可用"）。红测先行（esctest/vttest 经典用例形状），
JVM 单测覆盖解析/网格/滚动/重放。

参考来源：[ConnectBot vt320.java（GPL 头）](https://github.com/krajj7/BotHack/blob/master/jta26/de/mud/terminal/vt320.java)、
[Oracle 对 JTA de/mud/terminal 的 LGPL-2.1 口径](https://docs.oracle.com/cd/E56021_01/html/E24527/z40001041148863.html)、
[ATE LICENSE（Apache-2.0）](https://github.com/jackpal/Android-Terminal-Emulator/blob/master/LICENSE)、
[ATE 仓库（已归档）](https://github.com/jackpal/android-terminal-emulator)。
