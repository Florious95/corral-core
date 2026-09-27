/* Copyright 2026 AgentMirror Project Authors. Licensed under Apache-2.0. */
package dev.agentmirror.app.termview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * IME/dock 实时跟随（View 层）：终端高度逐帧变化时，跟随态的最新行必须与底边同帧、逐像素
 * 移动（不按整行跳、不等后台重捕获、不 resize），完全被裁出的行不画；静止排布保持顶对齐。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TermImeRealtimeAnchorTest {

    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val baselines = mutableMapOf<String, Float>()

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            record(text, y)
            super.drawText(text, x, y, paint)
        }

        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            record(text.substring(start, end), y)
            super.drawText(text, start, end, x, y, paint)
        }

        private fun record(text: String, y: Float) {
            val key = text.trim()
            if (key.isNotEmpty()) baselines[key] = y
        }
    }

    @Test
    fun newestRowTracksLiveBottomEdgeEveryFrameWithoutRecaptureOrResize() {
        val emulator = TerminalEmulator(20, 6)
        // scrollback L0..L5，屏幕 L6..L11；先灌内容再建 presenter，确保帧只来自下面的一次捕获。
        emulator.feed((0..11).joinToString("\r\n") { "L$it" })
        val resizes = mutableListOf<Pair<Int, Int>>()
        val presenter = TermViewPresenter(emulator) { r, c -> resizes += r to c }
        // 同 SessionViewModel：首订几何同步落到内核。
        presenter.onFirstGeometryReady = { r, c -> emulator.resize(c, r) }
        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.nightOverride = true
        view.presenter = presenter
        val cellH = presenter.cellHeight
        assertTrue("fixture needs real font metrics, cellH=$cellH", cellH >= 8)
        val gridPx = 6 * cellH
        val rest = gridPx + cellH / 2
        view.layout(0, 0, 400, rest)
        assertEquals(6, emulator.rows)
        assertTrue("first geometry is a subscription, not a resize: $resizes", resizes.isEmpty())
        presenter.beginFrame()
        assertEquals(6..11, presenter.drawWindow)

        val restCanvas = draw(view, rest)
        val restNewest = restCanvas.baselines.getValue("L11")
        val baselineInRow = restNewest - (gridPx - cellH)
        assertEquals("rest layout stays top-aligned", baselineInRow, restCanvas.baselines.getValue("L6"), 0.01f)

        // 键盘弹起 + 输入框膨胀 + 收起：非整行高度逐帧到达，中间不调用 beginFrame（无新捕获）。
        val frames = listOf(
            rest - cellH / 3, gridPx, gridPx - 1, 5 * cellH - 3, 4 * cellH + 7,
            3 * cellH + cellH / 2, 4 * cellH + 7, gridPx - 1, rest,
        )
        for (h in frames) {
            view.layout(0, 0, 400, h)
            val canvas = draw(view, rest)
            val expectedRowTop = minOf(h, gridPx) - cellH
            assertEquals(
                "h=$h newest row must sit on the live bottom edge",
                expectedRowTop + baselineInRow,
                canvas.baselines.getValue("L11"),
                0.01f,
            )
            // 行间距恒为整格：文字整体平移，不重排。
            assertEquals(
                canvas.baselines.getValue("L11") - cellH,
                canvas.baselines.getValue("L10"),
                0.01f,
            )
            if (h == 3 * cellH + cellH / 2) {
                assertTrue("partially visible top row is drawn", "L8" in canvas.baselines)
                assertFalse("rows fully above the top edge are skipped", "L7" in canvas.baselines)
            }
        }
        assertTrue("IME motion must never resize the PTY: $resizes", resizes.isEmpty())
        assertEquals(6..11, presenter.drawWindow)
    }

    @Test
    fun tapOnSqueezedNewestRowReportsLastGridRow() {
        val emulator = TerminalEmulator(20, 6)
        emulator.feed((0..11).joinToString("\r\n") { "L$it" })
        val presenter = TermViewPresenter(emulator) { _, _ -> }
        presenter.onFirstGeometryReady = { r, c -> emulator.resize(c, r) }
        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.presenter = presenter
        val reported = mutableListOf<Int>()
        view.onTermMouse = { _, row, press, _, _, _, _ ->
            if (press) reported += row
            true
        }
        val cellH = presenter.cellHeight
        view.layout(0, 0, 400, 6 * cellH + cellH / 2)
        presenter.beginFrame()
        val squeezed = 3 * cellH + cellH / 2
        view.layout(0, 0, 400, squeezed)
        draw(view, squeezed)

        // 触点与所见同源：挤压时点在底边最新行，上报的是内核末行而非按顶对齐换算的第 4 行。
        val y = (squeezed - cellH / 2).toFloat()
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 40f, y, 0)
        val up = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_UP, 40f, y, 0)
        view.onTouchEvent(down)
        view.onTouchEvent(up)
        down.recycle()
        up.recycle()
        assertEquals(listOf(6), reported)
    }

    private fun draw(view: TermSurfaceView, bitmapHeight: Int): RecordingCanvas {
        val bitmap = Bitmap.createBitmap(400, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = RecordingCanvas(bitmap)
        view.draw(canvas)
        bitmap.recycle()
        return canvas
    }
}
