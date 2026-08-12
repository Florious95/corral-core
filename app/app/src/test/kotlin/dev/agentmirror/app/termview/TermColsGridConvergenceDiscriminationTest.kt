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
 * 判别红测：fix-cols-grid-convergence 开工第一产出——分辨两个互斥根因，**不许靠推理选边**。
 *
 * 假说 A（web-vs-android-terminal-model）：上报 cols 用**名义字格宽**（默认 10px）算、
 *   画布列推进用**实测字形宽**（measureCells 的 cellW）算，两栅格从不收敛。
 *   可证伪预测：上报 cols ≠ floor(View宽 / 实测字形推进宽)。
 *
 * 假说 B（oss-terminal-solutions）：cols 正确，问题在渲染层 drawLine/drawCentered
 *   把**宽字符**画过画布右缘被 Canvas 裁半（ASCII 不会）。
 *
 * 三组测量（都从真实渲染输出取值，不猜 Robolectric 像素）：
 * ① reportedCols（presenter 首个真实视口上抛的 resize cols）；
 * ② measuredCellW = 实际绘制的单格背景矩形最小宽度（= 画布列推进宽）；
 * ③ 宽字符主格背景矩形右缘 vs 画布（视口）宽——末列宽字符是否越界。
 *
 * 判定：① 红 ⇒ A 成立（两栅格不收敛）；② 在「网格 cols 正确」时绿 ⇒ B 非独立根因；
 *   ③ 在「网格 cols 超宽（A 的条件）」时红 ⇒ B 是 A 触发下的渲染层越界机制。
 *   三测综合输出 A / B / both。
 *
 * Robolectric legacy graphics：measureText("W") 返回字符数 1 → cellW=1（与真机实测
 * 8-11px 方向相反，故 A 的不等式方向在 JVM 上翻转），但「两栅格是否同一来源」这一结构
 * 不变量与像素值无关，JVM 可判。背景矩形宽按 cell.width（1 或 2）铺、与字形是否被改写为
 * '?' 无关——越界可稳定测量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermColsGridConvergenceDiscriminationTest {

    /** 记录型 Canvas：记背景矩形（左、右、色）与文本（内容、x）。与 TermBgCjkAlignTest 同款。 */
    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        data class Rect(val left: Float, val right: Float, val color: Int)
        data class Text(val text: String, val x: Float, val color: Int)

        val rects = mutableListOf<Rect>()
        val texts = mutableListOf<Text>()

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            rects += Rect(left, right, paint.color)
            super.drawRect(left, top, right, bottom, paint)
        }

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            texts += Text(text, x, paint.color)
            super.drawText(text, x, y, paint)
        }

        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            texts += Text(text.substring(start, end), x, paint.color)
            super.drawText(text, start, end, x, y, paint)
        }
    }

    /** SGR 47（Indexed 7）白底 ARGB（TermSurfaceView.ANSI_COLORS[7]）。用白底过滤单元格矩形，
     *  把 onDraw 的全视口默认深底矩形排除在外。 */
    private val whiteBg = Color.rgb(229, 229, 229)

    private fun draw(view: TermSurfaceView, width: Int, height: Int): RecordingCanvas {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = RecordingCanvas(bitmap)
        view.draw(canvas)
        bitmap.recycle()
        return canvas
    }

    /** 白底单元格矩形中宽度最小的矩形宽度 = 单格列推进宽 cellW。 */
    private fun measuredCellW(canvas: RecordingCanvas): Int {
        val white = canvas.rects.filter { it.color == whiteBg }
        assertTrue("夹具失效：未画出任何白底格", white.isNotEmpty())
        val w = white.minOf { it.right - it.left }
        assertTrue("夹具失效：cellW=$w", w >= 1f)
        return w.toInt()
    }

    // ---- ① 假说 A 判别：上报 cols 与画布可容纳列数是否同一栅格 ----

    /**
     * 验收标准原文（taskbook）：「给定 View 宽度与实测字形宽，上报 cols 与画布可容纳列数
     * 必须相等；且 cols*字形宽 <= View 宽」。
     * 当前实现：cols = View宽 / presenter.cellWidth（名义 10）；可容纳列数 = View宽 / 实测 cellW。
     * 两来源不相等 ⇒ 红（修复：实测字形宽回写 presenter 后转绿）。
     */
    @Test
    fun hypothesisA_reportedColsMustEqualCanvasCapacity() {
        val viewportW = 1080
        val viewportH = 480
        val reportedCols = mutableListOf<Int>()
        val emulator = TerminalEmulator(80, 24)
        val presenter = TermViewPresenter(emulator) { rows, cols ->
            reportedCols += cols
            emulator.resize(rows, cols) // 模拟上层把 resize 落进内核
        }
        val view = TermSurfaceView(RuntimeEnvironment.getApplication()).apply {
            this.presenter = presenter
        }
        // 直接驱动 presenter 视口（= layout → onSizeChanged → onViewportSizeChanged 的效果，
        // 不走 Robolectric layout 路径——既有 TermBgCjkAlignTest 同款：view.draw 直接测 onDraw）。
        presenter.onViewportSizeChanged(viewportW, viewportH) // 首个真实视口 → 上抛 resize
        // 给内容一个 SGR 白底，便于从渲染输出取实测 cellW（可容纳列数 = floor(View宽/实测 cellW)）。
        emulator.feed("[47m" + "W".repeat(20) + "[0m")
        val canvas = draw(view, viewportW, viewportH)

        val cols = reportedCols.last()
        val cellW = measuredCellW(canvas)
        val canvasCapacity = viewportW / cellW

        println("[假说A判别] reportedCols=$cols, presenter.cellWidth=${presenter.cellWidth}, " +
            "实测推进宽 cellW=$cellW, 画布可容纳列数=$canvasCapacity, View宽=$viewportW")

        // 两栅格必须同源：上报 cols == 画布按实测推进宽可容纳的列数。
        // 当前：cols=1080/10=108，可容纳=1080/1=1080 —— 红。
        assertEquals(
            "[假说A] 上报 cols($cols) 与画布可容纳列数($canvasCapacity) 不相等——" +
                "cols 用名义字格宽 ${presenter.cellWidth} 算、绘制用实测宽 $cellW 算，两栅格不收敛",
            canvasCapacity, cols,
        )
        // 验收第二半：cols*实测推进宽 <= View 宽（floor 保证等号成立时恒满足；单列不溢出）。
        assertTrue(
            "[假说A] cols*推进宽=${cols * cellW} > View宽=$viewportW——网格比视口宽，末列必越界",
            cols * cellW <= viewportW,
        )
    }

    // ---- ② 假说 B 判别 A：网格 cols 正确时，末列宽字符是否不越界 ----

    /**
     * 把 presenter.cellWidth 置为实测推进宽（模拟修复后的同一栅格来源）→ 上报 cols == 画布
     * 可容纳列数。此时内核 TerminalGrid.write 已防宽字符占末列（整字符落到下一行），
     * 渲染层背景矩形右缘必须不越过视口。绿 ⇒ B 的「cols 正确时仍越界」预言不成立。
     */
    @Test
    fun hypothesisB_wideCharDoesNotOverflowWhenColsCorrect() {
        val viewportW = 1080
        val viewportH = 480
        val reportedCols = mutableListOf<Int>()
        val emulator = TerminalEmulator(80, 24)
        val presenter = TermViewPresenter(emulator) { rows, cols ->
            reportedCols += cols
            emulator.resize(rows, cols)
        }
        val view = TermSurfaceView(RuntimeEnvironment.getApplication()).apply {
            this.presenter = presenter
        }
        // 校准实测推进宽：直接驱动 presenter 视口 + 白底内容让背景矩形可测。
        presenter.onViewportSizeChanged(viewportW, viewportH)
        emulator.feed("[47m" + "W".repeat(20) + "[0m")
        val calib = draw(view, viewportW, viewportH)
        val cellW = measuredCellW(calib)

        // 修复后状态：实测推进宽回写 presenter → 上报 cols 与画布可容纳列数同源。
        reportedCols.clear()
        presenter.onFontSizeChanged(cellW, presenter.cellHeight) // recomputeGeometry → cols=View宽/实测宽
        val cols = reportedCols.last()
        assertEquals("校准失效：cols ≠ floor(View宽/实测宽)", viewportW / cellW, cols)

        // 清屏后摆宽字符在「末列前两格」：主格 cols-2、续格 cols-1（内核只防占 cols-1，允许到这）。
        emulator.feed("[2J")
        emulator.feed("[47m" + "X".repeat(cols - 2) + "它" + "[0m")
        val canvas = draw(view, viewportW, viewportH)
        val white = canvas.rects.filter { it.color == whiteBg }
        assertTrue("夹具失效：未画出白底格", white.isNotEmpty())
        val maxRight = white.maxOf { it.right }

        println("[假说B-正确cols] cols=$cols, cellW=$cellW, cols*cellW=${cols * cellW}, " +
            "View宽=$viewportW, 最右矩形右缘=$maxRight")

        // cols 正确时，最右矩形右缘必须不越过视口（宽字符整体可见或整字符已在下一行）。
        assertTrue(
            "[假说B] cols 正确（=$cols，cols*cellW=${cols * cellW} ≤ View宽）时，" +
                "末列宽字符主格矩形右缘($maxRight) 仍越过视口宽($viewportW) —— B 的越界预言成立",
            maxRight <= viewportW.toFloat(),
        )
        // 内核护栏：宽字符主格不得落在网格末列（cols-1）。"它"占 cols-2..cols-1，
        // 主格矩形起点应为 (cols-2)*cellW；末列起点 (cols-1)*cellW 不得有宽字符主格。
        val wideRects = white.filter { it.right - it.left > cellW * 1.5f }
        assertTrue("夹具失效：宽字符主格矩形未画出", wideRects.isNotEmpty())
        val lastColStart = (cols - 1) * cellW
        assertTrue(
            "[假说B] 末列（col ${cols - 1}）出现宽字符主格（起点=${wideRects.maxOf { it.left }}, " +
                "末列起点=$lastColStart）——内核 TerminalGrid.write 护栏失效",
            wideRects.none { it.left >= lastColStart.toFloat() - 0.01f },
        )
    }

    // ---- ③ 假说 B 判别 B：网格 cols 超宽（假说 A 的条件）时，宽字符是否被裁半 ----

    /**
     * 复现用户症状的最短路径：网格 cols（= 上报 cols）大于 View 可容纳列数（A 的条件）。
     * 内核只防「宽字符占 grid.cols-1 末列」，不防「宽字符占最后一个可见列」——宽字符主格
     * 矩形右缘越过视口宽，真机被 Canvas 裁半（「『它』的一半」）。同一行 ASCII 在末可见列的
     * 右缘恰到视口，不被裁 —— B 的「只有 CJK 被截」机制在此成立。
     */
    @Test
    fun hypothesisB_wideCharClippedWhenGridWiderThanView() {
        // 画布（视口）宽固定 100，模拟一个只装得下 100 列的手机视口；网格却是 120 列（A 的超宽）。
        val canvasW = 100
        val canvasH = 60
        val emulator = TerminalEmulator(cols = 120, rows = 3)
        // 不 layout：presenter 不被 seed，内核保留超宽 120 列（A 条件：上报 cols 偏大）。
        val presenter = TermViewPresenter(emulator) { _, _ -> }
        val view = TermSurfaceView(RuntimeEnvironment.getApplication()).apply {
            this.presenter = presenter
        }
        // 99 个 ASCII 填满可见列（0..98），宽字符主格落在第 99 列（最后一个可见列），
        // 主格 99 + 续格 100 —— 主格矩形右缘 = 101，越过 100 宽的视口 → 被 Canvas 裁半。
        emulator.feed("[47m" + "X".repeat(99) + "它" + "[0m")
        val canvas = draw(view, canvasW, canvasH)
        val white = canvas.rects.filter { it.color == whiteBg }
        assertTrue("夹具失效：未画出白底格", white.isNotEmpty())
        val cellW = measuredCellW(canvas)

        val asciiMaxRight = white.filter { (it.right - it.left) <= cellW * 1.5f }.maxOf { it.right }
        val wideRects = white.filter { (it.right - it.left) > cellW * 1.5f }
        assertTrue("夹具失效：末列宽字符主格矩形未画出", wideRects.isNotEmpty())
        val wideMaxRight = wideRects.maxOf { it.right }

        println("[假说B-超宽网格] 网格cols=120, 视口宽=$canvasW, cellW=$cellW, " +
            "末ASCII矩形右缘=$asciiMaxRight, 宽字符主格矩形右缘=$wideMaxRight")

        // ASCII 在末可见列：右缘恰到（或不超过）视口 —— 不被裁。
        assertTrue(
            "[假说B] ASCII 在末可见列右缘($asciiMaxRight) 越过视口宽($canvasW) —— ASCII 也被裁，与 B 预言矛盾",
            asciiMaxRight <= canvasW.toFloat(),
        )
        // 宽字符主格矩形右缘越过视口 —— 真机 Canvas 裁成「半个字」。红 = B 的渲染越界机制在 A 条件下成立。
        assertTrue(
            "[假说B] 网格超宽（A 条件）时，末列宽字符主格矩形右缘($wideMaxRight) " +
                "越过视口宽($canvasW) —— 渲染层无右缘护栏，宽字符被 Canvas 裁半",
            wideMaxRight > canvasW.toFloat(),
        )
    }
}
