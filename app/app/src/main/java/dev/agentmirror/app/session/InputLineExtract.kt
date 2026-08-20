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

package dev.agentmirror.app.session

import dev.agentmirror.terminal.Cell
import dev.agentmirror.terminal.ScreenSnapshot

/**
 * 用仿真器光标锚定抽取远端输入行（契约 087 / 思路 §2）。
 *
 * 锚 = [ScreenSnapshot.cursorY]，禁止写死行号或从底部切 N 行。
 * 失败返回 [Result.Fail]，永不返回空串当成功（空串覆盖会把 F-087-2 再引入一次）。
 */
internal object InputLineExtract {

    /** 盒线/圆角盒装饰（思路 §2 列出的字符；不依赖 OSC 133）。 */
    private val BOX_CHARS: Set<Char> = setOf('─', '│', '┌', '┐', '└', '┘', '╭', '╮', '╰', '╯')

    sealed interface Result {
        data class Ok(
            val text: String,
            val cursorY: Int,
            val cursorX: Int,
            val startRow: Int,
            val endRow: Int,
            val boundary: String,
        ) : Result

        data class Fail(val reason: String) : Result
    }

    /**
     * 从 [snap] 按光标扩张到边界行，拼出输入行文本。
     *
     * @contract
     * @pre snap.lines.size == snap.rows；cols/rows ≥ 0
     * @post Ok.text 为去掉行尾空白的输入段，且非空；Fail.reason 非空。锚为 cursorY，不是 rows-k
     * @err 光标越界 / 光标行空白 / 抽空 → Fail，不猜最后一行
     * @inv 不写死行号；装饰行（盒线/整行空白/反色条带/明显窄行）不进入 Ok.text
     */
    fun extractByCursor(snap: ScreenSnapshot): Result {
        val rows = snap.rows
        val cols = snap.cols
        if (rows <= 0 || cols <= 0 || snap.lines.size != rows) {
            return Result.Fail("grid-invalid")
        }
        val cy = snap.cursorY
        val cx = snap.cursorX
        if (cy !in 0 until rows || cx !in 0 until cols) {
            return Result.Fail("cursor-out-of-grid")
        }
        if (isBlankRow(snap, cy)) {
            return Result.Fail("cursor-row-blank")
        }

        var start = cy
        var end = cy
        while (start > 0 && !isBoundary(snap, start - 1, cy)) start--
        while (end < rows - 1 && !isBoundary(snap, end + 1, cy)) end++

        val boundary = boundaryKind(snap, start, end, cy)
        val text = joinSegment(snap, start, end).trimEnd()
        if (text.isEmpty()) return Result.Fail("extract-empty")
        return Result.Ok(
            text = text,
            cursorY = cy,
            cursorX = cx,
            startRow = start,
            endRow = end,
            boundary = boundary,
        )
    }

    private fun rowText(snap: ScreenSnapshot, y: Int): String = buildString {
        for (cell in snap.lines[y]) {
            if (cell.width == 0) continue
            append(cell.text)
        }
    }

    private fun isBlankRow(snap: ScreenSnapshot, y: Int): Boolean =
        rowText(snap, y).trimEnd().isEmpty()

    private fun isBoxRow(snap: ScreenSnapshot, y: Int): Boolean {
        val chars = rowText(snap, y).filter { it != ' ' }
        return chars.isNotEmpty() && chars.all { it in BOX_CHARS }
    }

    private fun isInverseStrip(snap: ScreenSnapshot, y: Int): Boolean {
        val cells = snap.lines[y].filter { it.width != 0 && it.text.isNotBlank() }
        return cells.isNotEmpty() && cells.all { it.style.inverse }
    }

    private fun visibleWidth(snap: ScreenSnapshot, y: Int): Int =
        rowText(snap, y).trimEnd().length

    /** 明显短于整行且不含光标：状态行/token 计数，不当输入行。 */
    private fun isNarrowRow(snap: ScreenSnapshot, y: Int, cursorY: Int): Boolean =
        y != cursorY && visibleWidth(snap, y) * 2 < snap.cols

    private fun isBoundary(snap: ScreenSnapshot, y: Int, cursorY: Int): Boolean {
        if (y !in 0 until snap.rows) return true
        if (isBlankRow(snap, y)) return true
        if (isBoxRow(snap, y)) return true
        if (y != cursorY && isInverseStrip(snap, y)) return true
        if (isNarrowRow(snap, y, cursorY)) return true
        return false
    }

    private fun boundaryKind(snap: ScreenSnapshot, start: Int, end: Int, cursorY: Int): String {
        val above = start - 1
        val below = end + 1
        fun kind(y: Int): String? {
            if (y !in 0 until snap.rows) return null
            return when {
                isBoxRow(snap, y) -> "box-drawing"
                isBlankRow(snap, y) -> "blank"
                isInverseStrip(snap, y) -> "inverse"
                isNarrowRow(snap, y, cursorY) -> "narrow"
                else -> null
            }
        }
        return kind(above) ?: kind(below) ?: "edge"
    }

    private fun lastColNonEmpty(snap: ScreenSnapshot, y: Int): Boolean {
        val row = snap.lines[y]
        if (row.size < snap.cols) return false
        val last: Cell = row[snap.cols - 1]
        if (last.width == 0) return true
        return last.text.isNotBlank()
    }

    /** 去掉行首尾的盒线与空白，保留盒内正文（装饰行本身已被边界排除）。 */
    private fun stripSideDecor(raw: String): String =
        raw.trimEnd().trim { it == ' ' || it in BOX_CHARS }

    private fun joinSegment(snap: ScreenSnapshot, start: Int, end: Int): String {
        val sb = StringBuilder()
        for (y in start..end) {
            if (y > start) {
                val wrapped = lastColNonEmpty(snap, y - 1) && !isBoundary(snap, y, snap.cursorY)
                if (!wrapped) sb.append('\n')
            }
            sb.append(stripSideDecor(rowText(snap, y)))
        }
        return sb.toString()
    }
}
