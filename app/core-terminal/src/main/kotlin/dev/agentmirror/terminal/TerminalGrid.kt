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
 * 字符网格：一屏单元格矩阵 + 游标 + 滚动区域，承载全部就地编辑操作（写入/擦除/滚动/插删）。
 *
 * 纯状态容器，不认识转义序列（那是 [TerminalEmulator] 的事）；行滚出顶部时经
 * [onLineScrolledOut] 上抛（仅滚动区域顶到屏幕第 0 行时），由持有方决定是否进 scrollback。
 * 自动换行采用 VT 惯例的 pending-wrap：写满最后一列后游标悬停，下个字符才触发换行。
 */
internal class TerminalGrid(cols: Int, rows: Int) {

    var cols: Int = cols; private set
    var rows: Int = rows; private set
    var cursorX: Int = 0; private set
    var cursorY: Int = 0; private set
    var scrollTop: Int = 0; private set
    var scrollBottom: Int = rows - 1; private set

    /** 屏幕顶行滚出时的回调（参数为整行拷贝）。 */
    var onLineScrolledOut: ((List<Cell>) -> Unit)? = null

    private var grid: Array<Array<Cell>> = Array(rows) { blankRow(TextStyle.DEFAULT) }
    private var pendingWrap = false
    private var dirtyTop = 0
    private var dirtyBottom = rows - 1

    /** 写入一个码点：处理 pending-wrap、宽字符占两格、组合字符并入前格。 */
    fun write(codePoint: Int, width: Int, style: TextStyle, fill: TextStyle) {
        if (width == 0) {
            appendCombining(codePoint)
            return
        }
        if (pendingWrap) {
            pendingWrap = false
            cursorX = 0
            lineFeed(fill)
        }
        if (width == 2 && cursorX == cols - 1) {
            // 宽字符放不进行尾：末格留空白，整字符落到下一行行首。
            clearWideAt(cursorY, cursorX, fill)
            grid[cursorY][cursorX] = Cell.blank(fill)
            markDirty(cursorY)
            cursorX = 0
            lineFeed(fill)
        }
        clearWideAt(cursorY, cursorX, fill)
        if (width == 2 && cursorX + 1 < cols) clearWideAt(cursorY, cursorX + 1, fill)
        val row = grid[cursorY]
        row[cursorX] = Cell(String(Character.toChars(codePoint)), style, width)
        if (width == 2) row[cursorX + 1] = Cell("", style, 0)
        markDirty(cursorY)
        val next = cursorX + width
        if (next >= cols) {
            cursorX = cols - 1
            pendingWrap = true
        } else {
            cursorX = next
        }
    }

    /** 把组合字符追加进游标前一个已写单元格（宽字符续格自动回退到首格）。 */
    private fun appendCombining(codePoint: Int) {
        var tx = if (pendingWrap) cols - 1 else cursorX - 1
        if (tx >= 0 && grid[cursorY][tx].width == 0) tx--
        if (tx < 0) return
        val cell = grid[cursorY][tx]
        if (cell.width == 0) return
        grid[cursorY][tx] = cell.copy(text = cell.text + String(Character.toChars(codePoint)))
        markDirty(cursorY)
    }

    /** 游标相对移动（屏幕边界内钳制，清 pending-wrap）。 */
    fun moveCursor(dx: Int, dy: Int) {
        setCursor(cursorX + dx, cursorY + dy)
    }

    /** 游标绝对定位（屏幕边界内钳制，清 pending-wrap）。 */
    fun setCursor(x: Int, y: Int) {
        cursorX = x.coerceIn(0, cols - 1)
        cursorY = y.coerceIn(0, rows - 1)
        pendingWrap = false
    }

    /** 回车：游标回到行首。 */
    fun carriageReturn() {
        cursorX = 0
        pendingWrap = false
    }

    /** 换行：滚动区域底部则区域上滚一行，否则游标下移。 */
    fun lineFeed(fill: TextStyle) {
        pendingWrap = false
        if (cursorY == scrollBottom) {
            scrollUp(1, fill)
        } else if (cursorY < rows - 1) {
            cursorY++
        }
    }

    /** 反向换行（ESC M）：滚动区域顶部则区域下滚一行，否则游标上移。 */
    fun reverseLineFeed(fill: TextStyle) {
        pendingWrap = false
        if (cursorY == scrollTop) {
            scrollDown(1, fill)
        } else if (cursorY > 0) {
            cursorY--
        }
    }

    /** 退格：游标左移一格（不删字符）。 */
    fun backspace() {
        if (cursorX > 0) cursorX--
        pendingWrap = false
    }

