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
 * alternate screen 测试：进出切换、历史不可用标记（006 边界）、主屏内容与游标恢复。
 */
class AltScreenTest {

    @Test
    fun enterAltGivesBlankScreenAndBlocksHistory() {
        val t = TerminalEmulator(10, 3)
        t.feed("main")
        t.feed("${E}[?1049h")
        assertTrue(t.snapshot().altScreen)
        assertFalse(t.historyAvailable) // 006：全屏 TUI 期间历史不可用
        assertEquals("", t.rowText(0)) // 备屏全新空白
        t.feed("tui")
        assertEquals("tui", t.rowText(0))
    }

    @Test
    fun exitAltRestoresMainContentAndCursor() {
        val t = TerminalEmulator(10, 3)
        t.feed("main${E}[?1049htui${E}[?1049l")
        assertFalse(t.snapshot().altScreen)
        assertTrue(t.historyAvailable)
        assertEquals("main", t.rowText(0))
        t.feed("X") // 游标恢复到 main 之后
        assertEquals("mainX", t.rowText(0))
    }

    @Test
    fun altScreenScrollingNeverEntersScrollback() {
        val t = TerminalEmulator(5, 2)
        t.feed("${E}[?1049ha\r\nb\r\nc\r\nd") // 备屏内滚动两次
        assertEquals(0, t.scrollback.size)
        t.feed("${E}[?1049l")
        assertEquals(0, t.scrollback.size)
    }

    @Test
    fun mode47AlsoSwitchesScreens() {
        val t = TerminalEmulator(10, 3)
        t.feed("${E}[?47h")
        assertTrue(t.snapshot().altScreen)
        t.feed("${E}[?47l")
        assertFalse(t.snapshot().altScreen)
    }

    @Test
    fun prependHistoryIgnoredWhileAltActive() {
        val t = TerminalEmulator(10, 3)
        t.feed("${E}[?1049h")
        t.prependHistory("h1\n")
        assertEquals(0, t.scrollback.size)
    }
}
