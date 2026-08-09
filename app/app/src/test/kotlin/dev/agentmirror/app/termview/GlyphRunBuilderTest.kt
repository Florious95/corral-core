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

package dev.agentmirror.app.termview

import dev.agentmirror.terminal.CharWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字形分段规划测试：夹具字符串（盲文轮转/框线/CJK/emoji 混排）断言段分组与列对齐
 * （红测先行，任务 goal：测量断言不破坏——宽字符仍占两格、段起列按宽度推进）。
 */
class GlyphRunBuilderTest {

    private class FakeGlyphProbe : GlyphProbe {
        override fun hasGlyph(codepoint: Int, slot: GlyphSlot): Boolean = when (slot) {
            GlyphSlot.MONO -> codepoint in 0x20..0x7E
            GlyphSlot.SYSTEM_FALLBACK -> systemRanges.any { codepoint in it }
            GlyphSlot.POWERLINE -> codepoint in 0xE0A0..0xE0B3
        }

        private val systemRanges = listOf(
            0x20..0x7E,
            0x2190..0x2200,
            0x2300..0x2400,
            0x2500..0x2580,
            0x2580..0x25A0,
            0x25A0..0x2600,
            0x2800..0x2900,
            0x4E00..0x9FFF,
            0xFF00..0xFF60,
            0xFFE0..0xFFE6,
            0x1F300..0x1F64F,
            0x1F680..0x1F6FF,
            0x1F900..0x1FAFF,
        )
    }

    private val builder = GlyphRunBuilder(GlyphFallbackPolicy(FakeGlyphProbe()))

    /** 断言一个段组的槽位/文本/起列与期望一致。 */
    private fun assertSegment(slot: GlyphSlot, text: String, startCol: Int, seg: GlyphSegment) {
        assertEquals(slot, seg.slot)
        assertEquals(text, seg.text)
        assertEquals(startCol, seg.startCol)
    }

    @Test
    fun monoRunStaysSingleSegment() {
        val segs = builder.build("hello world", 0)
        assertEquals(1, segs.size)
        assertSegment(GlyphSlot.MONO, "hello world", 0, segs[0])
    }

    @Test
    fun brailleBoxBlockGroupIntoSingleSystemSegment() {
        // 盲文轮转 + 框线 + 块元素全落 SYSTEM_FALLBACK：连续同槽位聚成一段。
        val segs = builder.build("⠋┌─█", 0)
        assertEquals(1, segs.size)
        assertSegment(GlyphSlot.SYSTEM_FALLBACK, "⠋┌─█", 0, segs[0])
    }

    @Test
    fun monoAndFallbackSplitAtSlotBoundary() {
        val segs = builder.build("a█b", 0)
        assertEquals(3, segs.size)
        assertSegment(GlyphSlot.MONO, "a", 0, segs[0])
        assertSegment(GlyphSlot.SYSTEM_FALLBACK, "█", 1, segs[1])
        assertSegment(GlyphSlot.MONO, "b", 2, segs[2])
    }

    @Test
    fun combiningMarkStaysWithBaseSegment() {
        // e + 组合尖音符：整体一个 MONO 段（不能把组合音符单独切段）。
        val segs = builder.build("é", 0)
        assertEquals(1, segs.size)
        assertSegment(GlyphSlot.MONO, "é", 0, segs[0])
    }

    @Test
    fun wideCharsKeepColumnAlignment() {
        // 你(2格) A(1格) 😀(2格)：段起列按宽度推进，宽字符不破坏列对齐。
        val segs = builder.build("你A😀", 0)
        assertEquals(3, segs.size)
        assertSegment(GlyphSlot.SYSTEM_FALLBACK, "你", 0, segs[0])   // 你占第 0-1 列
        assertSegment(GlyphSlot.MONO, "A", 2, segs[1])               // A 落第 2 列
        assertSegment(GlyphSlot.SYSTEM_FALLBACK, "😀", 3, segs[2])   // 😀 占第 3-4 列（astral 双宽）
    }

    @Test
    fun startColOffsetAppliesToAllSegments() {
        // 段首从非零列开始：所有段的起列都平移 startCol。
        val segs = builder.build("█a", 7)
        assertEquals(2, segs.size)
        assertSegment(GlyphSlot.SYSTEM_FALLBACK, "█", 7, segs[0])
        assertSegment(GlyphSlot.MONO, "a", 8, segs[1])
    }

    @Test
    fun totalColumnWidthPreserved() {
        // 测量断言：任意混排夹具，各码点宽度之和 == 段末列推进（CharWidth 权威，分段不改宽）。
        val fixture = "⠋⠙⠹┌─┐你A😀█▓░hello"
        val segs = builder.build(fixture, 0)
        val totalByCharWidth = fixture.codePoints().toArray().sumOf { CharWidth.of(it) }
        val last = segs.last()
        val lastWidth = last.text.codePoints().toArray().sumOf { CharWidth.of(it) }
        assertEquals(totalByCharWidth, last.startCol + lastWidth)
    }

    @Test
    fun emptyTextReturnsEmpty() {
        assertTrue(builder.build("", 0).isEmpty())
    }

    @Test
    fun powerlinePuaSeparatedFromSystemFallback() {
        // Powerline PUA 与盲文不连续槽位：切成独立 POWERLINE 段。
        val segs = builder.build("⠋", 0)
        assertEquals(2, segs.size)
        assertSegment(GlyphSlot.SYSTEM_FALLBACK, "⠋", 0, segs[0])
        assertSegment(GlyphSlot.POWERLINE, "", 1, segs[1])
    }

    @Test
    fun astralEmojiKeepsTwoCellWidth() {
        // 😀 是 astral 码点（两 UTF-16 单元），按码点切段且占两格。
        val segs = builder.build("😀", 5)
        assertEquals(1, segs.size)
        assertSegment(GlyphSlot.SYSTEM_FALLBACK, "😀", 5, segs[0])
        assertEquals(2, CharWidth.of(0x1F600))
    }
}
