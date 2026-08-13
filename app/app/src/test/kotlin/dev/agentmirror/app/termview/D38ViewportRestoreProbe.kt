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
 * 根因探针 · D-38「回前台输入框跑到屏幕中间」（fix-viewport-restore-d38 回炉审查席产物）。
 *
 * ══════════════════════════════════════════════════════════════
 * 阅读说明（重要）
 * ══════════════════════════════════════════════════════════════
 * 本文件是「回炉流程」审查席的**验收标准**，不是修复本身。
 *
 * 在当前 v6 HEAD（已回退）上跑 → 全部 PASS  = "命中" = 诊断正确，缺陷存在。
 * 修复后跑                      → 全部 FAIL  = "不命中" = 修复有效，缺陷消失。
 *
 * 任何一个 FAIL 在 v6 HEAD 上出现 → 停下报 leader，绝不改探针迁就诊断。
 * 任何一个 PASS 在修复后仍然存在 → 修复没有击中根因，继续排查。
 *
 * ══════════════════════════════════════════════════════════════
 * 已闭合根因（evidence.json 原文）——本探针需要"证伪或坐实"的对象
 * ══════════════════════════════════════════════════════════════
 * evidence.json 写：
 *   「回前台时 IME 仍在屏上，onRealViewportChanged 重算并上报，
 *     把被挤压的几何当成了永久基线；而 onViewportSizeChanged（IME 收起）
 *     按 fix-ime-no-resize 不再上报 → 挤压值成为永久基线。」
 *
 * ⚠️ 审查席发现：onRealViewportChanged 是 v3 patch 新增的方法，
 *    当前 v6 HEAD 的 TermViewPresenter 里根本不存在该方法。
 *    所以 evidence.json 描述的是 **patch 里的行为**，而非 **v6 的根因**。
 *    这是纪律①（回退期间立的账不能按回退前的代码来判）的第三个实例。
 *
 * ══════════════════════════════════════════════════════════════
 * v6 真正的根因（从代码和实测数字反推）
 * ══════════════════════════════════════════════════════════════
 * v6 TermViewPresenter.onViewportSizeChanged 两条路径：
 *   1. 首帧（viewportSeeded=false）：seed → recomputeGeometry() → emitResize(rows, cols)
 *   2. 首帧之后：只调 updateVisibleRows()，更新 visibleRowsOverride，**不动 emulator.rows**
 *
 * v6 TermSurfaceView：
 *   - onSizeChanged → presenter.onViewportSizeChanged （走路径 2 if already seeded）
 *   - 无 onWindowVisibilityChanged override（v5 有，被列为禁区未捞回）
 *
 * visibleRows getter（v6）：
 *   visibleRowsOverride?.coerceIn(1, emulator.rows) ?: emulator.rows
 *   ← 上限是 emulator.rows！IME 收起后 visibleRowsOverride 增长，
 *     但 emulator.rows 没更新 → visibleRows 被夹在旧的小值。
 *
 * 真正根因：
 *   **回前台时（乃至任何时刻），没有任何代码路径能在 viewportSeeded=true 之后
 *   调用 recomputeGeometry() 来更新 emulator.rows**——除了 onFontSizeChanged（捏合）。
 *   v5 用 onWindowVisibilityChanged 补了这个缺口，该文件被列为禁区未捞回，
 *   缺口一直空着。fix-ime-no-resize 进一步堵死了唯一能"顺带纠正"的路径。
 *
 * 实测数字（w-base-v2，与用户截图吻合）：
 *   首帧 IME 挤压 → emulator.rows=84；IME 收起后 → visibleRowsOverride=140；
 *   visibleRows = 140.coerceIn(1,84) = 84；空白 = (140-84)×20 = 1120px ≈ 1123px（吻合）。
 */
class D38ViewportRestoreProbe {

    private class Harness(
        val emulator: TerminalEmulator,
        val presenter: TermViewPresenter,
        val resizeCalls: MutableList<Pair<Int, Int>>,
    )

    private fun harness(): Harness {
        val emulator = TerminalEmulator(cols = 80, rows = 24)
        val resizeCalls = mutableListOf<Pair<Int, Int>>()
        val presenter = TermViewPresenter(emulator) { r, c ->
            resizeCalls.add(r to c)
            emulator.resize(c, r)
        }
        return Harness(emulator, presenter, resizeCalls)
    }

