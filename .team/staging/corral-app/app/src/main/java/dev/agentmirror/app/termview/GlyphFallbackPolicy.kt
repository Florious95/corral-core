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
 *    CJK/emoji/全角全落这里）；仍缺则 [GlyphSlot.POWERLINE]（内置兜底，仅 PUA 缺口）；
 *    全链皆缺时尝试形近等价映射（目标也须走真实探针）；映射未命中或目标仍缺才返回
 *    [GlyphSlot.VISIBLE_FALLBACK]，由 builder 把缺字码点替换成可见 ASCII。
 *
 * [probe] 判定结果按码点缓存：字体在一次 App 会话内不变，缓存终身有效（无需失效）。
 */
class GlyphFallbackPolicy(private val probe: GlyphProbe) {

    /** 经探针确认可画的形近等价码点及其实际字体槽。 */
    internal data class DrawableEquivalent(val codepoint: Int, val slot: GlyphSlot)

    /** BMP 缓存（0x0000..0xFFFF 每码点一槽，0=未决，1..4=槽位 ordinal+1）。256KB 一次性。 */
    private val bmpCache = IntArray(0x10000)

    /** 非 BMP（astral：emoji/CJK 扩展）缓存，惰性分配（BMP 之外实际用到才建）。 */
    private val astralCache = HashMap<Int, GlyphSlot>()

    /**
     * 判定码点 [codepoint] 的最终绘制槽位。热路径：ASCII/零宽两次分支即返回，
     * BMP 一次数组索引，零分配。
     *
     * @contract
     * @pre none（任意 Unicode 码点均可判定）
     * @post 零宽/组合及 C0/C1 控制 → [GlyphSlot.MONO]（不进缓存）；ASCII 可打印 → [GlyphSlot.MONO]（连探针都不调）；
     *       其余按 MONO → SYSTEM_FALLBACK → POWERLINE 回退链判定，全链皆缺转 VISIBLE_FALLBACK
     * @err none
     * @inv 判定结果按码点缓存终身复用（BMP 定长数组 / 非 BMP 惰性 HashMap），同码点不重复探测
     */
    fun resolve(codepoint: Int): GlyphSlot {
        // 零宽/组合码点并入主字符（CharWidth 判定，与内核一致）：必随主字符同字体槽画，
        // 单独切段会破坏字形组合（如 é）。不进缓存，直接返回。
        if (codepoint < 0x20 || codepoint in 0x7F..0x9F || CharWidth.of(codepoint) == 0) {
            return GlyphSlot.MONO
        }
        // ASCII 可打印快速路径：主等宽字体必有字形，连缓存与探针都不碰。
        if (codepoint in 0x20..0x7E) return GlyphSlot.MONO
        // 框线/块元素改走几何，⛔ 不让 MONO hasGlyph 把它们拐进 batch drawText。
        if (BoxBlockGeometry.handles(codepoint)) return GlyphSlot.SYSTEM_FALLBACK

        return if (codepoint < 0x10000) {
            cachedResolve(codepoint)
        } else {
            astralCache.getOrPut(codepoint) { probeResolve(codepoint) }
        }
    }

    /**
     * 查询全字体 miss 的 [codepoint] 是否有经真实探针确认可画的形近等价码点。
     *
     * @contract
     * @pre [codepoint] 已由 [resolve] 判为 [GlyphSlot.VISIBLE_FALLBACK]
     * @post 映射不存在、目标格宽不同或映射目标三槽全 miss 时返回 null；否则返回映射目标及探针选中的真实字体槽
     * @err none
     * @inv 映射目标绝不因“形近”被假定可画，必须经过 MONO → SYSTEM_FALLBACK → POWERLINE 探针链；映射不改变终端格宽
     */
    internal fun resolveDrawableEquivalent(codepoint: Int): DrawableEquivalent? {
        val equivalent = DRAWABLE_EQUIVALENTS[codepoint] ?: return null
        if (CharWidth.of(equivalent) != CharWidth.of(codepoint)) return null
        val slot = if (equivalent < 0x10000) {
            cachedResolve(equivalent)
        } else {
            astralCache.getOrPut(equivalent) { probeResolve(equivalent) }
        }
        return if (slot == GlyphSlot.VISIBLE_FALLBACK) null else DrawableEquivalent(equivalent, slot)
    }

    /** BMP 判定（数组缓存）：命中直接返回，未决则判一次并写入。 */
    private fun cachedResolve(codepoint: Int): GlyphSlot {
        val cached = bmpCache[codepoint]
        if (cached != 0) return slotOfOrdinal(cached - 1)
        val slot = probeResolve(codepoint)
        bmpCache[codepoint] = slot.ordinal + 1
        return slot
    }

    /** 一次完整回退链判定（MONO → SYSTEM_FALLBACK → POWERLINE → 可见替代信号）。 */
    private fun probeResolve(codepoint: Int): GlyphSlot = when {
        probe.hasGlyph(codepoint, GlyphSlot.MONO) -> GlyphSlot.MONO
        probe.hasGlyph(codepoint, GlyphSlot.SYSTEM_FALLBACK) -> GlyphSlot.SYSTEM_FALLBACK
        probe.hasGlyph(codepoint, GlyphSlot.POWERLINE) -> GlyphSlot.POWERLINE
        else -> GlyphSlot.VISIBLE_FALLBACK
    }

    private fun slotOfOrdinal(ordinal: Int): GlyphSlot = when (ordinal) {
        0 -> GlyphSlot.MONO
        1 -> GlyphSlot.SYSTEM_FALLBACK
        2 -> GlyphSlot.POWERLINE
        else -> GlyphSlot.VISIBLE_FALLBACK
    }

    private companion object {
        /** 数据表只描述视觉等价关系；能否使用仍由 [resolveDrawableEquivalent] 的探针决定。 */
        val DRAWABLE_EQUIVALENTS = mapOf(
            0x23F5 to 0x25B8,
        )
    }
}
