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
import dev.agentmirror.terminal.TerminalColor

/**
 * 终端自绘色板的取色入口（078 §2 / 080 / 083 §2）。
 *
 * 色值只来自 [TerminalPaletteLight] / [TerminalPaletteDark]，绘制层不许再写一份字面量。
 * [source] 恒为 `app-theme`：contentDescription 与单测钉这个串。
 *
 * 显式背景重映射（083 §2）：CLI 按深色终端发的底色，按当前主题落到设计色。
 * - Claude Code 用户消息：SGR `48;5;254`（256 色，非真彩）→ [TerminalPalette.userBlockBackground]
 * - grok 整屏黑：SGR `40` / `48;5;0` / `48;5;16` 或近黑真彩 → [TerminalPalette.background]
 *   （⛔ 不走 ansi[0]「浅底暗格」`E7EAF0`，那个值给局部色块）
 * - `TerminalColor.Rgb` 翻不了索引表，走亮度守卫（浅底过亮压暗保色相、过暗抬到纸色）
 */
object TermPalette {

    const val SOURCE = "app-theme"

    /** Claude Code 用户消息/recap 块用的 256 色索引（SGR 48;5;254）。 */
    const val USER_MESSAGE_INDEX = 254

    /** xterm 色立方原点，常被当成「整屏黑」（SGR 48;5;16）。 */
    const val CUBE_BLACK_INDEX = 16

    /** 近白 256 色：立方白 / 灰阶顶端。浅底上当作用户块，不留纯白。 */
    private val NEAR_WHITE_INDEXES = setOf(231, 253, 255)

    /** 原色亮度 ≤ 此值视为「终端黑」（整屏底），映射到 [Scheme.defaultBg]。 */
    const val SCREEN_BLACK_LUMA_MAX = 32

    /** 原色亮度 ≥ 此值视为「高亮白块」，浅底上压到用户块。 */
    const val HIGHLIGHT_WHITE_LUMA_MIN = 220

    /** 无色相：max-min ≤ 此值的近白走 userBlock，不按色相缩放。 */
    const val ACHROMA_MAX = 8

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

    /**
     * 终端色 → ARGB。背景路径做 083 §2 重映射；前景不把「黑」抬成纸色（否则字消失）。
     */
    fun colorFor(color: TerminalColor, background: Boolean, dark: Boolean): Int {
        val pal = of(dark)
        return when (color) {
            TerminalColor.Default -> if (background) pal.defaultBg else pal.defaultFg
            is TerminalColor.Indexed -> indexed(color.index, background, pal, dark)
            is TerminalColor.Rgb -> {
                val raw = pack(color.r, color.g, color.b)
                if (background) guardRgbBg(raw, pal, dark) else raw
            }
        }
    }

    private fun indexed(index: Int, background: Boolean, pal: Scheme, dark: Boolean): Int {
        if (background && (index == 0 || index == CUBE_BLACK_INDEX)) return pal.defaultBg
        if (index == USER_MESSAGE_INDEX) return pal.userBlockBg
        if (background && index in NEAR_WHITE_INDEXES) return pal.userBlockBg
        if (index in 0..15) return pal.ansi16[index] ?: pack(128, 128, 128)
        val raw = pal.xterm256.getOrElse(index) { pack(128, 128, 128) }
        return if (background) guardRgbBg(raw, pal, dark) else raw
    }

    /**
     * 真彩 / 256 扩展底：记录比较操作数后判决。
     * 浅底过亮 → 压到用户块亮度（有色相则缩放通道）；过暗 → 纸色。
     */
    private fun guardRgbBg(raw: Int, pal: Scheme, dark: Boolean): Int {
        val y = luma(raw)
        val r = (raw shr 16) and 0xFF
        val g = (raw shr 8) and 0xFF
        val b = raw and 0xFF
        val chroma = maxOf(r, g, b) - minOf(r, g, b)
        val blockY = luma(pal.userBlockBg)
        return when {
            y <= SCREEN_BLACK_LUMA_MAX -> pal.defaultBg
            y >= HIGHLIGHT_WHITE_LUMA_MIN && chroma <= ACHROMA_MAX -> pal.userBlockBg
            !dark && y >= HIGHLIGHT_WHITE_LUMA_MIN -> scaleLuma(raw, blockY)
            dark && y >= HIGHLIGHT_WHITE_LUMA_MIN -> pal.userBlockBg
            else -> raw
        }
    }

    private fun scaleLuma(argb: Int, targetY: Int): Int {
        val y = luma(argb)
        if (y <= 0) return pack(targetY, targetY, targetY)
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        fun ch(v: Int) = (v * targetY / y).coerceIn(0, 255)
        return pack(ch(r), ch(g), ch(b))
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
