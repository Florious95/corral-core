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

import androidx.compose.ui.graphics.toArgb

/**
 * 终端自绘色板的取色入口（078 §2 / 080）。
 *
 * 色值只来自 [TerminalPaletteLight] / [TerminalPaletteDark]，绘制层不许再写一份字面量。
 * [source] 恒为 `app-theme`：contentDescription 与单测钉这个串。
 *
 * Claude Code 用户消息块走 SGR `48;5;254`。这里把 254 映射成
 * [TerminalPalette.userBlockBackground]，浅底上更深、深底上更浅。
 */
object TermPalette {

    const val SOURCE = "app-theme"

    /** Claude Code 用户消息/recap 块用的 256 色索引（SGR 48;5;254）。 */
    const val USER_MESSAGE_INDEX = 254

    data class Scheme(
        val defaultBg: Int,
        val defaultFg: Int,
        val userBlockBg: Int,
        val ansi16: Map<Int, Int>,
        val source: String = SOURCE,
    ) {
        val xterm256: IntArray = IntArray(256) { i ->
            fun cube(v: Int): Int = if (v == 0) 0 else 55 + 40 * v
            when {
                i < 16 -> ansi16[i] ?: pack(128, 128, 128)
                i == USER_MESSAGE_INDEX -> userBlockBg
                i < 232 -> {
                    val c = i - 16
                    pack(cube(c / 36), cube(c / 6 % 6), cube(c % 6))
                }
                else -> {
                    val v = 8 + 10 * (i - 232)
                    pack(v, v, v)
                }
            }
        }
    }

    val Light: Scheme = schemeFrom(TerminalPaletteLight)
    val Dark: Scheme = schemeFrom(TerminalPaletteDark)

    fun of(dark: Boolean): Scheme = if (dark) Dark else Light

    fun token(dark: Boolean): String =
        (if (dark) "term-theme-dark" else "term-theme-light") + " source=$SOURCE"

    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun schemeFrom(p: TerminalPalette): Scheme = Scheme(
        defaultBg = p.background.toArgb(),
        defaultFg = p.foreground.toArgb(),
        userBlockBg = p.userBlockBackground.toArgb(),
        ansi16 = p.ansi.mapIndexed { i, c -> i to c.toArgb() }.toMap(),
    )

    private fun pack(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
