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
    @Test fun readableUpstreamSelectionIsPreservedExactly() {
        assertEquals(0xFF44475A.toInt(), block(0xFF282A36, 0xFFF8F8F2, 0xFF44475A))
        assertEquals(0xFFBFDBFE.toInt(), block(0xFFF7F7F7, 0xFF000000, 0xFFBFDBFE))
    }

    @Test fun unreadableVesperSelectionUsesThemeTintRatherThanAnInvertedTextPair() {
        assertEquals(0xFF2D2D2D.toInt(), block(0xFF101010, 0xFFFFFFFF, 0xFF988049))
    }

    @Test fun missingEqualAndFullyTransparentSelectionUseTheSameFallback() {
        val expected = block(0xFF101010, 0xFFFFFFFF, null)
        assertEquals(0xFF2D2D2D.toInt(), expected)
        assertEquals(expected, block(0xFF101010, 0xFFFFFFFF, 0xFF101010))
        assertEquals(expected, block(0xFF101010, 0xFFFFFFFF, 0x00FF0000))
        assertEquals(0xFFE0E0E0.toInt(), block(0xFFFEFEFE, 0xFF000000, null))
    }

    @Test fun translucentSelectionIsCompositedOntoTheActualTerminalBackground() {
        assertEquals(0xFF404040.toInt(), block(0xFF000000, 0xFFFFFFFF, 0x80808080))
        assertEquals(0xFF7FBF7F.toInt(), block(0xFFFFFFFF, 0xFF000000, 0x80008000))
    }

    @Test fun guardDoesNotMakeAnAlreadyLowContrastThemeWorse() {
        val paper = 0xFF777777.toInt()
        val foreground = 0xFF888888.toInt()
        val actual = TermPalette.userBlockBackground(paper, foreground, 0xFF888888.toInt())
        assertEquals(paper, actual)
        assertTrue(contrastRatio(foreground, actual) >= contrastRatio(foreground, paper))
    }

    @Test fun fallbackTapersTintWhenTwelvePercentWouldBreakReadability() {
        val paper = 0xFF101010.toInt()
        val foreground = 0xFF808080.toInt()
        val actual = TermPalette.userBlockBackground(paper, foreground, null)
        assertNotEquals(0xFF1D1D1D.toInt(), actual)
        assertTrue(contrastRatio(foreground, actual) >= minOf(4.5, contrastRatio(foreground, paper)))
    }

    @Test fun opaqueAndContrastInvariantsHoldAcrossEdgeColorsAndSelections() {
        val colors = listOf(0xFF000000, 0xFFFFFFFF, 0xFF101010, 0xFF777777, 0xFF808080,
            0xFF888888, 0xFF282A36, 0xFFF8F8F2, 0xFF002B36, 0xFFFDF6E3,
            0xFFFF0000, 0xFF00FF00, 0xFF0000FF)
        val selections = listOf<Long?>(null, 0x00000000, 0x00FF0000, 0x80808080,
            0xFF000000, 0xFFFFFFFF, 0xFF44475A, 0xFFF5E0DC)
        for (bg in colors) for (fg in colors) for (selection in selections) {
            val actual = block(bg, fg, selection)
            assertEquals(255, actual ushr 24)
            val floor = minOf(4.5, contrastRatio(fg.toInt(), bg.toInt()))
            assertTrue("bg=$bg fg=$fg selection=$selection", contrastRatio(fg.toInt(), actual) + 1e-9 >= floor)
        }
    }

    private fun block(bg: Long, fg: Long, selection: Long?): Int =
        TermPalette.userBlockBackground(bg.toInt(), fg.toInt(), selection?.toInt())
}
