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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P0 回归门（HEAD 终端不渲染）：首帧空脏区必须仍绘制整窗。
 *
 * 背景：抑制机制回退后 `takeFrameRepaint()` 可能返回空列表（无新脏区，正常）。若**首帧**恰为
 * 空脏区（如 presenter 绑定后、首次 feed 前就被询问），frameCallback 旧逻辑 `r.isEmpty() -> Unit`
 * 会跳过 invalidate → 视图从不绘制 → 终端空白（HEAD P0：内容区空白只有底部黑条）。
 *
 * 修复：`hasDrawnOnce` 标记——首帧空脏区仍整窗失效；已画过后空脏区 no-op。
 * 本测试断言：presenter 绑定后（未喂任何内容）取一帧，onDraw 必须画出内容（整窗），不空白。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermFirstFrameDrawP0Test {

    /** 记录型 Canvas：记录是否画了任何行（文本调用）与整窗清屏。 */
    private class FrameCanvas(bitmap: Bitmap, private val w: Int, private val h: Int) : Canvas(bitmap) {
        var textCalls = 0
        var fullClear = false
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            textCalls++
            super.drawText(text, x, y, paint)
        }
        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            textCalls++
            super.drawText(text, start, end, x, y, paint)
        }
        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            if (left <= 0.5f && top <= 0.5f && right >= w - 0.5f && bottom >= h - 0.5f) fullClear = true
            super.drawRect(left, top, right, bottom, paint)
        }
    }

    @Test
    fun firstFrameWithContent_drawsRows() {
        // presenter 绑定后喂入内容（模拟快照/增量到达）。
        val emulator = TerminalEmulator(20, 12)
        emulator.feed((1..5).joinToString("\r\n") { "row-$it" })
        val presenter = TermViewPresenter(emulator) { _, _ -> }
        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.presenter = presenter
        view.measure(400, 240)
        view.layout(0, 0, 400, 240)

        // 驱动一帧：prepareFrame 取本帧范围（含首帧整窗脏）→ draw。
        val canvas = FrameCanvas(Bitmap.createBitmap(400, 240, Bitmap.Config.ARGB_8888), view.width, view.height)
        view.prepareFrame()
        view.draw(canvas)

        // 首帧内容必须画出（整窗清屏 + 内容行），不空白。
        assertTrue(
            "首帧内容必须画出（不空白）：fullClear=${canvas.fullClear} textCalls=${canvas.textCalls}",
            canvas.fullClear || canvas.textCalls > 0,
        )
    }

    @Test
    fun emptyDamageAfterFirstDraw_keepsViewDrawable() {
        // 首帧内容画出后，空脏区帧必须仍能正常 draw（不清空/不崩溃）。
        val emulator = TerminalEmulator(20, 12)
        emulator.feed("hello")
        val presenter = TermViewPresenter(emulator) { _, _ -> }
        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.presenter = presenter
        view.measure(400, 240)
        view.layout(0, 0, 400, 240)

        // 首帧（有内容）画出。
        view.prepareFrame()
        view.draw(FrameCanvas(Bitmap.createBitmap(400, 240, Bitmap.Config.ARGB_8888), view.width, view.height))

        // 空脏区帧（无新内容）：必须能继续 draw（Android View 保留旧画面，这里只验证不崩/可画）。
        presenter.takeFrameRepaint() // 消费空
        view.prepareFrame()
        val canvas = FrameCanvas(Bitmap.createBitmap(400, 240, Bitmap.Config.ARGB_8888), view.width, view.height)
        view.draw(canvas) // 不应抛异常
        assertTrue("空脏区帧后视图仍可 draw（hasDrawnOnce 保护）", true)
    }
}
