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

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log

/**
 * 字形字体提供者：为 [GlyphSlot] 三槽位各造一支 Paint，并实现 [GlyphProbe]
 * （Android 层的运行时字形判定，替换单测假探针）：[GlyphSlot.MONO] 槽按 ASCII 原生等宽
 * 预判，系统/Powerline 槽用真实 Paint.hasGlyph 实测。
 *
 * 真机实证（本席位，API35 模拟器）：Typeface.MONOSPACE 会走系统 fallback 链，盲文/框线/
 * CJK/emoji 全部 hasGlyph=true——所以 [GlyphSlot.MONO] 的判定**不是** "hasGlyph"（那会把
 * fallback 字形也当等宽，batch 连画毁栅格），而是 "ASCII 可打印=主字体原生等宽"。非 ASCII
 * 一律落 [GlyphSlot.SYSTEM_FALLBACK] 或 [GlyphSlot.POWERLINE]，由渲染层逐格居中画
 * （fallback advance ≠ 格宽，见记忆 term-glyph-fallback-empirics）。
 *
 * 内置 PowerlineSymbols（官方 2.2KB，PUA U+E0A0-E0B3 等 8 字形）兜住系统字体零覆盖的
 * Powerline 私有区；加载失败降级 MONOSPACE（缺资源不应崩，豆腐缺口留档）。
 */
class GlyphFontProvider(context: Context) : GlyphProbe {

    /** 主等宽字体：ASCII/组合码点 batch 绘制。textSize 每帧由 View 同步。 */
    val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }

    /** 系统默认字体：fallback 链（Roboto→Noto）覆盖盲文/框线/块/CJK/emoji/全角。 */
    val systemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
    }

    /** 内置 PowerlineSymbols 字体：Powerline 私有区 U+E0A0-E0FF。 */
    val powerlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = loadBundled(context)
    }

    /** 回退策略：判码点最终槽位（纯 JVM 逻辑，探针即本类）。 */
    val policy = GlyphFallbackPolicy(this)

    /** 分段器：把颜色段按槽位切成可绘子段。 */
    val runBuilder = GlyphRunBuilder(policy)

    /** 把所有槽位 Paint 的 textSize 与主字体对齐（每帧 measure 后调用）。 */
    fun setTextSize(size: Float) {
        monoPaint.textSize = size
        systemPaint.textSize = size
        powerlinePaint.textSize = size
    }

    override fun hasGlyph(codepoint: Int, slot: GlyphSlot): Boolean = when (slot) {
        // 主等宽字体"原生"字形判定 = ASCII 可打印（实证：fallback 字形 advance 不配格宽，
        // 不能算等宽，否则 batch 连画毁栅格）。
        GlyphSlot.MONO -> codepoint in 0x20..0x7E
        // 系统 fallback 链覆盖面（Roboto→Noto）：盲文/框线/块/CJK/emoji/全角/符号。
        GlyphSlot.SYSTEM_FALLBACK -> systemPaint.hasGlyph(oneChar(codepoint))
        // 内置 Powerline 字体覆盖面（PUA + 实心块）。
        GlyphSlot.POWERLINE -> powerlinePaint.hasGlyph(oneChar(codepoint))
        // 内部终止信号，不是实际字体槽；不得参与候选链探测。
        GlyphSlot.VISIBLE_FALLBACK -> false
    }

    private fun oneChar(codepoint: Int): String = String(Character.toChars(codepoint))

    private fun loadBundled(context: Context): Typeface = try {
        Typeface.createFromAsset(context.assets, "fonts/PowerlineSymbols.otf")
    } catch (t: Throwable) {
        Log.w(TAG, "PowerlineSymbols.otf 加载失败，Powerline 私有区缺口", t)
        Typeface.MONOSPACE
    }

    private companion object {
        const val TAG = "TermSurfaceView"
    }
}
