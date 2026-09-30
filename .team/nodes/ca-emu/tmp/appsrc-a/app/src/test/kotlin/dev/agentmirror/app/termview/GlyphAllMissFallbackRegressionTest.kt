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
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * D-35 根因探针：候选字体全 miss 后，送往 Canvas 的文本仍必须由所选槽位画得出来。
 *
 * 真实 Claude Code 状态前缀的两个字符已确认为 U+23F5；但修复仍须是通用回退策略，
 * 所以安全网探针另覆盖未分配 BMP、私用区与非 BMP 码点，防止退化成单码位补丁。
 */
class GlyphAllMissFallbackRegressionTest {

    /** 模拟设备三槽均缺目标字形，但 MONO 至少可靠提供可打印 ASCII。 */
    private open class AllMissingProbe : GlyphProbe {
        override fun hasGlyph(codepoint: Int, slot: GlyphSlot): Boolean =
            slot == GlyphSlot.MONO && codepoint in 0x20..0x7E
    }

    @Test
    fun actualBypassTrianglesMapToProbedDrawableTriangles() {
        val probe = object : AllMissingProbe() {
            override fun hasGlyph(codepoint: Int, slot: GlyphSlot): Boolean =
                super.hasGlyph(codepoint, slot) ||
                    (slot == GlyphSlot.SYSTEM_FALLBACK && codepoint == 0x25B8)
        }
        val builder = GlyphRunBuilder(GlyphFallbackPolicy(probe))

        val rendered = builder.build("\u23F5\u23F5", startCol = 0)
            .joinToString(separator = "") { it.text }

        // 隔离实证 U+25B8 有真字形且形态匹配参考；"??" 仅是安全网，不是 D-35 修复。
        assertEquals("\u25B8\u25B8", rendered)
        rendered.codePoints().forEach { codepoint ->
            assertEquals(true, probe.hasGlyph(codepoint, GlyphSlot.SYSTEM_FALLBACK))
        }
    }

    @Test
    fun equivalentIsRejectedWhenItsTargetAlsoLacksAGlyph() {
        val rendered = GlyphRunBuilder(GlyphFallbackPolicy(AllMissingProbe()))
            .build("\u23F5", startCol = 0)
            .single()

        // 映射表只表达形近关系；目标 U+25B8 未通过 probe 时仍必须落最后安全网。
        assertEquals(GlyphSlot.MONO, rendered.slot)
        assertEquals("?", rendered.text)
    }

    @Test
    fun sourceGlyphIsPreservedWithoutConsultingEquivalentMapping() {
        val probe = GlyphProbe { codepoint, slot ->
            slot == GlyphSlot.SYSTEM_FALLBACK && codepoint == 0x23F5
        }

        val rendered = GlyphRunBuilder(GlyphFallbackPolicy(probe))
            .build("\u23F5", startCol = 0)
            .single()

        assertEquals(GlyphSlot.SYSTEM_FALLBACK, rendered.slot)
        assertEquals("\u23F5", rendered.text)
    }

    @Test
    fun allMissingCodepointsLeaveTheMissingFontSlots() {
        val probe = AllMissingProbe()
        val policy = GlyphFallbackPolicy(probe)
        // 与 D-35 三角分开：这些任意 BMP/PUA/astral 缺口用于验证最后安全网本身。
        val missing = intArrayOf(0x0378, 0xE200, 0x10FFFD)

        missing.forEach { codepoint ->
            val selected = policy.resolve(codepoint)
            // 不能退回任一已经探明 miss 的字体槽；旧实现 else -> MONO 正在这里命中。
            assertFalse(
                "all-miss U+%04X was sent back to missing font slot $selected".format(codepoint),
                selected in listOf(GlyphSlot.MONO, GlyphSlot.SYSTEM_FALLBACK, GlyphSlot.POWERLINE),
            )
        }

        val fixture = buildString { missing.forEach(::appendCodePoint) }
        val segments = GlyphRunBuilder(GlyphFallbackPolicy(probe)).build(fixture, startCol = 0)
        val rendered = segments.joinToString(separator = "") { it.text }
        // Builder 是 Canvas 前最后一环：原始缺字码点必须消失，且每个原终端列有一个 MONO '?'。
        missing.forEach { codepoint ->
            assertFalse(rendered.codePoints().anyMatch { it == codepoint })
        }
        assertEquals("?".repeat(missing.sumOf(CharWidth::of)), rendered)
        assertEquals(listOf(GlyphSlot.MONO), segments.map { it.slot }.distinct())
    }

    @Test
    fun existingCjkAndPowerlineGlyphsAreNotReplaced() {
        val probe = GlyphProbe { codepoint, slot ->
            when (slot) {
                GlyphSlot.MONO -> codepoint in 0x20..0x7E
                GlyphSlot.SYSTEM_FALLBACK -> codepoint == 0x4F60
                GlyphSlot.POWERLINE -> codepoint == 0xE0B0
                GlyphSlot.VISIBLE_FALLBACK -> false
            }
        }
        val builder = GlyphRunBuilder(GlyphFallbackPolicy(probe))

        val segments = builder.build("A你\uE0B0", startCol = 0)

        assertEquals("A你\uE0B0", segments.joinToString(separator = "") { it.text })
        assertEquals(
            listOf(GlyphSlot.MONO, GlyphSlot.SYSTEM_FALLBACK, GlyphSlot.POWERLINE),
            segments.map { it.slot },
        )
    }
}
