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

import android.view.View
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 场景红测 · D-38「重进 CLI 输入框跑到屏幕中间」（fix-viewport-restore-d38 测试席产物）。
 *
 * ══════════════════════════════════════════════════════════════
 * 与审查席探针的关系（不重复也不替代）
 * ══════════════════════════════════════════════════════════════
 * 审查席探针 P1-P5（D38ViewportRestoreProbe）测的是「缺陷条件在不在」：
 *   P1/P2 断言 emulator.rows 停在挤压 seed 值（84）→ 缺陷存在时 PASS。
 *   它们直接调 presenter.onViewportSizeChanged，不经过 View 层。
 *
 * 本文件测的是「用户场景走一遍会怎样」：把 FIELD.md 的完整用户序列
 * 「首帧被 IME 挤压 → IME 收起 → 回前台」走一遍，断言最终几何是否与当前
 * View 尺寸对齐。回前台通过 [View.dispatchWindowVisibilityChanged] 驱动——
 * 这是用户真实回前台事件在 View 层的入口，也是 v5 曾补过、v6 被回退时
 * 丢失的缺口（探针 P3 证实 v6 无 onWindowVisibilityChanged override）。
 *
 * 设计约束：**不得引用修复后才会存在的方法**（如 onRealViewportChanged）——
 * 那会让本测试在当前 v6 HEAD 上编译不过（违反「保持可编译」铁律）。
 * 因此回前台只用 Android 既有公开 API（dispatchWindowVisibilityChanged），
 * 修复时 View 层补 override 即可让测试转绿，测试本身不感知方法签名。
 *
 * 预期（干净 v6 HEAD，已回退）：
 *   S1 红（回前台/增长均无重对齐，emulator.rows 仍 84）
 *   S2 绿（fix-ime-no-resize 锚定：IME 挤压不 emit resize）
 *   S3 绿（双向守门：几何扰动恒 1，无黑屏闪链上的多余扰动）
 *   S4 绿（P4 场景版：首帧全高时新逻辑不额外 resize）
 * 修复后四条全绿。任何 S2/S3/S4 变红 = 不倒退被破坏，立即上报。
 *
 * ══════════════════════════════════════════════════════════════
 * 黑屏闪的可断言化（leader 最看重，S3）
 * ══════════════════════════════════════════════════════════════
 * v1/v2/v3 三次死因里，「单测全绿 + 真机黑屏闪」是最大陷阱——单测从未抓到过闪。
 * 黑屏闪在状态机层面的形态是「几何被改了错误的次数」：
 *   - 首帧 seed（1 次真实 resize）＝ 合法基数；
 *   - IME 弹起/收起（临时挤压/复原）＝ 0 次（fix-ime-no-resize 锚定）；
 *   - 回前台当几何本就正确（视口 == 内核）＝ 0 次；
 *   - v3 在「IME 弹出」这条链上加了状态分支 → 那链上多出一次几何扰动 → 黑屏闪。
 * 本文件把「几何被改了几次」变成可断言的量 [Harness.resizeCalls.size]（每次
 * onResizeRequest 实际改变内核几何记 1）。S3 对全高首帧的完整用户序列断言
 * 扰动恒为 1——这是前三次只靠真机机器眼（geometryCorrectionCount）才能看到的
 * 量的等价物，且可重复。修复若在 IME 链/回前台链上引入任何多余扰动即红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class D38ViewportRestoreScenarioTest {

    private class Harness(
        val emulator: TerminalEmulator,
        val presenter: TermViewPresenter,
        val resizeCalls: MutableList<Pair<Int, Int>>,
    )

    /**
     * 夹具：内核 + Presenter + resize 回调记录。
     *
     * 回调同时同步内核（emulator.resize），模拟 SessionViewModel 接线语义，使内核
     * 行列数与已 emit 的请求保持一致。几何扰动计数 = [Harness.resizeCalls].size：
     * recomputeGeometry 只在 rows/cols 与内核不一致时才 emit，故每次 emit 都伴随
     * 一次真实几何改变（S3 的可断言量）。
     */
    private fun harness(rows: Int = 24, cols: Int = 80): Harness {
        val emulator = TerminalEmulator(cols, rows)
        val resizeCalls = mutableListOf<Pair<Int, Int>>()
        val presenter = TermViewPresenter(emulator) { r, c ->
            resizeCalls.add(r to c)
            emulator.resize(c, r)
        }
        return Harness(emulator, presenter, resizeCalls)
    }

    /**
     * 场景 S1（核心红测）：完整用户序列「首帧被 IME 挤压 → IME 收起 → 回前台」，
     * 断言最终几何与当前 View 尺寸对齐（140 行），而非挤压 seed 值（84 行）。
     *
     * 用户现象：重进 CLI（IME 弹起）→ 输入框跑屏幕中间、下方大片空黑（56 行/1120px）。
     * v6 行为：IME 收起时 onViewportSizeChanged 只更新 visibleRowsOverride（不动
     * emulator.rows），回前台又无 onWindowVisibilityChanged override → 无任何重对齐
     * → emulator.rows 停在 84 → window 84 行而 View 有 140 行空间 → 56 行空黑。
     * 修复后：增长分支（视口 > 内核）或回前台 VISIBLE 事件触发真实视口重算 → rows=140。
     */
    @Test
    fun S1_foregroundReturnRealignsGeometry() {
        val h = harness()
        val view = viewBoundTo(h)

        // ① 首帧被 IME 挤压（1680px/20px = 84 行）：seed emulator.rows=84。
        h.presenter.onViewportSizeChanged(1080, 1680)
        assertEquals("首帧 seed: emulator.rows=84（缺陷触发前提）", 84, h.emulator.rows)

        // ② IME 收起，View 增长到 2800px（140 行空间）。
        h.presenter.onViewportSizeChanged(1080, 2800)

        // ③ 回前台：View 当前尺寸 1080x2800，触发 onWindowVisibilityChanged(VISIBLE)。
        view.layout(0, 0, 1080, 2800)
        view.dispatchWindowVisibilityChanged(View.VISIBLE)

        // 断言：用户走完序列后，几何必须重对齐到当前 View 能容纳的行数（140），
        // 而非被挤压时 seed 的 84——这是「输入框跑到屏幕中间」的消失条件。
        assertEquals(
            "用户完整序列后 emulator.rows 必须 = 140（当前 View 2800px/20px 能容纳），而非挤压 seed 84",
            140,
            h.emulator.rows,
        )
        val windowRows = h.presenter.window.let { it.last - it.first + 1 }
        assertEquals("回前台后 window 应覆盖 140 行（无 56 行空黑）", 140, windowRows)
    }

    /**
     * 场景 S2（守门·fix-ime-no-resize 锚定）：IME 弹起逐级挤压 + 收起复原，
     * 全程不得 emit resize，且挤压时可见行数必须与像素同步（视口上推贴底，无滞后）。
     *
     * 首帧选全高 seed（1920px→96 行），后续挤压到 92/89/86 行均小于 96，
     * 无夹制干扰——此场景下 v6 本就健康，改完必须还绿。
     * v3 黑屏闪死因正是「IME 弹出链上加状态导致 visibleRows 与画布脱节」，
     * 本测试断言每次挤压事件后 window 高度立即等于像素行数（无滞后）。
     */
    @Test
    fun S2_imePushPushesViewportWithoutResize() {
        val h = harness()

        // 首帧全高（无 IME）：唯一合法 resize。
        h.presenter.onViewportSizeChanged(1080, 1920)
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // IME 弹起 + 输入框 1→2→3 行：View 高度逐级收缩（63px 步进，对齐 w-test-ime 实测）。
        h.presenter.onViewportSizeChanged(1080, 1857)
        assertEquals("挤压后可见行数应立即与像素同步（92=1857/20，无滞后）", 92, h.presenter.window.let { it.last - it.first + 1 })

        h.presenter.onViewportSizeChanged(1080, 1794)
        assertEquals("继续挤压: 可见行数 89=1794/20", 89, h.presenter.window.let { it.last - it.first + 1 })

        h.presenter.onViewportSizeChanged(1080, 1731)
        assertEquals("继续挤压: 可见行数 86=1731/20", 86, h.presenter.window.let { it.last - it.first + 1 })

        // IME 收起复原到 96 行。
        h.presenter.onViewportSizeChanged(1080, 1920)
        assertEquals("复原: 可见行数回到 96", 96, h.presenter.window.let { it.last - it.first + 1 })

        // 全程 resize 序列必须只有首帧那一次（IME 挤压/收起零扰动）。
        assertEquals(
            "IME 弹起/收起全程不得 emit resize（fix-ime-no-resize 锚定）",
            listOf(96 to 108),
            h.resizeCalls,
        )
    }

    /**
     * 场景 S3（黑屏闪可断言化·双向守门）：把「首帧到稳定态之间几何被改了几次」
     * 变成可断言的数字 [Harness.resizeCalls].size，全高首帧的完整用户序列扰动恒为 1。
     *
     * 分段断言：
     *   - 首帧全高 seed（140 行）→ 恰好 1 次几何扰动（合法基数）；
     *   - IME 弹起挤压到 84 行 → 0 次扰动（fix-ime-no-resize）；
     *   - IME 收起复原到 140 行（== 内核）→ 0 次扰动；
     *   - 回前台（视口 == 内核，几何本就正确）→ 0 次扰动。
     *
     * v6：全程扰动恒 1 → 绿（缺陷在该场景本就不触发，守门基线）。
     * 修复后：仍恒 1 → 绿（不倒退）。若修复在「IME 弹出/收起」或「回前台」链上
     * 引入任何多余几何扰动（v3 黑屏闪死因），分段断言立即红。
     */
    @Test
    fun S3_geometryDisturbanceIsBoundedToOne() {
        val h = harness()
        val view = viewBoundTo(h)

        // 首帧全高 seed（无 IME）：几何扰动恰 1 次（合法基数）。
        h.presenter.onViewportSizeChanged(1080, 2800)
        assertEquals("首帧全高 seed: 几何扰动恰 1 次", 1, h.resizeCalls.size)

        // IME 弹起挤压到 84 行：0 次扰动（fix-ime-no-resize）。
        h.presenter.onViewportSizeChanged(1080, 1680)
        assertEquals("IME 弹起: 几何扰动不得增加", 1, h.resizeCalls.size)

        // IME 收起复原到 140 行（视口 == 内核）：0 次扰动。
        h.presenter.onViewportSizeChanged(1080, 2800)
        assertEquals("IME 收起: 几何扰动不得增加", 1, h.resizeCalls.size)

        // 回前台：View 2800px == 内核 140 行，几何本就正确：0 次扰动。
        view.layout(0, 0, 1080, 2800)
        view.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertEquals(
            "全程几何扰动恒 1（仅首帧 seed），IME 链/回前台链零多余扰动（无黑屏闪）",
            1,
            h.resizeCalls.size,
        )
    }

    /**
     * 场景 S4（守门·P4 场景版）：首帧本来就是全高（未被 IME 挤压）时，
     * 回前台重对齐必须 no-op——几何早已正确，不得因为新逻辑多做一次 resize。
     *
     * 这是探针 P4 的场景走查版：首帧全高 seed 140 → IME 挤压/收起（零扰动）→
     * 回前台重对齐计算 rows=140 == emulator.rows=140 → 不发 resize。
     */
    @Test
    fun S4_fullHeightFirstFrameDoesNotTriggerExtraResize() {
        val h = harness()
        val view = viewBoundTo(h)

        // 首帧全高（无 IME）：seed 140 行，resize 恰一次。
        h.presenter.onViewportSizeChanged(1080, 2800)
        assertEquals(listOf(140 to 108), h.resizeCalls)
        assertEquals(140, h.emulator.rows)

        // IME 弹起挤压 → 收起复原：都不得 emit（fix-ime-no-resize）。
        h.presenter.onViewportSizeChanged(1080, 1680)
        h.presenter.onViewportSizeChanged(1080, 2800)
        assertEquals(listOf(140 to 108), h.resizeCalls)

        // 回前台：几何本就正确（emulator.rows=140 == View 尺寸能容纳行数），重对齐应 no-op。
        view.layout(0, 0, 1080, 2800)
        view.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertEquals(
            "首帧全高场景回前台不得额外 emit resize（P4 守门场景版）",
            listOf(140 to 108),
            h.resizeCalls,
        )
        assertEquals("回前台后 emulator.rows 保持 140", 140, h.emulator.rows)
    }

    /** 构造绑定到 presenter 的 View（Robolectric 环境）。 */
    private fun viewBoundTo(h: Harness): TermSurfaceView =
        TermSurfaceView(RuntimeEnvironment.getApplication()).apply {
            presenter = h.presenter
        }
}
