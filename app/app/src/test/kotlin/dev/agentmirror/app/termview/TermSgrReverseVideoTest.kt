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
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * SGR 7 反显红测（feat-terminal-theme-selection ④）：:terminal 模块 Style.kt 早就正确
 * 解析 SGR 7/27 写入 [dev.agentmirror.terminal.TextStyle.inverse]，但渲染层
 * TermSurfaceView 取色时全程不读这个字段——反显信息在解析→渲染边界被静默丢弃
 * （隔离 pty 实录 cursor-agent 真实字节 `\x1b[2m\x1b[7mP\x1b[27m` 证实这不是假设，
 * 是它真在用）。断言全部锚定"画笔实际取到的颜色"，不锚定 style.inverse 是否被读到——
 * 即便实现换种方式做反显，红测依然有效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermSgrReverseVideoTest {

    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        data class Rect(val left: Float, val right: Float, val color: Int)
        data class Text(val text: String, val x: Float, val color: Int)

        val rects = mutableListOf<Rect>()
        val texts = mutableListOf<Text>()

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            rects += Rect(left, right, paint.color)
            super.drawRect(left, top, right, bottom, paint)
        }

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            texts += Text(text, x, paint.color)
            super.drawText(text, x, y, paint)
        }

        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            texts += Text(text.substring(start, end), x, paint.color)
            super.drawText(text, start, end, x, y, paint)
        }
    }

    private fun render(feed: String): RecordingCanvas {
        val emulator = TerminalEmulator(20, 5)
        emulator.feed(feed)
        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.nightOverride = true
        view.presenter = TermViewPresenter(emulator) { _, _ -> }
        val bitmap = Bitmap.createBitmap(400, 120, Bitmap.Config.ARGB_8888)
        val canvas = RecordingCanvas(bitmap)
        view.draw(canvas)
        bitmap.recycle()
        return canvas
    }

    private val ansiRed = TermPalette.Dark.ansi16[1]!!
    private val ansiGreen = TermPalette.Dark.ansi16[2]!!

    // 反显钉深色套；色值来自 TerminalSpec → TermPalette.Dark，不在本文件再写一份字面量。
    private val defaultFg = TermPalette.Dark.defaultFg
    private val defaultBg = TermPalette.Dark.defaultBg

    /** 判据 1：显式双色 + 反显——背景矩形须取到 fg 色，文字须取到 bg 色。 */
    @Test
    fun explicitColorsWithInverseSwapBackgroundAndForeground() {
        val canvas = render("[31;42;7mX[0m")

        val bgRect = canvas.rects.firstOrNull { it.color == ansiRed }
        assertTrue("反显未生效：背景矩形没有取到 fg 色(红)", bgRect != null)

        val fgText = canvas.texts.firstOrNull { it.text.contains("X") }
        assertTrue("夹具失效：未画出 X 文本", fgText != null)
        assertEquals("反显未生效：文字没有取到 bg 色(绿)", ansiGreen, fgText!!.color)
    }

    /** 判据 2：裸反显、无显式 fg/bg——最贴 cursor-agent 真实字节的场景。
     *  背景矩形须取到 themeFgArgb()（近白），文字须取到 themeBgArgb()（深色）。 */
    @Test
    fun bareInverseWithNoExplicitColorSwapsThemeDefaults() {
        val canvas = render("[7mX[0m")

        val bgRect = canvas.rects.firstOrNull { it.color == defaultFg }
        assertTrue("裸反显未生效：背景矩形没有取到默认前景色", bgRect != null)

        val fgText = canvas.texts.firstOrNull { it.text.contains("X") }
        assertTrue("夹具失效：未画出 X 文本", fgText != null)
        assertEquals("裸反显未生效：文字没有取到默认背景色", defaultBg, fgText!!.color)
    }

    /** 判据 3：作用域正确性——反显必须逐格生效，不能是"翻一次全局都翻"的开关。
     *  A 在反显区、B 在反显区之外，两者取到的前景色不能相同。 */
    @Test
    fun inverseScopeDoesNotLeakPastSgr27Reset() {
        val canvas = render("[7mA[27mB")

        val aText = canvas.texts.firstOrNull { it.text.contains("A") }
        val bText = canvas.texts.firstOrNull { it.text.contains("B") }
        assertTrue("夹具失效：未画出 A 文本", aText != null)
        assertTrue("夹具失效：未画出 B 文本", bText != null)

        assertNotEquals(
            "反显作用域泄漏：SGR 27 复位后 B 仍取到反显色（疑似全局开关式实现）",
            aText!!.color,
            bText!!.color,
        )
        // 精确锚定，防止两边都巧合地取错同一个值仍被 assertNotEquals 放过。
        assertEquals("A 未取到反显后的前景色（应为默认背景色）", defaultBg, aText.color)
        assertEquals("B 未取到复位后的正常前景色（应为默认前景色）", defaultFg, bText.color)
    }
}
