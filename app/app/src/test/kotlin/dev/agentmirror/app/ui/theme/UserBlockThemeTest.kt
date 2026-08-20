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

package dev.agentmirror.app.ui.theme

import dev.agentmirror.terminal.TerminalColor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 契约 089 §1：`userBlockBg` 从当前主题现算，封死 APP 常量逃逸。
 *
 * A-d1-theme 先验红：三个主题下 `Indexed(254)` 背景恒为 `0xFF10241F`。
 * A-d1-esc：派生集合从 scheme 独立算出（含 selection 与 defaultBg→defaultFg 抬档），
 * 不读 `Scheme.userBlockBg`——否则 254 路径恒真。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserBlockThemeTest {

    @After
    fun tearDown() {
        TermPalette.resetBindingForTest()
    }

    private val themeFamilies = listOf("vesper", "dracula", "catppuccin")

    @Test
    fun aD1ThemeIndexed254DiffersAcrossThreeThemesAndIsSchemeDerived() {
        val appBlock = 0xFF10241F.toInt()
        val got = linkedMapOf<String, Int>()
        for (id in themeFamilies) {
            TermPalette.bindSelectionForTest(id, id)
            val pal = TermPalette.of(true)
            val colors = TermSchemeCatalog.colors(pal.source)
            val mapped = TermPalette.colorFor(
                TerminalColor.Indexed(TermPalette.USER_MESSAGE_INDEX),
                background = true,
                dark = true,
            )
            val expected = expectedUserBlock(colors)
            assertEquals(
                "$id Indexed(254) 必须等于该主题 scheme 派生值 " +
                    "(selection=${hex(colors.selection)} lift=${hex(liftTowardFg(colors.background, colors.foreground))})",
                expected,
                mapped,
            )
            assertNotEquals("$id 仍钉在 APP userBlock 0xFF10241F", appBlock, mapped)
            got[id] = mapped
        }
        assertEquals(3, got.values.toSet().size)
        assertEquals(
            "三主题输出必须互不相同 got=$got",
            got.values.size,
            got.values.toSet().size,
        )
    }

    @Test
    fun aD1EscNoColorForEscapeFromSchemeDerivedSet() {
        val rgbSamples = listOf(
            Triple(0, 0, 0),
            Triple(255, 255, 255),
            Triple(255, 0, 0),
            Triple(0, 255, 0),
            Triple(0, 0, 255),
            Triple(128, 128, 128),
            Triple(255, 255, 200),
            Triple(16, 36, 31),
            Triple(255, 175, 0),
            Triple(10, 10, 10),
            Triple(240, 240, 240),
            Triple(220, 220, 220),
            Triple(32, 32, 32),
        )
        val leaks = mutableListOf<String>()
        for (id in themeFamilies) {
            TermPalette.bindSelectionForTest(id, id)
            for (dark in listOf(true, false)) {
                val pal = TermPalette.of(dark)
                val colors = TermSchemeCatalog.colors(pal.source)
                val allowed = derivedSet(colors)
                for (i in 0..255) {
                    for (bg in listOf(true, false)) {
                        val got = TermPalette.colorFor(TerminalColor.Indexed(i), bg, dark)
                        if (got !in allowed) {
                            leaks += "$id dark=$dark Indexed($i) bg=$bg -> ${hex(got)}"
                            if (leaks.size >= 16) break
                        }
                    }
                    if (leaks.size >= 16) break
                }
                for ((r, g, b) in rgbSamples) {
                    for (bg in listOf(true, false)) {
                        val got = TermPalette.colorFor(TerminalColor.Rgb(r, g, b), bg, dark)
                        if (got !in allowed) {
                            leaks += "$id dark=$dark Rgb($r,$g,$b) bg=$bg -> ${hex(got)}"
                            if (leaks.size >= 16) break
                        }
                    }
                    if (leaks.size >= 16) break
                }
                if (leaks.size >= 16) break
            }
            if (leaks.size >= 16) break
        }
        assertTrue("colorFor 逃出主题派生集合:\n${leaks.joinToString("\n")}", leaks.isEmpty())
    }

    /** 与 [TermPalette] 089 §1 同式：优先 selection（对 defaultFg 对比度达标），否则抬档。 */
    private fun expectedUserBlock(colors: TermSchemeColors): Int {
        val sel = colors.selection
        if (sel != null && contrast(sel, colors.foreground) >= 3.0) return sel
        return liftTowardFg(colors.background, colors.foreground)
    }

    private fun derivedSet(colors: TermSchemeColors): Set<Int> = buildSet {
        add(colors.background)
        add(colors.foreground)
        add(colors.cursor)
        add(liftTowardFg(colors.background, colors.foreground))
        colors.selection?.let { add(it) }
        colors.ansi.forEach { add(it) }
    }

    private fun liftTowardFg(bg: Int, fg: Int): Int {
        val t = 1.0 / 6.0
        fun ch(shift: Int): Int {
            val a = (bg shr shift) and 0xFF
            val b = (fg shr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun contrast(a: Int, b: Int): Double {
        val l1 = relativeLuma(a)
        val l2 = relativeLuma(b)
        val hi = max(l1, l2)
        val lo = min(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun relativeLuma(argb: Int): Double {
        fun lin(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = lin((argb shr 16) and 0xFF)
        val g = lin((argb shr 8) and 0xFF)
        val b = lin((argb and 0xFF))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun hex(v: Int?): String =
        if (v == null) "null" else "0x%08X".format(v)
}
