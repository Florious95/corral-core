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
import dev.agentmirror.app.ui.theme.TermPalette
import dev.agentmirror.app.ui.theme.TerminalMetrics
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 结果不变量红测（原 fix-cols-grid-convergence 判别测试，feat-font-size-setting-drop-pinch
 * 重写——leader 2026-08-14 明令：拆掉「名义→实测收敛」不许连带丢失它验证的结果覆盖）。
 *
 * 旧版验证的是**过程**：两套栅格（名义 vs 实测）先不一致、再靠回写收敛到一致。这个过程
 * 本身就是要拆的 bug（每次几何变化先上报错的 cols 再纠正，用户能看见的闪烁/延迟正是它）。
 *
 * 新架构下**结果不变量必须继续成立**：上报 cols 与画布实际能容纳的列数一致——只是达成
 * 方式从「两次上报收敛」变成「[TermViewPresenter.seedCellMetrics] 一次算对」。本文件断言
 * 这个结果，且额外断言【全流程只上报一次 resize】（新架构特有的更强约束，旧架构做不到）。
 *
 * **JVM 关键约束**（沿用旧文件的既有认知）：Robolectric legacy graphics 的 measureText
 * 在部分路径下是 stub；本文件用真机典型参数（realCellW=11 等）直接 seed，绕开 View 层
 * 每帧测量，使断言不受 JVM stub 值影响（新架构下 seed 值就是唯一权威来源，天然免疫这个问题）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermColsGridConvergenceDiscriminationTest {

    /** 记录型 Canvas：记背景矩形（左、右、色）。与 TermBgCjkAlignTest 同款。 */
    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        data class Rect(val left: Float, val right: Float, val color: Int)
        val rects = mutableListOf<Rect>()

        override fun drawRect(l: Float, t: Float, r: Float, b: Float, paint: Paint) {
            rects += Rect(l, r, paint.color)
            super.drawRect(l, t, r, b, paint)
        }
    }

    /** SGR 47（Indexed 7）ARGB（当前浅槽 ansi[7]）。用它过滤单元格矩形。 */
    private val whiteBg = TermPalette.of(false).ansi16[7]!!

    private fun draw(view: TermSurfaceView, w: Int, h: Int): RecordingCanvas {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = RecordingCanvas(bmp)
        view.draw(c)
        bmp.recycle()
        return c
    }

    // ---------------------------------------------------------------------------
    // 结果不变量①：presenter 层——seed 实测宽后，首次视口建立恰好一次 resize，
    // 且上报 cols 与画布按同一实测宽算出的容量一致（真机典型参数：11px > 名义 10）。
    // ---------------------------------------------------------------------------

    @Test
    fun seededRealCellWidth_singleEmitMatchesCanvasCapacity() {
        val viewportW = 1080
        val realCellW = 11 // 真机实测字形宽典型值
        val reportedCols = mutableListOf<Int>()
        val emulator = TerminalEmulator(80, 24)
        val presenter = TermViewPresenter(emulator) { rows, cols -> reportedCols += cols; emulator.resize(cols, rows) }

        presenter.seedCellMetrics(realCellW, 22) // 一次算对：不经名义值播种
        presenter.onViewportSizeChanged(viewportW, 480)

        assertEquals("[守恒] seed 后首次视口建立必须恰好一次 resize", 1, reportedCols.size)
        val cols = reportedCols.single()
        val capacity = viewportW / realCellW // 98

        println("[结果不变量①] viewportW=$viewportW realCellW=$realCellW reportedCols=$cols canvasCapacity=$capacity")

        assertEquals(
            "[①] 上报 cols($cols) 必须与画布容量($capacity) 一致——同一实测宽同源计算",
            capacity, cols,
        )
        assertTrue(
            "[①] cols×实测宽=${cols * realCellW} 不得超出 View 宽=$viewportW",
            cols * realCellW <= viewportW,
        )
    }

    // ---------------------------------------------------------------------------
    // 结果不变量②（护栏行为，leader 2026-08-14 裁定方向 1 沿用）。
    // **这条是异常路径护栏测试，参数是构造的（120 列网格 vs 100px 画布），不是用户场景**——
    // 看本类时勿与第三条（用户真机复现）混淆。
    // ---------------------------------------------------------------------------

    @Test
    fun hypothesisB_glyphClippedWhenGridOverflows() {
        // 画布（视口）宽固定 100；网格 120 列（异常超宽，非用户场景，presenter 不同步内核）。
        // 高度按 3 行整除 realCellH，确保可见窗口覆盖全部 3 行（含写了内容的第 0 行）——
        // 高度不整除会让 visibleRows 收窄、window 只剩底部空行，护栏永远不 engage（假绿）。
        val canvasW = 100
        val realCellW = 11
        val realCellH = 22
        val canvasH = 3 * realCellH
        val emulator = TerminalEmulator(cols = 120, rows = 3)
        val presenter = TermViewPresenter(emulator) { _, _ -> }
        presenter.seedCellMetrics(realCellW, realCellH) // 一次算对：不经回写
        val view = TermSurfaceView(RuntimeEnvironment.getApplication()).apply {
            this.presenter = presenter
            nightOverride = false
            layout(0, 0, canvasW, canvasH)
        }
        // 99 个 ASCII 填满可见列，宽字符主格落在第 99 列（末列）。
        emulator.feed("[47m" + "X".repeat(99) + "它" + "[0m")
        val canvas = draw(view, canvasW, canvasH)
        val white = canvas.rects.filter { it.color == whiteBg }
        assertTrue("夹具失效：未画出白底格", white.isNotEmpty())

        val maxRectRight = white.maxOf { it.right }
        val cols = emulator.cols // 120（超宽网格，presenter 不同步内核故 cols 不变）

        println("[结果不变量②-机制] 网格cols=$cols 视口宽=$canvasW realCellW=$realCellW")
        println("[结果不变量②-机制] 最大背景矩形右缘=$maxRectRight 画布右边界=$canvasW")
        println("[结果不变量②-机制] 护栏 engage 次数=${view.clipGuardEngageCount()}")

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
    // ★ 结果不变量③：用户真机复现（leader 判据：测试必须先抓到真实缺陷，抓不到不许改代码）。
    // 用**用户真实参数**（View 宽 1260px、实测字形宽 11px），不用 120/100 那种替身配置。
    //
    // 断言链（对应用户主诉「最右侧的字只能看到一半」）：
    //   - seed 实测宽 11 后首次视口建立恰好一次 resize；
    //   - 上报 cols = 画布容量 = 1260/11 = 114（新架构下结构性恒成立，不再需要先算出
    //     错误的 126 再纠正——这正是本任务要拆的「名义值播种」，用户看见的闪烁根因）；
    //   - 正常路径护栏 engage 次数恒为 0（cols 与容量同源，从不越界）。
    // ---------------------------------------------------------------------------

    @Test
    fun userRealParams_seededSingleEmitFitsWithoutClipping() {
        val viewportW = 1260 // 用户真机宽
        val viewportH = 480
        val realCellW = 11 // 真机实测字形宽典型值
        val realCellH = 22 // 真机实测字格高典型值
        val reportedCols = mutableListOf<Int>()
        val emulator = TerminalEmulator(80, 24)
        val presenter = TermViewPresenter(emulator) { rows, cols -> reportedCols += cols; emulator.resize(cols, rows) }

        presenter.seedCellMetrics(realCellW, realCellH) // 一次算对：不经「名义 126 → 回写 114」两段收敛
        presenter.onViewportSizeChanged(viewportW, viewportH)

        assertEquals("[守恒] seed 后首次视口建立必须恰好一次 resize", 1, reportedCols.size)
        val cols = reportedCols.single()
        val capacity = minOf(viewportW / realCellW, TerminalMetrics.maxCols)

        println("[结果不变量③] viewportW=$viewportW realCellW=$realCellW reportedCols=$cols canvasCapacity=$capacity")

        assertEquals(
            "[③] 上报 cols($cols) 必须等于画布容量($capacity)——同源计算，不再有名义/实测两套栅格",
            capacity, cols,
        )
        assertTrue(
            "[③] cols×实测宽=${cols * realCellW} 不得超出 View 宽=$viewportW——末列字形不越界",
            cols * realCellW <= viewportW,
        )
        // 正常路径下护栏是否恒为 0（绘制像素级验证，含 CJK 双宽内容）见
        // TermFontSizeSettingCjkFitTest——那条测试用同一 View 全程走 fontSizeSp→seed→layout→draw
        // 单一实测来源，不像本测试用字面量直接 seed presenter，二者混用会让 View 局部绘制
        // 字段（同一实例走自己的 fontSizeSp 实测）与 presenter 的字面量 seed 值脱钩，产生假信号。
    }
}
