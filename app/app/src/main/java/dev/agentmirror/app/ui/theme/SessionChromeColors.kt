package dev.agentmirror.app.ui.theme

import androidx.compose.ui.graphics.Color
/** Semantic session chrome derived only from the selected terminal scheme. */
data class SessionChromeColors(
    val page: Color,
    val surface: Color,
    val text: Color,
    val accent: Color,
    val muted: Color,
    val input: Color,
    val outline: Color,
    val success: Color,
    val interrupt: Color,
) {
    companion object {
        fun from(scheme: TermPalette.Scheme): SessionChromeColors {
            val bg = scheme.defaultBg
            val fg = scheme.defaultFg
            val accent = scheme.cursor ?: scheme.ansi16[14] ?: fg
            val green = readable(scheme.ansi16[10] ?: fg, bg, fg)
            val red = readable(scheme.ansi16[9] ?: fg, bg, fg)
            return SessionChromeColors(
                page = Color(bg.toLong() and 0xffffffffL),
                surface = Color(blend(bg, fg, .08f).toLong() and 0xffffffffL),
                text = Color(fg.toLong() and 0xffffffffL),
                accent = Color(readable(accent, bg, fg).toLong() and 0xffffffffL),
                muted = Color(blend(fg, bg, .45f).toLong() and 0xffffffffL),
                input = Color(blend(bg, fg, .14f).toLong() and 0xffffffffL),
                outline = Color(blend(fg, bg, .35f).toLong() and 0xffffffffL),
                success = Color(green.toLong() and 0xffffffffL),
                interrupt = Color(red.toLong() and 0xffffffffL),
            )
        }

        private fun readable(value: Int, bg: Int, fallback: Int): Int = if (contrast(value, bg) >= 3.0) value else fallback
        private fun blend(a: Int, b: Int, amount: Float): Int {
            fun ch(v: Int, shift: Int) = (v shr shift) and 255
            fun mix(shift: Int) = (ch(a, shift) * (1 - amount) + ch(b, shift) * amount).toInt()
            return (0xff shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
        private fun contrast(a: Int, b: Int): Double {
            fun lum(v: Int): Double = listOf(16, 8, 0).map { ((v shr it) and 255) / 255.0 }.map { if (it <= .03928) it / 12.92 else ((it + .055) / 1.055).let { x -> x * x * x } }.let { .2126 * it[0] + .7152 * it[1] + .0722 * it[2] }
            val x = lum(a); val y = lum(b)
            return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
        }
    }
}
