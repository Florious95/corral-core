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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增量流边界与脏区回调测试：UTF-8/CSI 跨 feed 切断、非法字节、脏行区间合并上报。
 */
class StreamAndDamageTest {

    @Test
    fun utf8SplitAcrossFeedsDecodesCorrectly() {
        val t = TerminalEmulator(10, 3)
        val bytes = "你".toByteArray(Charsets.UTF_8) // 3 字节
        t.feed(bytes, 0, 1)
        t.feed(bytes, 1, 2)
        assertEquals("你", t.cellAt(0, 0).text)
    }

    @Test
    fun csiSplitAcrossFeedsParsesCorrectly() {
        val t = TerminalEmulator(10, 3)
        t.feed("${E}[3")
        t.feed("1mX")
        assertEquals(TerminalColor.Indexed(1), t.cellAt(0, 0).style.fg)
    }

    @Test
    fun invalidUtf8ByteBecomesReplacementChar() {
        val t = TerminalEmulator(10, 3)
        t.feed(byteArrayOf(0xFF.toByte(), 'a'.code.toByte()))
        assertEquals("�", t.cellAt(0, 0).text)
        assertEquals("a", t.cellAt(1, 0).text)
    }

    @Test
    fun damageReportedForWrittenRow() {
        val t = TerminalEmulator(10, 5)
        val ranges = mutableListOf<IntRange>()
        t.damageListener = DamageListener { ranges.add(it) }
        t.feed("x")
        assertEquals(1, ranges.size)
        assertTrue(0 in ranges[0])
    }

    @Test
    fun damageCoalescedIntoSingleRangePerFeed() {
        val t = TerminalEmulator(10, 5)
        t.feed("${E}[H") // 排掉构造时的整屏初始脏区
        val ranges = mutableListOf<IntRange>()
        t.damageListener = DamageListener { ranges.add(it) }
        t.feed("a${E}[3;1Hb") // 同一次 feed 碰行 0 与行 2
        assertEquals(1, ranges.size)
        assertEquals(0..2, ranges[0])
    }

    @Test
    fun noDamageWhenNothingChanged() {
        val t = TerminalEmulator(10, 5)
        t.feed("x") // 先清掉初始脏区
        val ranges = mutableListOf<IntRange>()
        t.damageListener = DamageListener { ranges.add(it) }
        t.feed("${E}[2;2H") // 纯游标移动不标脏
        assertEquals(0, ranges.size)
    }
}
