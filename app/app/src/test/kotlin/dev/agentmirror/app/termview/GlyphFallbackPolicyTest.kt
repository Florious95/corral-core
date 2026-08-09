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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 字形回退策略测试：夹具字符串（盲文轮转/框线/块元素/CJK/emoji/Powerline 混排）
 * 逐码点断言 hasGlyph 判定后的最终槽位（红测先行，任务 goal：逐字符 hasGlyph 断言）。
 *
 * 假探针编码真机字体覆盖事实（Field 实证 + fontTools 实测本机仿真器字体）：
 * MONOSPACE(DroidSansMono) 仅 ASCII；系统 sans fallback 覆盖盲文/框线/块/几何/符号/箭头/
 * CJK/emoji/全角；内置 PowerlineSymbols 仅 PUA 常用字形 + U+2588。
 */
class GlyphFallbackPolicyTest {

    /** 真机覆盖事实的假探针：码点落在区间内即判有字形。 */
    private class FakeGlyphProbe : GlyphProbe {
        override fun hasGlyph(codepoint: Int, slot: GlyphSlot): Boolean = when (slot) {
            GlyphSlot.MONO -> codepoint in 0x20..0x7E
            GlyphSlot.SYSTEM_FALLBACK -> inSystemRanges(codepoint)
            GlyphSlot.POWERLINE -> codepoint in POWERLINE_COVERED
        }

        companion object {
            /** 内置 PowerlineSymbols 的 cmap（实测 8 字形）。 */
            private val POWERLINE_COVERED = setOf(0x2588, 0xE0A0, 0xE0A1, 0xE0A2, 0xE0B0, 0xE0B1, 0xE0B2, 0xE0B3)

            /** 系统 sans fallback 覆盖的区段（与 CharWidth 宽字符区间对齐的实测区段）。 */
            private val SYSTEM_RANGES = listOf(
                0x20..0x7E,          // ASCII
                0x2190..0x2200,      // 箭头
                0x2300..0x2400,      // 杂项技术符号
                0x2500..0x2580,      // 框线
                0x2580..0x25A0,      // 块元素
                0x25A0..0x2600,      // 几何形状
                0x2800..0x2900,      // 盲文
                0x4E00..0x9FFF,      // CJK 统一表意
                0xFF00..0xFF60,      // 全角形式
                0xFFE0..0xFFE6,      // 全角符号
                0x1F300..0x1F64F,    // emoji 主区段
                0x1F680..0x1F6FF,    // 交通 emoji
                0x1F900..0x1FAFF,    // 补充 emoji
            )

            private fun inSystemRanges(cp: Int): Boolean = SYSTEM_RANGES.any { cp in it }
        }
    }

    /** 带调用计数的探针：验证缓存命中后不再重复探。 */
    private class CountingProbe : GlyphProbe {
        var monoCalls = 0
        var fallbackCalls = 0
        private val fake = FakeGlyphProbe()
        override fun hasGlyph(codepoint: Int, slot: GlyphSlot): Boolean {
            when (slot) {
                GlyphSlot.MONO -> monoCalls++
                GlyphSlot.SYSTEM_FALLBACK -> fallbackCalls++
                GlyphSlot.POWERLINE -> {}
            }
            return fake.hasGlyph(codepoint, slot)
        }
    }

    private val policy = GlyphFallbackPolicy(FakeGlyphProbe())

    // ---- ASCII：主等宽快速路径 ----

    @Test
    fun asciiPrintableResolvesToMono() {
        // ASCII 可打印预填 MONO：连探针都不调。
        val p = GlyphFallbackPolicy(CountingProbe())
        assertEquals(GlyphSlot.MONO, p.resolve('A'.code))
        assertEquals(GlyphSlot.MONO, p.resolve('z'.code))
        assertEquals(GlyphSlot.MONO, p.resolve('0'.code))
        assertEquals(GlyphSlot.MONO, p.resolve(' '.code))
    }

    // ---- 盲文轮转符（Claude Code spinner 主力）----

    @Test
    fun brailleSpinnerFallsBackToSystem() {
        // 盲文 U+280B ⠋ 等：MONO 覆盖率 0%，回退系统 sans（NotoSansSymbols 100% 覆盖）。
        for (cp in listOf(0x280B, 0x2819, 0x2839, 0x2838, 0x283C, 0x2834, 0x2826, 0x2827, 0x2807, 0x280F)) {
            assertEquals("U+%04X".format(cp), GlyphSlot.SYSTEM_FALLBACK, policy.resolve(cp))
        }
    }

