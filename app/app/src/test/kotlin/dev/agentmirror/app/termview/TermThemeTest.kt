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
 * 078 §2 裁定 B：终端默认底/字走 APP 主题，不取主机。
 *
 * 钉的是数据来源 [TermPalette] + 浅/深两套相对关系（整体底 vs 用户消息块 48;5;254），
 * 不是「现在碰巧是浅色」——把两套都写死成浅色会让切换断言红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermThemeTest {

    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        data class Rect(val color: Int)

        val rects = mutableListOf<Rect>()

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            rects += Rect(paint.color)
            super.drawRect(left, top, right, bottom, paint)
        }
    }

    private fun render(dark: Boolean): Pair<TermSurfaceView, RecordingCanvas> {
        val emulator = TerminalEmulator(24, 6)
        emulator.feed("plain\n\u001b[48;5;254;38;5;16muser msg\u001b[0m")
        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.nightOverride = dark
        view.presenter = TermViewPresenter(emulator) { _, _ -> }
        val bitmap = Bitmap.createBitmap(480, 160, Bitmap.Config.ARGB_8888)
        val canvas = RecordingCanvas(bitmap)
        view.draw(canvas)
        bitmap.recycle()
        return view to canvas
    }

    @Test
    fun lightCanvasComesFromAppThemePaletteAndMessageBlockIsDarker() {
        val (view, canvas) = render(dark = false)
        val pal = TermPalette.of(false)
        assertEquals("数据来源必须是 TermPalette.Light，不许另写浅色字面量", TermPalette.SOURCE, pal.source)
        assertEquals(TermPalette.Light.defaultBg, pal.defaultBg)

        val canvasBg = canvas.rects.firstOrNull { it.color == pal.defaultBg }
        assertTrue("浅色模式清屏没用 TermPalette.Light.defaultBg", canvasBg != null)

        val msg = canvas.rects.firstOrNull { it.color == TermPalette.userMessageBg }
        assertTrue("夹具失效：没画出 48;5;254 用户消息块", msg != null)
        assertTrue(
            "浅色：用户消息块必须比整体底更深（白底开灰块）。canvas=${TermPalette.luma(pal.defaultBg)} msg=${TermPalette.luma(TermPalette.userMessageBg)}",
            TermPalette.luma(TermPalette.userMessageBg) < TermPalette.luma(pal.defaultBg),
        )
        assertEquals(TermPalette.token(false), view.contentDescription)
        assertTrue(view.contentDescription.contains("source=${TermPalette.SOURCE}"))
    }

    @Test
    fun darkCanvasInvertsMessageBlockRelationship() {
        val (view, canvas) = render(dark = true)
        val pal = TermPalette.of(true)
        assertEquals(TermPalette.Dark.defaultBg, pal.defaultBg)

        val canvasBg = canvas.rects.firstOrNull { it.color == pal.defaultBg }
        assertTrue("深色模式清屏没用 TermPalette.Dark.defaultBg", canvasBg != null)

        val msg = canvas.rects.firstOrNull { it.color == TermPalette.userMessageBg }
        assertTrue("夹具失效：没画出 48;5;254 用户消息块", msg != null)
        assertNotEquals("消息块底色必须可辨，不能与整体底同色", pal.defaultBg, TermPalette.userMessageBg)
        assertTrue(
            "深色：用户消息块必须比整体底更浅。canvas=${TermPalette.luma(pal.defaultBg)} msg=${TermPalette.luma(TermPalette.userMessageBg)}",
            TermPalette.luma(TermPalette.userMessageBg) > TermPalette.luma(pal.defaultBg),
        )
        assertEquals(TermPalette.token(true), view.contentDescription)
    }

    @Test
    fun switchingNightOverrideChangesCanvasBackgroundFromPalette() {
        val emulator = TerminalEmulator(12, 4)
        emulator.feed("x")
        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.presenter = TermViewPresenter(emulator) { _, _ -> }

        fun bg(dark: Boolean): Int {
            view.nightOverride = dark
            val bitmap = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
            val canvas = RecordingCanvas(bitmap)
            view.draw(canvas)
            bitmap.recycle()
            val expected = TermPalette.of(dark).defaultBg
            val hit = canvas.rects.firstOrNull { it.color == expected }
            assertTrue("night=$dark 清屏色不是 TermPalette.of($dark).defaultBg", hit != null)
            return expected
        }

        val lightBg = bg(false)
        val darkBg = bg(true)
        assertNotEquals("切换深浅色后终端背景必须真的变；写死同一套浅色会在这里红", lightBg, darkBg)
        assertEquals(TermPalette.Light.defaultBg, lightBg)
        assertEquals(TermPalette.Dark.defaultBg, darkBg)
    }
}
