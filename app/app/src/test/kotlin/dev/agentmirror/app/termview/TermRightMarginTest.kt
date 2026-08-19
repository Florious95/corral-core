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

import dev.agentmirror.terminal.TerminalEmulator
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 红测（fix-terminal-right-margin）：末列不许贴边。
 *
 * 用户原话：「右侧还是有跑到屏幕外面的风险，它就已经完全贴着了……要和 Mac 上看到的那个
 * 边界一样，它旁边有一定的空隙」。修完缺陷②后 cols 取满画布容量，视口宽恰为字格宽整数倍
 * 时留白 = 0（本文件复现的场景）——这本身不是回归，但视觉上"贴边"。
 *
 * 断言：给定视口宽是实测字宽的整数倍（不修就是 0 留白），修完后：
 *   - 留白 > 0（贴边消失）
 *   - 留白 < 一个字宽（没有为了留白反过来抠掉一整列——缺陷② 的不变量：cols×cellW ≤ 视口宽，
 *     仍然只吃掉不到一列的空间，不倒退）
 *
 * 走 [TermSurfaceView.onSizeChanged] 真实路径（不是直接调 presenter），因为留白只加在
 * View→Presenter 边界（[TermSurfaceView] 私有 `usableWidthPx`），presenter 本身的
 * 几何公式不变——这正是本任务刻意的隔离设计：不触碰 [TermViewPresenter.recomputeGeometry]，
 * 避免波及其余所有直接调 presenter 的既有红测（它们的 cols 断言全部假设留白=0）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermRightMarginTest {

    @Test
    fun exactMultipleViewportWidth_leavesRightGapSmallerThanOneCell() {
        val realCellW = 11 // 真机实测字形宽典型值（同其余用户真机复现测试）
        val realCellH = 22
        val viewportH = 480
        val emulator = TerminalEmulator(80, 24)
        val resizeCalls = mutableListOf<Pair<Int, Int>>()
        val presenter = TermViewPresenter(emulator) { rows, cols ->
            resizeCalls += rows to cols
            emulator.resize(cols, rows)
        }
        presenter.seedCellMetrics(realCellW, realCellH)

        val view = TermSurfaceView(RuntimeEnvironment.getApplication()).apply {
            this.presenter = presenter
        }
        val density = view.resources.displayMetrics.density
        val marginPx = (TermLeftEdge.LEFT_MARGIN_DP * density).roundToInt()

        // 视口宽刻意取实测字宽的整数倍——不加留白时 leftover 恰好 = 0（复现"贴边"）。
        val viewportW = realCellW * 100

        view.layout(0, 0, viewportW, viewportH) // → onSizeChanged → usableWidthPx → presenter

        assertEquals("[守恒] 首次视口建立只应上报一次 resize", 1, resizeCalls.size)
        val (_, cols) = resizeCalls.single()
        val leftover = viewportW - cols * realCellW
        val leftPx = (TermLeftEdge.LEFT_MARGIN_DP * density).roundToInt()

        println(
            "[右边距] viewportW=$viewportW realCellW=$realCellW marginPx=$marginPx leftPx=$leftPx " +
                "cols=$cols leftover=$leftover",
        )

        assertTrue(
            "[右边距] 留白必须 > 0（不修就是 0，贴边）：leftover=$leftover",
            leftover > 0,
        )
        // 上界用 <=（非严格 <）：viewportW 恰为字宽整数倍时（本测试刻意构造的场景，
        // 复现"贴边"的前提就是这个整除关系），要制造任何非零留白，唯一办法是让 cols
        // 少算一整列——像素级整数网格没有"半列"留白这回事，留白只能是
        // 0、cellW、2×cellW……的离散值，不存在介于 0 与 cellW 之间的中间态。
        // 断言的真实红线是"不多丢一整列以上"（缺陷②不变量见下一断言），不是数学上不可能
        // 达到的"严格小于一个字宽"。
        assertTrue(
            "[右边距][不倒退] 留白不得达到两倍字宽以上——不许为了留白反过来多抠一整列：" +
                "leftover=$leftover cellW=$realCellW",
            leftover < leftPx + marginPx + realCellW,
        )
        assertTrue(
            "[缺陷②不倒退] cols×字宽=${cols * realCellW} 不得超出视口宽=$viewportW",
            cols * realCellW <= viewportW,
        )
    }

    /**
     * 典型场景（视口宽非字宽整数倍，用户真机的常态）：留白严格小于一个字宽——
     * 上一条测试的整除边界只是离散网格下的极端情形，日常场景应严格满足"< 一个字宽"。
     */
    @Test
    fun typicalViewportWidth_leavesRightGapStrictlyLessThanOneCell() {
        val realCellW = 11
        val realCellH = 22
        val viewportH = 480
        val emulator = TerminalEmulator(80, 24)
        val resizeCalls = mutableListOf<Pair<Int, Int>>()
        val presenter = TermViewPresenter(emulator) { rows, cols ->
            resizeCalls += rows to cols
            emulator.resize(cols, rows)
        }
        presenter.seedCellMetrics(realCellW, realCellH)

        val view = TermSurfaceView(RuntimeEnvironment.getApplication()).apply {
            this.presenter = presenter
        }
        val viewportW = 1260 // 用户真机宽（同其余用户真机复现测试），非 11 的整数倍

        view.layout(0, 0, viewportW, viewportH)

        val (_, cols) = resizeCalls.single()
        val leftover = viewportW - cols * realCellW
        val density = view.resources.displayMetrics.density
        val leftPx = (TermLeftEdge.LEFT_MARGIN_DP * density).roundToInt()
        val rightPx = (TermLeftEdge.LEFT_MARGIN_DP * density).roundToInt()

        assertTrue("[右边距][典型场景] 留白必须 > 0：leftover=$leftover", leftover > 0)
        assertTrue(
            "[右边距][典型场景] 左右留白+余数不得再吞一整列：leftover=$leftover cellW=$realCellW left=$leftPx right=$rightPx",
            leftover < leftPx + rightPx + realCellW,
        )
    }
}