    /** 水平制表：跳到下一个 8 列制表位（行尾钳制，不改写单元格）。 */
    fun tab() {
        cursorX = (((cursorX / TAB_STOP) + 1) * TAB_STOP).coerceAtMost(cols - 1)
        pendingWrap = false
    }

    /** 滚动区域整体上滚 [n] 行；顶行在屏幕第 0 行时上抛给 [onLineScrolledOut]。 */
    fun scrollUp(n: Int, fill: TextStyle) {
        val count = n.coerceIn(1, scrollBottom - scrollTop + 1)
        repeat(count) {
            if (scrollTop == 0) onLineScrolledOut?.invoke(grid[0].toList())
            for (y in scrollTop until scrollBottom) grid[y] = grid[y + 1]
            grid[scrollBottom] = blankRow(fill)
        }
        markDirty(scrollTop, scrollBottom)
    }

    /** 滚动区域整体下滚 [n] 行（顶部补空行，底部行丢弃，不进 scrollback）。 */
    fun scrollDown(n: Int, fill: TextStyle) {
        val count = n.coerceIn(1, scrollBottom - scrollTop + 1)
        repeat(count) {
            for (y in scrollBottom downTo scrollTop + 1) grid[y] = grid[y - 1]
            grid[scrollTop] = blankRow(fill)
        }
        markDirty(scrollTop, scrollBottom)
    }

    /** 设置滚动区域（DECSTBM，入参 0 基、含端点）；非法区间回落全屏。 */
    fun setScrollRegion(top: Int, bottom: Int) {
        if (top in 0 until bottom && bottom < rows) {
            scrollTop = top
            scrollBottom = bottom
        } else {
            scrollTop = 0
            scrollBottom = rows - 1
        }
    }

    /** 行内擦除（EL）：0=游标到行尾，1=行首到游标（含），2=整行。 */
    fun eraseLine(mode: Int, fill: TextStyle) {
        when (mode) {
            0 -> blankRange(cursorY, cursorX, cols - 1, fill)
            1 -> blankRange(cursorY, 0, cursorX, fill)
            2 -> blankRange(cursorY, 0, cols - 1, fill)
        }
    }

    /** 屏幕擦除（ED）：0=游标到屏尾，1=屏首到游标（含），2/3=整屏。 */
    fun eraseDisplay(mode: Int, fill: TextStyle) {
        when (mode) {
            0 -> {
                blankRange(cursorY, cursorX, cols - 1, fill)
                for (y in cursorY + 1 until rows) grid[y] = blankRow(fill)
                markDirty(cursorY, rows - 1)
            }
            1 -> {
                for (y in 0 until cursorY) grid[y] = blankRow(fill)
                blankRange(cursorY, 0, cursorX, fill)
                markDirty(0, cursorY)
            }
            2, 3 -> {
                for (y in 0 until rows) grid[y] = blankRow(fill)
                markDirty(0, rows - 1)
            }
        }
    }

    /** 游标处插入 [n] 个空白格（ICH），行尾字符挤出丢弃。 */
    fun insertChars(n: Int, fill: TextStyle) {
        val row = grid[cursorY]
        val count = n.coerceIn(1, cols - cursorX)
        for (x in cols - 1 downTo cursorX + count) row[x] = row[x - count]
        for (x in cursorX until cursorX + count) row[x] = Cell.blank(fill)
        repairRow(cursorY, fill)
        markDirty(cursorY)
    }

    /** 游标处删除 [n] 个单元格（DCH），行尾补空白。 */
    fun deleteChars(n: Int, fill: TextStyle) {
        val row = grid[cursorY]
        val count = n.coerceIn(1, cols - cursorX)
        for (x in cursorX until cols - count) row[x] = row[x + count]
        for (x in cols - count until cols) row[x] = Cell.blank(fill)
        repairRow(cursorY, fill)
        markDirty(cursorY)
    }

    /** 游标起连续 [n] 格替换为空白（ECH），不移动后续字符。 */
    fun eraseChars(n: Int, fill: TextStyle) {
        val count = n.coerceIn(1, cols - cursorX)
        blankRange(cursorY, cursorX, cursorX + count - 1, fill)
    }

    /** 游标行处插入 [n] 个空行（IL），滚动区域底部行挤出丢弃。 */
    fun insertLines(n: Int, fill: TextStyle) {
        if (cursorY !in scrollTop..scrollBottom) return
        val count = n.coerceIn(1, scrollBottom - cursorY + 1)
        repeat(count) {
            for (y in scrollBottom downTo cursorY + 1) grid[y] = grid[y - 1]
            grid[cursorY] = blankRow(fill)
        }
        markDirty(cursorY, scrollBottom)
    }

