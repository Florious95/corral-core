package dev.agentmirror.app.session

import dev.agentmirror.terminal.Cell
import dev.agentmirror.terminal.ScreenSnapshot
import dev.agentmirror.terminal.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 光标锚定抽取：锚 = cursorY，禁止 rows-k；盒外装饰不进结果；失败不是空串。
 */
class InputLineExtractTest {

    @Test
    fun cursorNotOnLastRow_extractsCursorRowNotRowsMinusOne() {
        val lines = MutableList(12) { " ".repeat(20) }
        lines[4] = pad("completed-tab", 20)
        // 底行满宽且不是边界；中间空行截断扩张。若写死 rows-1 会抽到这一行。
        lines[11] = "X".repeat(20)
        val snap = snap(20, 12, cursorX = 13, cursorY = 4, lines)
        val r = InputLineExtract.extractByCursor(snap)
        assertTrue(r is InputLineExtract.Result.Ok)
        val ok = r as InputLineExtract.Result.Ok
        assertEquals("completed-tab", ok.text)
        assertEquals(4, ok.cursorY)
        assertTrue(ok.startRow != 11)
        assertTrue(ok.endRow != 11)
    }

    @Test
    fun boxedCursor_excludesDecorationRows() {
        val lines = listOf(
            "╭──────────╮",
            "│ hello    │",
            "╰──────────╯",
        )
        val snap = snap(12, 3, cursorX = 3, cursorY = 1, lines)
        val r = InputLineExtract.extractByCursor(snap)
        assertTrue(r is InputLineExtract.Result.Ok)
        val ok = r as InputLineExtract.Result.Ok
        assertEquals("hello", ok.text)
        assertEquals("box-drawing", ok.boundary)
        assertEquals(1, ok.startRow)
        assertEquals(1, ok.endRow)
        assertTrue(!ok.text.contains("╭") && !ok.text.contains("─"))
    }

    @Test
    fun cursorOnlyNoBox_degeneratesToCursorRow() {
        val lines = MutableList(6) { " ".repeat(10) }
        lines[2] = pad("plain", 10)
        val snap = snap(10, 6, cursorX = 5, cursorY = 2, lines)
        val r = InputLineExtract.extractByCursor(snap)
        assertTrue(r is InputLineExtract.Result.Ok)
        assertEquals("plain", (r as InputLineExtract.Result.Ok).text)
        assertEquals(2, r.startRow)
        assertEquals(2, r.endRow)
    }

    @Test
    fun softWrapTwoRows_joinsWithoutNewline() {
        val lines = listOf(
            " ".repeat(10),
            "ABCDEFGHIJ",
            pad("KLM", 10),
            " ".repeat(10),
        )
        val snap = snap(10, 4, cursorX = 3, cursorY = 2, lines)
        val r = InputLineExtract.extractByCursor(snap)
        assertTrue(r is InputLineExtract.Result.Ok)
        assertEquals("ABCDEFGHIJKLM", (r as InputLineExtract.Result.Ok).text)
    }

    @Test
    fun blankCursorRow_returnsFailNotEmptyString() {
        val lines = List(4) { " ".repeat(8) }
        val snap = snap(8, 4, cursorX = 0, cursorY = 2, lines)
        val r = InputLineExtract.extractByCursor(snap)
        assertTrue(r is InputLineExtract.Result.Fail)
        assertTrue((r as InputLineExtract.Result.Fail).reason.isNotEmpty())
    }

    @Test
    fun cursorOutOfGrid_returnsFail() {
        val lines = listOf(pad("x", 4), pad("y", 4))
        val snap = ScreenSnapshot(
            cols = 4,
            rows = 2,
            cursorX = 0,
            cursorY = 9,
            cursorVisible = true,
            altScreen = false,
            lines = lines.map { rowCells(it) },
        )
        val r = InputLineExtract.extractByCursor(snap)
        assertTrue(r is InputLineExtract.Result.Fail)
        assertEquals("cursor-out-of-grid", (r as InputLineExtract.Result.Fail).reason)
    }

    private fun pad(s: String, cols: Int): String =
        if (s.length >= cols) s.take(cols) else s + " ".repeat(cols - s.length)

    private fun rowCells(s: String, inverse: Boolean = false): List<Cell> {
        val style = if (inverse) TextStyle(inverse = true) else TextStyle.DEFAULT
        return s.map { Cell(it.toString(), style, 1) }
    }

    private fun snap(
        cols: Int,
        rows: Int,
        cursorX: Int,
        cursorY: Int,
        lines: List<String>,
        alt: Boolean = false,
    ): ScreenSnapshot {
        require(lines.size == rows)
        return ScreenSnapshot(
            cols = cols,
            rows = rows,
            cursorX = cursorX,
            cursorY = cursorY,
            cursorVisible = true,
            altScreen = alt,
            lines = lines.map { rowCells(pad(it, cols)) },
        )
    }
}
