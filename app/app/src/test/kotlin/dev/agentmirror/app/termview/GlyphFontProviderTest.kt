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

import android.graphics.Bitmap
import android.graphics.Canvas
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 字形回退 Android 装配测试（Robolectric）。
 *
 * Robolectric 的 Paint/Canvas 文本原语是 stub（measureText=字符串长度、hasGlyph 无 shadow、
 * createFromAsset 可能失败），**不能**验证真实字体 advance/覆盖（那由真机截图留档负责，
 * 见记忆 term-glyph-fallback-empirics）。这里锁定 Robolectric 能可靠验证的：
 * 1. 探针不崩溃（各槽位 hasGlyph 调用安全）；
 * 2. 渲染路径 smoke：TermSurfaceView 铺混排内容（盲文/框线/块/CJK/emoji/PUA）draw 不崩——
 *    drawCentered/drawGlyphRuns 的逐格循环与居中计算真实执行；
 * 3. 探针无关的策略不变量（ASCII/零宽必落 MONO）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GlyphFontProviderTest {

    private val provider by lazy {
        GlyphFontProvider(RuntimeEnvironment.getApplication())
    }

    @Test
    fun probeDoesNotCrashOnAnySlot() {
        // 各槽位 hasGlyph 调用安全（Robolectric stub 下不崩即可；真实覆盖由截图留档）。
        provider.hasGlyph(0x41, GlyphSlot.MONO)
        provider.hasGlyph(0x280B, GlyphSlot.MONO)
        provider.hasGlyph(0x280B, GlyphSlot.SYSTEM_FALLBACK)
        provider.hasGlyph(0xE0B0, GlyphSlot.POWERLINE)
        provider.hasGlyph(0x1F600, GlyphSlot.SYSTEM_FALLBACK)
    }

    @Test
    fun asciiAndZeroWidthNeverProbeMono() {
        // 探针无关不变量：ASCII 可打印与零宽/组合码点直接落 MONO（不依赖 hasGlyph）。
        assertEquals(GlyphSlot.MONO, provider.policy.resolve('A'.code))
        assertEquals(GlyphSlot.MONO, provider.policy.resolve('0'.code))
        assertEquals(GlyphSlot.MONO, provider.policy.resolve(' '.code))
        assertEquals(GlyphSlot.MONO, provider.policy.resolve(0x0301)) // 组合尖音符
        assertEquals(GlyphSlot.MONO, provider.policy.resolve(0x200D)) // ZWJ
    }

    @Test
    fun rendererDrawsMixedGlyphLineWithoutCrash() {
        // 渲染路径 smoke：混排夹具（盲文轮转/框线/块元素/CJK/emoji/PUA/ASCII）draw 不崩，
        // drawCentered/drawGlyphRuns 逐格循环真实执行。
        val emulator = TerminalEmulator(40, 5)
        emulator.feed("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏ hello ┌─┐ █▓░ 你好 😀 🚀 世")

        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.presenter = TermViewPresenter(emulator) { _, _ -> }
        // lineCells 无帧缓存时回落 emulator.snapshot()，无需显式 beginFrame。

        // 直接驱动 View 绘制路径（不依赖 Choreographer/attach，Robolectric 环境安全）。
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas) // onDraw：清屏 + 铺行 + drawTextRuns → drawGlyphRuns → drawCentered
        bitmap.recycle()
        assertTrue(true)
    }

    @Test
    fun rendererDrawsWideCharRunsWithoutCrash() {
        // 宽字符 + 组合字符混排渲染 smoke（列推进/居中计算不越界）。
        val emulator = TerminalEmulator(20, 3)
        emulator.feed("你a😀b界")
        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.presenter = TermViewPresenter(emulator) { _, _ -> }
        val bitmap = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.recycle()
    }
}
