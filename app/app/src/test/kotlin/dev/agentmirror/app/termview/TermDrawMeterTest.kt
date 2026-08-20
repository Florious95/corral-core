/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/LICENSE-2.0
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
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermDrawMeterTest {

    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        var rects = 0
        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            rects++
            super.drawRect(left, top, right, bottom, paint)
        }
    }

    @Before
    fun setUp() {
        DiagLog.resetForTest()
        TermDrawMeter.resetForTest()
    }

    @After
    fun tearDown() {
        DiagLog.resetForTest()
        TermDrawMeter.resetForTest()
    }

    @Test
    fun onDrawEmitsTermDrawTagWithOperands() {
        val view = drawOnce("hello world")
        val f = java.io.File.createTempFile("diag-draw-", ".log")
        f.deleteOnExit()
        DiagLog.exportTo(f)
        val text = f.readText()
        assertTrue("onDraw 必须落 [term-draw]：$text", text.contains("[term-draw]"))
        assertTrue("必须带 dt_us_p95：$text", text.contains("dt_us_p95="))
        assertTrue("必须带 source=onDraw：$text", text.contains("source=onDraw"))
        assertTrue("必须带 bgRect：$text", text.contains("bgRect="))
        view
    }

    @Test
    fun optOnSkipsDefaultBgCellRects() {
        val slow = countRects(opt = false, text = "aaaa")
        val fast = countRects(opt = true, text = "aaaa")
        assertTrue("改前应画出清屏+逐格底 slow=$slow", slow.rects >= 2)
        assertTrue("改后默认底应被跳过，只剩清屏 fast=$fast slow=$slow", fast.rects < slow.rects)
        assertTrue("改后至少保留清屏矩形", fast.rects >= 1)
    }

    private fun drawOnce(text: String): TermSurfaceView {
        val emulator = TerminalEmulator(20, 5)
        emulator.feed(text)
        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.presenter = TermViewPresenter(emulator) { _, _ -> }
        val bitmap = Bitmap.createBitmap(400, 120, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.recycle()
        return view
    }

    private data class Count(val rects: Int)

    private fun countRects(opt: Boolean, text: String): Count {
        TermDrawMeter.optEnabled = opt
        val emulator = TerminalEmulator(20, 5)
        emulator.feed(text)
        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.presenter = TermViewPresenter(emulator) { _, _ -> }
        val bitmap = Bitmap.createBitmap(400, 120, Bitmap.Config.ARGB_8888)
        val canvas = RecordingCanvas(bitmap)
        view.draw(canvas)
        bitmap.recycle()
        return Count(canvas.rects)
    }
}
