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

package dev.agentmirror.terminal

/**
 * 终端颜色：默认色 / 256 色索引 / 24 位真彩三态。
 */
sealed interface TerminalColor {
    /** 终端默认前景或背景色，具体色值由渲染层主题决定。 */
    data object Default : TerminalColor

    /** 256 色调色板索引（0..255，前 16 为基色+亮色）。 */
    data class Indexed(val index: Int) : TerminalColor

    /** 24 位真彩 RGB（各分量 0..255）。 */
    data class Rgb(val r: Int, val g: Int, val b: Int) : TerminalColor
}

/**
 * 单元格文本属性：SGR 可切换的前景/背景色与样式开关的不可变集合。
 */
data class TextStyle(
    val fg: TerminalColor = TerminalColor.Default,
    val bg: TerminalColor = TerminalColor.Default,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
    val strikethrough: Boolean = false,
) {
    companion object {
        /** 全默认样式单例，避免为每个空白格重复分配。 */
        val DEFAULT = TextStyle()
    }
}

/**
 * 把一串 SGR 参数应用到基准样式上，返回新样式（CSI ... m 的语义核心）。
 *
 * 支持：0 复位、1/2/3/4/7/9 开关及 22/23/24/27/29 关闭、30-37/90-97 前景、
 * 40-47/100-107 背景、38/48 的 5;n（256 色）与 2;r;g;b（真彩）扩展、39/49 回默认。
 * 未识别参数按 VT 惯例静默忽略。
 */
internal fun applySgr(base: TextStyle, params: List<Int>): TextStyle {
    if (params.isEmpty()) return TextStyle.DEFAULT
    var s = base
    var i = 0
    while (i < params.size) {
        when (val p = params[i]) {
            0 -> s = TextStyle.DEFAULT
            1 -> s = s.copy(bold = true)
            2 -> s = s.copy(dim = true)
            3 -> s = s.copy(italic = true)
            4 -> s = s.copy(underline = true)
            7 -> s = s.copy(inverse = true)
            9 -> s = s.copy(strikethrough = true)
            22 -> s = s.copy(bold = false, dim = false)
            23 -> s = s.copy(italic = false)
            24 -> s = s.copy(underline = false)
            27 -> s = s.copy(inverse = false)
            29 -> s = s.copy(strikethrough = false)
            in 30..37 -> s = s.copy(fg = TerminalColor.Indexed(p - 30))
            38 -> {
                val (color, consumed) = parseExtendedColor(params, i)
                if (color != null) s = s.copy(fg = color)
                i += consumed
            }
            39 -> s = s.copy(fg = TerminalColor.Default)
            in 40..47 -> s = s.copy(bg = TerminalColor.Indexed(p - 40))
            48 -> {
                val (color, consumed) = parseExtendedColor(params, i)
                if (color != null) s = s.copy(bg = color)
                i += consumed
            }
            49 -> s = s.copy(bg = TerminalColor.Default)
            in 90..97 -> s = s.copy(fg = TerminalColor.Indexed(p - 90 + 8))
            in 100..107 -> s = s.copy(bg = TerminalColor.Indexed(p - 100 + 8))
            else -> {}
        }
        i++
    }
    return s
}

/**
 * 解析 38/48 后跟的扩展色参数，返回颜色与额外消耗的参数个数（不含 38/48 本身）。
 */
private fun parseExtendedColor(params: List<Int>, at: Int): Pair<TerminalColor?, Int> {
    return when (params.getOrNull(at + 1)) {
        5 -> {
            val idx = params.getOrNull(at + 2) ?: return null to 1
            TerminalColor.Indexed(idx.coerceIn(0, 255)) to 2
        }
        2 -> {
            if (at + 4 >= params.size) return null to (params.size - at - 1)
            val r = params[at + 2].coerceIn(0, 255)
            val g = params[at + 3].coerceIn(0, 255)
            val b = params[at + 4].coerceIn(0, 255)
            TerminalColor.Rgb(r, g, b) to 4
        }
        else -> null to 1
    }
}
