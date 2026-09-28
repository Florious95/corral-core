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

package dev.agentmirror.app.termview

import dev.agentmirror.terminal.Cell
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 测试用 ESC 字符常量（裸字面量易碎，统一显式转义，见 term-core 沉淀）。 */
private const val E = "\\u001b"

/**
 * TermViewPresenter 测试：视口状态机（跟随/锁定/触底）、视口→行列数换算、脏区合并。
 *
 * 渲染核心可测性（term-view 知识基底 §1）：渲染逻辑与 Android View 分离，
 * 单测全部打在纯 JVM 的 Presenter 上；TermSurfaceView 只做画格与手势。
 */
class TermViewPresenterTest {

    /** 测试夹具：内核 + Presenter + 捕获到的 resize 请求序列。 */
    private class Harness(
        val emulator: TerminalEmulator,
        val presenter: TermViewPresenter,
        val resizeCalls: MutableList<Pair<Int, Int>>,
    )

    private fun harness(rows: Int = 5, cols: Int = 10): Harness {
        val emulator = TerminalEmulator(cols, rows)
        val resizeCalls = mutableListOf<Pair<Int, Int>>()
        val presenter = TermViewPresenter(emulator) { r, c -> resizeCalls.add(r to c) }
        // 防静默失效守卫要求先 seed（feat-font-size-setting-drop-pinch）：喂入与旧
        // DEFAULT_CELL_WIDTH/HEIGHT 相同的值（10x20），保持本文件既有断言数值不变。
        presenter.seedCellMetrics(10, 20)
        return Harness(emulator, presenter, resizeCalls)
    }

    /** 取逻辑行可见文本（Presenter 渲染数据源，去尾部空白）。 */
    private fun text(cells: List<Cell>): String = cells.joinToString("") { it.text }.trimEnd()

    // ---- 视口状态机 ----

    @Test
    fun followsBottomInitiallyWithWindowOnScreen() {
        val h = harness(rows = 3, cols = 5)
        // 无历史：跟随底部，窗口 = 全屏屏幕行。
        assertTrue(h.presenter.isFollowingBottom)
        assertFalse(h.presenter.showBackToBottom)
        assertEquals(0..2, h.presenter.window)
    }

    @Test
    fun scrollUpLocksViewportAndShowsBackToBottom() {
        val h = harness(rows = 2, cols = 5)
        // 制造 scrollback=[a,b]，屏幕=[c,d]，逻辑行总数=4。
        h.emulator.feed("a\r\nb\r\nc\r\nd")
        h.presenter.onScrollBy(2)
        // 锁定历史：回到底部按钮出现，窗口落在 scrollback 上。
        assertFalse(h.presenter.isFollowingBottom)
        assertTrue(h.presenter.showBackToBottom)
        assertEquals(0..1, h.presenter.window)
        assertEquals("a", text(h.presenter.lineCells(0)))
        assertEquals("b", text(h.presenter.lineCells(1)))
    }

    @Test
    fun scrollToBottomRestoresFollowing() {
        val h = harness(rows = 2, cols = 5)
        h.emulator.feed("a\r\nb\r\nc\r\nd")
        h.presenter.onScrollBy(2)
        assertTrue(h.presenter.showBackToBottom)
        h.presenter.onScrollToBottom()
        // 触底：恢复跟随，回到底部按钮消失，窗口回到屏幕。
        assertTrue(h.presenter.isFollowingBottom)
        assertFalse(h.presenter.showBackToBottom)
        assertEquals(2..3, h.presenter.window)
    }

    @Test
    fun newOutputWhileFollowingKeepsViewportAtBottom() {
        val h = harness(rows = 2, cols = 5)
        h.emulator.feed("a\r\nb\r\nc\r\nd")
        h.presenter.takeDamage() // 清掉 feed 产生的损伤
        // 跟随态：新输出到达，窗口底部始终是最新逻辑行。
        // "e" 接在 "d" 后写（无换行），\r\n 触发滚动 → 屏幕 row0="de"、row1="f"。
        h.emulator.feed("e\r\nf")
        assertTrue(h.presenter.isFollowingBottom)
        // 5 行 = scrollback 3 + 屏幕 2，窗口 = 末两行（屏幕）。
        assertEquals(3..4, h.presenter.window)
        assertEquals("de", text(h.presenter.lineCells(3)))
        assertEquals("f", text(h.presenter.lineCells(4)))
    }

    @Test
    fun newOutputWhileLockedDoesNotMoveWindow() {
        val h = harness(rows = 3, cols = 5)
        // scrollback=[a,b,c,d]，屏幕=[e,f,g]，总行数=7。
        h.emulator.feed("a\r\nb\r\nc\r\nd\r\ne\r\nf\r\ng")
        h.presenter.onScrollBy(4) // 滚到历史顶部，窗口 = [0,3) = a,b,c。
        assertEquals(0..2, h.presenter.window)
        assertEquals("a", text(h.presenter.lineCells(0)))
        h.presenter.takeDamage()

        // 006 锁定语义：锁定态新输出到达，窗口绝对位置不动（scrollback 增长由 offset 平移吸收）。
        h.emulator.feed("h\r\ni")
        assertEquals(0..2, h.presenter.window)
        assertEquals("a", text(h.presenter.lineCells(0)))
        assertEquals("b", text(h.presenter.lineCells(1)))
        assertEquals("c", text(h.presenter.lineCells(2)))
        assertFalse(h.presenter.isFollowingBottom)
    }

    // ---- 脏区合并（60fps 工作量 = 脏行数而非全屏）----

    @Test
    fun damageFollowsDirtyRowsNotFullScreen() {
        val h = harness(rows = 5, cols = 10)
        h.presenter.takeDamage()
        // 先写第 0 行满（清掉初始全屏脏区），再只更新该行。
        h.emulator.feed("hello")
        h.presenter.takeDamage()
        // 只写第 0 行：待重绘仅为该行，而非全屏 0..4。
        h.emulator.feed("!")
        assertEquals(listOf(0..0), h.presenter.takeDamage())
    }

    @Test
    fun adjacentDamageRangesMergeAcrossFrames() {
        val h = harness(rows = 5, cols = 10)
        // 首次 feed 承载内核构造后残留的整屏脏区（契约：首帧必全绘）。
        h.emulator.feed("a")
        assertEquals(listOf(0..4), h.presenter.takeDamage())
        // 第二帧用 setCursor 定位写第 1 行（避开自动换行的滚动区标脏），与首帧写过的第 0 行相邻。
        h.emulator.feed("${E}[2;1H")
        h.emulator.feed("b")
        // 相邻损伤合并成最小区间集 [0,1]。
        assertEquals(listOf(0..1), h.presenter.takeDamage())
    }

    @Test
    fun damageOutsideViewportWhileLockedIsIgnored() {
        val h = harness(rows = 3, cols = 5)
        // scrollback=[a,b,c,d]，屏幕=[e,f,g]。
        h.emulator.feed("a\r\nb\r\nc\r\nd\r\ne\r\nf\r\ng")
        h.presenter.onScrollBy(4) // 锁定，窗口 = 历史区 [0,3)。
        h.presenter.takeDamage()

        // 屏幕全变：但窗口落在 scrollback 上，不可见损伤不进待重绘。
        h.emulator.feed("x\r\ny")
        assertTrue(h.presenter.takeDamage().isEmpty())
        // 锁定语义仍保证窗口内容不动。
        assertEquals("a", text(h.presenter.lineCells(0)))
    }
}
