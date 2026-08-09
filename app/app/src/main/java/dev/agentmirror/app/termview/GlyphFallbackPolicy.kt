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

/**
 * 字形回退策略：逐码点判定最终绘制槽位（paint.hasGlyph 检测缺字 → 候选字体回退链），
 * 判定结果缓存，热路径零分配（BMP 用定长 IntArray 索引，非 BMP 用惰性 HashMap）。
 *
 * 回退链次序（Field 实证 + 本机字体实测：Typeface.MONOSPACE(DroidSansMono) 对盲文/
 * 框线/块元素/CJK/emoji 覆盖率≈0；系统 sans fallback 的 NotoSansSymbols 对盲文 256/256、
 * 框线 128/128、块元素 32/32 全覆盖；Powerline 私有区 U+E0A0+ 系统字体零覆盖）：
 * 1. 组合/零宽码点 → [GlyphSlot.MONO]（并入主字符一起画，不单独进回退）；
 * 2. ASCII 可打印 → [GlyphSlot.MONO] 快速路径（预判定，连探针都不调）；
 * 3. 其余 → 探 [GlyphSlot.MONO]；缺则 [GlyphSlot.SYSTEM_FALLBACK]（盲文/框线/块/
 *    CJK/emoji/全角全落这里）；仍缺则 [GlyphSlot.POWERLINE]（内置兜底，仅 PUA 缺口）。
 *
 * [probe] 判定结果按码点缓存：字体在一次 App 会话内不变，缓存终身有效（无需失效）。
 */
class GlyphFallbackPolicy(private val probe: GlyphProbe) {

    /** BMP 缓存（0x0000..0xFFFF 每码点一槽，0=未决，1..3=槽位 ordinal+1）。256KB 一次性。 */
    private val bmpCache = IntArray(0x10000)

    /** 非 BMP（astral：emoji/CJK 扩展）缓存，惰性分配（BMP 之外实际用到才建）。 */
    private val astralCache = HashMap<Int, GlyphSlot>()

    /**
     * 判定码点 [codepoint] 的最终绘制槽位。热路径：ASCII/零宽两次分支即返回，
     * BMP 一次数组索引，零分配。
     */
    fun resolve(codepoint: Int): GlyphSlot {
        // 零宽/组合码点并入主字符（CharWidth 判定，与内核一致）：必随主字符同字体槽画，
        // 单独切段会破坏字形组合（如 é）。不进缓存，直接返回。
        if (codepoint < 0x20 || codepoint in 0x7F..0x9F || CharWidth.of(codepoint) == 0) {
            return GlyphSlot.MONO
        }
        // ASCII 可打印快速路径：主等宽字体必有字形，连缓存与探针都不碰。
        if (codepoint in 0x20..0x7E) return GlyphSlot.MONO

        return if (codepoint < 0x10000) {
            cachedResolve(codepoint)
        } else {
            astralCache.getOrPut(codepoint) { probeResolve(codepoint) }
        }
    }

    /** BMP 判定（数组缓存）：命中直接返回，未决则判一次并写入。 */
    private fun cachedResolve(codepoint: Int): GlyphSlot {
        val cached = bmpCache[codepoint]
        if (cached != 0) return slotOfOrdinal(cached - 1)
        val slot = probeResolve(codepoint)
        bmpCache[codepoint] = slot.ordinal + 1
        return slot
    }

    /** 一次完整回退链判定（MONO → SYSTEM_FALLBACK → POWERLINE → 保底 MONO）。 */
    private fun probeResolve(codepoint: Int): GlyphSlot = when {
        probe.hasGlyph(codepoint, GlyphSlot.MONO) -> GlyphSlot.MONO
        probe.hasGlyph(codepoint, GlyphSlot.SYSTEM_FALLBACK) -> GlyphSlot.SYSTEM_FALLBACK
        probe.hasGlyph(codepoint, GlyphSlot.POWERLINE) -> GlyphSlot.POWERLINE
        // 全链皆缺：保底主等宽（豆腐兜底优先级高于崩溃；缺口由 Field 留档）。
        else -> GlyphSlot.MONO
    }

    private fun slotOfOrdinal(ordinal: Int): GlyphSlot = when (ordinal) {
        0 -> GlyphSlot.MONO
        1 -> GlyphSlot.SYSTEM_FALLBACK
        else -> GlyphSlot.POWERLINE
    }
}
