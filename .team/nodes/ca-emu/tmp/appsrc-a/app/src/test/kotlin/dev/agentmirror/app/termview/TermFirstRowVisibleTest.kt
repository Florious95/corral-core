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
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 首行完整可见红测（fix-term-residuals 缺陷②，A-snapshot-align.png 顶行被切半）。
 *
 * Robolectric 的 Paint 文本指标是 stub，不能做像素断言；这里用**记录型 Canvas**
 * 捕获 onDraw 全部 drawRect/drawText 的设备系（translate 累计后）y 坐标，锁定几何
 * 不变量：任何行背景与字形基线都不得落在画布顶边（y=0）之上。旧实现在铺行前
 * translate(0, -lineHeightPx)，首行背景顶 = -lineHeightPx、首行基线 < 0——整行被
 * 抬出画布顶（本测试对旧代码红）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermFirstRowVisibleTest {

    /**
     * 记录型 Canvas：累计 translate 的纵向偏移 [ty]，把每次 drawRect 顶边 / drawText
     * 基线折算到设备坐标后记录（onDraw 只用 translate 变换，无 save/rotate/scale，
     * 线性累计即真实设备 y）。
     */
    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        var ty = 0f
            private set
        val rectTops = mutableListOf<Float>()
        val textBaselines = mutableListOf<Float>()

        override fun translate(dx: Float, dy: Float) {
            ty += dy
            super.translate(dx, dy)
        }

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            rectTops += top + ty
            super.drawRect(left, top, right, bottom, paint)
        }

        // MONO batch 路径：drawText(String, x, y, paint)。
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            textBaselines += y + ty
            super.drawText(text, x, y, paint)
        }

        // 回退槽逐格路径：drawText(text, start, end, x, y, paint)（drawCentered 零分配重载）。
        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            textBaselines += y + ty
            super.drawText(text, start, end, x, y, paint)
        }
    }

    @Test
    fun firstRowBackgroundAndGlyphsNotClippedAboveTop() {
        // 夹具：首行有内容 ALIGN_A（对应真机取证图 A 的顶行标记）。
        val emulator = TerminalEmulator(20, 5)
        emulator.feed("ALIGN_A")

        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.presenter = TermViewPresenter(emulator) { _, _ -> }

        val bitmap = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888)
        val canvas = RecordingCanvas(bitmap)
        view.draw(canvas)
        bitmap.recycle()

        assertTrue("onDraw 未画任何文本（夹具失效）", canvas.textBaselines.isNotEmpty())
        assertTrue("onDraw 未画任何背景（夹具失效）", canvas.rectTops.isNotEmpty())

        // 不变量 1：任何背景块的顶边不得高于画布顶（负 y = 首行带被裁出屏幕）。
        val minRectTop = canvas.rectTops.min()
        assertTrue("首行背景顶边被抬出画布：minRectTop=$minRectTop", minRectTop >= 0f)
        // 首行背景带必须恰好从 y=0 开始（整帧清屏矩形与首行行带同顶，均应为 0）。
        assertEquals("首行背景带未从画布顶开始", 0f, minRectTop, 0.01f)

        // 不变量 2：任何字形基线不得为负（drawText 的字形画在基线上方，基线 ≤ 0
        // 意味着首行字形整体/大半在画布外——正是"切半行"现场）。
        val minBaseline = canvas.textBaselines.min()
        assertTrue("首行基线被抬出画布：minBaseline=$minBaseline", minBaseline >= 0f)

        // 不变量 3：首行基线不得越过首行行带底（防反向过修：往下平移整行以上）。
        val presenter = view.presenter!!
        assertTrue(
            "首行基线越过行带底：minBaseline=$minBaseline, cellHeight=${presenter.cellHeight}",
            minBaseline <= presenter.cellHeight.toFloat(),
        )
    }
}
