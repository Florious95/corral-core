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
import dev.agentmirror.app.ui.theme.TerminalMetrics
import dev.agentmirror.terminal.TerminalEmulator
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 红测（feat-font-size-setting-drop-pinch，测试席）：移除捏合缩放，改为设置页字号。
 *
 * 用户裁定与代码根因见 `.team/nodes/feat-font-size-setting-drop-pinch/CLAUDE.md`。
 * 四条硬要求逐条落测（钉住 taskbook 五条 acceptance 的前三条 + 第五条；CJK 见另一测试）：
 *
 * 1. 字号 → 单元尺寸走实测字形度量（不查表配常量）
 * 2. 禁止「名义值播种 → 实测回写收敛」：全流程只上报一次 resize
 * 3.（此文件不测）字号持久化——见 [TermFontSizeSettingPersistenceTest]
 * 4.（此文件不测）CJK 末列不越界——见 [TermFontSizeSettingCjkFitTest]
 * 5. cellHeight 用实测值，不钉死在 DEFAULT_CELL_HEIGHT
 *
 * ## 契约声明（测试席对开发席的接口约定，非猜测——已用本文件的失败信息显式标注）
 *
 * 本文件假设 [TermViewPresenter] 新增一个公开方法：
 * ```
 * fun seedCellMetrics(cellWidthPx: Int, cellHeightPx: Int)
 * ```
 * 语义：在首次 [TermViewPresenter.onViewportSizeChanged] 到来之前，把 cellWidth/cellHeight
 * 直接置为「实测字形度量」得出的值（不经过 DEFAULT_CELL_WIDTH/HEIGHT 占位常量）。
 * 调用方（TermSurfaceView）应在选定字号后、首次 onSizeChanged 前调用一次。
 *
 * 若开发席选择了不同的方法名/签名，请修改本文件对应处或找测试席同步——
 * 不得为了让测试变绿而悄悄放宽断言语义（判据纪律）。
 *
 * 方法不存在时本文件的探测辅助函数直接 fail()（真正的红，不是 skip），因为「现在必须是红的」。
 *
 * **防静默失效（leader 2026-08-14 补充裁定，批准 seedCellMetrics/FontSizeStore 接口时附带）**：
 * 未先调用 [TermViewPresenter.seedCellMetrics] 就调用 [TermViewPresenter.onViewportSizeChanged]，
 * presenter 必须显式失败（抛异常），不许静默用 0/DEFAULT_CELL_WIDTH/HEIGHT 占位值继续算并上抛
 * resize——否则等于把刚拆掉的"名义值播种"用另一种更隐蔽的形式（无可观察的"两次上报"信号）请回来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermFontSizeSettingDropPinchTest {

    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap)

    private fun draw(view: TermSurfaceView, w: Int, h: Int) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = RecordingCanvas(bmp)
        view.draw(c)
        bmp.recycle()
    }

    /**
     * 反射调用 `presenter.seedCellMetrics(w, h)`；方法不存在则 fail（真红，非 skip——
     * 这是本任务的核心契约，缺失就是没做，不是"暂未开发的旁支"）。
     */
    private fun seedCellMetricsOrFail(presenter: TermViewPresenter, cellW: Int, cellH: Int) {
        val method: Method = presenter.javaClass.methods.firstOrNull { it.name == "seedCellMetrics" }
            ?: throw AssertionError(
                "[契约缺失] TermViewPresenter.seedCellMetrics(cellWidthPx, cellHeightPx) 不存在——" +
                    "字号选定后必须能在首次视口建立前把实测值喂给 presenter，不许靠 DEFAULT 播种再回写收敛。" +
                    "红测红在正确的地方：请开发席实现该入口（或与测试席同步改名）。",
            )
        method.isAccessible = true
        method.invoke(presenter, cellW, cellH)
    }

    // ---------------------------------------------------------------------------
    // 硬要求②（守恒式断言）：全流程只上报一次 resize，不存在「名义 → 实测」两次上报。
    //
    // 用真实 TermSurfaceView 走完整链路：seed 实测尺寸 → layout（onSizeChanged）→
    // draw（measureCells 回写）。今天必红：即使 seed 了"真实"尺寸，View 层 measureCells
    // 仍按 `cellHeight × 0.85` 反推 textSize（旧的 cellH→textSize→cellH 环未拆），
    // 测出的 cellW/cellH 与外部探针用同一字号独立测出的值不一致 → 触发第二次上报。
    // ---------------------------------------------------------------------------

    @Test
    fun fullFlow_seededWithRealMetrics_reportsResizeExactlyOnce() {
        val viewportW = 1080
        val viewportH = 480
        val emulator = TerminalEmulator(80, 24)
        val resizeCalls = mutableListOf<Pair<Int, Int>>()
        val presenter = TermViewPresenter(emulator) { rows, cols ->
            resizeCalls += rows to cols
            emulator.resize(cols, rows)
        }

        // 真机实测字形典型值（非查表常量，是"给定字号后应当测出的样子"）——不用 Robolectric
        // 的 Paint.fontMetrics 现场测：legacy graphics 下 descent-ascent 恒为 0（与
        // TermColsGridConvergenceDiscriminationTest 类文档记录的 measureText stub 同类现象，
        // 但这次是 fontMetrics 维度），会在 seedCellMetrics 的 require(cellH>0) 上崩溃——
        // 这本身是本文件之外的一个环境发现，已同步开发席，不在本测试断言范围内。
        val realCellW = 11
        val realCellH = 22
        seedCellMetricsOrFail(presenter, realCellW, realCellH)

        // 必须先 seed 再注入 View：TermSurfaceView.presenter setter 对未 seed 的 presenter
        // 会自动调用 applyFontMetrics()（走 Robolectric 的 fontMetrics stub，返回 0，撞上面
        // 那个环境限制）——已 seed 过的 presenter 不会被覆盖（契约：显式 seed 优先）。
        val view = TermSurfaceView(RuntimeEnvironment.getApplication()).apply {
            this.presenter = presenter
        }

        view.layout(0, 0, viewportW, viewportH) // → onSizeChanged → onViewportSizeChanged
        draw(view, viewportW, viewportH) // → measureCells → 可能的第二次上报

        assertEquals(
            "[守恒] 全流程只应上报一次 resize，不存在「名义值播种 → 实测值回写」两次上报；" +
                "实际上报序列=$resizeCalls——说明 View 层测出的字格与外部按同一字号独立测出的" +
                "字格不同源（cellH→textSize→cellH 环仍在，或 seed 未被采信）",
            1, resizeCalls.size,
        )
    }

    // ---------------------------------------------------------------------------
    // 硬要求①：给定字号，上报的 cols 与画布实际能容纳的列数一致（同源校验，presenter 层）。
    // ---------------------------------------------------------------------------

    /**
     * 每档预设字号对应的真机实测字形宽/高典型值（px，按与 sp 大致成比例估算，非查表
     * 常量——这里只是测试夹具的输入参数，不是产品代码的换算依据）。
     *
     * leader 2026-08-14 17:21 补充裁定（用户新证据：默认字号下、从未捏合，行末字符
     * 也会被切掉半个，只是字大时溢出量小不明显）：溢出量 ≈ 列数 ×（实测宽－名义宽），
     * **字越小→列越多→累积误差越大越容易暴露**。若只用较大字号跑 cols 断言，即使产品
     * 代码里仍潜伏着"名义值 ≠ 实测值"的旧模式，溢出量也可能小到被 floor 除法吸收掉，
     * 断言照样通过——等于没测到东西。所以覆盖面必须含最小档（12sp），不能只测中间值。
     */
    private data class CellMetricsTier(val fontSizeSp: Int, val cellW: Int, val cellH: Int)

    private val fontSizeTiers = listOf(
        CellMetricsTier(fontSizeSp = 12, cellW = 8, cellH = 17), // 最小档：列数最多，误差最容易暴露
        CellMetricsTier(fontSizeSp = 14, cellW = 10, cellH = 20),
        CellMetricsTier(fontSizeSp = 16, cellW = 11, cellH = 22), // 原用户真机截图典型值
        CellMetricsTier(fontSizeSp = 18, cellW = 13, cellH = 25),
        CellMetricsTier(fontSizeSp = 20, cellW = 14, cellH = 27),
    )

    @Test
    fun givenFontSize_seededSingleEmitMatchesCanvasCapacity_acrossAllPresetTiers() {
        val viewportW = 1260 // 用户真机宽
        val viewportH = 480

        for (tier in fontSizeTiers) {
            val emulator = TerminalEmulator(80, 24)
            val resizeCalls = mutableListOf<Pair<Int, Int>>()
            val presenter = TermViewPresenter(emulator) { rows, cols ->
                resizeCalls += rows to cols
                emulator.resize(cols, rows)
            }

            seedCellMetricsOrFail(presenter, tier.cellW, tier.cellH)
            presenter.onViewportSizeChanged(viewportW, viewportH) // 初始几何建立（不是"变更后"）

            assertEquals(
                "[守恒][${tier.fontSizeSp}sp] seed 实测值后首次视口建立必须恰好上报一次 resize",
                1, resizeCalls.size,
            )
            val (_, cols) = resizeCalls.single()
            val capacity = minOf(viewportW / tier.cellW, TerminalMetrics.maxCols)
            assertEquals(
                "[①][${tier.fontSizeSp}sp] 上报 cols($cols) 必须与画布实测容量($capacity) 一致——" +
                    "同源校验（本档列数=$capacity，是覆盖列表里累积误差最容易暴露的一档吗：" +
                    "${tier.fontSizeSp == fontSizeTiers.minOf { it.fontSizeSp }}）",
                capacity, cols,
            )
            assertTrue(
                "[①][${tier.fontSizeSp}sp] cols×实测宽=${cols * tier.cellW} 不得超出 View 宽=$viewportW",
                cols * tier.cellW <= viewportW,
            )
        }
    }

    // ---------------------------------------------------------------------------
    // 防静默失效（leader 2026-08-14 补充裁定）：未先 seedCellMetrics 就建立视口，必须
    // 显式失败，不许静默拿 0/DEFAULT_CELL_WIDTH/HEIGHT 占位值继续跑几何计算。
    //
    // 理由：今天已经吃过一次同类亏——TermSurfaceView.lineHeightPx 只在 onDraw 赋值，
    // 纯 layout 不触发时它是 0，deltaLines 变成 Int.MIN_VALUE，当时的测试只断言 `<0`，
    // MIN_VALUE 照样满足，"通过"了整整一轮。若 seed 缺失时 presenter 仍用 DEFAULT 常量
    // 悄悄把几何算出来，等于把刚拆掉的"名义值播种"用另一种形式请了回来——只是这次连
    // "两次上报"这个可观察信号都没有了，比原来的 bug 更隐蔽。
    // ---------------------------------------------------------------------------

    @Test
    fun onViewportSizeChanged_withoutPriorSeed_mustFailExplicitly_notSilentlyUseDefaults() {
        val emulator = TerminalEmulator(80, 24)
        val resizeCalls = mutableListOf<Pair<Int, Int>>()
        val presenter = TermViewPresenter(emulator) { rows, cols -> resizeCalls += rows to cols }

        var threw = false
        try {
            // 没有调用 seedCellMetrics：presenter 尚不知道"实测字形度量"是什么。
            presenter.onViewportSizeChanged(1080, 480)
        } catch (e: Throwable) {
            threw = true
        }

        assertTrue(
            "[防静默失效] 未先 seedCellMetrics 就建立视口，presenter 必须显式失败（抛异常/明确错误态），" +
                "不许静默用 0 或 DEFAULT_CELL_WIDTH/HEIGHT 占位值继续算 rows/cols 并上抛 resize；" +
                "实际 resizeCalls=$resizeCalls——若非空说明用占位值悄悄跑通了，" +
                "等于把刚拆掉的「名义值播种」用另一种形式请了回来",
            threw,
        )
    }

    // ---------------------------------------------------------------------------
    // 硬要求⑤：cellHeight 使用实测值，不得钉死在 DEFAULT_CELL_HEIGHT（=20，见生产代码常量）。
    //
    // 未 seed 时（当前默认构造路径）cellHeight 恒为 20，从不实测——这正是要拆的
    // cellH→textSize→cellH 反馈环（回写只动 cellWidth，cellHeight 永远原地不动）。
    //
    // 环境限制记录（不是本测试断言对象，是过程中发现，已同步给开发席）：TermSurfaceView
    // 自动补 seed 的路径（未 seed 时 presenter setter 内部调用 applyFontMetrics）在
    // Robolectric legacy graphics 下会撞上 fontMetrics 恒返回 0 的 stub 限制，触发
    // seedCellMetrics 的 require(cellH>0) 抛异常——这是 Robolectric 环境的已知短板
    // （同类问题 TermColsGridConvergenceDiscriminationTest 类文档已记录过 measureText 维度的
    // 版本），不代表真机行为，本测试不依赖该自动路径，避免被这个环境噪音污染断言。
    // ---------------------------------------------------------------------------

    @Test
    fun cellHeight_afterSeeding_mustNotStayAtHardcodedDefault() {
        val emulator = TerminalEmulator(80, 24)
        val presenter = TermViewPresenter(emulator) { rows, cols -> emulator.resize(cols, rows) }

        seedCellMetricsOrFail(presenter, 11, 22)

        assertNotEquals(
            "[⑤] seedCellMetrics 落定后 cellHeight 仍是硬编码 DEFAULT_CELL_HEIGHT(20)——" +
                "说明写入没生效，或实现绕过了实测值改用了占位常量",
            20, presenter.cellHeight,
        )
        assertEquals("[⑤] cellHeight 必须等于喂入的实测值", 22, presenter.cellHeight)
    }
}
