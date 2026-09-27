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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    // ---- IME/dock 实时跟随 ----

    /** 先灌内容再建 presenter：首帧同步捕获，后续是否重捕获完全由视口路径决定（可断言为 0）。 */
    private fun seededFollowing(rows: Int = 5, lines: Int = 10): Harness {
        val emulator = TerminalEmulator(10, rows)
        emulator.feed((0 until lines).joinToString("\r\n") { "r$it" })
        val resizeCalls = mutableListOf<Pair<Int, Int>>()
        val presenter = TermViewPresenter(emulator) { r, c ->
            resizeCalls.add(r to c)
            emulator.resize(c, r)
        }
        presenter.seedCellMetrics(10, 20)
        return Harness(emulator, presenter, resizeCalls)
    }

    private fun awaitDrawWindow(h: Harness, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            h.presenter.beginFrame()
            if (predicate()) return
            Thread.sleep(5)
        }
        throw AssertionError("frame never converged: window=${h.presenter.drawWindow}")
    }

    @Test
    fun imeMotionFramesOnlyMoveDrawOffsetWithoutCaptureOrResize() {
        val h = seededFollowing()
        // 静止：5 行内核 + 10px 余量（110 / 20）。
        h.presenter.onViewportSizeChanged(100, 110)
        h.presenter.beginFrame()
        val restWindow = h.presenter.drawWindow
        assertEquals(5..9, restWindow)
        assertTrue(h.presenter.drawAnchorBottom)
        h.resizeCalls.clear()
        val frameRequests = AtomicInteger()
        h.presenter.onFrameRequested = { frameRequests.incrementAndGet() }

        // IME 弹起 + 输入框膨胀 + 收起：逐帧非整行高度。
        for (height in listOf(104, 97, 83, 61, 40, 22, 61, 97, 110)) {
            h.presenter.onViewportSizeChanged(100, height)
        }
        Thread.sleep(100)

        assertEquals("motion frames must not capture or request frames", 0, frameRequests.get())
        assertTrue("motion frames must not resize the PTY", h.resizeCalls.isEmpty())
        h.presenter.beginFrame()
        assertEquals("render window already spans every emulator row", restWindow, h.presenter.drawWindow)
        assertTrue(h.presenter.drawAnchorBottom)
        // 可见行语义不变：挤压到 61px 仍是末 3 行。
        h.presenter.onViewportSizeChanged(100, 61)
        assertEquals(7..9, h.presenter.window)
    }

    @Test
    fun heightOnlyOutgrowDuringMotionResizesOnceWhenSettled() {
        val h = seededFollowing(rows = 5, lines = 12)
        // 首帧恰逢键盘未收完：以 3 行建立几何。
        h.presenter.onViewportSizeChanged(100, 60)
        assertEquals(listOf(3 to 10), h.resizeCalls)

        // 键盘继续收起：逐帧 outgrow，动画中不得逐行 resize。
        for (height in listOf(75, 98, 121, 139, 150)) {
            h.presenter.onViewportSizeChanged(100, height)
        }
        assertEquals(listOf(3 to 10), h.resizeCalls)
        // 等待期间用历史行补满像素容量（7 行），底部不露空带。
        awaitDrawWindow(h) { h.presenter.drawWindow.count() == 7 }
        assertEquals(h.presenter.drawWindow.last, h.emulator.scrollback.size + h.emulator.rows - 1)

        h.presenter.onViewportSettled()
        assertEquals(listOf(3 to 10, 7 to 10), h.resizeCalls)
        h.presenter.onViewportSettled()
        assertEquals("settle is idempotent", listOf(3 to 10, 7 to 10), h.resizeCalls)
    }

    @Test
    fun squeezeSettleNeverResizes() {
        val h = seededFollowing()
        h.presenter.onViewportSizeChanged(100, 110)
        h.resizeCalls.clear()
        h.presenter.onViewportSizeChanged(100, 47)
        h.presenter.onViewportSettled()
        h.presenter.onViewportSizeChanged(100, 110)
        h.presenter.onViewportSettled()
        assertTrue(h.resizeCalls.isEmpty())
    }

    @Test
    fun viewportSqueezedOnlyWhileSeededViewportIsBelowEmulatorRows() {
        val h = seededFollowing()
        assertFalse("首订前没有已协商几何，谈不上挤压", h.presenter.viewportSqueezed)
        h.presenter.onViewportSizeChanged(100, 110)
        assertFalse(h.presenter.viewportSqueezed)
        h.presenter.onViewportSizeChanged(100, 61) // IME 弹起：只剩 3 行
        assertTrue("挤压排布不得落入 warm 几何缓存", h.presenter.viewportSqueezed)
        h.presenter.onViewportSizeChanged(100, 110)
        assertFalse(h.presenter.viewportSqueezed)
    }

    // ---- 滚动跟手：视口偏移同步、O(1)、不等内核锁 ----

    @Test
    fun scrollOffsetMovesSynchronouslyWhileParserHoldsEmulator() {
        val h = harness(rows = 3, cols = 5)
        h.emulator.feed((0 until 12).joinToString("\r\n") { "l$it" }) // scrollback 9 + 屏幕 3
        val monitorHeld = CountDownLatch(1)
        val releaseMonitor = CountDownLatch(1)
        val holder = Thread {
            synchronized(h.emulator) { // WS 线程正在 feed/重放大快照
                monitorHeld.countDown()
                releaseMonitor.await(2, TimeUnit.SECONDS)
            }
        }
        holder.start()
        assertTrue(monitorHeld.await(1, TimeUnit.SECONDS))
        try {
            val started = System.nanoTime()
            repeat(4) { h.presenter.onScrollBy(1) } // 逐帧拖动/甩动投送
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("拖动不得等内核锁：${elapsedMs}ms", elapsedMs < 250)
            assertEquals("偏移当帧生效，不等捕获", 5..7, h.presenter.window)
            h.presenter.onScrollBy(-4)
            assertTrue("拖回底部当帧恢复跟随", h.presenter.isFollowingBottom)
        } finally {
            releaseMonitor.countDown()
            holder.join(1_000)
        }
    }

    @Test
    fun lockedHistoryIsTopAnchoredWhileFollowingIsBottomAnchored() {
        val h = seededFollowing()
        h.presenter.onViewportSizeChanged(100, 110)
        h.presenter.onViewportSizeChanged(100, 61)
        h.presenter.beginFrame()
        assertTrue(h.presenter.drawAnchorBottom)

        h.presenter.onScrollBy(2)
        awaitDrawWindow(h) { !h.presenter.drawAnchorBottom }
        // 锁定窗口从冻结顶行开始，挤压只在 View 底部裁剪。
        assertEquals(5, h.presenter.drawWindow.first)

        h.presenter.onScrollToBottom()
        awaitDrawWindow(h) { h.presenter.drawAnchorBottom }
        assertEquals(5..9, h.presenter.drawWindow)
    }

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
    fun beginFrameDoesNotWaitForEmulatorAfterPreparedFrame() {
        val h = harness(rows = 2, cols = 5)
        val captured = CountDownLatch(1)
        h.presenter.onFrameRequested = { captured.countDown() }
        h.emulator.feed("ready")
        assertTrue(captured.await(1, TimeUnit.SECONDS))
        h.presenter.beginFrame()

        val monitorHeld = CountDownLatch(1)
        val releaseMonitor = CountDownLatch(1)
        val holder = Thread {
            synchronized(h.emulator) {
                monitorHeld.countDown()
                releaseMonitor.await(1, TimeUnit.SECONDS)
            }
        }
        holder.start()
        assertTrue(monitorHeld.await(1, TimeUnit.SECONDS))
        try {
            val started = System.nanoTime()
            h.presenter.beginFrame()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("beginFrame waited on emulator monitor: ${elapsedMs}ms", elapsedMs < 250)
        } finally {
            releaseMonitor.countDown()
            holder.join(1_000)
        }
    }

    @Test
    fun emulatorMutationDefersCaptureUntilMutationCompletes() {
        val h = harness(rows = 2, cols = 5)
        val frameRequested = CountDownLatch(1)
        h.presenter.onFrameRequested = { frameRequested.countDown() }
        val mutationEntered = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val worker = Thread {
            h.presenter.withEmulatorMutation {
                h.emulator.feed("busy")
                mutationEntered.countDown()
                releaseMutation.await(1, TimeUnit.SECONDS)
            }
        }
        worker.start()
        assertTrue(mutationEntered.await(1, TimeUnit.SECONDS))
        assertFalse(frameRequested.await(100, TimeUnit.MILLISECONDS))
        releaseMutation.countDown()
        worker.join(1_000)
        assertTrue(frameRequested.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun beginFrameFreezesScrollbackBoundaryAndRows() {
        val h = harness(rows = 2, cols = 5)
        h.emulator.feed("a\r\nb\r\nc\r\nd")
        h.presenter.onScrollBy(2)
        h.presenter.beginFrame()
        val frameWindow = h.presenter.drawWindow
        val before = frameWindow.map { text(h.presenter.lineCells(it)) }

        // A history page arriving while Canvas is iterating must not shift the
        // already captured rows or window to the newly prepended boundary.
        h.emulator.prependHistory("new-history\r\n")

        assertEquals(frameWindow, h.presenter.drawWindow)
        assertEquals(before, frameWindow.map { text(h.presenter.lineCells(it)) })
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

    @Test
    fun windowAnchorsToLatestActiveRowWhenScrollbackExists() {
        // 4 行高终端，先产生 scrollback（2 行），然后主屏幕只有 1 行文字（其余 3 行空白）
        val h = harness(rows = 4, cols = 10)
        h.emulator.feed("hist1\r\nhist2\r\nactive1")
        // 此时 scrollback=2 ("hist1", "hist2")，屏幕第0行="active1"，第1~3行空白。
        // 首帧锚定在最新有效行：窗口底部直接钉在 active1（逻辑行 2），窗口为 0..3
        assertEquals(0..3, h.presenter.window)
        assertEquals("hist1", text(h.presenter.lineCells(0)))
        assertEquals("hist2", text(h.presenter.lineCells(1)))
        assertEquals("active1", text(h.presenter.lineCells(2)))
    }
}