    // ---- 框线/块元素 ----

    @Test
    fun boxDrawingFallsBackToSystem() {
        for (cp in listOf(0x250C, 0x2500, 0x2502, 0x2514, 0x2518, 0x251C, 0x2524)) {
            assertEquals("U+%04X".format(cp), GlyphSlot.SYSTEM_FALLBACK, policy.resolve(cp))
        }
    }

    @Test
    fun blockElementsFallBackToSystem() {
        for (cp in listOf(0x2588, 0x2593, 0x2592, 0x2591)) {
            assertEquals("U+%04X".format(cp), GlyphSlot.SYSTEM_FALLBACK, policy.resolve(cp))
        }
    }

    // ---- CJK / emoji（双宽字符）----

    @Test
    fun cjkAndEmojiFallBackToSystem() {
        assertEquals(GlyphSlot.SYSTEM_FALLBACK, policy.resolve(0x4F60)) // 你
        assertEquals(GlyphSlot.SYSTEM_FALLBACK, policy.resolve(0x597D)) // 好
        assertEquals(GlyphSlot.SYSTEM_FALLBACK, policy.resolve(0x4E16)) // 世
        assertEquals(GlyphSlot.SYSTEM_FALLBACK, policy.resolve(0x1F600)) // 😀 astral
        assertEquals(GlyphSlot.SYSTEM_FALLBACK, policy.resolve(0x1F680)) // 🚀
        assertEquals(GlyphSlot.SYSTEM_FALLBACK, policy.resolve(0xFF21)) // Ａ 全角
    }

    // ---- Powerline 私有区：内置兜底优先 ----

    @Test
    fun powerlinePuaUsesBundledFont() {
        for (cp in listOf(0xE0B0, 0xE0B1, 0xE0A0, 0xE0A1, 0xE0A2, 0xE0B2, 0xE0B3)) {
            assertEquals("U+%04X".format(cp), GlyphSlot.POWERLINE, policy.resolve(cp))
        }
    }

    @Test
    fun puaOutsideBundledFontFallsToSystemThenMono() {
        // 内置 Powerline 未覆盖的 PUA（如 Nerd Font 扩展区）：系统 sans 也无 → 保底 MONO（豆腐优于崩溃，缺口留档）。
        val p = GlyphFallbackPolicy(FakeGlyphProbe())
        assertEquals(GlyphSlot.MONO, p.resolve(0xE200))
    }

    // ---- 组合/零宽码点：并入主字符，不进回退 ----

    @Test
    fun combiningMarkStaysMono() {
        // 组合尖音符 U+0301：零宽，跟主字符走 MONO（与 CharWidth.of == 0 对齐）。
        assertEquals(GlyphSlot.MONO, policy.resolve(0x0301))
    }

    // ---- 缓存：一次判定终身复用，热路径零分配 ----

    @Test
    fun resolveCacheSkipsReprobe() {
        val counting = CountingProbe()
        val p = GlyphFallbackPolicy(counting)
        // 首次判定 你：MONO 探一次（miss）+ FALLBACK 探一次（hit）= 2 次。
        assertEquals(GlyphSlot.SYSTEM_FALLBACK, p.resolve(0x4F60))
        assertEquals(1, counting.monoCalls)
        assertEquals(1, counting.fallbackCalls)
        // 缓存命中：再次判定不触发任何探针。
        assertEquals(GlyphSlot.SYSTEM_FALLBACK, p.resolve(0x4F60))
        assertEquals(1, counting.monoCalls)
        assertEquals(1, counting.fallbackCalls)
    }

    @Test
    fun cacheIsPerCodepoint() {
        val counting = CountingProbe()
        val p = GlyphFallbackPolicy(counting)
        // 两个不同码点各自独立判定（探针各一次）。
        assertEquals(GlyphSlot.SYSTEM_FALLBACK, p.resolve(0x4F60))
        assertEquals(GlyphSlot.SYSTEM_FALLBACK, p.resolve(0x597D))
        assertEquals(2, counting.monoCalls)
        assertEquals(2, counting.fallbackCalls)
    }

    @Test
    fun asciiCacheNeverProbes() {
        val counting = CountingProbe()
        val p = GlyphFallbackPolicy(counting)
        repeat(100) { p.resolve(0x41 + it % 26) }
        assertEquals(0, counting.monoCalls)
        assertEquals(0, counting.fallbackCalls)
    }
}
