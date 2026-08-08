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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快照重放测试（capture-pane -e 语义）：清屏重建、样式/解析状态复位、scrollback 保留、换尺寸。
 */
class SnapshotReplayTest {

    @Test
    fun replayRebuildsScreenWithStyles() {
        val t = TerminalEmulator(20, 5)
        t.feed("old stale content")
        t.replaySnapshot("${E}[31mred${E}[0m ok\r\nline2", 10, 4)
        assertEquals("red ok", t.rowText(0))
        assertEquals("line2", t.rowText(1))
        assertEquals("", t.rowText(2)) // 旧内容不残留
        assertEquals(TerminalColor.Indexed(1), t.cellAt(0, 0).style.fg)
        assertEquals(TextStyle.DEFAULT, t.cellAt(4, 0).style)
    }

    @Test
    fun replayChangesGridSize() {
        val t = TerminalEmulator(20, 5)
        t.replaySnapshot("x", 7, 3)
        val s = t.snapshot()
        assertEquals(7, s.cols)
        assertEquals(3, s.rows)
    }

    @Test
    fun replayKeepsLocalScrollback() {
        val t = TerminalEmulator(5, 2)
        t.feed("a\r\nb\r\nc") // a 进 scrollback
        t.replaySnapshot("fresh", 5, 2)
        assertEquals(1, t.scrollback.size)
        assertEquals("a", t.scrollbackText(0))
    }

    @Test
    fun replayResetsAltScreenAndStyle() {
        val t = TerminalEmulator(10, 3)
        t.feed("${E}[?1049h${E}[31;1m") // 备屏 + 红色粗体
        t.replaySnapshot("x", 10, 3)
        assertFalse(t.snapshot().altScreen)
        assertTrue(t.historyAvailable)
        t.feed("y")
        assertEquals(TextStyle.DEFAULT, t.cellAt(1, 0).style) // 样式已复位
    }

    @Test
    fun replayDropsHalfParsedEscapeSequence() {
        val t = TerminalEmulator(10, 3)
        t.feed("${E}[3") // 半截 CSI 断在 feed 边界
        t.replaySnapshot("ok", 10, 3)
        assertEquals("ok", t.rowText(0))
    }

    @Test
    fun replayHonorsCursorPositioningInSnapshot() {
        val t = TerminalEmulator(10, 3)
        t.replaySnapshot("ab${E}[2;1Hcd", 5, 3)
        assertEquals("ab", t.rowText(0))
        assertEquals("cd", t.rowText(1))
        assertEquals(2, t.snapshot().cursorX)
        assertEquals(1, t.snapshot().cursorY)
    }
}
