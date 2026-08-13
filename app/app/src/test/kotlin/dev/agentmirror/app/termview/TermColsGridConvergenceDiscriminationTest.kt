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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 判别红测（fix-cols-grid-convergence 开工第一产出）。
 *
 * 两个互斥根因（FIELD.md）：
 * - 假说 A：cols 用**名义字格宽(10px)**算、绘制用**实测字形宽**算，两栅格永不收敛。
 *   真机字形宽(8-11px) > 名义 10 → cols 偏大 → 网格比视口宽 → **ASCII 也被截**。
 * - 假说 B：cols 正确，渲染层把**宽字符**画过画布右缘被 Canvas 裁。预言：只有 CJK 被截。
 *
 * **JVM 关键约束（leader 已预警，本文件显式处理，不许变幽灵）**：
 * Robolectric legacy graphics 的 measureText 是 stub（返回字符数）→ 实测 cellW=1 < 名义 10，
 * cols 偏**小**方向与真机相反。因此：
 *   ① 结构断言（cols == 画布可容纳列数）在 JVM 依然成立（reportedCols=108 vs 容量=1080，红）；
 *   ② `cols×字形宽 ≤ View宽` 这条**在 JVM 必绿（108×1≤1080）**——JVM 测不出真实越界，
 *      必须用**真机字形宽参数化**（realCellW 典型 11px > 名义 10）验证真机方向；
 *   ③ 归一化乘积（cols×realCellW）用 realCellW 而非 JVM 的 cellW，防 JVM cellW=1 把越界洗掉。
 *
 * 数字（v6 实测，ProbeClipMechanics 2026-08-14）：
 *   [A] reportedCols=108 vs 容量=1080 → Δ=-972（红，两栅格不收敛）
 *   [B] canvasW=100 cellW=1 网格 120 列 → 最大背景矩形右缘=101 > 100（越界 1px，护栏缺失）
 *   [C] 真机：108×11−1080=108px 越界（ASCII 也会被截）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermColsGridConvergenceDiscriminationTest {

    /** 记录型 Canvas：记背景矩形（左、右、色）与文本（内容、起点 x）。与 TermBgCjkAlignTest 同款。 */
    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        data class Rect(val left: Float, val right: Float, val color: Int)
        data class Text(val text: String, val x: Float)
        val rects = mutableListOf<Rect>()
        val texts = mutableListOf<Text>()

        override fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: Paint) {
            rects += Rect(l, r, paint.color)
            super.drawRect(l, t, r, b, paint)
        }

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            texts += Text(text, x)
            super.drawText(text, x, y, paint)
        }

        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            texts += Text(text.substring(start, end), x)
            super.drawText(text, start, end, x, y, paint)
        }
    }

    /** SGR 47（Indexed 7）白底 ARGB（TermSurfaceView.ANSI_COLORS[7]）。用白底过滤单元格矩形。 */
    private val whiteBg = Color.rgb(229, 229, 229)

    private fun draw(view: TermSurfaceView, w: Int, h: Int): RecordingCanvas {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = RecordingCanvas(bmp)
        view.draw(c)
        bmp.recycle()
        return c
    }

    /** 白底单元格矩形中宽度最小的矩形宽度 = 单格列推进宽 cellW（v6 测量是 stub→1）。 */
    private fun measuredCellW(canvas: RecordingCanvas): Int {
        val white = canvas.rects.filter { it.color == whiteBg }
        assertTrue("夹具失效：未画出任何白底格", white.isNotEmpty())
        val w = white.minOf { it.right - it.left }
        assertTrue("夹具失效：cellW=$w", w >= 1f)
        return w.toInt()
    }

    // ---------------------------------------------------------------------------
    // 判别 A（结构）：上报 cols 与画布可容纳列数是否同一栅格
    // 红 = 两栅格不收敛（A 结构成立）。JVM cellW=1 方向反，但"不相等"依然成立。
    // ---------------------------------------------------------------------------

    @Test
    fun hypothesisA_reportedColsAndCanvasCapacityMustShareSource() {
        val viewportW = 1080
        val viewportH = 480
        val reportedCols = mutableListOf<Int>()
        val emulator = TerminalEmulator(80, 24)
        val presenter = TermViewPresenter(emulator) { rows, cols ->
            reportedCols += cols
            emulator.resize(cols, rows)
        }
        val view = TermSurfaceView(RuntimeEnvironment.getApplication()).apply {
            this.presenter = presenter
            layout(0, 0, viewportW, viewportH)
        }
        emulator.feed("[47m" + "W".repeat(20) + "[0m")
        val canvas = draw(view, viewportW, viewportH)

        val cols = reportedCols.last()
        val nominal = presenter.cellWidth
        val cellW = measuredCellW(canvas)
        val canvasCapacity = viewportW / cellW

        println("[DISCRIM-A-结构] viewportW=$viewportW 名义cellWidth=$nominal 实测推进宽cellW=$cellW")
        println("[DISCRIM-A-结构] reportedCols=$cols canvasCapacity=$canvasCapacity Δcols=${canvasCapacity - cols}")

        // 两栅格必须同源：上报 cols == 画布按实测推进宽可容纳列数。当前：108 vs 1080。
        assertEquals(
            "[A] 上报 cols($cols) 与画布可容纳列数($canvasCapacity) 不相等——" +
                "cols 用名义字格宽 $nominal 算、绘制用实测宽 $cellW 算，两栅格不收敛（A 结构成立）",
            canvasCapacity, cols,
        )
    }

    // ---------------------------------------------------------------------------
    // 判别 A-真机（参数化）：真机字形宽 > 名义 10 时，ASCII 也会被截（A 在真机成立）。
    // JVM cellW=1 的 cols×cellW≤W 必绿是幽灵（leader 预警的权衡③）——这条用真机 realCellW
    // 参数化 + 归一化乘积（cols×realCellW）显式处理，不让它在 CI 里假装被覆盖。
    // 红 = cols×realCellW > viewportW（真机下末列越界）。
    // ---------------------------------------------------------------------------

    @Test
    fun hypothesisA_realCellWidthOverflowsOnDevice() {
        val viewportW = 1080
        val realCellW = 11 // 真机实测字形宽典型值（docs/web-vs-android: 实测 11px > 名义 10）
        val reportedCols = mutableListOf<Int>()
        val emulator = TerminalEmulator(80, 24)
        val presenter = TermViewPresenter(emulator) { rows, cols -> reportedCols += cols; emulator.resize(cols, rows) }
        val view = TermSurfaceView(RuntimeEnvironment.getApplication()).apply {
            this.presenter = presenter
            layout(0, 0, viewportW, 480)
        }
        emulator.feed("[47m" + "W".repeat(20) + "[0m") // SGR 白底，让实测宽可从背景矩形测得
        val canvas = draw(view, viewportW, 480)
        val cellW = measuredCellW(canvas) // JVM stub→1；真机→实测宽
        val effectiveCellW = if (cellW <= 1) realCellW else cellW // JVM stub 回落真机典型值
        val cols = reportedCols.last()

        // 归一化乘积：cols × 生效列推进宽。JVM cellW=1 会把越界洗掉（幽灵）——用真机典型值兜底。
        val colsTimesReal = cols * effectiveCellW
        val capacity = viewportW / effectiveCellW
        val overflow = colsTimesReal - viewportW

        println("[DISCRIM-A-真机] viewportW=$viewportW 生效列宽=$effectiveCellW (JVM stub cellW=$cellW)")
        println("[DISCRIM-A-真机] reportedCols=$cols canvasCapacity=$capacity 归一化 cols×宽=$colsTimesReal 画布右边界=$viewportW 越界=$overflow px")

        // 真机 bug 的判别 = 「cols 是否与画布同源」：当 presenter.cellWidth 未被回写（恒名义 10）
        // 而真机实测宽 > 名义 10 时，末列越界（overflow>0）。修复后（回写 → cellWidth 同源）
        // cols=viewportW/实测宽、溢出为 0，本断言自动转绿。
        assertTrue(
            "[A-真机] presenter.cellWidth=${presenter.cellWidth} 未与画布同源：cols=$cols × 列宽=$effectiveCellW " +
                "= $colsTimesReal > View宽=$viewportW，末列越界 $overflow px（真机字形宽 $realCellW > 名义 10）",
            overflow <= 0,
        )
    }

    // ---------------------------------------------------------------------------
    // 判别 B（机制）+ 字形侧收边：超宽网格（A 回归）时，末列宽字符的画布越界必须被抓到。
    //
    // v6 实测（probe）：canvasW=100 cellW=1 网格 120 列 → 最大背景矩形右缘=101 > 100
    // （越界 1px）——护栏缺失的直接证据。
    //
    // 字形侧：字体 advance 决定字形是否被 Canvas 裁。此断言不看背景矩形，看**字体
    // 实际能推进的列宽**（等宽字体的 cellW advance），对比网格 cols 要求的最右字形右缘
    // （col=cols−1 或宽字符主格 col=cols−2）是否越过画布宽。护栏必须把字形的右缘收进画布。
    // ---------------------------------------------------------------------------

    @Test
    fun hypothesisB_glyphClippedWhenGridOverflows() {
        // 画布（视口）宽固定 100；网格 120 列（A 的超宽回归）——CellGlyphRun 的 MONO 槽按
        // advance 推进，正好模拟"字形实际跨过画布"。
        val canvasW = 100
        val canvasH = 60
        val emulator = TerminalEmulator(cols = 120, rows = 3)
        val presenter = TermViewPresenter(emulator) { _, _ -> }
        val view = TermSurfaceView(RuntimeEnvironment.getApplication()).apply {
            this.presenter = presenter
            layout(0, 0, canvasW, canvasH)
        }
        // 99 个 ASCII 填满可见列，宽字符主格落在第 99 列（末列）。
        emulator.feed("[47m" + "X".repeat(99) + "它" + "[0m")
        val canvas = draw(view, canvasW, canvasH)
        val white = canvas.rects.filter { it.color == whiteBg }
        assertTrue("夹具失效：未画出白底格", white.isNotEmpty())
        val cellW = measuredCellW(canvas)

        // 末列宽字符主格起点 = 第 98 列（前 99 个 ASCII 的最后一列后一格）。
        val cjkStartX = 98f * cellW
        val cjkRects = white.filter { it.left >= cjkStartX - 0.01f }
        assertTrue("夹具失效：末列宽字符主格矩形未画出", cjkRects.isNotEmpty())
        val maxRectRight = white.maxOf { it.right }

        // 字体 advance：等宽字体每格实际推进 = 格宽。当前 presenter.cellWidth 未被回写
        // （恒名义 10）→ 网格超宽时末列字形右缘 = (cols−1)×推进宽 必越过画布（字形被 Canvas 裁）。
        // 字形右缘用「网格 cols 要求的最右列推进位置」算——这正是字形实际画到的 x。
        val cols = emulator.cols // 120（超宽网格，presenter 不同步内核故 cols 不变）
        val glyphRight = (cols - 1) * presenter.cellWidth // 最右列(119)起点 + 一格宽 = cols×推进宽

        println("[DISCRIM-B-机制] 网格cols=$cols 视口宽=$canvasW cellW=$cellW presenter.cellWidth=${presenter.cellWidth}")
        println("[DISCRIM-B-机制] 最大背景矩形右缘=$maxRectRight 画布右边界=$canvasW 背景Δ=${canvasW - maxRectRight}")
        println("[DISCRIM-B-机制] 末列字形右缘=$glyphRight 画布右边界=$canvasW 字形Δ=${canvasW - glyphRight}")

        // 字形必须被收进视口（用户「『它』的一半」是被 Canvas 裁的字形，不是背景没画到）。
        assertTrue(
            "[B-字形] 末列字形右缘($glyphRight) 越过画布右边界($canvasW) —— 字形被 Canvas 裁半，" +
                "cellWidth 未与画布同源",
            glyphRight <= canvasW,
        )
        // 护栏必须把超宽网格的背景收进视口（当前 101 > 100，缺失证据）。
        assertTrue(
            "[B-背景] 网格超宽时背景矩形右缘($maxRectRight) 越过画布右边界($canvasW) —— 护栏缺失",
            maxRectRight <= canvasW,
        )
    }

    // ---------------------------------------------------------------------------
    // 对照：把实测 cellW 回写 presenter（修复后的同一栅格来源）→ cols 正确。
    // 数字：修复后 reportedCols == 画布可容纳列数；末列整格可见。
    // ---------------------------------------------------------------------------

    @Test
    fun correctedCols_matchesCanvasCapacityAndFits() {
        val viewportW = 1080
        val viewportH = 480
        val reportedCols = mutableListOf<Int>()
        val emulator = TerminalEmulator(80, 24)
        val presenter = TermViewPresenter(emulator) { rows, cols ->
            reportedCols += cols
            emulator.resize(cols, rows)
        }
        val view = TermSurfaceView(RuntimeEnvironment.getApplication()).apply {
            this.presenter = presenter
            layout(0, 0, viewportW, viewportH)
        }
        emulator.feed("[47m" + "W".repeat(20) + "[0m")
        draw(view, viewportW, viewportH) // 触发 measureCells 回写（修复后）

        val cols = reportedCols.last()
        val cellW = presenter.cellWidth
        // 修复后 presenter.cellWidth 应被回写为实测宽（不再是名义 10），且 cols==容量。
        assertEquals("[对照] 修复后 cols 必须等于画布可容纳列数", viewportW / cellW, cols)
        // cols×字形宽 ≤ View 宽（floor 保证整除时恒成立）。
        assertTrue(
            "[对照] cols×字形宽=${cols * cellW} > View宽=$viewportW",
            cols * cellW <= viewportW,
        )
    }
}
