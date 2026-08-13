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

import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 写回约束测试（fix-cols-grid-convergence 修法 1 的护栏 + 权衡①裁定）。
 *
 * 待开发席实现 `setMeasuredCellWidth` 后启用。**本文件按当前真实 API 编译**——
 * 通过反射探测方法是否已存在：v6 上方法缺失则显式跳过（打印 `[SKIP]` 信号，非静默幽灵），
 * 开发席实现后自动改为实际验证。
 *
 * ## 权衡①裁定：测量值胜于捏合（单槽 cellWidth，`measureCells` 回写覆盖捏合设的值）
 *
 * 原因（报告 §4.1(a) 路 1）：实测 cellW 是 cellHeight 的函数（measureCells 里
 * textSize=cellHeight*0.85 → 测"W"），捏合设的 newW 与测量值**无关**——下次绘制必然用
 * 测量值回写覆盖。因此：
 *   - 捏合缩放仍然生效（newH 变 → cellW 变），但**宽度不能自由设**（由高度间接决定）；
 *   - presenter.cellWidth 的最终值 = 测量值（唯一同源）；
 *   - 上报 cols 用这个最终 cellWidth 算（与绘制同一栅格来源）。
 *
 * 若开发席不同意此裁定（如改双槽：cellWidth 保留捏合语义、另加 measuredCellW），
 * **必须推翻本测试并让 leader 批准**，不得静默改期望。
 *
 * ## 幂等契约（反馈环收敛）
 *
 * `setMeasuredCellWidth(v)` 只在 v ≠ 当前 cellWidth 时 recomputeGeometry；同值 no-op
 * （不 emit、不请求帧）——这是"measureCells 每帧回写 → 反馈环收敛"的收敛点。
 * cellHeight 永不被回写改动（否则 cellH→textSize→cellH 反馈环）。
 */
class TermMeasuredCellWritebackTest {

    private class Harness(
        val emulator: TerminalEmulator,
        val presenter: TermViewPresenter,
        val resizeCalls: MutableList<Pair<Int, Int>>,
    )

    private fun harness(rows: Int = 24, cols: Int = 80): Harness {
        val emulator = TerminalEmulator(cols, rows)
        val resizeCalls = mutableListOf<Pair<Int, Int>>()
        val presenter = TermViewPresenter(emulator) { r, c ->
            resizeCalls.add(r to c)
            emulator.resize(c, r)
        }
        return Harness(emulator, presenter, resizeCalls)
    }

    /**
     * 反射调用 `setMeasuredCellWidth(measuredCellW)`；方法不存在（v6）则显式跳过并打信号。
     *
     * 不走直接引用：v6 上 `setMeasuredCellWidth` 尚不存在，直接引用会让整个 `testDebugUnitTest`
     * 编译失败，锁死其他席（w-up-probe 教训）。反射探测让本文件**始终可编译**，
     * 修复后自动从跳过切换为实际验证。
     */
    private fun setMeasuredCellWidthOrSkip(h: Harness, measuredW: Int): Boolean {
        val method = h.presenter.javaClass.methods.firstOrNull { it.name == "setMeasuredCellWidth" }
            ?: run {
                println("[SKIP] setMeasuredCellWidth 不存在（v6 现状）——开发席实现后本断言自动启用")
                assumeTrue(false) // 标记 skipped，非静默（stdout 有信号）
                return false
            }
        method.isAccessible = true
        method.invoke(h.presenter, measuredW)
        return true
    }

    // ---- 现有 API 回归护栏（v6 绿，修复后仍须绿，保护捏合语义不倒退）----

    @Test
    fun pinchSetsCellWidthAndRequestsFrame() {
        val h = harness(rows = 24, cols = 80)
        var frames = 0
        h.presenter.onFrameRequested = { frames++ }
        h.presenter.onFontSizeChanged(newCellWidth = 22, newCellHeight = 24)
        // 捏合直接设 cellWidth（当前唯一写者）——修复后此语义不得被回写破坏。
        assertEquals(22, h.presenter.cellWidth)
        assertEquals(24, h.presenter.cellHeight)
        assert(frames > 0)
    }

    @Test
    fun pinchStillRequestsResize() {
        val h = harness(rows = 15, cols = 50)
        h.presenter.onViewportSizeChanged(500, 300) // 与内核一致 → no-op
        assertEquals(emptyList<Pair<Int, Int>>(), h.resizeCalls)
        h.presenter.onFontSizeChanged(newCellWidth = 12, newCellHeight = 24)
        assertEquals(listOf(12 to 41), h.resizeCalls)
    }

    // ---- setMeasuredCellWidth 幂等（反馈环收敛点）----

    @Test
    fun measuredCellWritebackConvergesAfterOneEmit() {
        val h = harness(rows = 24, cols = 80)
        h.presenter.onViewportSizeChanged(1080, 480) // 首帧 seed：24x108（名义 10）
        assertEquals(listOf(24 to 108), h.resizeCalls)
        h.resizeCalls.clear()

        // 首次回写实测宽 12：cols 收敛到 1080/12=90 → 恰好一次 emit。
        if (!setMeasuredCellWidthOrSkip(h, 12)) return
        assertEquals("首次回写实测宽应恰好 emit 一次", listOf(24 to 90), h.resizeCalls)

        // 同值重复回写（模拟每帧 measureCells）：必须 no-op，绝不重复 emit。
        repeat(5) { setMeasuredCellWidthOrSkip(h, 12) }
        assertEquals("同值回写必须幂等 no-op", listOf(24 to 90), h.resizeCalls)
    }

    // ---- 权衡①裁定：测量值胜于捏合 ----

    @Test
    fun measuredWritebackWinsOverPinch() {
        val h = harness(rows = 24, cols = 80)
        h.presenter.onViewportSizeChanged(1080, 480)
        h.resizeCalls.clear()

        // 捏合设 newW=22（此时 cellWidth=22）。
        h.presenter.onFontSizeChanged(newCellWidth = 22, newCellHeight = 24)
        // 测量回写实测宽 12：cellWidth 被测量值覆盖（测量值胜于捏合）。
        if (!setMeasuredCellWidthOrSkip(h, 12)) return
        assertEquals("测量回写应覆盖捏合设的宽度（测量值胜）", 12, h.presenter.cellWidth)
        // 捏合设的高度不受回写影响（回写只动 cellWidth）。
        assertEquals("回写不得改 cellHeight", 24, h.presenter.cellHeight)
    }

    // ---- 回写不动 cellHeight（否则 cellH→textSize→cellH 反馈环）----

    @Test
    fun writebackDoesNotMutateCellHeight() {
        val h = harness()
        val beforeH = h.presenter.cellHeight
        setMeasuredCellWidthOrSkip(h, 13)
        assertEquals("回写不得改 cellHeight", beforeH, h.presenter.cellHeight)
    }
}
