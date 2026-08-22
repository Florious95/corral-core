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
import dev.agentmirror.app.ui.theme.TermSchemeCatalog
import dev.agentmirror.terminal.TerminalColor
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.hypot

/**
 * 契约 085 §1.5：256 / 真彩必须落在当前主题色板；换主题集合不等。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermRemapTest {

    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val colors = mutableSetOf<Int>()
        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            colors += paint.color
            super.drawRect(left, top, right, bottom, paint)
        }
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            colors += paint.color
            super.drawText(text, x, y, paint)
        }
        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            colors += paint.color
            super.drawText(text, start, end, x, y, paint)
        }
    }

    @After
    fun tearDown() {
        TermPalette.resetBindingForTest()
    }

    private val probes = listOf(
        TerminalColor.Default to true,
        TerminalColor.Default to false,
        TerminalColor.Indexed(1) to false,
        TerminalColor.Indexed(2) to false,
        TerminalColor.Indexed(4) to false,
        TerminalColor.Indexed(9) to false,
        TerminalColor.Indexed(196) to false,
        TerminalColor.Indexed(46) to false,
        TerminalColor.Indexed(21) to false,
        TerminalColor.Indexed(244) to false,
        TerminalColor.Rgb(255, 0, 0) to false,
        TerminalColor.Rgb(0, 0, 255) to false,
        TerminalColor.Rgb(255, 175, 0) to false,
        TerminalColor.Indexed(0) to true,
        TerminalColor.Indexed(16) to true,
        TerminalColor.Indexed(254) to true,
        TerminalColor.Rgb(0, 0, 0) to true,
        TerminalColor.Rgb(255, 255, 255) to true,
    )

    private val fixture = buildString {
        append("plain\n")
        append("\u001b[31mA16R\u001b[32mA16G\u001b[34mA16B\u001b[91mA16BR\u001b[0m\n")
        append("\u001b[38;5;196mI196\u001b[38;5;46mI46\u001b[38;5;21mI21\u001b[38;5;244mI244\u001b[0m\n")
        append("\u001b[38;2;255;0;0mTcred\u001b[38;2;0;0;255mTcblu\u001b[38;2;255;175;0mTcora\u001b[0m\n")
        append("\u001b[48;5;0mB0..\u001b[48;5;16mB16.\u001b[48;5;254mB254\u001b[0m\n")
        append("\u001b[48;2;0;0;0mTrbgk\u001b[48;2;255;255;255mTrbgw\u001b[0m\n")
    }

    @Test
    fun probesStayInsidePaletteAndVesperDiffersFromDracula() {
        TermPalette.bindSelectionForTest("vesper", "vesper")
        val sVesper = probeSet(dark = true)
        val rVesper = TermPalette.of(true).slotArgbSet()
        assertSubset("Vesper", sVesper, rVesper)
        assertEquals(
            TermPalette.of(true).defaultBg,
            TermPalette.colorFor(TerminalColor.Indexed(0), background = true, dark = true),
        )
        assertEquals(
            TermPalette.of(true).userBlockBg,
            TermPalette.colorFor(TerminalColor.Indexed(254), background = true, dark = true),
        )
        assertEquals(
            TermPalette.of(true).defaultBg,
            TermPalette.colorFor(TerminalColor.Rgb(0, 0, 0), background = true, dark = true),
        )
        assertNotEquals(
            0xFFFF0000.toInt(),
            TermPalette.colorFor(TerminalColor.Rgb(255, 0, 0), background = false, dark = true),
        )
        assertEquals(
            TermPalette.colorFor(TerminalColor.Indexed(196), background = false, dark = true),
            TermPalette.colorFor(TerminalColor.Rgb(255, 0, 0), background = false, dark = true),
        )

        TermPalette.bindSelectionForTest("dracula", "dracula")
        val sDracula = probeSet(dark = true)
        val rDracula = TermPalette.of(true).slotArgbSet()
        assertSubset("Dracula", sDracula, rDracula)
        assertNotEquals(sVesper, sDracula)
        assertNotEquals(TermPalette.of(true).defaultBg, 0xFF101010.toInt())
    }

    @Test
    fun scaleLumaLeakIsClosedOnLightNearWhiteTint() {
        TermPalette.bindSelectionForTest("follow-system", "vesper")
        val pal = TermPalette.of(false)
        val mapped = TermPalette.colorFor(TerminalColor.Rgb(255, 255, 200), background = true, dark = false)
        assertEquals(pal.userBlockBg, mapped)
        assertTrue(mapped in pal.slotArgbSet())
    }

    @Test
    fun oklabGoldWithinTolerance() {
        val lab = TermPalette.toOkLab(0xFFFF0000.toInt())
        assertTrue(kotlin.math.abs(lab.L - 0.62796) < 1e-3)
        assertTrue(kotlin.math.abs(lab.a - 0.22486) < 1e-3)
        assertTrue(kotlin.math.abs(lab.b - 0.12585) < 1e-3)
    }

    @Test
    fun ansi16ChromaGateHasEnoughColorSlots() {
        val failed = mutableListOf<String>()
        for ((name, colors) in TermSchemeCatalog.colorsBySourceFile) {
            var good = 0
            for (i in 1..6) {
                val lab = TermPalette.toOkLab(colors.ansi[i])
                if (hypot(lab.a, lab.b) >= 0.06) good++
            }
            if (good < 4) failed += name
        }
        // 投影规则 §7.1：失败 halt 那一个主题，不准全局改 0.08/0.06。
        assertEquals(listOf("Melange Dark.itermcolors"), failed)
    }

    @Test
    fun lightFamiliesKeepUserBlockDarkerThanPaper() {
        val failed = mutableListOf<String>()
        for (family in TermSchemeCatalog.families) {
            TermPalette.bindSelectionForTest(family.id, family.id)
            val pal = TermPalette.of(false)
            val paperY = TermPalette.luma(pal.defaultBg)
            val blockY = TermPalette.luma(pal.userBlockBg)
            if (paperY >= 128 && blockY >= paperY) {
                failed += family.id
            }
        }
        // 方案 §3.3：浅纸 vs APP userBlock 翻了就 halt，不准改 userBlock 去凑。
        assertEquals(
            listOf(
                "catppuccin", "tokyo-night", "gruvbox", "nord",
                "kanagawa", "everforest", "iceberg", "zenbones",
            ).sorted(),
            failed.sorted(),
        )
    }

    @Test
    fun drawFixtureColorsStayInsidePalette() {
        TermPalette.bindSelectionForTest("vesper", "vesper")
        val pal = TermPalette.of(true)
        val emulator = TerminalEmulator(80, 12)
        emulator.feed(fixture)
        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.nightOverride = true
        view.presenter = TermViewPresenter(emulator) { _, _ -> }
        val bitmap = Bitmap.createBitmap(800, 320, Bitmap.Config.ARGB_8888)
        val canvas = RecordingCanvas(bitmap)
        view.draw(canvas)
        bitmap.recycle()
        val allowed = pal.slotArgbSet() + setOfNotNull(pal.cursor, pal.selection)
        val leaked = canvas.colors.filter { it !in allowed && (it ushr 24) == 0xFF }
        assertTrue("draw leaked ${leaked.map { hex(it) }} allowed=${allowed.map { hex(it) }}", leaked.isEmpty())
    }

    private fun hex(argb: Int): String = (argb.toLong() and 0xffffffffL).toString(16)

    private fun probeSet(dark: Boolean): Set<Int> =
        probes.map { (c, bg) -> TermPalette.colorFor(c, bg, dark) }.toSet()

    private fun assertSubset(label: String, got: Set<Int>, allowed: Set<Int>) {
        val extra = got - allowed
        assertTrue("$label leaked $extra not in $allowed", extra.isEmpty())
    }
}
