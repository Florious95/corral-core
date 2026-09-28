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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归护栏（leader 2026-08-14 16:41 并单：用户真机截图，回前台后终端只占屏幕上方约 1/3）。
 *
 * ## 两次判据自我纠正（如实记录——判据的价值在于能区分，不在于它是红是绿）
 *
 * **第一次**：第一版断言「恢复阶段必须恰好一次 resize」，跑出来是红的，但往下查发现红得
 * 不对——squeeze（IME 挤压）本就不动内核 rows/cols，"挤压后恢复到原尺寸"这一步 0 次上抛
 * 在数学上就是对的，不是 bug。改成断言可观察结果（恢复后渲染窗口有没有重新覆盖真实视口
 * 行数）后变绿。
 *
 * **第二次（这次）**：对"restore-to-原尺寸"场景做定点变异（禁用 [TermViewPresenter.onRealViewportChanged]
 * 里 outgrew 守卫触发的重算），结果**测试仍然是绿的**——说明这个场景对该守卫根本没有检测力：
 * squeeze 从不触碰内核 rows/cols，所以"恢复到原尺寸"时 [TermViewPresenter] 的 `visibleRows`
 * getter 用 `coerceIn(1, emulator.rows)` 钳制，而 emulator.rows 从始至终就是对的（50），
 * 跟守卫是否触发无关——**这不是场景写错，是这个特定场景本来就测不到这个守卫**，得换一个
 * 真正依赖守卫的场景。
 *
 * 换成「首次建立时视口本身就偏小（模拟布局未完成的瞬时小尺寸被误当真实视口）→ 之后真实
 * 视口变大」——此时内核 rows/cols 会先被钉在小值，只有 outgrew 守卫触发的重算才能纠正，
 * `visibleRows` 的 `coerceIn(1, emulator.rows)` 上限会直接把恢复后的可见行数摁在旧的小值
 * 上，与用户主诉的「只占1/3」形状吻合。同一变异下这个场景确认转红，再验证变异前是绿的、
 * 变异后是红的、改回来又是绿的——才算这条护栏真的有检测力（定点变异留档见下）。
 *
 * ## 定点变异留档（leader 2026-08-14 裁定：每个硬要求至少变异一次证明用例真能转红）
 *
 * 变异点：[TermViewPresenter.onRealViewportChanged] 里 `outgrew = viewportOutgrewEmulator()`
 * 改成硬编码 `outgrew = false`（禁用回前台恢复的重算守卫）。
 *
 * - 变异前（HEAD）：两个用例全绿。
 * - 变异后：`smallInitialViewport_thenRealGrowth_recoversFullCapacity` 转红——
 *   `expected:<50> but was:<18>`，与用户主诉「画面只占屏幕上面约1/3」（18/50=36%）吻合。
 * - 已改回原样，本文件不含任何变异代码。
 *
 * ## 这条护栏排除了什么、没排除什么
 *
 * 排除了：viewportOutgrewEmulator 的比较逻辑本身有算术错误——只要「视口恢复/增长」事件真的
 * 送达 presenter，两条入口（onViewportSizeChanged/onRealViewportChanged）都能正确纠正。
 *
 * 没排除：**事件根本没被送达**（Activity 级 ON_STOP/ON_START 与 View/Window 级回调是否
 * 对齐，长后台系统回收 Surface 时尤其可疑）。presenter 单测测不到这层，已交给 w-font-probe
 * 第四问 + DiagLog 调用点仪表坐实。
 */
class TermViewportRegrowAfterSqueezeTest {

    private class Harness(
        val emulator: TerminalEmulator,
        val presenter: TermViewPresenter,
        val resizeCalls: MutableList<Pair<Int, Int>>,
    )

    private fun harness(): Harness {
        val emulator = TerminalEmulator(80, 24)
        val resizeCalls = mutableListOf<Pair<Int, Int>>()
        val presenter = TermViewPresenter(emulator) { rows, cols ->
            resizeCalls += rows to cols
            emulator.resize(cols, rows)
        }
        presenter.seedCellMetrics(11, 22) // 真机实测字格典型值，字号选定后固定不变
        return Harness(emulator, presenter, resizeCalls)
    }

    /** 当前渲染窗口覆盖的行数（可观察结果：用户报的"画面只占1/3"就是这个数偏小）。 */
    private fun visibleRowCount(presenter: TermViewPresenter): Int {
        val w = presenter.window
        return w.last - w.first + 1
    }

    @Test
    fun squeezeThenRealViewportRegrow_rendersFullCapacityAgain() {
        // 挤压→恢复到【原】尺寸：squeeze 本就不动内核 rows/cols，这一步理应自愈——
        // 覆盖"守卫没有反应过度、挤压恢复不会被误判成需要重算"这条对称面。
        val h = harness()
        val fullW = 1260
        val fullH = 1100
        val expectedFullRows = fullH / 22 // 50

        h.presenter.onViewportSizeChanged(fullW, fullH)
        assertEquals(expectedFullRows, visibleRowCount(h.presenter))

        h.presenter.onViewportSizeChanged(fullW, 400) // IME 挤压
        assertTrue(
            "[夹具] 挤压阶段渲染窗口应收缩，否则本用例没有制造出挤压后这个前提",
            visibleRowCount(h.presenter) < expectedFullRows,
        )

        h.presenter.onRealViewportChanged(fullW, fullH) // 回前台：恢复到原尺寸

        assertEquals(
            "[可观察结果] 挤压恢复到原尺寸后，渲染窗口必须覆盖真实视口能放下的全部行数",
            expectedFullRows, visibleRowCount(h.presenter),
        )
    }

    @Test
    fun smallInitialViewport_thenRealGrowth_recoversFullCapacity() {
        // 真正依赖 outgrew 守卫的场景（定点变异证明过检测力，见类文档）：首次建立时视口本身
        // 偏小（模拟布局未完成的瞬时尺寸被当成了"首次真实视口"），内核 rows/cols 被钉在小值；
        // 之后真实视口变大（回前台 / 分屏结束 / 系统重建 Surface 后拿到正确尺寸），只有
        // viewportOutgrewEmulator 触发的重算才能把 emulator.rows/cols 纠正回真实容量——
        // 不纠正就是用户主诉"画面只占屏幕上面约1/3"的直接复现（本例 18/50 ≈ 36%）。
        val h = harness()
        val fullW = 1260
        val fullH = 1100
        val smallH = 400
        val expectedSmallRows = smallH / 22 // 18
        val expectedFullRows = fullH / 22 // 50

        h.presenter.onViewportSizeChanged(fullW, smallH) // 首次建立即偏小（钉死内核 rows）
        assertEquals(expectedSmallRows, visibleRowCount(h.presenter))

        h.presenter.onRealViewportChanged(fullW, fullH) // 真实视口变大

        assertEquals(
            "[可观察结果] 真实视口变大后，渲染窗口必须重新覆盖到真实容量，不能停在首次建立时" +
                "误判的小值上——停在小值就是用户主诉「画面只占屏幕上面约1/3」的直接复现",
            expectedFullRows, visibleRowCount(h.presenter),
        )
    }
}
