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
import org.junit.Test

/**
 * 约束一三测试（fix-cols-grid-convergence 修法 1 的护栏）：实测 cellW 回写 presenter
 * 必须不破坏今晚两条已收口改动（fix-ime-no-resize / fix-viewport-restore-d38）。
 *
 * leader 明确要求的三点各一条测试锁住：
 * 1. **反馈环收敛**：measureCells → setMeasuredCellWidth → recomputeGeometry → resize
 *    → 布局变化 → 再 measureCells。必须证明至多一次，不能靠"实际上不会"。
 * 2. **IME 成果不误伤**：回写若在 IME 挤压期间发生，不得绕过 fix-ime-no-resize 的
 *    「首帧后不 emit resize」约束又开始发帧。
 * 3. **D-38 顺序无关**：onRealViewportChanged 与 setMeasuredCellWidth 谁先谁后，几何收敛
 *    到同一结果。
 *
 * 收敛性论证（为什么至多一次）：setMeasuredCellWidth 只在值变化时触发 recomputeGeometry；
 * cellW 是 cellHeight 的函数（measureCells 里 textSize=cellHeight*0.85），回写 cellW 不改变
 * 下次测量的 cellW → 首次回写后幂等（同值 no-op）→ 至多一次 recomputeGeometry。
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
            emulator.resize(c, r) // 同步内核（贴近 SessionViewModel 语义）
        }
        return Harness(emulator, presenter, resizeCalls)
    }

    // ---- ① 反馈环收敛：回写至多触发一次 recomputeGeometry/resize ----

    /**
     * 首帧 seed 后（cols 仍名义 108）回写实测 cellW：应恰好 emit 一次（cols 收敛到实测栅格）。
     * 随后同一 cellW 重复回写（模拟连续帧 onDraw 的 measureCells）不得再 emit——幂等收敛点。
     */
    @Test
    fun measuredCellWritebackConvergesAfterOneEmit() {
        val h = harness(rows = 24, cols = 80)
        h.presenter.onViewportSizeChanged(1080, 480)
        val firstEmit = h.resizeCalls.toList()
        assertEquals(listOf(24 to 108), firstEmit) // 首帧 seed：cols=1080/名义10=108
        h.resizeCalls.clear()

        // 首次回写实测 cellW=12：cols 从 108 收敛到 1080/12=90 → 恰好 emit 一次。
        h.presenter.setMeasuredCellWidth(12)
        assertEquals(
            "首次回写实测宽应恰好 emit 一次（cols 收敛到实测栅格）",
            listOf(24 to 90),
            h.resizeCalls,
        )

        // 收敛后同值重复回写（模拟后续帧 measureCells）：必须 no-op，绝不重复 emit。
        repeat(5) { h.presenter.setMeasuredCellWidth(12) }
        assertEquals(
            "同值回写必须幂等 no-op（反馈环收敛点：绝不重复 emit）",
            listOf(24 to 90),
            h.resizeCalls,
        )
    }

    /**
     * 回写是 measureCells→presenter 单向流：回写本身不得改变下次测量的 cellW（否则反馈环）。
     * cellW 是 cellHeight 的函数，回写 cellW 不碰 cellHeight → 下次 measureCells 得同值。
     * 本测试锚定 presenter 状态机：setMeasuredCellWidth 只改 cellWidth、不改 cellHeight。
     */
    @Test
    fun writebackDoesNotMutateCellHeight() {
        val h = harness()
        val beforeH = h.presenter.cellHeight
        h.presenter.setMeasuredCellWidth(13)
        assertEquals("回写不得改 cellHeight（否则 cellH→textSize→cellH 反馈环）", beforeH, h.presenter.cellHeight)
        assertEquals("回写应改 cellWidth", 13, h.presenter.cellWidth)
    }

    // ---- ② IME 成果不误伤：回写在 IME 挤压期间不得绕过「首帧后不 emit」约束 ----

    /**
     * IME 挤压只缩高度不缩宽度 → 回写（只改 cols 来源）不因 IME 触发 resize；
     * IME 挤压后同值回写也不得 emit（首帧后 IME 相关尺寸变化一律静默，fix-ime-no-resize 裁定）。
     */
    @Test
    fun writebackDuringImeShrinkDoesNotEmitResize() {
        val h = harness(rows = 24, cols = 80)
        h.presenter.onViewportSizeChanged(1080, 1920) // 首帧 seed：96 行 108 列，emit 一次
        assertEquals(listOf(96 to 108), h.resizeCalls)
        h.resizeCalls.clear()

        // IME 挤压（高度收缩）：首帧后只推 visibleRows，不得 emit。
        h.presenter.onViewportSizeChanged(1080, 1857)
        assertEquals("IME 挤压不得 emit", emptyList<Pair<Int, Int>>(), h.resizeCalls)

        // IME 挤压期间回写实测 cellW（模拟挤压后重绘帧的 measureCells 回写）：
        // 只触发一次 cols 收敛 emit（这是回写的正常语义，非 IME 误伤）——rows 用挤压后
        // 的当前视口高 1857 算 = 1857/20 = 92，cols 收敛到 1080/12=90。
        // 关键断言：**恰好一次** emit，绝不因 IME 挤压多 emit 一次（反馈环 + IME 约束）。
        h.presenter.setMeasuredCellWidth(12)
        assertEquals(
            "挤压期间回写恰好一次 emit（rows=挤压后 92、cols=实测收敛 90），不得因 IME 高度变化额外 emit",
            listOf(92 to 90),
            h.resizeCalls,
        )
        // 同值再回写（后续帧 measureCells）：幂等 no-op，保持恰一次。
        h.presenter.setMeasuredCellWidth(12)
        assertEquals(
            "同值回写幂等 no-op（反馈环收敛点）",
            listOf(92 to 90),
            h.resizeCalls,
        )
    }

    // ---- ③ D-38 顺序无关：onRealViewportChanged 与 setMeasuredCellWidth 收敛到同一几何 ----

    /**
     * 顺序一：先 onRealViewportChanged（回前台重算）再 setMeasuredCellWidth（回写实测宽）。
     * 几何先对齐到当前 View（像素 rows/cols），再收敛到实测宽栅格（cols 再缩到实测可容纳）。
     */
    @Test
    fun realViewportThenWritebackAlignsToMeasuredGrid() {
        val h = harness(rows = 24, cols = 80)
        h.presenter.onViewportSizeChanged(1080, 480) // 首帧：24x108
        h.resizeCalls.clear()

        // 回前台重放 1920 高：96 行 108 列（cols 仍名义）→ emit 一次。
        h.presenter.onRealViewportChanged(1080, 1920)
        assertEquals(listOf(96 to 108), h.resizeCalls)
        // 回写实测宽：cols 收敛到 1080/实测。
        h.resizeCalls.clear()
        h.presenter.setMeasuredCellWidth(12)
        assertEquals("回写应使 cols 收敛到实测宽栅格", listOf(96 to 90), h.resizeCalls)
    }

    /**
     * 顺序二：先 setMeasuredCellWidth（回写实测宽）再 onRealViewportChanged（回前台重算）。
     * 两者正交：onRealViewportChanged 只按当前像素重算 rows，不覆盖 cellWidth（实测来源）。
     * 收敛结果与顺序一一致（96 行；cols 取决于实测宽，不因顺序漂移）。
     */
    @Test
    fun writebackThenRealViewportKeepsMeasuredCols() {
        val h = harness(rows = 24, cols = 80)
        h.presenter.onViewportSizeChanged(1080, 480) // 首帧：24x108
        h.resizeCalls.clear()

        // 先回写实测宽：cols 收敛到 90。
        h.presenter.setMeasuredCellWidth(12)
        assertEquals(listOf(24 to 90), h.resizeCalls)
        // 再回前台重放 1920 高：rows 96（cellWidth 保持实测来源，cols 仍 = 1920 无关 90）。
        h.resizeCalls.clear()
        h.presenter.onRealViewportChanged(1080, 1920)
        assertEquals("onRealViewportChanged 不得覆盖实测 cellWidth（cols 保持 90）", listOf(96 to 90), h.resizeCalls)
    }

}
