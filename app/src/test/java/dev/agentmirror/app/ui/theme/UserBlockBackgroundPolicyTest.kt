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

import android.app.Application
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class UserBlockBackgroundPolicyTest {
    @Test fun neutralVesperBorrowsItsAmberAccentInsteadOfDeadGray() {
        val paper = 0xFF101010.toInt()
        val actual = block(0xFF101010, 0xFFFFFFFF, 0xFF988049)
        assertNotEquals("不再是统一的死灰", 0xFF2D2D2D.toInt(), actual)
        assertTrue(chroma(actual) >= 0.012)
        assertHueNear(0xFF988049.toInt(), actual)
        assertTrue(contrastRatio(actual, paper) >= TermPalette.BLOCK_RATIO_DARK)
    }

    @Test fun chromaticPaperKeepsItsOwnHue() {
        val themes = listOf(
            Triple(0xFF002B36, 0xFF839496, 0xFF073642), // Solarized Dark
            Triple(0xFFFDF6E3, 0xFF657B83, 0xFFEEE8D5), // Solarized Light
            Triple(0xFF1A1B26, 0xFFC0CAF5, 0xFF283457), // Tokyo Night
            Triple(0xFF282A36, 0xFFF8F8F2, 0xFF44475A), // Dracula
        )
        for ((bg, fg, selection) in themes) {
            val actual = block(bg, fg, selection)
            assertHueNear(bg.toInt(), actual)
            assertTrue("bg=$bg", chroma(actual) + 1e-3 >= chroma(bg.toInt()))
        }
    }

    @Test fun neutralPaperTakesItsWarmthFromThemeText() {
        assertHueNear(0xFFEBDBB2.toInt(), block(0xFF282828, 0xFFEBDBB2, 0xFF665C54)) // Gruvbox Dark
        assertHueNear(0xFFCECDC3.toInt(), block(0xFF100F0F, 0xFFCECDC3, 0xFF403E3C)) // Flexoki Dark
    }

    @Test fun missingTransparentAndAchromaticAccentsFallThroughToTheNextAccent() {
        val blue = 0xFF0031A9
        val expected = block(0xFFFFFFFF, 0xFF000000, null, blue)
        assertEquals(expected, block(0xFFFFFFFF, 0xFF000000, 0x00FF0000, blue))
        assertEquals(expected, block(0xFFFFFFFF, 0xFF000000, 0xFFBDBDBD, blue))
        assertHueNear(blue.toInt(), expected)
    }

    @Test fun translucentAccentIsCompositedOntoTheActualTerminalBackground() {
        assertEquals(block(0xFFFFFFFF, 0xFF000000, 0xFF7FBF7F), block(0xFFFFFFFF, 0xFF000000, 0x80008000))
    }

    @Test fun paperAtTheEndOfItsRangeStepsTheOtherWayInsteadOfCollapsing() {
        for (paper in listOf(0xFFFFFFFF, 0xFF000000)) {
            val actual = block(paper, paper, null)
            assertNotEquals(paper.toInt(), actual)
            assertTrue(contrastRatio(actual, paper.toInt()) >= TermPalette.BLOCK_RATIO_LIGHT)
        }
    }

    @Test fun lowContrastThemeTextIsStrengthenedAlongItsOwnHue() {
        val paper = 0xFFFDF6E3.toInt()
        val foreground = 0xFF657B83.toInt()
        val bubble = TermPalette.userBlockBackground(paper, foreground, 0xFFEEE8D5.toInt())
        val text = TermPalette.userBlockForeground(foreground, bubble)
        assertTrue(contrastRatio(foreground, bubble) < TermPalette.BLOCK_TEXT_CONTRAST_MIN)
        assertTrue(contrastRatio(text, bubble) >= TermPalette.BLOCK_TEXT_CONTRAST_MIN)
        assertTrue("浅底上只加深", TermPalette.toOkLab(text).L < TermPalette.toOkLab(foreground).L)
        assertHueNear(foreground, text)
    }

    @Test fun invariantsHoldAcrossEdgeColorsAndAccents() {
        val colors = listOf(0xFF000000, 0xFFFFFFFF, 0xFF101010, 0xFF777777, 0xFF808080,
            0xFF888888, 0xFF282A36, 0xFFF8F8F2, 0xFF002B36, 0xFFFDF6E3,
            0xFFFF0000, 0xFF00FF00, 0xFF0000FF)
        val selections = listOf<Long?>(null, 0x00000000, 0x00FF0000, 0x80808080,
            0xFF000000, 0xFFFFFFFF, 0xFF44475A, 0xFFF5E0DC)
        for (bg in colors) for (fg in colors) for (selection in selections) {
            val label = "bg=$bg fg=$fg selection=$selection"
            val actual = block(bg, fg, selection)
            assertEquals(label, 255, actual ushr 24)
            assertNotEquals(label, bg.toInt(), actual)
            val target = if (luminance(bg.toInt()) > luminance(fg.toInt())) {
                TermPalette.BLOCK_RATIO_LIGHT
            } else {
                TermPalette.BLOCK_RATIO_DARK
            }
            assertTrue(label, contrastRatio(actual, bg.toInt()) >= target)
            val text = TermPalette.userBlockForeground(fg.toInt(), actual)
            assertTrue(label, contrastRatio(text, actual) >= TermPalette.BLOCK_TEXT_CONTRAST_MIN)
            if (contrastRatio(fg.toInt(), actual) >= TermPalette.BLOCK_TEXT_CONTRAST_MIN) {
                assertEquals("可读的主题字色原样保留 $label", fg.toInt(), text)
            }
        }
    }

    private fun block(bg: Long, fg: Long, vararg accents: Long?): Int =
        TermPalette.userBlockBackground(bg.toInt(), fg.toInt(), *accents.map { it?.toInt() }.toTypedArray())

    private fun chroma(argb: Int): Double = TermPalette.toOkLab(argb).let { hypot(it.a, it.b) }

    private fun assertHueNear(expected: Int, actual: Int) {
        fun hue(argb: Int) = TermPalette.toOkLab(argb).let { Math.toDegrees(atan2(it.b, it.a)) }
        val delta = abs(((hue(actual) - hue(expected)) % 360 + 540) % 360 - 180)
        assertTrue("hue Δ=$delta expected=${Integer.toHexString(expected)} actual=${Integer.toHexString(actual)}", delta <= 15)
    }

    /** 与 [contrastRatio] 同一独立 WCAG 公式；只用来判浅/深纸。 */
    private fun luminance(color: Int): Double = contrastRatio(color, 0xFF000000.toInt())
}
