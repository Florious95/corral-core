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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 游标移动与擦除/插删测试：CUP/CUU/CUD/CUF/CUB 钳制、EL/ED、ICH/DCH/ECH、IL/DL、TAB/BS。
 */
class CursorAndEraseTest {

    private fun term(cols: Int = 10, rows: Int = 5) = TerminalEmulator(cols, rows)

    @Test
    fun cupPositionsCursorOneBased() {
        val t = term()
        t.feed("${E}[3;4HX")
        assertEquals("X", t.cellAt(3, 2).text)
    }

    @Test
    fun cursorMovesClampAtEdges() {
        val t = term()
        t.feed("${E}[99;99H") // 越界钳制到右下角
        var s = t.snapshot()
        assertEquals(9, s.cursorX)
        assertEquals(4, s.cursorY)
        t.feed("${E}[99A${E}[99D") // 上移+左移越界钳制到左上角
        s = t.snapshot()
        assertEquals(0, s.cursorX)
        assertEquals(0, s.cursorY)
    }

    @Test
    fun relativeMovesUseDefaultOne() {
        val t = term()
        t.feed("${E}[2;2H${E}[A${E}[C")
        val s = t.snapshot()
        assertEquals(2, s.cursorX)
        assertEquals(0, s.cursorY)
    }

    @Test
    fun eraseLineVariants() {
        val t = term(5, 3)
        t.feed("abcde${E}[1;3H${E}[K")
        assertEquals("ab", t.rowText(0)) // EL0：游标(含)到行尾
        t.feed("${E}[2;1Habcde${E}[2;3H${E}[1K")
        assertEquals("de", t.rowText(1).trimStart().let { it }) // EL1：行首到游标(含)
        assertEquals(" ", t.cellAt(2, 1).text) // 游标位也被清
        t.feed("${E}[3;1Habcde${E}[2K")
        assertEquals("", t.rowText(2)) // EL2：整行
    }

    @Test
    fun eraseDisplayVariants() {
        val t = term(5, 3)
        t.feed("aaaaa\r\nbbbbb\r\nccccc")
        t.feed("${E}[2;3H${E}[J") // ED0：游标到屏尾
        assertEquals("aaaaa", t.rowText(0))
        assertEquals("bb", t.rowText(1))
        assertEquals("", t.rowText(2))
        val u = term(5, 3)
        u.feed("aaaaa\r\nbbbbb\r\nccccc")
        u.feed("${E}[2;3H${E}[1J") // ED1：屏首到游标
        assertEquals("", u.rowText(0))
        assertEquals("bb", u.rowText(1).trimStart().let { it })
        assertEquals("ccccc", u.rowText(2))
        u.feed("${E}[2J")
        assertEquals("", u.rowText(0) + u.rowText(1) + u.rowText(2)) // ED2：整屏
    }

    @Test
    fun insertDeleteEraseChars() {
        val t = term(5, 3)
        t.feed("abcde${E}[1;2H${E}[2@") // ICH2：插两空格，de 挤出
        assertEquals("a  bc", t.rowText(0).padEnd(5).substring(0, 5))
        val u = term(5, 3)
        u.feed("abcde${E}[1;2H${E}[2P") // DCH2：删 bc
        assertEquals("ade", u.rowText(0))
        val v = term(5, 3)
        v.feed("abcde${E}[1;2H${E}[2X") // ECH2：bc 变空白，de 不动
        assertEquals("a  de", v.rowText(0))
    }

    @Test
    fun insertAndDeleteLines() {
        val t = term(5, 4)
        t.feed("aa\r\nbb\r\ncc\r\ndd")
        t.feed("${E}[2;1H${E}[L") // 第 2 行插一空行
        assertEquals("aa", t.rowText(0))
        assertEquals("", t.rowText(1))
        assertEquals("bb", t.rowText(2))
        assertEquals("cc", t.rowText(3)) // dd 挤出屏幕
        t.feed("${E}[2;1H${E}[M") // 删掉空行
        assertEquals("bb", t.rowText(1))
        assertEquals("cc", t.rowText(2))
        assertEquals("", t.rowText(3))
    }

    @Test
    fun tabAndBackspace() {
        val t = term(20, 3)
        t.feed("ab\bX") // BS 退一格后覆盖
        assertEquals("aX", t.rowText(0))
        t.feed("\r\n\tY") // TAB 跳到第 8 列
        assertEquals("Y", t.cellAt(8, 1).text)
    }

    @Test
    fun saveRestoreCursorKeepsStyle() {
        val t = term()
        t.feed("${E}[2;3H${E}[31m${E}7${E}[H${E}[mZ${E}8W")
        // ESC 8 恢复位置(2,3)与红色前景。
        val cell = t.cellAt(2, 1)
        assertEquals("W", cell.text)
        assertEquals(TerminalColor.Indexed(1), cell.style.fg)
    }

    @Test
    fun oscSequencesAreIgnored() {
        val t = term()
        t.feed("${E}]0;window title\u0007ok")
        assertEquals("ok", t.rowText(0))
    }

    @Test
    fun cursorVisibilityFollowsDecMode() {
        val t = term()
        t.feed("${E}[?25l")
        assertEquals(false, t.snapshot().cursorVisible)
        t.feed("${E}[?25h")
        assertEquals(true, t.snapshot().cursorVisible)
    }
}
