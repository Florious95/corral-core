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

import android.graphics.Typeface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 字形回退的 Android 装配回归锁（Robolectric 真实 Android 类型）。
 *
 * Robolectric 的 Paint.hasGlyph 无 shadow（走原生 stub）、measureText=字符串长度——
 * 单测无法测真实字体 advance（那由真机截图留档验证）。这里锁定的是**决策不变量**：
 * 非 ASCII 无论系统是否有字形，一律不落 [GlyphSlot.MONO]（MONO = ASCII 原生等宽，
 * batch 连画不漂移；fallback advance ≠ 格宽必须逐格居中，见记忆 term-glyph-fallback-empirics）。
 * 若有人把非 ASCII 加进 MONO 快速路径，本测试立即红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GlyphFontProviderTest {

    private val provider by lazy {
        GlyphFontProvider(RuntimeEnvironment.getApplication())
    }

    @Test
    fun asciiStaysMonoBatch() {
        // ASCII 可打印是主字体原生等宽：MONO 槽位（batch 连画安全）。
        assertEquals(GlyphSlot.MONO, provider.policy.resolve('A'.code))
        assertEquals(GlyphSlot.MONO, provider.policy.resolve('0'.code))
        assertEquals(GlyphSlot.MONO, provider.policy.resolve(' '.code))
    }

    @Test
    fun nonAsciiNeverResolvesToMono() {
        // 真机实证（记忆 term-glyph-fallback-empirics）：MONO Paint.hasGlyph 对盲文/框线/
        // 块/CJK/emoji 全 true（系统 fallback），但 fallback advance ≠ 格宽——若落 MONO
        // batch 连画栅格漂移。策略必须把非 ASCII 全排除出 MONO。
        val nonAscii = listOf(
            0x280B, 0x2839,          // 盲文（Claude spinner）
            0x2500, 0x2502, 0x250C,  // 框线
            0x2588, 0x2593,          // 块元素
            0x4F60, 0x597D,          // CJK
            0x1F600,                  // emoji astral
            0xFF21,                   // 全角
        )
        for (cp in nonAscii) {
            assertTrue("U+%04X must NOT be MONO".format(cp), provider.policy.resolve(cp) != GlyphSlot.MONO)
        }
    }

    @Test
    fun bundledPowerlineFontLoaded() {
        // 内置 PowerlineSymbols 必须能加载（assets 就位）且覆盖 PUA 主字形。
        val tf = provider.powerlinePaint.typeface
        assertNotNull(tf)
        // 成功加载 = 非 MONOSPACE 兜底（createFromAsset 失败会 fallback MONOSPACE）。
        assertTrue(tf !== Typeface.MONOSPACE)
        // 组合断言：PUA 判定走 POWERLINE 槽（renderer 会用内置字体逐格居中画）。
        assertEquals(GlyphSlot.POWERLINE, provider.policy.resolve(0xE0B0))
        assertEquals(GlyphSlot.POWERLINE, provider.policy.resolve(0xE0A0))
    }

    @Test
    fun puaOutsideBundledFallsToSystemThenMono() {
        // 内置 Powerline 未覆盖的 PUA：全链皆缺 → 保底 MONO（豆腐兜底优于崩溃，缺口留档）。
        assertEquals(GlyphSlot.MONO, provider.policy.resolve(0xE200))
    }

    @Test
    fun runBuilderSplitsBySlotThroughProvider() {
        // 全链路（provider 探针 → 策略 → 分段器）：混排夹具按槽位正确分段。
        val segs = provider.runBuilder.build("a█你😀", 0)
        assertEquals(GlyphSlot.MONO, segs[0].slot)
        assertEquals("a", segs[0].text)
        // 非 ASCII 均不落 MONO。
        assertTrue(segs.drop(1).all { it.slot != GlyphSlot.MONO })
        // 列推进：a(1)+█(1)+你(2)+😀(2)=6 列。
        val last = segs.last()
        assertEquals(6, last.startCol + last.text.codePoints().toArray().sumOf { dev.agentmirror.terminal.CharWidth.of(it) })
    }
}
