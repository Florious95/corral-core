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
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.app.ui.theme.TermPalette
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A-gl-seam：框线/块元素相邻格矩形必须首尾相接。
 * 区分「缝」和「线本来就细」——量的是相邻格绘制矩形的 left/right，两个数都记。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GlyphSeamTest {

    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float, val color: Int)

        val rects = mutableListOf<Rect>()

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            rects += Rect(left, top, right, bottom, paint.color)
            super.drawRect(left, top, right, bottom, paint)
        }
    }

    @Before
    fun setUp() {
        DiagLog.resetForTest()
    }

    @After
    fun tearDown() {
        DiagLog.resetForTest()
    }

    @Test
    fun glyphSeamGeometryTableAbutsForAnyIntegerCellW() {
        for (cellW in listOf(7, 11, 17, 19)) {
            val origin = 14
            val rects = (0 until 5).map { col ->
                BoxBlockGeometry.fills(0x2588, origin + col * cellW, 0, cellW, 22).single().rect
            }
            for (i in 0 until rects.size - 1) {
                println(
                    "[A-gl-seam table] cellW=$cellW i=$i right=${rects[i].right} nextLeft=${rects[i + 1].left}",
                )
                assertEquals(rects[i].right, rects[i + 1].left)
            }
            val bars = (0 until 5).map { col ->
                BoxBlockGeometry.fills(0x2500, origin + col * cellW, 0, cellW, 22)
                    .maxBy { it.rect.width }.rect
            }
            for (i in 0 until bars.size - 1) {
                println(
                    "[A-gl-seam table ─] cellW=$cellW i=$i right=${bars[i].right} nextLeft=${bars[i + 1].left}",
                )
                assertEquals(bars[i].right, bars[i + 1].left)
            }
        }
    }

    @Test
    fun glyphSeamLegacyCenteredAdvanceLeavesGap() {
        val cellW = 17
        val advance = 14.2f
        val left0 = TermLeftEdge.centeredGlyphX(0, cellW, advance)
        val left1 = TermLeftEdge.centeredGlyphX(cellW, cellW, advance)
        val right0 = left0 + advance
        println("[A-gl-seam legacy] cellW=$cellW advance=$advance right0=$right0 left1=$left1 gap=${left1 - right0}")
        assertTrue(
            "旧居中路径必须留下缝（否则判据分不清缝和细线）right0=$right0 left1=$left1",
            right0 < left1 - 0.01f,
        )
    }

    @Test
    fun glyphSeamFullBlocksAbutAtIntegerDensity() {
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        assertSeam("integer-density=$density", densityHint = density)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun glyphSeamFullBlocksAbutAtFractionalDensity() {
        val dm = RuntimeEnvironment.getApplication().resources.displayMetrics
        dm.density = 2.75f
        dm.densityDpi = 440
        assertSeam("fractional-density=2.75", densityHint = 2.75f)
    }

    @Test
    fun glyphSeamHorizRulesAbut() {
        val (view, canvas) = render("────────")
        val fg = TermPalette.of(false).defaultFg
        val bars = canvas.rects
            .filter { it.color == fg && (it.right - it.left) >= 1f && (it.bottom - it.top) >= 1f }
            .sortedBy { it.left }
        assertTrue("夹具：没画出横线几何 rects=${canvas.rects.size}", bars.size >= 2)
        for (i in 0 until bars.size - 1) {
            println(
                "[A-gl-seam ─] i=$i right=${bars[i].right} nextLeft=${bars[i + 1].left}",
            )
            assertEquals(
                "横线格 $i 右缘必须接下格左缘",
                bars[i].right,
                bars[i + 1].left,
                0.01f,
            )
        }
        view
    }

    @Test
    fun glyphSeamVertRulesAbut() {
        val cellW = 17
        val cellH = 22
        val originX = 14
        val bars = (0 until 4).map { row ->
            BoxBlockGeometry.fills(0x2502, originX, row * cellH, cellW, cellH)
                .maxBy { it.rect.height }.rect
        }
        for (i in 0 until bars.size - 1) {
            println(
                "[A-gl-seam │] i=$i bottom=${bars[i].bottom} nextTop=${bars[i + 1].top}",
            )
            assertEquals(bars[i].bottom, bars[i + 1].top)
        }
    }

    @Test
    fun glyphSeamQuietOnDrawDoesNotFloodAndLogsOnViewWChange() {
        val emulator = TerminalEmulator(20, 4)
        emulator.feed("x")
        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.nightOverride = false
        view.presenter = TermViewPresenter(emulator) { _, _ -> }
        view.layout(0, 0, 400, 160)
        val bmp = Bitmap.createBitmap(400, 160, Bitmap.Config.ARGB_8888)
        val canvas = RecordingCanvas(bmp)
        repeat(40) { view.draw(canvas) }
        val before = DiagLog.snapshotForTest().count { it.contains("[term-left-edge]") }
        println("[A-gl-quiet] 40 draws term-left-edge=$before")
        assertTrue("40 次 onDraw 后 term-left-edge 必须 ≤ 3，实际 $before", before <= 3)
        assertTrue("至少应有首条仪表，不能整段关掉", before >= 1)

        view.layout(0, 0, 520, 160)
        view.draw(canvas)
        val after = DiagLog.snapshotForTest().count { it.contains("[term-left-edge]") }
        println("[A-gl-quiet] after viewW change term-left-edge=$after")
        assertTrue("改 viewW 后必须立刻多一条，before=$before after=$after", after >= before + 1)
        bmp.recycle()
    }

    private fun assertSeam(label: String, densityHint: Float) {
        val (view, canvas) = render("█████")
        val fg = TermPalette.of(false).defaultFg
        val blocks = canvas.rects
            .filter { it.color == fg && (it.right - it.left) >= 1f && (it.bottom - it.top) >= 1f }
            .sortedBy { it.left }
        assertTrue("$label 夹具：没画出 █ 几何 fg=$fg rects=${canvas.rects.map { it.color }}", blocks.size >= 2)
        for (i in 0 until blocks.size - 1) {
            println(
                "[A-gl-seam █ $label] i=$i right=${blocks[i].right} nextLeft=${blocks[i + 1].left} " +
                    "density=$densityHint cellW=${blocks[i].right - blocks[i].left}",
            )
            assertEquals(
                "$label █ 格 $i 右缘必须接下格左缘（缝=背景露出来）",
                blocks[i].right,
                blocks[i + 1].left,
                0.01f,
            )
        }
        view
    }

    private fun render(text: String): Pair<TermSurfaceView, RecordingCanvas> {
        val emulator = TerminalEmulator(24, 6)
        emulator.feed(text)
        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.nightOverride = false
        view.presenter = TermViewPresenter(emulator) { _, _ -> }
        val bmp = Bitmap.createBitmap(480, 160, Bitmap.Config.ARGB_8888)
        val canvas = RecordingCanvas(bmp)
        view.draw(canvas)
        bmp.recycle()
        return view to canvas
    }
}
