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

/**
 * 终端自绘色板的唯一事实源（078 §2 裁定 B）。
 *
 * 配色由 APP 深/浅主题决定，不取主机 OSC 11 / tmux / 协议字段。
 * [source] 恒为 `app-theme`：渲染层 contentDescription 与单测都钉这个串，
 * 防止把默认色写死成浅色来骗「现在是浅底」判据。
 *
 * 用户消息块不是 App 另标的区域——Claude Code 在 PTY 里发 SGR `48;5;254`
 * （xterm 灰阶 rgb(228,228,228)）。渲染层只换默认底/默认字；254 灰块保持原值，
 * 浅底上它比画布深、深底上它比画布浅，主次关系随主题反转。
 *
 * ANSI 16 色相已与主机一致（078 对照图），本色板原样集中，不改数值。
 */
object TermPalette {

    const val SOURCE = "app-theme"

    /** Claude Code 用户消息/recap 块用的 256 色索引（SGR 48;5;254）。 */
    const val USER_MESSAGE_INDEX = 254

    data class Scheme(
        val defaultBg: Int,
        val defaultFg: Int,
        val source: String = SOURCE,
    )

    /** 浅色：近白底 + 深字。背景取自 [LightColorScheme] 的 background / onBackground。 */
    val Light = Scheme(
        defaultBg = 0xFFF8F9FD.toInt(),
        defaultFg = 0xFF191C22.toInt(),
    )

    /** 深色：沿用历史终端画布（0x0D1626 / 近白字），不跟 chrome 的 0x0B111D 混成一块。 */
    val Dark = Scheme(
        defaultBg = 0xFF0D1626.toInt(),
        defaultFg = 0xFFE8E8E8.toInt(),
    )

    fun of(dark: Boolean): Scheme = if (dark) Dark else Light

    fun token(dark: Boolean): String =
        (if (dark) "term-theme-dark" else "term-theme-light") + " source=$SOURCE"

    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** 用户消息块底（索引 254 → 灰阶 8+10×(254−232) = 228）。 */
    val userMessageBg: Int = pack(228, 228, 228)

    val ansi16: Map<Int, Int> = mapOf(
        0 to pack(0, 0, 0),
        1 to pack(205, 49, 49),
        2 to pack(13, 188, 121),
        3 to pack(229, 229, 16),
        4 to pack(36, 114, 200),
        5 to pack(188, 63, 188),
        6 to pack(17, 168, 205),
        7 to pack(229, 229, 229),
        8 to pack(102, 102, 102),
        9 to pack(241, 76, 76),
        10 to pack(35, 209, 139),
        11 to pack(245, 245, 67),
        12 to pack(59, 142, 234),
        13 to pack(214, 112, 214),
        14 to pack(41, 184, 219),
        15 to pack(229, 229, 229),
    )

    val xterm256: IntArray = IntArray(256) { i ->
        fun cube(v: Int): Int = if (v == 0) 0 else 55 + 40 * v
        when {
            i < 16 -> ansi16[i] ?: pack(128, 128, 128)
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

    private fun pack(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
