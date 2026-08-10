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
 * 码点显示宽度判定（wcwidth 的务实实现）：0=零宽/组合，1=普通，2=宽字符（CJK/emoji）。
 *
 * 覆盖东亚 Wide/Fullwidth 主区段、Hangul、CJK 扩展平面与主流 emoji 区段；
 * 组合记号、变体选择符、ZWJ/ZWNJ 判零宽。刻意不追求 Unicode 全表精确
 * （上游是 tmux 输出，宽度以 tmux/主机侧一致为准，边缘区段出入由快照重放兜底）。
 */
object CharWidth {

    /** 返回码点的占格宽度（0/1/2）。 */
    fun of(codePoint: Int): Int = when {
        codePoint < 0x20 || codePoint in 0x7F..0x9F -> 0
        isZeroWidth(codePoint) -> 0
        isWide(codePoint) -> 2
        else -> 1
    }

    /** 判定零宽码点：组合记号、变体选择符、零宽空格/连接符。 */
    private fun isZeroWidth(cp: Int): Boolean = when {
        cp == 0x200B || cp == 0x200C || cp == 0x200D -> true
        cp in 0x0300..0x036F -> true
        cp in 0x1AB0..0x1AFF -> true
        cp in 0x1DC0..0x1DFF -> true
        cp in 0x20D0..0x20FF -> true
        cp in 0xFE00..0xFE0F -> true
        cp in 0xFE20..0xFE2F -> true
        else -> false
    }

    /** 判定占两格的宽码点（东亚 Wide/Fullwidth + emoji 主区段）。 */
    private fun isWide(cp: Int): Boolean = when {
        cp in 0x1100..0x115F -> true    // Hangul Jamo 声母
        cp in 0x2E80..0x303E -> true    // CJK 部首/符号
        cp in 0x3041..0x33FF -> true    // 假名/CJK 符号/兼容
        cp in 0x3400..0x4DBF -> true    // CJK 扩展 A
        cp in 0x4E00..0x9FFF -> true    // CJK 统一表意
        cp in 0xA000..0xA4CF -> true    // 彝文
        cp in 0xA960..0xA97F -> true    // Hangul Jamo 扩展 A
        cp in 0xAC00..0xD7A3 -> true    // Hangul 音节
        cp in 0xF900..0xFAFF -> true    // CJK 兼容表意
        cp in 0xFE10..0xFE19 -> true    // 竖排标点
        cp in 0xFE30..0xFE52 -> true    // CJK 兼容形式
        cp in 0xFE54..0xFE66 -> true
        cp in 0xFE68..0xFE6B -> true
        cp in 0xFF00..0xFF60 -> true    // 全角形式
        cp in 0xFFE0..0xFFE6 -> true
        cp == 0x26A0 -> true            // ⚠ + VS16：dogfood 夹具按 emoji 两列显示
        cp == 0x2705 -> true            // ✅（East Asian Width=Wide）
        cp == 0x274C -> true            // ❌（East Asian Width=Wide）
        cp in 0x1F300..0x1F64F -> true  // emoji 主区段
        cp in 0x1F680..0x1F6FF -> true  // 交通/地图 emoji
        cp in 0x1F900..0x1FAFF -> true  // 补充 emoji
        cp in 0x20000..0x2FFFD -> true  // CJK 扩展 B-F
        cp in 0x30000..0x3FFFD -> true  // CJK 扩展 G
        else -> false
    }
}
