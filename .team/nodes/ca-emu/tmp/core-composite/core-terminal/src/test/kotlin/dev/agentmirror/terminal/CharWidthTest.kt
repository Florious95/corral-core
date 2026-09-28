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
 * 码点宽度判定测试：ASCII/CJK/emoji/Hangul/组合记号/零宽连接符。
 */
class CharWidthTest {

    @Test
    fun asciiIsSingleWidth() {
        assertEquals(1, CharWidth.of('a'.code))
        assertEquals(1, CharWidth.of('~'.code))
        assertEquals(1, CharWidth.of(' '.code))
    }

    @Test
    fun cjkIsDoubleWidth() {
        assertEquals(2, CharWidth.of('你'.code))
        assertEquals(2, CharWidth.of('あ'.code))
        assertEquals(2, CharWidth.of('한'.code))
        assertEquals(2, CharWidth.of(0xFF21)) // 全角 Ａ
    }

    @Test
    fun emojiIsDoubleWidth() {
        assertEquals(2, CharWidth.of(0x1F600)) // 😀
        assertEquals(2, CharWidth.of(0x1F680)) // 🚀
        assertEquals(2, CharWidth.of(0x1F9E0)) // 🧠
    }

    @Test
    fun dogfoodEmojiFixtureUsesTwoCellGridWidth() {
        // 真机夹具中的 BMP emoji 也按两列推进，否则会覆盖紧随其后的空格。
        assertEquals(2, CharWidth.of(0x2705)) // ✅
        assertEquals(2, CharWidth.of(0x274C)) // ❌
        assertEquals(2, CharWidth.of(0x26A0)) // ⚠（后接 VS16）
    }

    @Test
    fun combiningAndJoinersAreZeroWidth() {
        assertEquals(0, CharWidth.of(0x0301)) // 组合尖音符
        assertEquals(0, CharWidth.of(0x200D)) // ZWJ
        assertEquals(0, CharWidth.of(0xFE0F)) // 变体选择符 VS16
    }

    @Test
    fun controlBytesAreZeroWidth() {
        assertEquals(0, CharWidth.of(0x1B))
        assertEquals(0, CharWidth.of(0x7F))
    }
}
