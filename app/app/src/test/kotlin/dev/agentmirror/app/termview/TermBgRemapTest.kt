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
import dev.agentmirror.app.ui.theme.TermPalette
import dev.agentmirror.terminal.TerminalColor
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
 * 083 §2 显式背景重映射（t.bg / A-bg-map）。
 *
 * 钉的是相对关系与落点常量，不是「背景碰巧是浅的」：
 * - grok 整屏黑（40 / 48;5;0 / 近黑真彩）→ TerminalSpec.background，⛔ 不是 ansi[0] 暗格
 * - Claude Code 用户块（48;5;254）→ userBlockBackground，浅底上比纸色更深、不是纯白
 * - 深色关系反过来；nightOverride 切换后真的变
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermBgRemapTest {

    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        data class Rect(val color: Int)
        val rects = mutableListOf<Rect>()
        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            rects += Rect(paint.color)
            super.drawRect(left, top, right, bottom, paint)
        }
    }

    private fun render(feed: String, dark: Boolean): RecordingCanvas {
        val emulator = TerminalEmulator(24, 6)
        emulator.feed(feed)
        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.nightOverride = dark
        view.presenter = TermViewPresenter(emulator) { _, _ -> }
        val bitmap = Bitmap.createBitmap(480, 160, Bitmap.Config.ARGB_8888)
        val canvas = RecordingCanvas(bitmap)
        view.draw(canvas)
        bitmap.recycle()
        return canvas
    }

    @Test
    fun lightGrokScreenBlackIndexed0MapsToPaperNotAnsi0() {
        val pal = TermPalette.Light
        val ansi0 = pal.ansi16[0]!!
        val mapped = TermPalette.colorFor(TerminalColor.Indexed(0), background = true, dark = false)
        assertEquals(
            "40m/index0 整屏应落 background，旧路径走 ansi[0]=0x${hex(ansi0)} luma=${TermPalette.luma(ansi0)}",
            pal.defaultBg,
            mapped,
        )
        assertNotEquals("整屏不得等于 ANSI 0 暗格", ansi0, mapped)
        assertTrue(
            "浅底 grok 整屏 luma=${TermPalette.luma(mapped)} 必须高于暗格 luma=${TermPalette.luma(ansi0)}",
            TermPalette.luma(mapped) > TermPalette.luma(ansi0),
        )

        val canvas = render("\u001b[40mXXXX\u001b[0m", dark = false)
        assertTrue("夹具：应画出纸色格 defaultBg=0x${hex(pal.defaultBg)}", canvas.rects.any { it.color == pal.defaultBg })
        assertTrue("40m 不得画出 ansi0 暗格", canvas.rects.none { it.color == ansi0 })
    }

    @Test
    fun lightGrokCubeBlackAndRgbBlackMapToPaper() {
        val pal = TermPalette.Light
        val i16 = TermPalette.colorFor(TerminalColor.Indexed(16), background = true, dark = false)
        val rgb = TermPalette.colorFor(TerminalColor.Rgb(0, 0, 0), background = true, dark = false)
        assertEquals("48;5;16 整屏黑 → paper", pal.defaultBg, i16)
        assertEquals("48;2;0;0;0 真彩黑 → paper（亮度守卫）", pal.defaultBg, rgb)
        val canvas = render("\u001b[48;2;0;0;0mBBBB\u001b[0m", dark = false)
        assertTrue(canvas.rects.any { it.color == pal.defaultBg })
        assertTrue("真彩黑不得原样 0xFF000000", canvas.rects.none { it.color == 0xFF000000.toInt() })
    }

    @Test
    fun lightClaudeUserBlockIsDarkerGrayNotWhite() {
        val pal = TermPalette.Light
        val block = TermPalette.colorFor(TerminalColor.Indexed(254), background = true, dark = false)
        val paper = pal.defaultBg
        val white = 0xFFFFFFFF.toInt()
        assertEquals(pal.userBlockBg, block)
        assertNotEquals("用户块不得与纸色同色（刷成同色会骗「浅底」判据）", paper, block)
        assertNotEquals("用户块不得是纯白", white, block)
        assertTrue(
            "浅色：块 luma=${TermPalette.luma(block)} 必须 < 纸 luma=${TermPalette.luma(paper)}",
            TermPalette.luma(block) < TermPalette.luma(paper),
        )

        val canvas = render("plain\n\u001b[48;5;254;38;5;16muser msg\u001b[0m", dark = false)
        assertTrue(canvas.rects.any { it.color == paper })
        assertTrue(canvas.rects.any { it.color == block })
    }

    @Test
    fun lightTruecolorWhiteMessageMapsToUserBlockNotRawWhite() {
        val pal = TermPalette.Light
        val mapped = TermPalette.colorFor(TerminalColor.Rgb(255, 255, 255), background = true, dark = false)
        assertEquals("48;2;255;255;255 近白 → userBlock（亮度守卫）", pal.userBlockBg, mapped)
        assertNotEquals(0xFFFFFFFF.toInt(), mapped)
        val canvas = render("\u001b[48;2;255;255;255mWWWW\u001b[0m", dark = false)
        assertTrue(canvas.rects.any { it.color == pal.userBlockBg })
        assertTrue(canvas.rects.none { it.color == 0xFFFFFFFF.toInt() })
    }

    @Test
    fun darkThemeInvertsPaperAndUserBlockRelationship() {
        val pal = TermPalette.Dark
        val paper = TermPalette.colorFor(TerminalColor.Default, background = true, dark = true)
        val grok = TermPalette.colorFor(TerminalColor.Indexed(0), background = true, dark = true)
        val block = TermPalette.colorFor(TerminalColor.Indexed(254), background = true, dark = true)
        assertEquals(pal.defaultBg, paper)
        assertEquals("深色整屏黑仍落 paper，不是 ansi0", pal.defaultBg, grok)
        assertEquals(pal.userBlockBg, block)
        assertNotEquals(paper, block)
        assertTrue(
            "深色：块 luma=${TermPalette.luma(block)} 必须 > 纸 luma=${TermPalette.luma(paper)}",
            TermPalette.luma(block) > TermPalette.luma(paper),
        )
        val canvas = render("plain\n\u001b[48;5;254muser\u001b[0m", dark = true)
        assertTrue(canvas.rects.any { it.color == paper })
        assertTrue(canvas.rects.any { it.color == block })
    }

    @Test
    fun switchingNightChangesPaperAndUserBlockFromPalette() {
        val lightPaper = TermPalette.colorFor(TerminalColor.Indexed(0), background = true, dark = false)
        val darkPaper = TermPalette.colorFor(TerminalColor.Indexed(0), background = true, dark = true)
        val lightBlock = TermPalette.colorFor(TerminalColor.Indexed(254), background = true, dark = false)
        val darkBlock = TermPalette.colorFor(TerminalColor.Indexed(254), background = true, dark = true)
        assertNotEquals("切换后纸色必须变", lightPaper, darkPaper)
        assertNotEquals("切换后用户块必须变", lightBlock, darkBlock)
        assertEquals(TermPalette.Light.defaultBg, lightPaper)
        assertEquals(TermPalette.Dark.defaultBg, darkPaper)

        val emulator = TerminalEmulator(12, 4)
        emulator.feed("\u001b[40mZ\u001b[0m")
        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.presenter = TermViewPresenter(emulator) { _, _ -> }
        fun painted(dark: Boolean): Int {
            view.nightOverride = dark
            val bitmap = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
            val canvas = RecordingCanvas(bitmap)
            view.draw(canvas)
            bitmap.recycle()
            val expect = TermPalette.of(dark).defaultBg
            assertTrue("night=$dark 40m 未画到 paper 0x${hex(expect)}", canvas.rects.any { it.color == expect })
            return expect
        }
        assertNotEquals(painted(false), painted(true))
    }

    @Test
    fun localAnsi8CellDoesNotCollapseToPaper() {
        val pal = TermPalette.Light
        val local = TermPalette.colorFor(TerminalColor.Indexed(8), background = true, dark = false)
        assertEquals("局部暗格走 ansi[8]，不是整屏纸色", pal.ansi16[8], local)
        assertNotEquals(pal.defaultBg, local)
    }

    private fun hex(argb: Int): String = (argb.toLong() and 0xffffffffL).toString(16)
}
