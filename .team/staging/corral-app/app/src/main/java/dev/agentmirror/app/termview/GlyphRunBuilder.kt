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
 * 一行内字形分段规划：把连续同槽位的码点聚成段，供绘制层执行。
 *
 * [GlyphSegment.slot] == [GlyphSlot.MONO] 的段整段一次 drawText（等宽网格不破坏，
 * draw 调用数 = 段数而非格数）；fallback 段由绘制层逐码点按格居中画（回退字体字宽
 * 不可靠，不能整段连画，否则破坏列对齐）。
 *
 * 组合/零宽码点并入前一个非零宽码点的段（必须跟主字符一起画，不能单独切段）。
 */
data class GlyphSegment(
    val slot: GlyphSlot,
    val text: String,
    val startCol: Int,
)

/**
 * 字形分段器：把一个颜色段（连续同色格子的拼接文本）按字形槽位切成子段。
 *
 * 切段只按码点槽位，不改变各码点的占格宽度（列对齐由调用方按 CharWidth 推进，
 * 见 [GlyphSegment.startCol] 的推进规则）。
 */
class GlyphRunBuilder(private val policy: GlyphFallbackPolicy) {

    /**
     * 把 [text] 按字形槽位切成子段；[startCol] 为 [text] 首字符的格列。
     * 返回空表当 [text] 为空。
     *
     * @contract
     * @pre none（任意 text / startCol 均可调用）
     * @post text 为空返回空表；非空时连续同槽位码点聚成同一段，各段 [GlyphSegment.startCol] 为段首字符格列；
     *       全字体 miss 的码点先采用经探针确认可画的形近等价映射；映射未命中再改写为
     *       MONO 可画的 '?'，且按原码点格宽补足字符数
     * @err none
     * @inv 各码点占格宽度不变，总列宽 == [CharWidth] 各码点宽度之和（列对齐由调用方按宽度推进）
     */
    fun build(text: String, startCol: Int): List<GlyphSegment> {
        if (text.isEmpty()) return emptyList()

        val segments = ArrayList<GlyphSegment>()
        var curSlot: GlyphSlot? = null
        var curText = StringBuilder()
        var curStart = startCol
        var col = startCol

        var i = 0
        val n = text.length
        while (i < n) {
            val cp = text.codePointAt(i)
            val width = CharWidth.of(cp)
            val sourceSlot = policy.resolve(cp)
            val equivalent = if (sourceSlot == GlyphSlot.VISIBLE_FALLBACK) {
                policy.resolveDrawableEquivalent(cp)
            } else {
                null
            }
            val resolvedSlot = equivalent?.slot ?: sourceSlot
            val resolvedCodepoint = equivalent?.codepoint ?: cp
            val slot = if (resolvedSlot == GlyphSlot.VISIBLE_FALLBACK) GlyphSlot.MONO else resolvedSlot
            if (width == 0) {
                // 零宽/组合码点：并入当前段，绝不单独切段（字形组合必须整体画）。
                // 段尚未打开时（理论边界）以当前槽位起段，列不推进。
                if (curSlot == null) {
                    curSlot = slot
                    curStart = col
                }
                curText.appendCodePoint(cp)
            } else {
                // 段未开：起段；槽位切换：flush 上一段再开新段（startCol 记新段首列）。
                if (curSlot == null) {
                    curSlot = slot
                    curStart = col
                } else if (curSlot != slot) {
                    segments.add(GlyphSegment(curSlot, curText.toString(), curStart))
                    curSlot = slot
                    curText = StringBuilder()
                    curStart = col
                }
                if (resolvedSlot != GlyphSlot.VISIBLE_FALLBACK) {
                    curText.appendCodePoint(resolvedCodepoint)
                } else {
                    // 缺字替代仍须覆盖原码点的完整列宽；双宽码点用两个 ASCII '?' 占两格。
                    repeat(width.coerceAtLeast(1)) { curText.appendCodePoint(VISIBLE_FALLBACK_CODEPOINT) }
                }
                col += width
            }
            i += Character.charCount(cp)
        }
        if (curSlot != null) segments.add(GlyphSegment(curSlot, curText.toString(), curStart))
        return segments
    }

    private companion object {
        const val VISIBLE_FALLBACK_CODEPOINT = '?'.code
    }
}
