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
 * 自动换行与本地 scrollback 测试：pending-wrap、滚出进环形缓冲、容量淘汰、历史头插、滚动区域。
 */
class WrapAndScrollbackTest {

    @Test
    fun autowrapUsesPendingWrapSemantics() {
        val t = TerminalEmulator(5, 3)
        t.feed("abcde")
        // 写满末列后游标悬停在末列，不立即换行。
        assertEquals(4, t.snapshot().cursorX)
        assertEquals(0, t.snapshot().cursorY)
        t.feed("f")
        assertEquals("abcde", t.rowText(0))
        assertEquals("f", t.rowText(1))
    }

    @Test
    fun lineFeedAtBottomPushesTopLineToScrollback() {
        val t = TerminalEmulator(5, 2)
        t.feed("a\r\nb\r\nc")
        assertEquals(1, t.scrollback.size)
        assertEquals("a", t.scrollbackText(0))
        assertEquals("b", t.rowText(0))
        assertEquals("c", t.rowText(1))
    }

    @Test
    fun ringCapacityEvictsOldestLine() {
        val t = TerminalEmulator(5, 1, scrollbackCapacity = 2)
        t.feed("a\r\nb\r\nc\r\nd")
        assertEquals(2, t.scrollback.size)
        assertEquals("b", t.scrollbackText(0))
        assertEquals("c", t.scrollbackText(1))
    }

    @Test
    fun prependHistoryInsertsAtHead() {
        val t = TerminalEmulator(5, 2)
        t.feed("x\r\ny\r\nz") // x 滚入 scrollback
        t.prependHistory("h1\nh2\n")
        assertEquals(3, t.scrollback.size)
        assertEquals("h1", t.scrollbackText(0))
        assertEquals("h2", t.scrollbackText(1))
        assertEquals("x", t.scrollbackText(2))
    }

    @Test
    fun prependHistoryRespectsRemainingCapacity() {
        val t = TerminalEmulator(5, 2, scrollbackCapacity = 3)
        t.feed("x\r\ny\r\nz") // scrollback: [x]
        t.prependHistory("h1\nh2\nh3\nh4\nh5\n")
        // 只剩 2 格：保留与现有头部衔接的最新两行 h4/h5。
        assertEquals(3, t.scrollback.size)
        assertEquals("h4", t.scrollbackText(0))
        assertEquals("h5", t.scrollbackText(1))
        assertEquals("x", t.scrollbackText(2))
    }

    @Test
    fun historyLinesKeepSgrStyles() {
        val t = TerminalEmulator(10, 2)
        t.prependHistory("${E}[31mred${E}[0m plain\n")
        val line = t.scrollback.line(0)
        assertEquals(TerminalColor.Indexed(1), line[0].style.fg)
        assertEquals(TerminalColor.Default, line[4].style.fg)
    }

    @Test
    fun scrollRegionScrollDoesNotEnterScrollback() {
        val t = TerminalEmulator(5, 5)
        t.feed("r1\r\nr2\r\nr3\r\nr4\r\nr5")
        t.feed("${E}[2;4r${E}[4;1H\n") // 区域 2..4，区域底 LF
        assertEquals(0, t.scrollback.size)
        assertEquals("r1", t.rowText(0)) // 区域外不动
        assertEquals("r3", t.rowText(1))
        assertEquals("r4", t.rowText(2))
        assertEquals("", t.rowText(3))
        assertEquals("r5", t.rowText(4)) // 区域外不动
    }

    @Test
    fun reverseLineFeedAtTopScrollsDown() {
        val u = TerminalEmulator(5, 3)
        u.feed("a\r\nb${E}[H${E}M")
        assertEquals("", u.rowText(0))
        assertEquals("a", u.rowText(1))
        assertEquals("b", u.rowText(2))
        assertEquals(0, u.scrollback.size)
    }

    @Test
    fun resizePreservesTopLeftContent() {
        val t = TerminalEmulator(10, 4)
        t.feed("hello\r\nworld")
        t.resize(3, 2)
        val s = t.snapshot()
        assertEquals(3, s.cols)
        assertEquals(2, s.rows)
        assertEquals("hel", t.rowText(0))
        assertEquals("wor", t.rowText(1))
    }
}
