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

package dev.agentmirror.app.overlay

/**
 * 悬浮窗屏幕缓冲（066：移植 web/js/overlay.js OverlayEmulator）。
 * 解释备用屏 / CUP / ED / EL / SGR，不把 CSI 当字形；每帧 [resize] 后 [feed] = 整屏替换。
 *
 * @contract
 * @pre cols/rows ≥ 1
 * @post [plainText] 不含控制序列字面量
 * @inv 网格尺寸恒为 cols×rows，不因 feed 增长
 */
class OverlayEmulator(cols: Int = 40, rows: Int = 16) {

    var cols: Int = cols
        private set
    var rows: Int = rows
        private set

    private var cx: Int = 0
    private var cy: Int = 0
    private var grid: Array<Array<String>> = blank(cols, rows)

    fun resize(cols: Int, rows: Int) {
        this.cols = cols
        this.rows = rows
        cx = 0
        cy = 0
        grid = blank(cols, rows)
    }

    fun feed(text: String) {
        if (text.isEmpty()) return
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when (ch) {
                ESC -> {
                    i = consumeEsc(text, i)
                    continue
                }
                '\r' -> {
                    cx = 0
                    i += 1
                    continue
                }
                '\n' -> {
                    newline()
                    i += 1
                    continue
                }
                '\b' -> {
                    if (cx > 0) cx -= 1
                    i += 1
                    continue
                }
            }
            val cp = text.codePointAt(i)
            val glyph = String(intArrayOf(cp), 0, 1)
            i += Character.charCount(cp)
            put(glyph)
        }
    }

    fun plainText(): String {
        val lines = grid.map { row ->
            row.joinToString("") { it }.trimEnd()
        }
        return lines.joinToString("\n").trimEnd()
    }

    private fun consumeEsc(text: String, i: Int): Int {
        if (i + 1 >= text.length) return text.length
        val next = text[i + 1]
        if (next == '[') {
            var j = i + 2
            while (j < text.length && !isCsiFinal(text[j])) j += 1
            val body = if (j > i + 2) text.substring(i + 2, j) else ""
            val final = if (j < text.length) text[j] else 0.toChar()
            csi(body, final)
            return j + 1
        }
        if (next == ']') {
            var j = i + 2
            while (j < text.length && text[j] != BEL &&
                !(text[j] == ESC && j + 1 < text.length && text[j + 1] == '\\')
            ) {
                j += 1
            }
            if (j < text.length && text[j] == BEL) return j + 1
            if (j < text.length && text[j] == ESC) return j + 2
            return text.length
        }
        if (next == '(' || next == ')') return (i + 3).coerceAtMost(text.length)
        return i + 2
    }

    private fun csi(body: String, final: Char) {
        val priv = body.startsWith("?") || body.startsWith(">")
        val nums = (if (priv) body.drop(1) else body)
            .split(';')
            .map { part -> if (part.isEmpty()) 0 else part.toIntOrNull() ?: 0 }
        if (final == 'h' && priv && nums.contains(1049)) {
            resetCursorAndGrid()
            return
        }
        if (final == 'l' && priv && nums.contains(1049)) {
            resetCursorAndGrid()
            return
        }
        if (final == 'H' || final == 'f') {
            val row = (nums.getOrElse(0) { 1 }.coerceAtLeast(1)) - 1
            val col = (nums.getOrElse(1) { 1 }.coerceAtLeast(1)) - 1
            cy = row.coerceAtMost(rows - 1)
            cx = col.coerceAtMost(cols - 1)
            return
        }
        if (final == 'J') {
            val n = nums.getOrElse(0) { 0 }
            if (n == 2 || n == 3) resetCursorAndGrid()
            return
        }
        if (final == 'K') {
            val n = nums.getOrElse(0) { 0 }
            val row = grid.getOrNull(cy) ?: return
            when (n) {
                0 -> for (x in cx until cols) row[x] = " "
                1 -> for (x in 0..cx) row[x] = " "
                2 -> for (x in 0 until cols) row[x] = " "
            }
            return
        }
        val step = nums.getOrElse(0) { 1 }.coerceAtLeast(1)
        when (final) {
            'A' -> cy = (cy - step).coerceAtLeast(0)
            'B' -> cy = (cy + step).coerceAtMost(rows - 1)
            'C' -> cx = (cx + step).coerceAtMost(cols - 1)
            'D' -> cx = (cx - step).coerceAtLeast(0)
        }
    }

    private fun put(glyph: String) {
        val w = cellWidth(glyph)
        if (w <= 0) return
        if (cx + w > cols) newline()
        val row = grid.getOrNull(cy) ?: return
        row[cx] = glyph
        if (w == 2 && cx + 1 < cols) row[cx + 1] = ""
        cx += w
    }

    private fun newline() {
        cx = 0
        if (cy < rows - 1) cy += 1
    }

    private fun resetCursorAndGrid() {
        grid = blank(cols, rows)
        cx = 0
        cy = 0
    }

    private companion object {
        const val ESC = '\u001b'
        const val BEL = '\u0007'

        fun blank(cols: Int, rows: Int): Array<Array<String>> =
            Array(rows) { Array(cols) { " " } }

        fun isCsiFinal(c: Char): Boolean =
            c in 'A'..'Z' || c in 'a'..'z' || c == '@' || c == '`' || c == '~'

        fun cellWidth(ch: String): Int {
            if (ch.isEmpty()) return 0
            val c = ch.codePointAt(0)
            if (c <= 0x1f || (c in 0x7f..0x9f)) return 0
            if (c < 0x1100) return 1
            if (
                c in 0x2e80..0xa4cf ||
                c in 0xac00..0xd7a3 ||
                c in 0xf900..0xfaff ||
                c in 0xfe10..0xfe19 ||
                c in 0xfe30..0xfe6f ||
                c in 0xff00..0xff60 ||
                c in 0xffe0..0xffe6 ||
                c in 0x20000..0x3fffd
            ) {
                return 2
            }
            return 1
        }
    }
}

private val scratchPane = Regex("""\b(?:tree|sleep)\*""")

/** 去掉观测装置行（am-overlay / ov-spin / tree* / sleep*）。 */
fun dropScratchLines(text: String): String =
    text.lineSequence()
        .filter { line ->
            !line.contains("am-overlay") &&
                !line.contains("ov-spin") &&
                !scratchPane.containsMatchIn(line)
        }
        .joinToString("\n")
