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

import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.app.ui.theme.TerminalMetrics
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 090 §2.5：回前台重排几何必须等于真实视口算出的几何（A-geom-resume / A-geom-log）。
 *
 * 复现场景（用户截图：字号过大、右侧被切）：会话页几何已建立 → 后台 → 回前台时
 * View 重新实测出更大的字格，像素视口没变。此时 candidate 行列变少，
 * [TermViewPresenter.viewportOutgrewEmulator] 为 false，旧实现不重算 ——
 * 内核仍按小字格的多列画，绘制却用大字格 ⇒ 右侧切掉。这是概率性的（字格
 * 是否漂、布局时序），单次绿不算；本文件重复 N 次并断言每一次都对。
 */
class GeomResumeForegroundTest {

    @Before
    fun setUp() {
        DiagLog.resetForTest()
    }

    @After
    fun tearDown() {
        DiagLog.resetForTest()
    }

    /**
     * A-geom-resume：N=20 次「会话页 → 后台 → 回前台」，每次重排后的 rows/cols/cell
     * 必须等于真实视口 ÷ 当前字格（再夹 maxCols）。
     */
    @Test
    fun aGeomResume_backgroundThenForeground_geometryMatchesRealViewport_n20() {
        val n = 20
        val failures = mutableListOf<String>()
        for (i in 0 until n) {
            val viewW = 720 + i * 16
            val viewH = 400 + i * 12
            val oldCellW = 10
            val oldCellH = 20
            // 每次换一组更大的字格，模拟回前台 View 重新实测（字号过大那条截图）。
            val newCellW = 12 + (i % 5)
            val newCellH = 24 + (i % 4)
            val h = harness()
            h.presenter.seedCellMetrics(oldCellW, oldCellH)
            h.presenter.onViewportSizeChanged(viewW, viewH)

            // 后台：尺寸回调不会再来。回前台前字格漂到更大值。
            h.presenter.seedCellMetrics(newCellW, newCellH)
            h.presenter.onRealViewportChanged(viewW, viewH)

            val expectedRows = viewH / newCellH
            val expectedCols = minOf(viewW / newCellW, TerminalMetrics.maxCols).coerceAtLeast(1)
            if (h.emulator.rows != expectedRows ||
                h.emulator.cols != expectedCols ||
                h.presenter.cellWidth != newCellW ||
                h.presenter.cellHeight != newCellH
            ) {
                failures += "i=$i view=${viewW}x$viewH cell ${oldCellW}x$oldCellH→${newCellW}x$newCellH " +
                    "want ${expectedRows}x$expectedCols got ${h.emulator.rows}x${h.emulator.cols} " +
                    "cell=${h.presenter.cellWidth}x${h.presenter.cellHeight}"
            }
        }
        assertEquals(
            "A-geom-resume：N=$n 次必须全部等于真实视口几何，错了 ${failures.size} 次: $failures",
            0,
            failures.size,
        )
    }

    /**
     * A-geom-log：三个回调各产出一条含两边原始数值的记录。
     * 先制造比较条件（已 seed 的视口 + 一次真实视口事件），再读导出。
     * 守卫为 false 也必须把两边数字和结论写出来，不许只写「未触发」。
     */
    @Test
    fun aGeomLog_threeCallbacksEachRecordBothOperands() {
        val h = harness()
        h.presenter.seedCellMetrics(10, 20)
        h.presenter.onViewportSizeChanged(800, 480)
        DiagLog.resetForTest()
        // 同尺寸再走一遍 size + 回前台：outgrew 为 false，仍必须落记两边数值。
        h.presenter.onViewportSizeChanged(800, 480)
        h.presenter.onRealViewportChanged(800, 480)

        val text = exportedText()
        val sizeLine = lineOf(text, "source=onViewportSizeChanged oldW=")
        assertTrue("onViewportSizeChanged 必须记下旧/新像素: $sizeLine", sizeLine.contains("oldW=") && sizeLine.contains("newW="))
        assertTrue("onViewportSizeChanged 必须记下内核行列: $sizeLine", sizeLine.contains("emulatorRows=") && sizeLine.contains("emulatorCols="))

        val realLine = lineOf(text, "source=onRealViewportChanged oldW=")
        assertTrue("onRealViewportChanged 必须记下旧/新像素: $realLine", realLine.contains("oldW=") && realLine.contains("newW="))
        assertTrue("onRealViewportChanged 必须记下内核行列: $realLine", realLine.contains("emulatorRows=") && realLine.contains("emulatorCols="))

        val outgrewLine = lineOf(text, "viewportOutgrewEmulator:")
        assertTrue("viewportOutgrewEmulator 必须记下视口行: $outgrewLine", outgrewLine.contains("viewport_rows="))
        assertTrue("viewportOutgrewEmulator 必须记下内核行: $outgrewLine", outgrewLine.contains("emulator_rows="))
        assertTrue("viewportOutgrewEmulator 必须记下视口列: $outgrewLine", outgrewLine.contains("viewport_cols="))
        assertTrue("viewportOutgrewEmulator 必须记下内核列: $outgrewLine", outgrewLine.contains("emulator_cols="))
        assertTrue("viewportOutgrewEmulator 必须记下结论箭头: $outgrewLine", outgrewLine.contains("→"))
        assertTrue(
            "守卫拦下时也要把 false 写出来，不许只写未触发: $outgrewLine",
            outgrewLine.contains("→ false") || outgrewLine.contains("→ true"),
        )
    }

    private class Harness(
        val emulator: TerminalEmulator,
        val presenter: TermViewPresenter,
    )

    private fun harness(): Harness {
        val emulator = TerminalEmulator(80, 24)
        val presenter = TermViewPresenter(emulator) { rows, cols, _ ->
            emulator.resize(cols, rows)
        }
        return Harness(emulator, presenter)
    }

    private fun exportedText(): String {
        val dir = File("/Volumes/nvme/Projects/远程Agent安卓/.team/nodes/pr1-geom/tmp")
        dir.mkdirs()
        val f = File(dir, "diag-geom.log")
        DiagLog.exportTo(f)
        return f.readText()
    }

    private fun lineOf(text: String, needle: String): String =
        text.lineSequence().lastOrNull { it.contains(needle) }
            ?: error("导出里读不到 `$needle` —— 仪表没做够\n$text")
}
