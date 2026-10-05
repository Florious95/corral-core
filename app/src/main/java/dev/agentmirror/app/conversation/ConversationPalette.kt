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
 * The conversation surface takes its colour from the user's terminal theme (all 30 families ×
 * light/dark slots), so GUI and TUI feel like one product. Every derived pair is computed in OkLab
 * and contrast-repaired: text never lands below WCAG AA on its own surface, whatever the theme —
 * the white-on-white class of bug is impossible by construction, not by review.
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
    val surfaceGlint: Color,
    val code: Color,
    val codeInk: Color,
    val codeSoft: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val glass: Color,
    val glassStroke: Color,
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

        /**
         * Depth and glints follow the canvas's real lightness, not the app appearance: a slot may
         * carry a dark scheme in light appearance (single-variant families such as Vesper).
         */
        fun from(scheme: TermPalette.Scheme): ConversationPalette {
            val bg = scheme.defaultBg
            val dark = TermPalette.toOkLab(bg).L < 0.6
            val ink = ensureContrast(scheme.defaultFg, bg, BODY_TARGET)
            val accentBase = listOf(12, 4, 14, 6, 13, 5).mapNotNull { scheme.ansi16[it] }
                .filter { distance(it, bg) > 0.12 }
                .maxByOrNull { chroma(it) } ?: ink
            val onAccent = listOf(0xFFFFFFFF.toInt(), 0xFF0E0F13.toInt()).maxBy { TermPalette.contrast(it, ensureContrast(accentBase, bg, 3.0)) }
            // The send button carries its glyph: the fill moves away from the glyph until both
            // the glyph (AA) and the fill against the canvas (3:1 UI component) hold.
            val accent = ensureContrast(ensureContrast(accentBase, bg, 3.0), onAccent, TEXT_MIN + 0.1)
            val accentInk = ensureContrast(accentBase, bg, TEXT_MIN + 0.1)
            val bubble = tint(scheme.userBlockBg, accentBase, 0.16)
            // Quantisation collapses tiny OkLab shifts near pure black/white, so each layer is
            // also held to a minimum luminance separation from the canvas.
            val surface = ensureContrast(shift(bg, if (dark) 0.042 else -0.018, accentBase, 0.006), bg, if (dark) 1.12 else 1.05)
            val code = if (dark) {
                ensureContrast(shift(bg, -0.018, accentBase, 0.004), bg, 1.03)
            } else {
                ensureContrast(shift(bg, -0.034, accentBase, 0.004), bg, 1.11)
            }
            val inkSoft = ensureContrast(ensureContrast(mix(ink, bg, 0.42), bg, TEXT_MIN + 0.1), surface, TEXT_MIN + 0.1)
            fun signal(vararg slots: Int): Int = slots.toList().mapNotNull { scheme.ansi16[it] }.maxByOrNull { chroma(it) } ?: ink
            return ConversationPalette(
                dark = dark,
                canvas = c(bg),
                glow = c(shift(bg, if (dark) 0.06 else 0.03, accentBase, 0.03)).copy(alpha = 0.9f),
                ink = c(ink),
                inkSoft = c(inkSoft),
                inkFaint = c(mix(ink, bg, 0.84)),
                accent = c(accent),
                accentInk = c(accentInk),
                onAccent = c(onAccent),
                userBubble = c(bubble),
                userInk = c(ensureContrast(scheme.userBlockFg, bubble, BODY_TARGET)),
                surface = c(surface),
                surfaceStroke = c(ink).copy(alpha = if (dark) 0.10f else 0.09f),
                surfaceGlint = Color.White.copy(alpha = if (dark) 0.07f else 0.75f),
                code = c(code),
                codeInk = c(ensureContrast(ink, code, BODY_TARGET)),
                codeSoft = c(ensureContrast(mix(ink, code, 0.42), code, TEXT_MIN + 0.1)),
                success = c(ensureContrast(signal(10, 2), surface, TEXT_MIN + 0.1)),
                warning = c(ensureContrast(signal(11, 3), surface, TEXT_MIN + 0.1)),
                danger = c(ensureContrast(signal(9, 1), surface, TEXT_MIN + 0.1)),
                glass = c(bg).copy(alpha = if (dark) 0.64f else 0.72f),
                glassStroke = (if (dark) Color.White else Color.Black).copy(alpha = if (dark) 0.10f else 0.07f),
            )
        }

        private fun c(argb: Int) = Color(argb.toLong() and 0xFFFFFFFFL)

        private fun chroma(argb: Int): Double = TermPalette.toOkLab(argb).let { hypot(it.a, it.b) }

        private fun distance(a: Int, b: Int): Double {
            val x = TermPalette.toOkLab(a)
            val y = TermPalette.toOkLab(b)
            return hypot(x.L - y.L, hypot(x.a - y.a, x.b - y.b))
        }

        private fun lab(L: Double, a: Double, b: Double): Int =
            TermPalette.fromOkLab(L.coerceIn(0.0, 1.0), TermPalette.OkLab(L, a, b), hypot(a, b))

        /** OkLab interpolation: perceptually even, no muddy sRGB midpoints. */
        fun mix(from: Int, to: Int, t: Double): Int {
            val x = TermPalette.toOkLab(from)
            val y = TermPalette.toOkLab(to)
            return lab(x.L + (y.L - x.L) * t, x.a + (y.a - x.a) * t, x.b + (y.b - x.b) * t)
        }

        /** Lightness shift with a whisper of [hueFrom]'s hue, so surfaces belong to the theme. */
        private fun shift(base: Int, dL: Double, hueFrom: Int, chromaAdd: Double): Int {
            val x = TermPalette.toOkLab(base)
            val h = TermPalette.toOkLab(hueFrom)
            val n = hypot(h.a, h.b).takeIf { it > 1e-6 } ?: 1.0
            return lab(x.L + dL, x.a + h.a / n * chromaAdd, x.b + h.b / n * chromaAdd)
        }

        /** Moves [base] toward [toward]'s hue only; lightness stays (keeps the bubble's depth). */
        private fun tint(base: Int, toward: Int, t: Double): Int {
            val x = TermPalette.toOkLab(base)
            val y = TermPalette.toOkLab(toward)
            return lab(x.L, x.a + (y.a - x.a) * t, x.b + (y.b - x.b) * t)
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
