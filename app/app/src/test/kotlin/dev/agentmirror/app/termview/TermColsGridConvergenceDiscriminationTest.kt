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
import java.lang.reflect.Method
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
 *
 * ## 第 5 条是「用户真实参数复现」（leader 批准并入，2026-08-14）
 *
 * 前 4 条是**结构断言**（证明两套栅格不收敛的机制，参数是构造的但机制真实）。
 * 第 5 条 `userRealParams_rightmostGlyphClippedByHalfCell` 是**用户真机复现**——
 * 用用户真实参数（View 宽 1260px、实测字形 11px、名义 10px）直接对上用户主诉
 * 「最右侧的字只能看到一半」：超出量 5px ≈ 半字宽 5.5px，独立坐实诊断，
 * 并与「字形侧收边」（超出的是字形被 Canvas 裁，不是背景没画到）互相印证。
 * 看本类时注意分清：**结构断言看机制，第 5 条看用户真实场景**。
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
    // 判别 A-真机（presenter 层，leader 裁定「测试环境喂 11 而非归一化」）。
    // 关键实现事实（w-cols-dev）：只要 view.draw，measureCells 就把 cellWidth 覆盖为当帧
    // 实测（JVM=1）。故**不经 draw**，直接 presenter 层：onViewportSizeChanged 建立首帧
    // （cols=W/名义10），再 setMeasuredCellWidth(11) 让 cols 收敛到 W/实测11。
    // 期望值 = 用户真机参数算出的数（98），非 JVM stub 产物。
    // ---------------------------------------------------------------------------

    @Test
    fun hypothesisA_realCellWidthOverflowsOnDevice() {
        val viewportW = 1080
        val realCellW = 11 // 真机实测字形宽典型值（11 > 名义 10）
        val reportedCols = mutableListOf<Int>()
        val emulator = TerminalEmulator(80, 24)
        val presenter = TermViewPresenter(emulator) { rows, cols -> reportedCols += cols; emulator.resize(cols, rows) }
        // 首帧 seed：cols=1080/10=108（名义）。修复前无 setMeasuredCellWidth → 停在这。
        presenter.onViewportSizeChanged(viewportW, 480)
        // 修复后：回写实测宽 11 → cols 收敛到 1080/11=98。
        setMeasuredCellWidthOrFail(presenter, realCellW)
        val cols = reportedCols.last()
        val capacity = viewportW / realCellW // 98

        println("[DISCRIM-A-真机] viewportW=$viewportW 实测cellW=$realCellW")
        println("[DISCRIM-A-真机] reportedCols=$cols canvasCapacity=$capacity 末列字形右缘=${cols * realCellW} 画布右边界=$viewportW")

        // 两栅格同源：上报 cols == 画布按实测宽可容纳列数。修复前无 setMeasuredCellWidth → 红。
        assertEquals(
            "[A-真机] 上报 cols($cols) 与画布容量($capacity) 不相等——cols 未用实测宽 11 算",
            capacity, cols,
        )
        // 末列字形右缘必须回到画布内（真机 11px 下 cols×11 ≤ 1080）。
        assertTrue(
            "[A-真机] cols×实测宽=${cols * realCellW} > View宽=$viewportW——末列字形越界",
            cols * realCellW <= viewportW,
        )
    }

    // ---------------------------------------------------------------------------
    // 判别 B（护栏行为，leader 2026-08-14 裁定方向 1）。
    // **这条是异常路径护栏测试，参数是构造的（120 列网格 vs 100px 画布），不是用户场景**——
    // 看本类时勿与第 5 条（用户真机复现）混淆。
    //
    // 为什么断言护栏行为而非计算值：`cols × realCellW` 是「网格理想最右推进位置」的纯计算，
    // 不受任何实现影响、恒为 1320 > 100，断言它恒假 = 死结（w-cols-dev 指出，leader 采纳）。
    // 正确断言对象 = 护栏可观测行为：
    //   1. view.clipGuardEngageCount() > 0   ← 护栏在异常网格上确实响了（金丝雀）
    //   2. 背景实测右缘 ≤ 画布宽              ← 护栏把越界背景收进画布
    //
    // 判据（leader）：B-字形 clipGuardEngageCount()>0 与 USER-REAL clipGuardEngageCount()==0
    // 两条同时成立 = 护栏既兜住了异常、又没在正常路径上乱伸手。
    // 方向 2（从画布读字形实际右缘）JVM 下不可行（stub advance=1 读不到真机 11）→ 挪真机验收。
    // ---------------------------------------------------------------------------

    @Test
    fun hypothesisB_glyphClippedWhenGridOverflows() {
        // 画布（视口）宽固定 100；网格 120 列（异常超宽，非用户场景）。
        val canvasW = 100
        val canvasH = 60
        val realCellW = 11
        val emulator = TerminalEmulator(cols = 120, rows = 3)
        val presenter = TermViewPresenter(emulator) { _, _ -> }
        val view = TermSurfaceView(RuntimeEnvironment.getApplication()).apply {
            this.presenter = presenter
            layout(0, 0, canvasW, canvasH)
        }
        setMeasuredCellWidthOrFail(presenter, realCellW) // 回写实测宽 11
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
        val cols = emulator.cols // 120（超宽网格，presenter 不同步内核故 cols 不变）

        println("[DISCRIM-B-机制] 网格cols=$cols 视口宽=$canvasW cellW=$cellW realCellW=$realCellW")
        println("[DISCRIM-B-机制] 最大背景矩形右缘=$maxRectRight 画布右边界=$canvasW 背景Δ=${canvasW - maxRectRight}")
        println("[DISCRIM-B-机制] 护栏 engage 次数=${view.clipGuardEngageCount()}")

        // 断言 1（金丝雀）：护栏必须在异常超宽网格上 engage（可观测，非静默）。
        assertTrue(
            "[B-金丝雀] 异常超宽网格（120 列 > 画布 100px）上护栏必须 engage——clipGuardEngageCount()=0 说明" +
                "护栏没兜住，或网格根本没超宽",
            view.clipGuardEngageCount() > 0,
        )
        // 断言 2：护栏必须把越界背景收进画布（背景右缘 ≤ 画布宽）。
        assertTrue(
            "[B-背景] 网格超宽时背景矩形右缘($maxRectRight) 越过画布右边界($canvasW) —— 背景护栏未收进",
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

    // ---------------------------------------------------------------------------
    // ★ 第 5 条：用户真机复现（leader 判据：测试必须先抓到真实缺陷，抓不到不许改代码）。
    // 用**用户真实参数**，不用 120/100 那种替身配置（纪律⑥）：
    //   View 宽 1260px（用户真机）、实测字形宽 ~11px、名义字格宽 10px。
    //
    // 断言链（对应用户主诉「最右侧的字只能看到一半」）：
    //   - 上报 cols = 1260/名义10 = 126（当前 bug，cols 偏大）；
    //   - 画布容量 = 1260/实测11 = 114（实测栅格只能放 114 列）；
    //   - 第 114 列（容量边界，用户能看到的最后一列）起点 = 114×11 = 1254（屏内），
    //     字形右缘 = 1265，**超出 View 右边界 5px**（≈半字宽 5.5px）→ 用户看到的「一半」。
    //   - 若超出量只 ~2px（不足半字宽），说明诊断有缺口——**立刻停下报 leader，不调参凑**。
    //   - 实测 5px ≈ 半字宽 5.5px：诊断独立坐实，且印证「字形侧收边」（字形被 Canvas 裁）。
    // ---------------------------------------------------------------------------

    @Test
    fun userRealParams_rightmostGlyphClippedByHalfCell() {
        val viewportW = 1260 // 用户真机宽
        val viewportH = 480
        val realCellW = 11 // 真机实测字形宽典型值（11 > 名义 10）
        val nominal = 10
        val reportedCols = mutableListOf<Int>()
        val emulator = TerminalEmulator(80, 24)
        val presenter = TermViewPresenter(emulator) { rows, cols -> reportedCols += cols; emulator.resize(cols, rows) }
        // presenter 层先建 cols（不经 draw，避免 measureCells 把 cellWidth 覆盖回 JVM=1）。
        presenter.onViewportSizeChanged(viewportW, viewportH) // 首帧 seed：cols=1260/10=126（名义）
        setMeasuredCellWidthOrFail(presenter, realCellW) // 修复后：回写实测 11 → cols=1260/11=114
        val cols = reportedCols.last() // 修复后 114；修复前 126
        val capacity = viewportW / realCellW // 114
        val halfCell = realCellW / 2.0 // 5.5（半字宽）
        // 修复前超出量：容量边界列右缘（115×11=1265）− 画布右边界 1260 = 5px。
        val overflow = if (cols > capacity) (capacity + 1) * realCellW - viewportW else 0

        println("[USER-REAL] viewportW=$viewportW 名义cellWidth=$nominal 实测cellW=$realCellW")
        println("[USER-REAL] reportedCols=$cols canvasCapacity=$capacity")
        println("[USER-REAL] 超出量=$overflow px（容量边界列右缘 ${(capacity + 1) * realCellW} - 画布右边界 $viewportW）半字宽=$halfCell px")

        // 主断言：上报 cols 必须 == 画布容量（两栅格同源）。期望 114（用户真机参数算出的数）。
        assertEquals(
            "[USER-REAL] 上报 cols($cols) != 画布容量($capacity)——服务端按名义 10 排内容、画布按实测 11 放，" +
                "末列字形被 Canvas 裁（用户「最右侧的字只能看到一半」）",
            capacity, cols,
        )
        // 末列字形右缘必须回到画布内。
        assertTrue(
            "[USER-REAL] cols×实测宽=${cols * realCellW} > View宽=$viewportW——末列字形越界",
            cols * realCellW <= viewportW,
        )

        // 金丝雀（leader 判据）：正常路径（用户真机参数，cols 收敛后）护栏必须从不 engage。
        // cols==capacity（内容不超画布）→ X3 护栏不该响。经一次真实 draw 读计数。
        val view = TermSurfaceView(RuntimeEnvironment.getApplication()).apply {
            this.presenter = presenter
            layout(0, 0, viewportW, viewportH)
        }
        emulator.feed("W".repeat(20))
        draw(view, viewportW, viewportH)
        println("[USER-REAL] 护栏 engage 次数=${view.clipGuardEngageCount()}")
        assertTrue(
            "[USER-REAL-金丝雀] 正常路径（cols=$cols==容量）护栏必须从不 engage——" +
                "clipGuardEngageCount()=${view.clipGuardEngageCount()} != 0 说明护栏在正常路径乱伸手",
            view.clipGuardEngageCount() == 0,
        )
    }

    /**
     * 测试环境把实测宽显式置成真机值（leader 裁定：不让 JVM stub 的 cellW=1 污染断言）。
     *
     * 调用 `presenter.setMeasuredCellWidth(realCellW)`（开发席已实现）。v6 上方法不存在则
     * 断言失败（红在"没回写"上）；修复后正常调用，reportedCols 按真机实测宽算。
     */
    private fun setMeasuredCellWidthOrFail(presenter: TermViewPresenter, realCellW: Int) {
        val methods = presenter.javaClass.methods
        var target: Method? = null
        for (m in methods) {
            if (m.name == "setMeasuredCellWidth") {
                target = m
                break
            }
        }
        val method: Method = target
            ?: throw AssertionError("[夹具] setMeasuredCellWidth 不存在——修复未实现，红测红在正确的地方")
        method.isAccessible = true
        method.invoke(presenter, realCellW)
    }
}