    /** 删除游标行起 [n] 行（DL），滚动区域底部补空行。 */
    fun deleteLines(n: Int, fill: TextStyle) {
        if (cursorY !in scrollTop..scrollBottom) return
        val count = n.coerceIn(1, scrollBottom - cursorY + 1)
        repeat(count) {
            for (y in cursorY until scrollBottom) grid[y] = grid[y + 1]
            grid[scrollBottom] = blankRow(fill)
        }
        markDirty(cursorY, scrollBottom)
    }

    /** 整屏复位：清屏、游标归位、滚动区域回落全屏。 */
    fun reset() {
        for (y in 0 until rows) grid[y] = blankRow(TextStyle.DEFAULT)
        cursorX = 0
        cursorY = 0
        scrollTop = 0
        scrollBottom = rows - 1
        pendingWrap = false
        markDirty(0, rows - 1)
    }

    /**
     * 换网格尺寸（005：重排是 CLI 的事，这里只换尺寸不 reflow）。
     *
     * 左上角重叠区内容保留（等待快照重放覆盖前不至于白屏），滚动区域回落全屏。
     */
    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        val next = Array(newRows) { y ->
            Array(newCols) { x ->
                if (y < rows && x < cols) grid[y][x] else Cell.BLANK
            }
        }
        grid = next
        cols = newCols
        rows = newRows
        scrollTop = 0
        scrollBottom = newRows - 1
        cursorX = cursorX.coerceIn(0, newCols - 1)
        cursorY = cursorY.coerceIn(0, newRows - 1)
        pendingWrap = false
        for (y in 0 until newRows) repairRow(y, TextStyle.DEFAULT)
        markDirty(0, newRows - 1)
    }

    /** 深拷贝整屏行快照（外层行序 0..rows-1）。 */
    fun snapshotRows(): List<List<Cell>> = List(rows) { y -> grid[y].toList() }

    /** 取指定行的深拷贝。 */
    fun rowCells(y: Int): List<Cell> = grid[y].toList()

    /** 全屏标脏（切换主/备屏后强制整屏重绘用）。 */
    fun markAllDirty() {
        markDirty(0, rows - 1)
    }

    /** 取走并清空当前脏行区间；无脏行返回 null。 */
    fun takeDirty(): IntRange? {
        if (dirtyBottom < dirtyTop) return null
        val range = dirtyTop..dirtyBottom
        dirtyTop = Int.MAX_VALUE
        dirtyBottom = -1
        return range
    }

    private fun markDirty(y: Int) = markDirty(y, y)

    private fun markDirty(top: Int, bottom: Int) {
        if (top < dirtyTop) dirtyTop = top
        if (bottom > dirtyBottom) dirtyBottom = bottom
    }

    /** 把 [x0]..[x1]（含）填空白；两端切到宽字符时先把整字符抹掉，避免残留半个。 */
    private fun blankRange(y: Int, x0: Int, x1: Int, fill: TextStyle) {
        val row = grid[y]
        val from = x0.coerceIn(0, cols - 1)
        val to = x1.coerceIn(0, cols - 1)
        if (row[from].width == 0 && from > 0) row[from - 1] = Cell.blank(fill)
        if (row[to].width == 2 && to + 1 < cols) row[to + 1] = Cell.blank(fill)
        for (x in from..to) row[x] = Cell.blank(fill)
        markDirty(y)
    }

    /** 覆盖 [x] 处若命中宽字符任一半，把整个宽字符抹成空白。 */
    private fun clearWideAt(y: Int, x: Int, fill: TextStyle) {
        val row = grid[y]
        val cell = row[x]
        if (cell.width == 2) {
            row[x] = Cell.blank(fill)
            if (x + 1 < cols) row[x + 1] = Cell.blank(fill)
        } else if (cell.width == 0) {
            row[x] = Cell.blank(fill)
            if (x > 0) row[x - 1] = Cell.blank(fill)
        }
    }

    /** 修复整行的宽字符配对（插删/换尺寸后孤立的首格或续格抹成空白）。 */
    private fun repairRow(y: Int, fill: TextStyle) {
        val row = grid[y]
        for (x in 0 until cols) {
            val cell = row[x]
            if (cell.width == 2 && (x == cols - 1 || row[x + 1].width != 0)) {
                row[x] = Cell.blank(fill)
            } else if (cell.width == 0 && (x == 0 || row[x - 1].width != 2)) {
                row[x] = Cell.blank(fill)
            }
        }
    }

    private fun blankRow(fill: TextStyle): Array<Cell> = Array(cols) { Cell.blank(fill) }

    private companion object {
        const val TAB_STOP = 8
    }
}
