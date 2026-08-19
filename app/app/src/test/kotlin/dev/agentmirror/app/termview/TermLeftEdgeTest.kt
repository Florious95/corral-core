/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
 * A-cl-col0：行首 `●` 必须完整可见。
 *
 * 区分两种同形外观：
 * - LAYOUT_PUSHED：第 0 列格子左缘 < contentLeft（网格被推出内容区）
 * - CLIPPED：格子在内容区内，但字形起绘 x < 0（画进 clip 边，被 Canvas 裁）
 *
 * 不依赖 Robolectric 的 measureText（stub）。格子原点走真实 onDraw 的 drawRect；
 * 宽字形负偏移走 [TermLeftEdge.centeredGlyphX] 的显式操作数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermLeftEdgeTest {

    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        data class Rect(val left: Float, val right: Float)
        data class Text(val text: String, val x: Float)

        val rects = mutableListOf<Rect>()
        val texts = mutableListOf<Text>()

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            rects += Rect(left, right)
            super.drawRect(left, top, right, bottom, paint)
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

    @Test
    fun col0CellOriginMatchesContentLeft_notPushedOut_notClipped() {
        val emulator = TerminalEmulator(40, 5)
        emulator.feed("● uiautomator dump")

        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.presenter = TermViewPresenter(emulator) { _, _ -> }

        val contentLeft = view.contentLeftPx()
        val bitmap = Bitmap.createBitmap(400, 120, Bitmap.Config.ARGB_8888)
        val canvas = RecordingCanvas(bitmap)
        view.draw(canvas)
        bitmap.recycle()

        // 排除整帧清屏矩形（左 0、宽≈画布），只留字格。
        val cellRects = canvas.rects.filter {
            val w = it.right - it.left
            w > 0.5f && w < 80f
        }.sortedBy { it.left }
        assertTrue("夹具失效：未画出任何格背景", cellRects.isNotEmpty())
        val col0RectLeft = cellRects.first().left
        val col0TextX = canvas.texts.minOfOrNull { it.x } ?: Float.NaN

        val verdict = TermLeftEdge.classify(col0TextX, col0RectLeft.toInt(), contentLeft)
        println(
            "[A-cl-col0] contentLeft=$contentLeft col0RectLeft=$col0RectLeft " +
                "col0TextX=$col0TextX verdict=$verdict texts=${canvas.texts.take(4)}",
        )

        assertTrue(
            "contentLeft 必须 > 0（坏基线是 0，第 0 列贴 clip 边）：contentLeft=$contentLeft",
            contentLeft > 0,
        )
        assertEquals(
            "第 0 列格子左缘必须等于 contentLeft（否则是布局把首列推出去了）",
            contentLeft.toFloat(),
            col0RectLeft,
            0.01f,
        )
        assertTrue(
            "任何字形起绘 x 不得为负（负值 = 画在 clip 边左侧，● 被裁半）：col0TextX=$col0TextX",
            col0TextX >= 0f,
        )
        assertEquals("onDraw 路径必须判 OK，不能是 CLIPPED/LAYOUT_PUSHED", TermLeftEdge.Verdict.OK, verdict)
    }

    @Test
    fun overflowCenteredGlyph_oldFormulaIsClip_newFormulaStaysInView() {
        // 模拟器实拍：● 可见宽 36、高 46 ⇒ 左溢约 13px。这里用 advance=16 cell=10 复现同号。
        val contentLeft = 0
        val cellPx = 10
        val advance = 16f
        val oldX = contentLeft + (cellPx - advance) / 2f
        println("[A-cl-col0 overflow] oldX=$oldX contentLeft=$contentLeft cellPx=$cellPx advance=$advance")
        assertTrue("坏基线：旧居中公式必须为负（CLIPPED）oldX=$oldX", oldX < 0f)
        assertEquals(TermLeftEdge.Verdict.CLIPPED, TermLeftEdge.classify(oldX, contentLeft, contentLeft))

        val newX = TermLeftEdge.centeredGlyphX(contentLeft, cellPx, advance)
        println("[A-cl-col0 overflow] newX=$newX")
        assertTrue("修后字形起绘必须 >= 0：newX=$newX", newX >= 0f)
        assertEquals(TermLeftEdge.Verdict.OK, TermLeftEdge.classify(newX, contentLeft, contentLeft))
    }

    @Test
    fun classifyDiscriminatesLayoutPushFromClip() {
        assertEquals(
            TermLeftEdge.Verdict.LAYOUT_PUSHED,
            TermLeftEdge.classify(glyphDrawX = -8f, cellOriginX = -8, contentLeft = 0),
        )
        assertEquals(
            TermLeftEdge.Verdict.CLIPPED,
            TermLeftEdge.classify(glyphDrawX = -3f, cellOriginX = 0, contentLeft = 0),
        )
        assertEquals(
            TermLeftEdge.Verdict.OK,
            TermLeftEdge.classify(glyphDrawX = 8f, cellOriginX = 8, contentLeft = 8),
        )
    }
}
