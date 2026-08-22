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

package dev.agentmirror.app.diag

import dev.agentmirror.app.termview.TermViewPresenter
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 缺陷② 可算性红测（验收 goal 追加段 + 角色文件第 4 类）。
 *
 * 判据：**光看导出的日志就能算出「最右列超出 View 几个像素」**，不需要用户截图。
 * 用户真机实测：viewportW=1260 名义 10 实测 11 → 126 vs 114，末列字形右缘超出 5px（半字宽 5.5px）。
 *
 * 字段规格（w-cols-prep 逐行对齐，开发席已按原文打进 grid 日志）：
 *   viewport_width_px / cell_width_nominal / cell_width_measured /
 *   reported_cols / canvas_capacity_cols / overflow_px / half_cell_px
 *
 * 正确公式（非 reported_cols×measured，那是误判成 126px）：
 *   overflow_px = 当 reported_cols > canvas_capacity_cols 时
 *                 (canvas_capacity_cols + 1) * cell_width_measured - viewport_width_px，否则 0
 *
 * 三层：
 * 1. [userRealParams_overflowComputableFromExport] —— 从**导出文本**独立复算 overflow，命中用户真机 5px。
 *    记录字段不够（比如漏了 measured）→ 复算失败 → 红。
 * 2. [afterFix_noOverflow_computable] —— 修复后（回写实测宽）overflow=0，同样可从导出复算。
 * 3. [realPresenter_userParams_emitGridRecord] —— 接线红测：驱动真实 [TermViewPresenter]，
 *    DiagLog 必须出现 [grid] 记录（开发席未接调用点时红，接入后绿）。
 */
class DiagLogGridComputabilityTest {

    private fun exportedText(): String {
        val f = File.createTempFile("diag-grid-", ".log").apply { deleteOnExit() }
        DiagLog.exportTo(f)
        return f.readText()
    }

    /** 从导出文本里取一条 [tag] 记录的消息体（去掉时间戳与 tag）。 */
    private fun messageOf(text: String, tag: String): String =
        text.lineSequence().first { it.contains("[$tag]") }.substringAfter("] ")

    /** 解析消息体里的 `key=value`（值不含空格）。 */
    private fun field(msg: String, key: String): String =
        Regex("$key=([^ ]+)").find(msg)?.groupValues?.get(1)
            ?: error("字段缺失: $key —— 记录字段不够，光看日志复算不出根因")

    @Before
    fun setUp() {
        DiagLog.resetForTest()
    }

    @After
    fun tearDown() {
        DiagLog.resetForTest()
    }

    /** ★ 主判据：从导出日志独立复算「末列超出 View 多少像素」，命中用户真机 5px。 */
    @Test
    fun userRealParams_overflowComputableFromExport() {
        // 用户真机参数。
        val vw = 1260
        val nominal = 10
        val measured = 11
        val cols = vw / nominal // 126（上报服务端，缺陷态：名义栅格）
        val cap = vw / measured // 114（画布按实测推进宽可容纳）
        val overflow = if (cols > cap) (cap + 1) * measured - vw else 0 // 5
        val half = measured / 2.0 // 5.5

        DiagLog.record(
            "grid",
            "viewport_width_px=$vw cell_width_nominal=$nominal cell_width_measured=$measured " +
                "reported_cols=$cols canvas_capacity_cols=$cap overflow_px=$overflow half_cell_px=$half",
        )

        val msg = messageOf(exportedText(), "grid")
        // 只从日志恢复这些字段（模拟"用户只看导出日志"）。
        val rVw = field(msg, "viewport_width_px").toInt()
        val rNominal = field(msg, "cell_width_nominal").toInt()
        val rMeasured = field(msg, "cell_width_measured").toInt()
        val rCols = field(msg, "reported_cols").toInt()
        val rCap = field(msg, "canvas_capacity_cols").toInt()
        val rOverflow = field(msg, "overflow_px").toInt()
        val rHalf = field(msg, "half_cell_px").toDouble()

        // 可算性：只用日志字段复算，必须与记录值一致。
        val recomputedCols = rVw / rNominal
        val recomputedCap = rVw / rMeasured
        val recomputedOverflow = if (recomputedCols > recomputedCap) {
            (recomputedCap + 1) * rMeasured - rVw
        } else {
            0
        }
        assertEquals("记录 reported_cols 与复算不符", recomputedCols, rCols)
        assertEquals("记录 canvas_capacity_cols 与复算不符", recomputedCap, rCap)
        assertEquals("记录 overflow_px 与复算不符", recomputedOverflow, rOverflow)

        // 命中用户真机已知值：末列越界 5px ≈ 半字宽 5.5px。
        assertEquals("用户真机已知越界量应为 5px", 5, rOverflow)
        assertEquals("半字宽应为 5.5px", 5.5, rHalf, 0.001)

        // 缺陷态必须可从日志检出（>0）——这就是用户「最右侧的字只能看到一半」的量化。
        assertTrue("缺陷态（修复前）末列越界必须 > 0", rOverflow > 0)
        assertTrue("越界量应接近半字宽（截断量级）", Math.abs(rHalf - rOverflow) <= 1.0)
    }

    /** 修复后（回写实测宽 → 两栅格同源）：overflow=0，同样可从导出复算。 */
    @Test
    fun afterFix_noOverflow_computable() {
        val vw = 1260
        val nominal = 11 // 修复后：回写实测宽，上报与绘制同源
        val measured = 11
        val cols = vw / nominal // 114
        val cap = vw / measured // 114
        val overflow = if (cols > cap) (cap + 1) * measured - vw else 0 // 0

        DiagLog.record(
            "grid",
            "viewport_width_px=$vw cell_width_nominal=$nominal cell_width_measured=$measured " +
                "reported_cols=$cols canvas_capacity_cols=$cap overflow_px=$overflow half_cell_px=${measured / 2.0}",
        )

        val msg = messageOf(exportedText(), "grid")
        val rCols = field(msg, "reported_cols").toInt()
        val rCap = field(msg, "canvas_capacity_cols").toInt()
        val rOverflow = field(msg, "overflow_px").toInt()

        assertEquals("修复后上报 cols 必须等于画布容量", rCap, rCols)
        assertEquals("修复后末列不得越界", 0, rOverflow)
        assertTrue("cols ≤ 画布容量（内容不超右边界）", rCols <= rCap)
    }

    /** ★ 接线红测：驱动真实渲染栅格生产者，DiagLog 必须出现 [grid] 记录。 */
    @Test
    fun realPresenter_userParams_emitGridRecord() {
        val reported = mutableListOf<Pair<Int, Int>>()
        val emulator = TerminalEmulator(80, 24)
        val presenter = TermViewPresenter(emulator) { r, c ->
            reported.add(r to c)
            emulator.resize(c, r)
        }
        presenter.seedCellMetrics(11, 22) // 一次算对（feat-font-size-setting-drop-pinch）：实测值直接喂入
        presenter.onViewportSizeChanged(1260, 480) // 用户真机宽：cols=1260/11=114，无收敛

        val text = exportedText()
        assertTrue(
            "【grid 接线红测】驱动真实 TermViewPresenter 后 DiagLog 必须有 [grid] 记录——" +
                "缺陷②要能从日志复算越界，termview 得先把栅格记下来",
            text.contains("[grid]"),
        )
    }
}
