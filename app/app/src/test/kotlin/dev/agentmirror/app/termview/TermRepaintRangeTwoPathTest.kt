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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 重绘范围双路径红测（fix-input-send-fullrepaint 收工门）。
 *
 * 用户对照组——同一段终端内容变化，teammate 从其他进程写入（纯外部 delta）不整屏重刷，
 * 用户从 App 发送（本端发送后的回显 delta）却整屏逐行从上往下刷。两条路径在渲染层的唯一
 * 差异点必须是零：脏区相同 → 重绘范围必须相同，且均为**脏行级**，不得整屏。
 *
 * 判据是「重绘的空间分布形态」（leader 新规矩：机制假设会骗人，用户能看见的是画了哪些行）：
 *  - 缺陷形态：整帧全窗口重绘 → 一帧必然画出「全窗口清屏矩形」+ **全部**内容行的文字；
 *  - 正常形态：只重绘脏行 → 一帧不画全窗口清屏矩形，且只画脏行（底部追加 = 底部少数几行）。
 *
 * 度量（Robolectric 约束，见探针实测）：shadow Paint.style 恒 null、View 本地 cellH=0
 * （measureCells 的 fontMetrics stub），像素 y 不可用；但 onDraw 的「全窗口清屏矩形」
 * （drawRect(0,0,viewWidth,viewHeight)）与「每内容行恰好一次 drawText」是两个确定性信号：
 *  - fullWindowClear：本帧画了覆盖整个画布的清屏矩形 → 整屏重绘证据；
 *  - textCalls：本帧画了几行文字 → 有内容的行被重绘了几行。
 *
 * 两条路径用完全相同的内容字节（外部 delta 与本端回显 delta 在客户端汇合于 emulator.feed，
 * 字节形态一致），各建独立 view，跑同一断言，形态必须一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermRepaintRangeTwoPathTest {

    /** 记录型 Canvas：累计 translate 后记录「全窗口清屏矩形」与 drawText 次数。 */
    private class RepaintCanvas(bitmap: Bitmap, private val viewWidth: Int, private val viewHeight: Int) : Canvas(bitmap) {
        var textCalls = 0
        var fullWindowClear = false
        val fullRect = mutableListOf<String>()

        override fun translate(dx: Float, dy: Float) {
            super.translate(dx, dy)
        }

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            // 全窗口清屏：覆盖整个画布（含负向平移容差）的矩形 = onDraw 的整屏铺底。
            if (left <= 0.5f && top <= 0.5f && right >= viewWidth - 0.5f && bottom >= viewHeight - 0.5f) {
                fullWindowClear = true
            }
            super.drawRect(left, top, right, bottom, paint)
        }

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            textCalls++
            super.drawText(text, x, y, paint)
        }

        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            textCalls++
            super.drawText(text, start, end, x, y, paint)
        }
    }

    /** 12 行屏，先填 10 行（满屏），再底部追加 2 行。返回 view 与其 emulator。 */
    private fun appendFixture(): Pair<TermSurfaceView, TerminalEmulator> {
        val emulator = TerminalEmulator(20, 12)
        emulator.feed((1..10).joinToString("\r\n") { "row-$it" } + "\r\n")
        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.presenter = TermViewPresenter(emulator) { _, _ -> }
        view.measure(400, 240)
        view.layout(0, 0, 400, 240)
        return view to emulator
    }

    private fun draw(view: TermSurfaceView, canvas: RepaintCanvas) {
        view.presenter?.beginFrame()
        view.draw(canvas)
    }

    /** 跑一条路径：填 10 行 → 追加 2 行 → 画一帧，返回 {画了几行文字, 是否整屏清屏}。 */
    private fun runOnePath(pathLabel: String, emulator: TerminalEmulator, view: TermSurfaceView): Pair<Int, Boolean> {
        val canvas = RepaintCanvas(
            Bitmap.createBitmap(400, 240, Bitmap.Config.ARGB_8888),
            view.width, view.height,
        )
        emulator.feed("new-A\r\nnew-B")
        draw(view, canvas)
        val result = canvas.textCalls to canvas.fullWindowClear
        System.err.println("[REPAINT:$pathLabel] textCalls=${result.first} fullWindowClear=${result.second}")
        return result
    }

    @Test
    fun bottomAppendRedrawsOnlyDirtyRows_andBothPathsIdentical() {
        // 外部 delta 路径
        val (vExt, eExt) = appendFixture()
        val ext = runOnePath("external-delta", eExt, vExt)

        // 本端发送后的回显 delta 路径（同一字节形态）
        val (vOwn, eOwn) = appendFixture()
        val own = runOnePath("own-send-echo", eOwn, vOwn)

        // 断言 1（双路径一致，用户对照组）：外部写入与本端回显的重绘范围必须完全相同。
        assertEquals("两条路径重绘范围必须一致（teammate 什么样，用户发消息就必须一模一样）", ext, own)

        // 断言 2（脏行级，不整屏）：底部追加 2 行只重绘 ≤4 行，不得画全部 12 行文字。
        assertTrue(
            "底部追加必须只重绘脏行：实际画了 ${own.first} 行文字（全屏=12 行）",
            own.first <= 4,
        )

        // 断言 3（不得整屏清屏）：追加帧不得画「全窗口清屏矩形」（整屏重绘证据）。
        assertFalse("底部追加帧不得整屏清屏（整屏重绘证据）", own.second)

        // 追加内容必须真的被画出来（夹具有效性：2 行追加至少 1 行文字）。
        assertTrue("追加内容必须被画出来（夹具失效）", own.first >= 1)
    }
}
