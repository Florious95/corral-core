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
 * 判别红测（数字优先版）：fix-cols-grid-convergence 开工第一产出。
 * **产出是数字，不是红绿** —— 每个测试都在 stdout 打 `[DISCRIM]` 判别行，断言只是把
 * 数字指向 A / B / 都不是 的哪一档。
 *
 * 两个互斥根因 + 我们的实测形态：
 * - 假说 A：cols 用**名义字格宽(10px)**算、绘制用**实测字形宽**算，两栅格永不收敛。
 *   真机字形宽(8-11px) > 名义 10 → cols 偏大 → 网格比视口宽 → **ASCII 也被截**。
 * - 假说 B：cols 正确，渲染层 drawLine/drawCentered 把**宽字符**画过画布右缘被 Canvas 裁。
 *   预言：只有 CJK 被截，ASCII 不会。
 *
 * JVM 关键约束（必须向 leader 讲清）：Robolectric legacy graphics 的 measureText 是
 * stub（返回字符数），实测 cellW=1 < 名义 10，cols 偏**小**方向与真机相反——
 * JVM 渲染上测不出「ASCII 越界」。因此 ASCII 越界用**真机字形宽参数化**判别（结构级
 * 数字，无需渲染），CJK 越界用**网格超宽渲染**判别（机制级数字，需要渲染）。
 *
 * 判别矩阵（四个数，均=末字符右缘 x - 画布右边界 x，负=越界被裁）：
 * - ASCII 差值 < 0（真机参数化）→ A 成立（两栅格不收敛导致 cols 偏大）
 * - CJK 差值 < 0 且 ASCII 差值 ≥ 0（网格超宽渲染）→ B 的机制在 A 条件下成立
 * - 两差值均 ≥ 0 却仍截断 → A、B 都不是，根因在别处（服务端产内容时 cols 就超）
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

    /** SGR 47（Indexed 7）白底 ARGB（TermSurfaceView.ANSI_COLORS[7]）。用白底过滤单元格矩形。 */
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

    // ---------------------------------------------------------------------------
    // 判别 A（结构）：上报 cols 与画布可容纳列数是否同一栅格
    // 数字：reportedCols vs canvasCapacity（= viewportW / 实测 cellW）
    // 红 = 两栅格不收敛（A 结构成立）。真机方向在 判别A-真机 参数化补。
    // ---------------------------------------------------------------------------

    @Test
    fun hypothesisA_reportedColsAndCanvasCapacityMustShareSource() {
        val viewportW = 1080
        val viewportH = 480
        val reportedCols = mutableListOf<Int>()
        val emulator = TerminalEmulator(80, 24)
        val presenter = TermViewPresenter(emulator) { rows, cols ->
            reportedCols += cols
            emulator.resize(cols, rows) // onResizeRequest 参数序 = rows, cols
        }
        val view = TermSurfaceView(RuntimeEnvironment.getApplication()).apply {
            this.presenter = presenter
            layout(0, 0, viewportW, viewportH) // layout→onSizeChanged→首帧 seed→emit；view.width 反映画布宽
        }
        // 白底内容让实测推进宽可从渲染输出测得。
        emulator.feed("[47m" + "W".repeat(20) + "[0m")
        val canvas = draw(view, viewportW, viewportH)

        val cols = reportedCols.last()
        val nominal = presenter.cellWidth
        val cellW = measuredCellW(canvas)
        val canvasCapacity = viewportW / cellW

        val deltaCols = canvasCapacity - cols
        println("[DISCRIM-A-结构] viewportW=$viewportW 名义cellWidth=$nominal 实测推进宽cellW=$cellW")
        println("[DISCRIM-A-结构] reportedCols=$cols canvasCapacity=$canvasCapacity Δcols=$deltaCols")

        // 两栅格必须同源：上报 cols == 画布按实测推进宽可容纳列数。当前：108 vs 1080。
        assertEquals(
            "[A] 上报 cols($cols) 与画布可容纳列数($canvasCapacity) 不相等——" +
                "cols 用名义字格宽 $nominal 算、绘制用实测宽 $cellW 算，两栅格不收敛（A 结构成立）",
            canvasCapacity, cols,
        )
        // 验收第二半：cols*实测推进宽 <= View 宽（floor 保证等号成立时恒满足）。
        assertTrue(
            "[A] cols*推进宽=${cols * cellW} > View宽=$viewportW——网格比视口宽，末列必越界",
            cols * cellW <= viewportW,
        )
    }

    // ---------------------------------------------------------------------------
    // 判别 A-真机（参数化）：真机字形宽 > 名义 10 时，ASCII 是否也会被截。
    // JVM 的 cellW=1 < 名义10，cols 偏小，渲染测不出 ASCII 越界；但真机字形宽
    // 典型 11px > 名义10，cols=viewportW/10 偏大。用结构数字（不依赖渲染）判别：
    //   cols(名义10) 与 canvasCapacity(真机字形宽) 的差 → ASCII 行末列越界量。
    // 红 = 真机下 ASCII 也会越界 → A 在真机成立（不只 CJK）。
    // ---------------------------------------------------------------------------

    @Test
    fun hypothesisA_realCellWidthOverflowsAsciiOnDevice() {
        val viewportW = 1080
        val realCellW = 11 // 真机实测字形宽典型值（web-vs-android doc: 实测 11px > 名义 10）
        val cols = viewportW / 10 // presenter 用名义 10 算的上报 cols
        val capacity = viewportW / realCellW // 画布按真机实测推进宽可容纳列数
        val asciiEdgeDelta = cols * realCellW - viewportW // 末 ASCII 列右缘 - 画布右边界

        println("[DISCRIM-A-真机] viewportW=$viewportW 真机cellW=$realCellW")
        println("[DISCRIM-A-真机] reportedCols=$cols(=viewportW/名义10) canvasCapacity=$capacity(=viewportW/实测11)")
        println("[DISCRIM-A-真机] ASCII末列右缘=$cols*$realCellW=${cols * realCellW} 画布右边界=$viewportW " +
            "ASCII越界量=$asciiEdgeDelta px")

        // 真机 A 成立 ⇔ ASCII 末列右缘越过画布右边界（差值 < 0）。当前：108*11-1080=108px 越界。
        assertTrue(
            "[A-真机] 真机字形宽 ${realCellW}px > 名义 10px 时，cols=$cols 而画布只容纳 $capacity 列，" +
                "ASCII 末列右缘(${cols * realCellW}) 越过画布右边界($viewportW) $asciiEdgeDelta px —— " +
                "A 在真机成立：ASCII 也会被截",
            asciiEdgeDelta > 0,
        )
    }

    // ---------------------------------------------------------------------------
    // 判别 B（机制）+ 护栏 engage（约束三）：网格超宽（A 的条件）时，渲染层护栏必须
    // engage 把宽字符收进视口（可观测），且 ASCII 从不受收边。
    // 修复前数字（已取到）：CJK 主格矩形右缘 101 > 画布 100（越界被裁）；
    //                     ASCII 末列右缘 99 ≤ 100（不越界）。
    // 修复后：护栏把 CJK 收进（右缘 ≤ 画布宽）、clipGuardEngageCount > 0（可观测）、
    //         ASCII 原样（从不受收边）。红测在修复后转绿。
    // ---------------------------------------------------------------------------

    @Test
    fun hypothesisB_clipGuardEngagesOnlyForWideCharWhenGridOverflows() {
        // 画布（视口）宽固定 100，模拟一个只装得下 100 列的手机视口；网格却是 120 列（A 的超宽）。
        val canvasW = 100
        val canvasH = 60
        val emulator = TerminalEmulator(cols = 120, rows = 3)
        val presenter = TermViewPresenter(emulator) { _, _ -> } // 不同步内核：保持超宽 120 列
        val view = TermSurfaceView(RuntimeEnvironment.getApplication()).apply {
            this.presenter = presenter
            layout(0, 0, canvasW, canvasH) // view.width=100 → 护栏 clipRight=100；grid 120 > 100
        }
        // 99 个 ASCII 填满可见列（0..98），宽字符主格落在第 99 列（最后一个可见列）。
        emulator.feed("[47m" + "X".repeat(99) + "它" + "[0m")
        val canvas = draw(view, canvasW, canvasH)
        val white = canvas.rects.filter { it.color == whiteBg }
        assertTrue("夹具失效：未画出白底格", white.isNotEmpty())
        val cellW = measuredCellW(canvas)

        // 宽字符主格起点 = 第 99 列（末列前两格之一）→ 按 x 坐标（起点 ≥ 98*cellW）区分，
        // 不用矩形宽度：护栏收边后宽字符矩形可能只剩 1 格宽，宽度过滤会漏判（previous bug）。
        val lastColStartX = (canvasW / cellW - 2) * cellW // = 98*cellW（CJK 主格起点）
        val cjkRects = white.filter { it.left >= lastColStartX.toFloat() - 0.01f }
        assertTrue("夹具失效：末列宽字符主格矩形未画出", cjkRects.isNotEmpty())
        val cjkRight = cjkRects.maxOf { it.right }
        val asciiRight = white.filter { it.left < lastColStartX.toFloat() - 0.01f }.maxOf { it.right }

        val asciiDelta = canvasW - asciiRight // + = 不越界
        val cjkDelta = canvasW - cjkRight // + = 被护栏收进（≤ 画布宽）

        println("[DISCRIM-B-机制] 网格cols=120 视口宽=$canvasW cellW=$cellW")
        println("[DISCRIM-B-机制] ASCII末可见列右缘=$asciiRight 画布右边界=$canvasW Δ=$asciiDelta")
        println("[DISCRIM-B-机制] CJK主格矩形右缘=$cjkRight 画布右边界=$canvasW Δ=$cjkDelta")
        println("[DISCRIM-B-机制] 护栏engage次数=${presenter.clipGuardEngageCount}")

        // 修复后红测转绿断言：
        // 1) 护栏必须 engage（可观测）——证明网格超宽异常被兜住，不是静默遮羞布。
        assertTrue(
            "[B-机制] 网格超宽（A 回归）时护栏必须 engage（clipGuardEngageCount>0），" +
                "而非静默裁掉——约束三可观测要求",
            presenter.clipGuardEngageCount > 0,
        )
        // 2) CJK 被收进：主格矩形右缘 ≤ 画布宽（不再越界被 Canvas 裁半）。
        assertTrue(
            "[B-机制] CJK 主格矩形右缘($cjkRight) 仍越过画布右边界($canvasW) Δ=$cjkDelta —— " +
                "护栏未把宽字符收进视口",
            cjkDelta >= 0,
        )
        // 3) ASCII 从不受收边：末列 ASCII 右缘 ≤ 画布宽（且不被收——护栏只收越界的宽字符）。
        assertTrue(
            "[B-机制] ASCII 末列右缘($asciiRight) 越过画布右边界($canvasW) Δ=$asciiDelta —— " +
                "ASCII 也被收边（护栏误伤窄字符）",
            asciiDelta >= 0,
        )
    }

    // ---------------------------------------------------------------------------
    // 对照：把 cellW 回写 presenter（修复后的同一栅格来源）→ cols 正确。
    // 数字：CJK 末列主格矩形右缘 vs 画布右边界。
    // 绿 = CJK 差值 ≥ 0（不越界）→ B 非独立根因，A 修复后 B 的越界消失。
    // ---------------------------------------------------------------------------

    @Test
    fun hypothesisB_correctCols_wideCharDoesNotOverflow() {
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
            layout(0, 0, viewportW, viewportH) // layout→seed；view.width 反映画布宽（护栏 clipRight）
        }
        // 校准实测推进宽：白底内容让背景矩形可测。draw 时 measureCells 自动回写 cellWidth。
        emulator.feed("[47m" + "W".repeat(20) + "[0m")
        val calib = draw(view, viewportW, viewportH)
        val cellW = measuredCellW(calib)
        // 修复后：draw 的 measureCells 已把实测 cellW 回写 presenter → cols 收敛到画布可容纳数。
        assertEquals("校准失效：实测宽未回写 presenter", cellW, presenter.cellWidth)

        // 清屏后摆宽字符在「末列前两格」：主格 cols-2、续格 cols-1（内核允许到这）。
        emulator.feed("[2J")
        emulator.feed("[47m" + "X".repeat(emulator.cols - 2) + "它" + "[0m")
        val canvas = draw(view, viewportW, viewportH)
        val white = canvas.rects.filter { it.color == whiteBg }
        assertTrue("夹具失效：未画出白底格", white.isNotEmpty())
        val maxRight = white.maxOf { it.right }
        val delta = viewportW - maxRight

        println("[DISCRIM-B-对照] emulator.cols=${emulator.cols} cellW=$cellW 视口宽=$viewportW")
        println("[DISCRIM-B-对照] CJK最右矩形右缘=$maxRight 画布右边界=$viewportW Δ=$delta")
        println("[DISCRIM-B-对照] 护栏engage次数=${presenter.clipGuardEngageCount}")

        // cols 正确时，最右矩形右缘必须不越过视口（宽字符整体可见或整字符已在下一行）。
        assertTrue(
            "[B-对照] cols 正确（=${emulator.cols}，${emulator.cols}*cellW=${emulator.cols * cellW} ≤ View宽）时，" +
                "末列宽字符主格矩形右缘($maxRight) 越过视口宽($viewportW) Δ=$delta —— B 越界预言成立",
            delta >= 0,
        )
        // 约束三：正常 cols 下护栏必须从不 engage（可观测地证明护栏只在异常条件工作）。
        assertEquals(
            "[B-对照] cols 正确时护栏必须从不 engage（正常条件静默为 0）",
            0, presenter.clipGuardEngageCount,
        )
    }
}
