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
import android.graphics.Paint
import dev.agentmirror.app.ui.theme.TerminalMetrics
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
 * 红测（feat-font-size-setting-drop-pinch，taskbook acceptance 第 4 条）：
 * 纯 CJK 双宽内容下，末列字形右缘不超出画布宽度。
 *
 * 前置契约：需要 [TermViewPresenter.seedCellMetrics]（见
 * [TermFontSizeSettingDropPinchTest] 顶部注释的完整契约声明）——把用户选定字号的
 * 实测字形宽/高直接喂给 presenter，首次视口建立即用实测值算 cols，不经播种-收敛。
 *
 * 用真机典型参数（非构造异常值）：CJK 字形宽 = 2×ASCII 实测宽，填满一整行末列必须
 * 完整落在画布内，护栏（clipGuardEngageCount）在正常路径必须恒为 0——engage 说明
 * 上游 cols 计算又和实际绘制脱钩了，护栏是兜底不是常态（TermSurfaceView 注释口径）。
 *
 * **必须用最小档字号（leader 2026-08-14 17:21 补充裁定）**：用户新证据——默认字号、
 * 从未捏合的初始状态下末列也会被切，只是字大时溢出量小不明显（溢出量≈列数×误差，
 * 字越小列越多，累积误差越容易暴露）。故本文件用 [SharedPreferencesFontSizeStore]
 * 预设字号里最小的 12sp 对应的典型像素值（而非中间档 16sp），viewport 宽用用户真机
 * 尺寸把列数拉到最大，让"名义 vs 实测"一旦有残余偏差就必然在 cols 断言上现形。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermFontSizeSettingCjkFitTest {

    private fun draw(view: TermSurfaceView, w: Int, h: Int) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        view.draw(c)
        bmp.recycle()
    }

    private fun seedCellMetricsOrFail(presenter: TermViewPresenter, cellW: Int, cellH: Int) {
        val method: Method = presenter.javaClass.methods.firstOrNull { it.name == "seedCellMetrics" }
            ?: throw AssertionError(
                "[契约缺失] TermViewPresenter.seedCellMetrics(cellWidthPx, cellHeightPx) 不存在——" +
                    "见 TermFontSizeSettingDropPinchTest 的契约声明。红测红在正确的地方。",
            )
        method.isAccessible = true
        method.invoke(presenter, cellW, cellH)
    }

    @Test
    fun pureCjkContent_fillsExactlyToCapacity_rightEdgeStaysInsideCanvas() {
        val viewportW = 1260 // 用户真机宽
        val viewportH = 480
        val realCellW = 8 // 最小档（12sp）真机 ASCII 实测字形宽典型值——列数最多，误差最容易暴露
        val realCellH = 17
        val emulator = TerminalEmulator(200, 24) // 内核列数留够余量，最终 cols 由 resize 收窄
        val resizeCalls = mutableListOf<Pair<Int, Int>>()
        val presenter = TermViewPresenter(emulator) { rows, cols ->
            resizeCalls += rows to cols
            emulator.resize(cols, rows)
        }
        // 必须先 seed 再注入 View（同 TermFontSizeSettingDropPinchTest：未 seed 的 presenter
        // 注入时会触发 View 的自动测量，在 Robolectric fontMetrics stub 下会因回读 0 而崩溃）。
        seedCellMetricsOrFail(presenter, realCellW, realCellH)
        val view = TermSurfaceView(RuntimeEnvironment.getApplication()).apply {
            this.presenter = presenter
        }
        view.layout(0, 0, viewportW, viewportH)

        val capacity = minOf(viewportW / realCellW, TerminalMetrics.maxCols)
        // 纯 CJK（双宽）内容，逐格填满到刚好覆盖画布容量列数（capacity/2 个双宽字符，
        // 余 1 列时也无所谓——只看末列字形是否越界，不追求精确占满）。
        val cjkCount = capacity / 2
        emulator.feed("[47m" + "国".repeat(cjkCount) + "[0m")

        draw(view, viewportW, viewportH)

        assertEquals(
            "[守恒] 全流程只应上报一次 resize（seed 实测值后不应再有第二次收敛上报）",
            1, resizeCalls.size,
        )

        // 护栏必须恒为 0：正常路径（cols 与画布实测容量同源）不需要护栏兜底越界。
        assertTrue(
            "[④-金丝雀] 正常 CJK 填充路径下 clipGuardEngageCount()=${view.clipGuardEngageCount()} != 0——" +
                "说明上报 cols 与实际可绘制列数又脱钩了，末列会被裁",
            view.clipGuardEngageCount() == 0,
        )

        // 末列（双宽字符主格）右缘 = 起始列 × cellW + 2×cellW，不得超过画布宽度。
        val lastGlyphStartCol = (cjkCount - 1) * 2
        val lastGlyphRightEdge = (lastGlyphStartCol + 2) * realCellW
        assertTrue(
            "[④] 末列 CJK 字形右缘=$lastGlyphRightEdge 超出画布宽度=$viewportW——末列被裁",
            lastGlyphRightEdge <= viewportW,
        )
    }
}
