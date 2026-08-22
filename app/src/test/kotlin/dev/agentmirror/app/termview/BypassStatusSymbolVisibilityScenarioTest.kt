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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowPaint

/** D-35 user scenario: Claude Code's two U+23F5 status symbols must render as real triangles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [BypassStatusSymbolVisibilityScenarioTest.GlyphCoveragePaintShadow::class])
class BypassStatusSymbolVisibilityScenarioTest {

    /** Encodes the isolated device probe: U+23F5 is missing while its U+25B8 equivalent exists. */
    @Implements(Paint::class)
    class GlyphCoveragePaintShadow : ShadowPaint() {
        @Implementation
        fun hasGlyph(text: String): Boolean = text.codePoints().allMatch { cp ->
            cp in 0x20..0x7E || cp == 0x25B8
        }
    }

    private data class DrawnCodepoint(val codepoint: Int, val hasGlyph: Boolean)

    /** Records the observable text submitted to Canvas and whether its selected paint can draw it. */
    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val drawnText = StringBuilder()
        val codepoints = mutableListOf<DrawnCodepoint>()

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            record(text, paint)
            super.drawText(text, x, y, paint)
        }

        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            record(text.substring(start, end), paint)
            super.drawText(text, start, end, x, y, paint)
        }

        private fun record(text: String, paint: Paint) {
            drawnText.append(text)
            text.codePoints().forEach { cp ->
                if (!Character.isWhitespace(cp)) {
                    val glyph = String(Character.toChars(cp))
                    codepoints += DrawnCodepoint(cp, paint.hasGlyph(glyph))
                }
            }
        }
    }

    @Test
    fun statusSymbolsBeforeBypassPermissionsAreVisible() {
        // 隔离取证确认真实输入是两个 U+23F5；本机字形探针确认形近等价 U+25B8 有真字形。
        // 用户验收看的是两个三角，故原缺字码点、空白与最后安全网 "??" 均不得通过。
        val symbols = "\u23F5\u23F5"
        val suffix = " bypass permissions on (shift+tab to cycle)"
        val emulator = TerminalEmulator(cols = 48, rows = 3)
        emulator.feed(symbols + suffix)

        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.presenter = TermViewPresenter(emulator) { _, _ -> }
        val bitmap = Bitmap.createBitmap(720, 160, Bitmap.Config.ARGB_8888)
        val canvas = RecordingCanvas(bitmap)
        view.draw(canvas)
        bitmap.recycle()

        assertTrue(
            "status text was not submitted to Canvas: ${canvas.drawnText}",
            canvas.drawnText.contains(suffix),
        )
        val prefix = canvas.drawnText.toString().substringBefore(suffix)
        val renderedSymbols = prefix.codePoints().toArray().toList()
        assertTrue(
            "expected two visible U+25B8 triangles before bypass permissions, got " +
                renderedSymbols.joinToString { "U+%04X".format(it) },
            renderedSymbols == listOf(0x25B8, 0x25B8),
        )
        val drawnTriangles = canvas.codepoints.filter { it.codepoint == 0x25B8 }
        assertTrue(
            "mapped triangles were submitted with a paint that lacks their glyph: $drawnTriangles",
            drawnTriangles.size == 2 && drawnTriangles.all { it.hasGlyph },
        )
    }
}
