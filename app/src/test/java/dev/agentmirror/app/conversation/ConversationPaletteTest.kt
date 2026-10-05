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

package dev.agentmirror.app.conversation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import dev.agentmirror.app.ui.theme.TermPalette
import dev.agentmirror.app.ui.theme.TermSchemeCatalog
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every text/surface pair of the conversation UI, for every bundled theme family and slot. */
class ConversationPaletteTest {

    @After
    fun tearDown() = TermPalette.resetBindingForTest()

    @Test
    fun everyThemeFamilyKeepsEveryTextPairAtOrAboveAA() {
        val failures = mutableListOf<String>()
        var checked = 0
        for (family in TermSchemeCatalog.families) {
            TermPalette.bindSelectionForTest(family.id, family.id)
            for (dark in listOf(false, true)) {
                val p = ConversationPalette.from(TermPalette.of(dark))
                val glassOverCanvas = p.glass.compositeOver(p.canvas)
                val pairs = listOf(
                    Triple("ink/canvas", p.ink, p.canvas) to ConversationPalette.BODY_TARGET,
                    Triple("inkSoft/canvas", p.inkSoft, p.canvas) to ConversationPalette.TEXT_MIN,
                    Triple("accentInk/canvas", p.accentInk, p.canvas) to ConversationPalette.TEXT_MIN,
                    Triple("onAccent/accent", p.onAccent, p.accent) to ConversationPalette.TEXT_MIN,
                    Triple("userInk/userBubble", p.userInk, p.userBubble) to ConversationPalette.BODY_TARGET,
                    Triple("ink/surface", p.ink, p.surface) to ConversationPalette.TEXT_MIN,
                    Triple("inkSoft/surface", p.inkSoft, p.surface) to ConversationPalette.TEXT_MIN,
                    Triple("codeInk/code", p.codeInk, p.code) to ConversationPalette.BODY_TARGET,
                    Triple("codeSoft/code", p.codeSoft, p.code) to ConversationPalette.TEXT_MIN,
                    Triple("success/surface", p.success, p.surface) to ConversationPalette.TEXT_MIN,
                    Triple("warning/surface", p.warning, p.surface) to ConversationPalette.TEXT_MIN,
                    Triple("danger/surface", p.danger, p.surface) to ConversationPalette.TEXT_MIN,
                    Triple("ink/glass", p.ink, glassOverCanvas) to ConversationPalette.TEXT_MIN,
                    Triple("inkSoft/glass", p.inkSoft, glassOverCanvas) to ConversationPalette.TEXT_MIN,
                    Triple("accent/canvas (UI)", p.accent, p.canvas) to 3.0,
                    Triple("ink/panel", p.ink, p.panel) to ConversationPalette.TEXT_MIN,
                    Triple("ink/panelEnd", p.ink, p.panelEnd) to ConversationPalette.TEXT_MIN,
                    Triple("inkSoft/panelEnd", p.inkSoft, p.panelEnd) to ConversationPalette.TEXT_MIN,
                    Triple("accentInk/panelEnd", p.accentInk, p.panelEnd) to 3.0,
                )
                for ((pair, min) in pairs) {
                    val (name, fg, bg) = pair
                    val ratio = TermPalette.contrast(fg.toArgb(), bg.toArgb())
                    checked++
                    if (ratio < min - 0.01) failures += "${family.id}/${if (dark) "dark" else "light"} $name=${"%.2f".format(ratio)} < $min"
                }
            }
        }
        assertTrue("checked=$checked\n" + failures.joinToString("\n"), failures.isEmpty())
        assertTrue(checked >= 30 * 2 * 19)
    }

    @Test
    fun surfacesStayDistinctFromTheCanvas() {
        for (family in TermSchemeCatalog.families) {
            TermPalette.bindSelectionForTest(family.id, family.id)
            for (dark in listOf(false, true)) {
                val p = ConversationPalette.from(TermPalette.of(dark))
                assertTrue("${family.id} bubble invisible", p.userBubble != p.canvas)
                assertTrue("${family.id} surface invisible", p.surface != p.canvas)
                assertTrue("${family.id} code invisible", p.code != p.surface)
                assertTrue(p.canvas.alpha == 1f && p.surface.alpha == 1f && p.userBubble.alpha == 1f && p.code.alpha == 1f)
                assertTrue(p.glass.alpha < 1f && p.ink != Color.Unspecified)
            }
        }
    }
}
