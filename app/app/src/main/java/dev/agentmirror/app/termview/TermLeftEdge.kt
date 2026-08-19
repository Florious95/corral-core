/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

import dev.agentmirror.app.ui.theme.TerminalMetrics
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 终端首列几何：第 0 列原点 vs 容器 contentLeft，并区分
 * 「布局把首列推出去」与「首列画在那儿但被 clip」。
 *
 * 现场（078 §1）：`●` 走 SYSTEM_FALLBACK 居中，advance > 格宽时
 * `x + (cellPx - advance)/2` 为负，Canvas 把左半圆裁掉。
 * 布局本身把网格钉在 view x=0，不是负偏移。
 */
internal object TermLeftEdge {

    /** 左内边距。数值以设计 [TerminalMetrics.paddingLeft] 为准（14dp，左右对称）。 */
    val LEFT_MARGIN_DP: Float = TerminalMetrics.paddingLeft.value

    fun contentLeftPx(density: Float): Int =
        (LEFT_MARGIN_DP * density).roundToInt()

    fun cellOriginX(col: Int, cellW: Int, contentLeft: Int): Int =
        contentLeft + col * cellW

    /**
     * fallback 居中后的字形绘制 x。
     * 旧式 `origin + (cell - advance)/2` 在 col0 且 advance>cell 时为负。
     */
    fun centeredGlyphX(cellOriginX: Int, cellPx: Int, glyphAdvance: Float): Float {
        val raw = cellOriginX + (cellPx - glyphAdvance) / 2f
        return clampGlyphX(raw)
    }

    fun clampGlyphX(rawX: Float): Float = max(0f, rawX)

    enum class Verdict { OK, CLIPPED, LAYOUT_PUSHED }

    /**
     * @param glyphDrawX 首列字形实际起绘 x（view 坐标）
     * @param cellOriginX 第 0 列格子左缘
     * @param contentLeft 容器内容区左缘
     */
    fun classify(glyphDrawX: Float, cellOriginX: Int, contentLeft: Int): Verdict = when {
        cellOriginX < 0 || cellOriginX < contentLeft -> Verdict.LAYOUT_PUSHED
        glyphDrawX < 0f -> Verdict.CLIPPED
        else -> Verdict.OK
    }

    /** 字形起绘落在 clip 边（x=0）左侧 ⇒ 画了但被裁。 */
    fun isClipped(glyphDrawX: Float): Boolean = glyphDrawX < 0f
}
