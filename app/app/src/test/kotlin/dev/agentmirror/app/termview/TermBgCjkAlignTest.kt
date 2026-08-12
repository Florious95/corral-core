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
import android.graphics.Color
import android.graphics.Paint
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * SGR 背景色块内 CJK 列对齐红测（fix-term-bg-cjk，用户真机实拍：浅色 recap 框内
 * 中文重叠错位黑块化，默认背景区同字符完全正常）。
 *
 * 根因（静态取证）：宽字符在网格里是「width=2 主格 + width=0 续格」两个条目，
 * 而 TermSurfaceView 两条扫描路径都把它当 3 列推进——
 * ① drawLine 背景遍历：主格只画 1 格宽矩形却推进 2 格、续格跳过绘制再推进 1 格
 *    ⇒ 每个 CJK 在背景色 run 里留 2 列默认深底"黑洞"（实拍黑块），后续格背景整体右漂；
 * ② drawTextRuns 颜色 run 扫描：主格 col+=2 后续格又 col+=1 ⇒ 换色 run 的起始列
 *    每经过一个 CJK 多漂 1 列（实拍文字错位重叠）。
 * 默认背景区"黑洞"与清屏底色同色不可见、整行常为单 run，故完全正常——症状吻合。
 *
 * Robolectric 的 Paint 指标是 stub，不做像素断言；沿用 TermFirstRowVisibleTest 的
 * 记录型 Canvas 捕获 drawRect（左右边+颜色）与 drawText（文本+x），锁三条几何不变量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermBgCjkAlignTest {

    /** 记录型 Canvas：记背景矩形（左、右、色）与文本（内容、x）。onDraw 无 translate 之外
     *  的变换需求（本测试只看首行，translate 累计为 0，直接记原始坐标即可）。 */
    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        data class Rect(val left: Float, val right: Float, val color: Int)
        data class Text(val text: String, val x: Float, val color: Int)

        val rects = mutableListOf<Rect>()
        val texts = mutableListOf<Text>()

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            rects += Rect(left, right, paint.color)
            super.drawRect(left, top, right, bottom, paint)
        }

        // MONO batch 路径：drawText(String, x, y, paint)。
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            texts += Text(text, x, paint.color)
            super.drawText(text, x, y, paint)
        }

        // 回退槽逐格路径：drawText(text, start, end, x, y, paint)（drawCentered 零分配重载）。
        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            texts += Text(text.substring(start, end), x, paint.color)
            super.drawText(text, start, end, x, y, paint)
        }
    }

    /** SGR 47（Indexed 7）经 colorFor 的 ARGB 值（TermSurfaceView.ANSI_COLORS[7]）。 */
    private val whiteBg = Color.rgb(229, 229, 229)

    @Test
    fun bgRunWithCjkKeepsCellGridAligned() {
        // 夹具：白底(47)黑字(30) 4 个 CJK（8 列）+ 换红字(31) "AB"（2 列）——
        // 换 fg 强制切第二个颜色 run，其起始列暴露 col 推进错误；背景 run 暴露黑洞。
        val emulator = TerminalEmulator(20, 5)
        emulator.feed("[47;30m中文测试[31mAB[0m")

        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.presenter = TermViewPresenter(emulator) { _, _ -> }

        val bitmap = Bitmap.createBitmap(400, 120, Bitmap.Config.ARGB_8888)
        val canvas = RecordingCanvas(bitmap)
        view.draw(canvas)
        bitmap.recycle()

        // 白底格矩形（按左边排序）。最窄者即单格宽 cellW（"AB"格必为单格矩形，新旧代码同）。
        val white = canvas.rects.filter { it.color == whiteBg }.sortedBy { it.left }
        assertTrue("夹具失效：未画出任何白底格", white.isNotEmpty())
        val cellW = white.minOf { it.right - it.left }
        assertTrue("夹具失效：cellW=$cellW", cellW >= 1f)

        // 不变量 1：白底区从第 0 列开始、连续无缝——任何缝隙都是实拍里的默认深底黑块。
        assertEquals("白底区未从第 0 列开始", 0f, white.first().left, 0.01f)
        var reach = 0f
        for (r in white) {
            assertTrue("白底区在 x=${r.left} 前有黑洞（覆盖到 $reach）", r.left <= reach + 0.01f)
            if (r.right > reach) reach = r.right
        }

        // 不变量 2：白底区总宽恰为 10 列（4 CJK×2 + AB×2）——少铺即黑洞，多铺即右漂。
        assertEquals("白底区总宽 ≠ 10 列", 10f * cellW, reach, 0.01f)

        // 不变量 3：换色 run "AB" 的起始 x 恰在第 8 列（4 个 CJK 各占 2 列）。
        // 旧代码每 CJK 多推 1 列 ⇒ AB 落在第 12 列（文字与背景错位重叠的直接来源）。
        val ab = canvas.texts.filter { it.text.contains("AB") }
        assertTrue("夹具失效：未画出 AB 文本", ab.isNotEmpty())
        assertEquals("AB run 起始列漂移", 8f * cellW, ab.first().x, 0.01f)
    }

    /** xterm 256 色扩展区期望值：254 = 灰阶梯 8+10×(254−232) = rgb(228,228,228)；
     *  16 = 色立方原点 rgb(0,0,0)。模拟器实拍第二缺陷：旧 colorFor 把 >15 的索引
     *  coerceIn(0,15) 全塌缩到 15 号浅灰——fg 16（黑）与 bg 254（浅灰）同色，recap
     *  块文字整块隐形（浅条无字）。 */
    private val recapBg = Color.rgb(228, 228, 228)
    private val recapFg = Color.rgb(0, 0, 0)

    @Test
    fun realRecapStyleBytesKeepGridAlignedAndLegible() {
        // 夹具 2：真实 Claude Code recap 的字节形态——256 色背景 48;5;254 + 256 色
        // 前景 38;5;16 + 中英混排 + 块内换 fg。
        // 列布局：␣(1) 递交清单(8) ：(2，全角冒号双宽) 中文渲染(8) ␣(1) = 20 列，
        // 换红后 "OK" 2 列，浅底共 22 列。
        val emulator = TerminalEmulator(40, 5)
        emulator.feed("[48;5;254;38;5;16m 递交清单：中文渲染 [38;5;196mOK[0m")

        val view = TermSurfaceView(RuntimeEnvironment.getApplication())
        view.presenter = TermViewPresenter(emulator) { _, _ -> }

        val bitmap = Bitmap.createBitmap(600, 120, Bitmap.Config.ARGB_8888)
        val canvas = RecordingCanvas(bitmap)
        view.draw(canvas)
        bitmap.recycle()

        // 254 号必须映射为灰阶 228——旧实现塌缩到 15 号 rgb(229) 时此处即红。
        val light = canvas.rects.filter { it.color == recapBg }.sortedBy { it.left }
        assertTrue("夹具失效/256 色塌缩：未画出 48;5;254 浅底格", light.isNotEmpty())
        val cellW = light.minOf { it.right - it.left }

        // 浅底区连续无黑洞、总宽恰 22 列（9 个双宽 + 4 个单宽）。
        var reach = 0f
        for (r in light) {
            assertTrue("浅底区在 x=${r.left} 前有黑洞（覆盖到 $reach）", r.left <= reach + 0.01f)
            if (r.right > reach) reach = r.right
        }
        assertEquals("浅底区总宽 ≠ 22 列", 22f * cellW, reach, 0.01f)

        // 换色 run "OK"（MONO batch 路径，x = startCol*cellW 精确）恰在第 20 列。
        val ok = canvas.texts.filter { it.text.contains("OK") }
        assertTrue("夹具失效：未画出 OK 文本", ok.isNotEmpty())
        assertEquals("OK run 起始列漂移", 20f * cellW, ok.first().x, 0.01f)

        // 可读性：38;5;16 前景必须落黑（色立方原点），绝不与浅底同色（模拟器实拍
        // 第二缺陷：fg/bg 同塌缩到 15 号浅灰 → recap 块整块隐形）。
        val recapText = canvas.texts.firstOrNull { it.color == recapFg }
        assertTrue("浅底块未提交任何黑色前景文字", recapText != null)
        assertTrue("recap 块前景与背景同色（文字隐形）", recapText!!.color != recapBg)
    }
}
