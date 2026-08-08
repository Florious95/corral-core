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
 * 宽字符/emoji/组合字符测试：占两格、半字符覆盖、行尾换行、组合并入前格。
 */
class WideCharTest {

    @Test
    fun cjkOccupiesTwoCells() {
        val t = TerminalEmulator(10, 3)
        t.feed("你")
        assertEquals("你", t.cellAt(0, 0).text)
        assertEquals(2, t.cellAt(0, 0).width)
        assertEquals(0, t.cellAt(1, 0).width) // 续格占位
    }

    @Test
    fun textAfterWideCharStartsAtThirdColumn() {
        val t = TerminalEmulator(10, 3)
        t.feed("你a")
        assertEquals("a", t.cellAt(2, 0).text)
    }

    @Test
    fun emojiOccupiesTwoCells() {
        val t = TerminalEmulator(10, 3)
        t.feed("😀b") // 😀
        assertEquals("😀", t.cellAt(0, 0).text)
        assertEquals(0, t.cellAt(1, 0).width)
        assertEquals("b", t.cellAt(2, 0).text)
    }

    @Test
    fun overwritingHalfClearsWholeWideChar() {
        val t = TerminalEmulator(10, 3)
        t.feed("你\rx") // 覆盖首格
        assertEquals("x", t.rowText(0))
        assertEquals(1, t.cellAt(1, 0).width) // 续格被抹成普通空白
    }

    @Test
    fun erasingHalfClearsWholeWideChar() {
        val t = TerminalEmulator(10, 3)
        t.feed("你好${E}[1;2H${E}[1X") // ECH 打在"你"的续格上
        assertEquals(" ", t.cellAt(0, 0).text)
        assertEquals("好", t.cellAt(2, 0).text)
    }

    @Test
    fun wideCharWrapsWhenNotFittingAtRowEnd() {
        val t = TerminalEmulator(5, 3)
        t.feed("abcd你") // 第 5 列放不下宽字符，整字符落到下一行
        assertEquals("abcd", t.rowText(0))
        assertEquals("你", t.rowText(1))
    }

    @Test
    fun wideCharAtExactRowEndSetsPendingWrap() {
        val t = TerminalEmulator(4, 3)
        t.feed("ab你")
        assertEquals("ab你", t.rowText(0))
        t.feed("x") // pending-wrap：下个字符才换行
        assertEquals("x", t.rowText(1))
    }

    @Test
    fun combiningMarkAttachesToPreviousCell() {
        val t = TerminalEmulator(10, 3)
        t.feed("é") // e + 组合尖音符
        assertEquals("é", t.cellAt(0, 0).text)
        assertEquals(1, t.snapshot().cursorX)
    }

    @Test
    fun combiningMarkAttachesToWideChar() {
        val t = TerminalEmulator(10, 3)
        t.feed("你́")
        assertEquals("你́", t.cellAt(0, 0).text)
    }
}
