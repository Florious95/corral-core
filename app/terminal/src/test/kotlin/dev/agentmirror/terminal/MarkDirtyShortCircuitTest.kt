/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.agentmirror.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TerminalGrid.write 无条件 markDirty 短路红测（leader msg_2099cd8b6fa0）。
 *
 * 现状缺陷：`TerminalGrid.write`（TerminalGrid.kt:44-75）每次写入码点**无条件** markDirty
 * （第 58/67 行），没有「新内容与旧内容相同则跳过」的短路。同内容重写同一格会白白触发该行
 * 标脏 → 即使脏行级渲染也会多画没变化的行（冗余重绘）。
 *
 * 判据：
 *  - 红测：用**相同内容**重写同一格，不得产生脏区（当前代码无条件标脏 → 红）；
 *  - 守卫测试：**同字符不同属性**（前景色/背景色/粗体/下划线/反显任一变）必须标脏——
 *    只比字符会漏掉「同字不同色」的真实变化（那种情况必须重绘，跳过即丢显示）。
 *
 * 属性比较依赖 Cell/TextStyle 的 data class 相等（text+style+width 全比，style 含全部属性位）。
 */
class MarkDirtyShortCircuitTest {

    /** 夹具：排掉构造时的初始整屏脏区后，返回后续伤害区间收集器。 */
    private fun damageCollector(emulator: TerminalEmulator): MutableList<IntRange> {
        emulator.feed("${E}[H") // 排掉初始整屏脏区（构造后初始 dirty = 全屏）
        val ranges = mutableListOf<IntRange>()
        emulator.damageListener = DamageListener { ranges.add(it) }
        return ranges
    }

    /** 写一个字符到指定行/列（经转义序列定位，模拟 CLI 重写同一位置）。 */
    private fun writeAt(emulator: TerminalEmulator, row: Int, col: Int, ch: String) {
        emulator.feed("${E}[${row + 1};${col + 1}H$ch")
    }

    /** 读一个单元格（供断言内容/样式是否真的变了）。 */
    private fun cellAt(emulator: TerminalEmulator, row: Int, col: Int): Cell =
        emulator.snapshot().lines[row][col]

    /** 红测本体：同内容重写同一格，不得产生脏区。当前代码无条件标脏 → 红。 */
    @Test
    fun rewritingSameCell_doesNotMarkDirty() {
        val emulator = TerminalEmulator(10, 3)
        val ranges = damageCollector(emulator)

        // 先写一次 'x' 到 (0,0)，排空这次伤害。
        writeAt(emulator, 0, 0, "x")
        emulator.feed("${E}[1;1H") // 排掉 (0,0) 写入的伤害
        ranges.clear()

        // 同内容重写 'x' 到 (0,0)：内容与样式都相同 → 不应标脏。
        writeAt(emulator, 0, 0, "x")

        // 报告数字：期望脏行数 0，实际（当前代码）N。
        val dirtyRowCount = ranges.sumOf { it.last - it.first + 1 }
        println("[MARKDIRTY] rewrite-same-cell dirty rows = $dirtyRowCount (expected 0)")
        assertTrue("同内容重写同一格不得标脏：脏行数=$dirtyRowCount（期望 0）", ranges.isEmpty())
    }

    /** 守卫测试 1：同字符不同**前景色**必须标脏（跳过即丢显示）。 */
    @Test
    fun sameCharDifferentFg_mustMarkDirty() {
        val emulator = TerminalEmulator(10, 3)
        val ranges = damageCollector(emulator)

        writeAt(emulator, 0, 0, "x") // 默认色
        emulator.feed("${E}[1;1H")
        ranges.clear()

        // 同字符 'x' 但 SGR 31 红前景：属性变了 → 必须标脏。
        emulator.feed("${E}[31m${E}[1;1Hx")

        assertTrue(
            "同字符不同前景色必须标脏（跳过即丢颜色显示）：实得 $ranges",
            ranges.isNotEmpty(),
        )
        // 内容确认：字符仍是 x，但样式 fg 已变（保证夹具真的改了属性）。
        val cell = cellAt(emulator, 0, 0)
        assertEquals("x", cell.text)
        assertEquals(TerminalColor.Indexed(1), cell.style.fg)
    }

    /** 守卫测试 2：同字符不同**背景色**必须标脏（BCE 背景变化）。 */
    @Test
    fun sameCharDifferentBg_mustMarkDirty() {
        val emulator = TerminalEmulator(10, 3)
        val ranges = damageCollector(emulator)

        writeAt(emulator, 0, 0, "x") // 默认 bg
        emulator.feed("${E}[1;1H")
        ranges.clear()

        // 同字符 'x' 但 SGR 41 红背景：属性变了 → 必须标脏。
        emulator.feed("${E}[41m${E}[1;1Hx")

        assertTrue(
            "同字符不同背景色必须标脏（BCE 背景变化必须重绘）：实得 $ranges",
            ranges.isNotEmpty(),
        )
        val cell = cellAt(emulator, 0, 0)
        assertEquals(TerminalColor.Indexed(1), cell.style.bg)
    }

    /** 守卫测试 3：同字符不同**粗体**必须标脏。 */
    @Test
    fun sameCharDifferentBold_mustMarkDirty() {
        val emulator = TerminalEmulator(10, 3)
        val ranges = damageCollector(emulator)

        writeAt(emulator, 0, 0, "x")
        emulator.feed("${E}[1;1H")
        ranges.clear()

        emulator.feed("${E}[1m${E}[1;1Hx") // 粗体
        assertTrue("同字符不同粗体必须标脏：实得 $ranges", ranges.isNotEmpty())
        assertTrue("粗体应生效", cellAt(emulator, 0, 0).style.bold)
    }

    /** 守卫测试 4：同字符不同**反显**必须标脏。 */
    @Test
    fun sameCharDifferentReverse_mustMarkDirty() {
        val emulator = TerminalEmulator(10, 3)
        val ranges = damageCollector(emulator)

        writeAt(emulator, 0, 0, "x")
        emulator.feed("${E}[1;1H")
        ranges.clear()

        emulator.feed("${E}[7m${E}[1;1Hx") // 反显
        assertTrue("同字符不同反显必须标脏：实得 $ranges", ranges.isNotEmpty())
    }

    /** 对照：不同字符写同一格，必须标脏（确认短路不误伤真实变化）。 */
    @Test
    fun differentChar_sameStyle_mustMarkDirty() {
        val emulator = TerminalEmulator(10, 3)
        val ranges = damageCollector(emulator)

        writeAt(emulator, 0, 0, "x")
        emulator.feed("${E}[1;1H")
        ranges.clear()

        writeAt(emulator, 0, 0, "y") // 字符变了 → 必须标脏
        assertTrue("不同字符必须标脏：实得 $ranges", ranges.isNotEmpty())
    }

    private companion object {
        const val E = ""
    }
}
