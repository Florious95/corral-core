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

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.agentmirror.app.R
import dev.agentmirror.app.ui.theme.TermPalette
import kotlin.math.abs
import kotlin.math.hypot

/*
 * Native GUI uses one true-black reading surface, independent of terminal theme/appearance.
 * Terminal colours remain unchanged in TUI. Only semantic foreground signals use the terminal
 * palette, contrast-repaired against #000000; surfaces never inherit its warm/accent tint.
 */

@Immutable
data class ConversationPalette(
    val dark: Boolean,
    val canvas: Color,
    /** Soft light pooled at the top of the canvas: the "breathing" ambient. */
    val glow: Color,
    val ink: Color,
    val inkSoft: Color,
    /** Hairlines and dividers only; never text. */
    val inkFaint: Color,
    val accent: Color,
    val accentInk: Color,
    val onAccent: Color,
    val userBubble: Color,
    val userInk: Color,
    val surface: Color,
    val surfaceStroke: Color,
    val code: Color,
    val codeInk: Color,
    val codeSoft: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val glass: Color,
    val glassStroke: Color,
    /** Modernism's opaque flat panel and its contrast-safe alternate tone. */
    val panel: Color,
    val panelEnd: Color,
    /** Modernism's hairline grid and panel outline; a line, never text. */
    val rule: Color,
) {
    companion object {
        const val TEXT_MIN = 4.5
        const val BODY_TARGET = 7.0

        private val memo = java.util.IdentityHashMap<TermPalette.Scheme, ConversationPalette>()

        /** Memoised per scheme instance (TermPalette keeps one per theme selection and slot). */
        fun of(scheme: TermPalette.Scheme): ConversationPalette {
            synchronized(memo) { memo[scheme]?.let { return it } }
            val palette = from(scheme)
            synchronized(memo) {
                if (memo.size >= 4) memo.clear()
                memo[scheme] = palette
            }
            return palette
        }

        /** All native GUI slots are pure black; separation comes from geometry and hairlines. */
        fun from(scheme: TermPalette.Scheme): ConversationPalette {
            val black = 0xFF000000.toInt()
            val white = Color.White
            val soft = c(0xFFB6BDC8.toInt())
            fun signal(vararg slots: Int): Color {
                val fg = slots.toList().mapNotNull { scheme.ansi16[it] }.maxByOrNull { chroma(it) } ?: 0xFFFFFFFF.toInt()
                return c(ensureContrast(fg, black, TEXT_MIN + 0.1))
            }
            return ConversationPalette(
                dark = true,
                canvas = Color.Black,
                glow = Color.Transparent,
                ink = white,
                inkSoft = soft,
                inkFaint = white.copy(alpha = 0.24f),
                accent = white,
                accentInk = white,
                onAccent = Color.Black,
                userBubble = Color.Black,
                userInk = white,
                surface = Color.Black,
                surfaceStroke = white.copy(alpha = 0.16f),
                code = Color.Black,
                codeInk = white,
                codeSoft = soft,
                success = signal(10, 2),
                warning = signal(11, 3),
                danger = signal(9, 1),
                // Opaque protection also blocks warm colours sampled from logos/transcript.
                glass = Color.Black,
                glassStroke = white.copy(alpha = 0.16f),
                panel = Color.Black,
                panelEnd = Color.Black,
                rule = white.copy(alpha = 0.24f),
            )
        }

        private fun c(argb: Int) = Color(argb.toLong() and 0xFFFFFFFFL)

        private fun chroma(argb: Int): Double = TermPalette.toOkLab(argb).let { hypot(it.a, it.b) }

        private fun lab(L: Double, a: Double, b: Double): Int =
            TermPalette.fromOkLab(L.coerceIn(0.0, 1.0), TermPalette.OkLab(L, a, b), hypot(a, b))

        /** OkLab interpolation: perceptually even, no muddy sRGB midpoints. */
        fun mix(from: Int, to: Int, t: Double): Int {
            val x = TermPalette.toOkLab(from)
            val y = TermPalette.toOkLab(to)
            return lab(x.L + (y.L - x.L) * t, x.a + (y.a - x.a) * t, x.b + (y.b - x.b) * t)
        }

        /**
         * Smallest OkLab lightness move that lifts [fg] to [target] contrast on [bg], keeping hue
         * and chroma. Falls back to pure white/black when even the extreme is not enough.
         */
        fun ensureContrast(fg: Int, bg: Int, target: Double): Int {
            if (TermPalette.contrast(fg, bg) >= target) return fg
            val x = TermPalette.toOkLab(fg)
            val bgL = TermPalette.toOkLab(bg).L
            val up = bgL < 0.6
            var lo = x.L
            var hi = if (up) 1.0 else 0.0
            if (TermPalette.contrast(lab(hi, x.a, x.b), bg) < target) {
                return if (up) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            }
            repeat(24) {
                val mid = (lo + hi) / 2
                if (TermPalette.contrast(lab(mid, x.a, x.b), bg) >= target) hi = mid else lo = mid
                if (abs(hi - lo) < 1e-4) return@repeat
            }
            return lab(hi, x.a, x.b)
        }
    }
}

val LocalConversationPalette = staticCompositionLocalOf<ConversationPalette> { error("ConversationPalette not provided") }

/** Inter (bundled variable font) with real weight axes; CJK falls back to the system face. */
@OptIn(ExperimentalTextApi::class)
val ConversationSans: FontFamily = FontFamily(
    Font(R.font.inter_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

val ConversationMono: FontFamily = FontFamily.Monospace