    /**
     * 探针 P1（核心）：IME 挤压后 View 增长，emulator.rows 不跟着增长。
     *
     * 模拟序列：进入 CLI 时 IME 弹起 → 首帧被挤压 seed(84 行) → IME 收起 View 增长(140 行)。
     *
     * v6 PASS（命中）：emulator.rows 仍为 84；window 被夹在 84 行而非 140 行。
     * 修复后 FAIL（不命中）：修复会调 recomputeGeometry() 更新 emulator.rows=140，
     *   断言 84 不再成立。
     */
    @Test
    fun PROBE_P1_emulatorRowsStuckAtSqueezedValueAfterViewGrows() {
        val h = harness()

        // 首帧被 IME 挤压（1680px / 20px = 84 行）→ seed，emit 一次 resize(84,108)。
        h.presenter.onViewportSizeChanged(1080, 1680)
        assertEquals("首帧 seed: emulator.rows 应为 84", 84, h.emulator.rows)
        assertEquals("首帧 seed: 应 emit resize(84,108)", listOf(84 to 108), h.resizeCalls)

        // IME 收起，View 增长到 2800px（140 行）。
        // fix-ime-no-resize：只调 updateVisibleRows()，不调 recomputeGeometry()。
        h.presenter.onViewportSizeChanged(1080, 2800)

        // PROBE 命中条件 ①：emulator.rows 仍为 84（服务端对终端几何的认知未更新）。
        assertEquals(
            "PROBE P1 命中: IME 收起后 emulator.rows 仍为 84，服务端只知道 84 行",
            84,
            h.emulator.rows,
        )
        // PROBE 命中条件 ②：resize 回调序列不变（确认 fix-ime-no-resize 生效，第二次未 emit）。
        assertEquals(
            "PROBE P1 命中: fix-ime-no-resize 吞掉了增长事件，resize 回调序列不变",
            listOf(84 to 108),
            h.resizeCalls,
        )
    }

    /**
     * 探针 P2（窗口被夹住）：visibleRows 被 emulator.rows 上限夹住，导致 window 显示行数不足。
     *
     * 验证 v6 visibleRows getter：override.coerceIn(1, emulator.rows)
     * 当 override=140, emulator.rows=84 → visibleRows=84，window 只覆盖 84 行（56 行空白）。
     *
     * v6 PASS（命中）：window 长度 = 84（非 140）。
     * 修复后 FAIL：window 长度 = 140。
     */
    @Test
    fun PROBE_P2_windowCappedByStaleEmulatorRows() {
        val h = harness()

        // 完整序列：挤压 seed → 增长（被 fix-ime-no-resize 吞）。
        h.presenter.onViewportSizeChanged(1080, 1680) // seed 84 行
        h.presenter.onViewportSizeChanged(1080, 2800) // grow 140 行，visibleRowsOverride=140

        val win = h.presenter.window
        val windowRows = win.last - win.first + 1

        // PROBE: window 长度被 emulator.rows(84) 夹住，而非 viewportRows(140)。
        // 即：用户只看到 84 行，下方 56 行（1120px）是空黑。
        assertEquals(
            "PROBE P2 命中: window 被 emulator.rows(84) 夹住，显示 84 行而非 viewportRows(140)",
            84,
            windowRows,
        )

        // 辅助观测：emulator.rows 与 viewportRows 的差值 = 空白行数（实证 1120/20=56）。
        val viewportRows = 2800 / 20 // = 140（默认 cellHeight=20）
        val blankRows = viewportRows - windowRows
        assertEquals(
            "PROBE P2 命中: 空白行数 = 56（1120px，与用户截图吻合）",
            56,
            blankRows,
        )
    }

    /**
     * 探针 P3（回前台无补救）：模拟"回前台触发 onSizeChanged"（即再次调 onViewportSizeChanged），
     * 证明 v6 无论额外触发多少次 onViewportSizeChanged，emulator.rows 都不会被纠正。
     *
     * 这是 fix-ime-no-resize 的"叠加因素"：即使回前台触发了 onSizeChanged → onViewportSizeChanged，
     * 走的也是「只更新 visibleRowsOverride」路径，emulator.rows 永远不被纠正。
     *
     * v6 PASS（命中）：额外的 onViewportSizeChanged(2800) 调用不改变 emulator.rows。
     * 修复后 FAIL：修复会走另一个入口（onRealViewportChanged 或等价），emulator.rows 被纠正。
     */
    @Test
    fun PROBE_P3_foregroundReturnViaSizeChangedCannotRecoverGeometry() {
        val h = harness()

        // 挤压 seed → 增长（被吞）。
        h.presenter.onViewportSizeChanged(1080, 1680) // seed 84
        h.presenter.onViewportSizeChanged(1080, 2800) // grow, swallowed

        // 模拟"回前台时 onSizeChanged 重新触发"（即 View 尺寸未变，但就算重新触发也一样）。
        h.presenter.onViewportSizeChanged(1080, 2800) // 再调一次，模拟回前台
        h.presenter.onViewportSizeChanged(1080, 2800) // 多调几次确认幂等

        // PROBE: emulator.rows 仍为 84，多次 onViewportSizeChanged 无济于事。
        assertEquals(
            "PROBE P3 命中: 无论调多少次 onViewportSizeChanged(2800)，emulator.rows 仍为 84",
            84,
            h.emulator.rows,
        )
        assertEquals(
            "PROBE P3 命中: resize 回调序列仍只有首帧的 (84,108)，后续调用均被 fix-ime-no-resize 吞掉",
            listOf(84 to 108),
            h.resizeCalls,
        )
    }

    /**
     * 探针 P4（首帧被 IME 挤压是触发条件）：如果首帧不被挤压，则 emulator.rows 从一开始就正确，
     * 后续 IME 弹起/收起不影响 emulator.rows（fix-ime-no-resize 正常工作）。
     *
     * 这个探针验证"首帧被 IME 挤压 seed 小值"才是触发 D-38 的前提条件。
     * 如果首帧是全高，缺陷不触发（fix-ime-no-resize 合法工作）。
     *
     * v6 PASS（命中）：首帧全高 seed → emulator.rows=140；IME 挤压/收起 emulator.rows 不变。
     * 修复后仍 PASS（应同样命中，此探针验证不倒退）。
     */
    @Test
    fun PROBE_P4_ifFirstFrameIsFullHeightNoDefect() {
        val h = harness()

        // 首帧全高（无 IME）→ seed 140 行，这是健康情况。
        h.presenter.onViewportSizeChanged(1080, 2800) // 2800/20 = 140 行
        assertEquals("首帧全高: emulator.rows=140", 140, h.emulator.rows)
        assertEquals("首帧全高: emit resize(140,108)", listOf(140 to 108), h.resizeCalls)

        // IME 弹起，挤压到 84 行。
        h.presenter.onViewportSizeChanged(1080, 1680) // 1680/20 = 84 行
        // fix-ime-no-resize: 不 emit，emulator.rows 仍 140（正确）。
        assertEquals("IME 挤压后: emulator.rows 仍为 140（首帧是全高，缺陷不触发）", 140, h.emulator.rows)
        assertEquals("IME 挤压后: resize 回调不变", listOf(140 to 108), h.resizeCalls)

        // IME 收起，复原到 140 行。
        h.presenter.onViewportSizeChanged(1080, 2800)
        assertEquals("IME 收起后: emulator.rows 仍为 140", 140, h.emulator.rows)

        // 关键：window 长度正确 = 140，无空白（缺陷未触发）。
        val windowRows = h.presenter.window.let { it.last - it.first + 1 }
        assertEquals("PROBE P4 命中: 首帧全高时 window=140，缺陷不触发", 140, windowRows)
    }

    /**
     * 探针 P5（确认不存在 onRealViewportChanged）：v6 TermViewPresenter 是否不含
     * onRealViewportChanged 方法——这直接否定了 evidence.json 的根因描述。
     *
     * 此探针用反射检查方法存在性，明确记录 v6 和修复后的差异。
     *
     * v6 PASS（命中）：方法不存在 → evidence 描述的是 patch 里的行为。
     * 修复后 FAIL（不命中）：方法存在 → 修复有效。
     */
    @Test
    fun PROBE_P5_onRealViewportChangedDoesNotExistInV6() {
        val methods = TermViewPresenter::class.java.declaredMethods.map { it.name }

        // 断言 v6 里该方法不存在（confirm evidence.json 描述是对 patch 的描述，非 v6）。
        assertTrue(
            "PROBE P5 命中: v6 TermViewPresenter 无 onRealViewportChanged 方法。" +
            "evidence.json 「onRealViewportChanged 把挤压几何当基线」描述的是 v3 patch 的行为，" +
            "v6 里该方法根本不存在。已实际方法列表：$methods",
            "onRealViewportChanged" !in methods,
        )
    }
}
