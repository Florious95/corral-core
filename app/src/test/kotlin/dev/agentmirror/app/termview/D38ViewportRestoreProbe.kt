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
import org.junit.Test

/**
 * D-38 回归闸（fix-viewport-restore-d38，2026-08-14 回炉审查席产物）。
 *
 * ══════════════════════════════════════════════════════════════
 * 历史背景（重要，看到红需要先读这里）
 * ══════════════════════════════════════════════════════════════
 * 本文件原为「回炉流程」审查席产出的**取证探针**，在 v6 回退 HEAD 上所有断言均 PASS，
 * 以证明 D-38 缺陷存在。2026-08-14 缺陷③修复提交 `3c8e2c2e3` 落地后，极性反转为
 * **回归闸**：断言修复后的正确行为，失败即代表 D-38 重现。
 *
 * 修复核心（详见 docs/d38-rootcause-probe.md）：
 *   `TermViewPresenter.viewportOutgrewEmulator()` —— 只有视口像素行/列数**超出**内核时
 *   重算并 emit，挤压（<= 内核）永不触发。这个判据天然区分「临时 IME 挤压」和
 *   「真实视口增长/回前台」，无需知道 IME 是否在屏（v1/v2/v3 都在推断 IME，都翻了）。
 *
 * ══════════════════════════════════════════════════════════════
 * 读取须知（若看到失败）
 * ══════════════════════════════════════════════════════════════
 * 测试红 = D-38「切后台再回前台输入框跑到屏幕中间」很可能重现。请检查：
 *   1. TermViewPresenter.onViewportSizeChanged 的 viewportOutgrewEmulator() 分支是否仍在
 *   2. TermViewPresenter.onRealViewportChanged 是否仍有 viewportOutgrewEmulator() 触发
 *   3. visibleRows getter 的 coerceIn(1, emulator.rows) 上限是否随 emulator.rows 正确更新
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
        // 防静默失效守卫要求先 seed（feat-font-size-setting-drop-pinch）：喂入与旧
        // DEFAULT_CELL_WIDTH/HEIGHT 相同的值（10x20），保持本文件既有断言数值不变。
        presenter.seedCellMetrics(10, 20)
        return Harness(emulator, presenter, resizeCalls)
    }

    /**
     * P1（回归闸）：IME 挤压后 View 增长，emulator.rows 必须随之恢复。
     *
     * 历史：取证探针断言「emulator.rows 卡在 84」（v6 BUG）。
     * 翻转后断言：增长触发 viewportOutgrewEmulator() → recomputeGeometry() → emulator.rows=140。
     *
     * 失败含义：D-38 重现——View 增长后 emulator.rows 仍然停在旧的挤压小值，
     *   visibleRows 被夹住，用户看到下方大片空黑。
     */
    @Test
    fun P1_emulatorRowsRecoverAfterViewGrows() {
        val h = harness()

        // 首帧被 IME 挤压到 84 行，seed 一次 resize。
        h.presenter.onViewportSizeChanged(1080, 1680) // 1680/20 = 84 行
        assertEquals("首帧 seed: emulator.rows=84", 84, h.emulator.rows)
        assertEquals("首帧 seed: 应 emit (84,108)", listOf(84 to 108), h.resizeCalls)

        // IME 收起，View 增长到 140 行。
        // viewportOutgrewEmulator(): 2800/20=140 > emulator.rows(84) → true → recomputeGeometry()
        h.presenter.onViewportSizeChanged(1080, 2800)

        assertEquals(
            "P1 PASS = D-38 已修复: IME 收起后 emulator.rows 应恢复到 140（不再卡在 84）",
            140,
            h.emulator.rows,
        )
        assertEquals(
            "P1 PASS: resize 回调序列 = 首帧 seed(84) + 增长恢复(140)",
            listOf(84 to 108, 140 to 108),
            h.resizeCalls,
        )
    }

    /**
     * P2（回归闸）：window 必须覆盖完整视口，无空黑行。
     *
     * 历史：取证探针断言「windowRows=84，空白=56行」（v6 BUG，与用户截图 1123px 吻合）。
     * 翻转后断言：windowRows=140，空白=0。
     *
     * 失败含义：D-38 重现——window 仍被旧的 emulator.rows 夹住，用户看到顶部约 1/4 空黑。
     */
    @Test
    fun P2_windowCoversFullViewportNoBlankRows() {
        val h = harness()

        h.presenter.onViewportSizeChanged(1080, 1680) // seed 84
        h.presenter.onViewportSizeChanged(1080, 2800) // grow → viewportOutgrewEmulator() → 140

        val win = h.presenter.window
        val windowRows = win.last - win.first + 1

        assertEquals(
            "P2 PASS = D-38 已修复: window 应覆盖完整视口 140 行（不被旧 emulator.rows 夹住）",
            140,
            windowRows,
        )

        val viewportRows = 2800 / 20 // = 140
        assertEquals(
            "P2 PASS: 空白行数应为 0（viewportRows - windowRows）",
            0,
            viewportRows - windowRows,
        )
    }

    /**
     * P3（回归闸）：onRealViewportChanged 路径——回前台时 onSizeChanged 未触发场景的恢复。
     *
     * 历史：取证探针断言「多次调 onViewportSizeChanged 也无法修复」（v6 BUG）。
     * 翻转后测试的是 onRealViewportChanged（修复新增的回前台专用入口）：
     *   View 尺寸在后台期间未变（onSizeChanged 不触发），回前台由 onWindowVisibilityChanged
     *   → onRealViewportChanged 触发，viewportOutgrewEmulator() 重算恢复几何。
     *
     * 失败含义：D-38 重现——回前台路径（onRealViewportChanged）失效，
     *   尺寸未变时回前台几何无法恢复。
     */
    @Test
    fun P3_onRealViewportChangedRecoversGeometryOnForegroundReturn() {
        val h = harness()

        // 首帧被挤压 seed（84 行），IME 未收起（View 尺寸未恢复）。
        h.presenter.onViewportSizeChanged(1080, 1680) // seed 84
        assertEquals("seed: emulator.rows=84", 84, h.emulator.rows)

        // 后台期间 View 尺寸未变，onSizeChanged 不触发。
        // 回前台：TermSurfaceView.onWindowVisibilityChanged → onRealViewportChanged(1080, 2800)。
        // viewportOutgrewEmulator(): 2800/20=140 > 84 → true → recomputeGeometry() → emit(140,108)。
        h.presenter.onRealViewportChanged(1080, 2800)

        assertEquals(
            "P3 PASS = D-38 已修复: onRealViewportChanged 路径下 emulator.rows 恢复到 140",
            140,
            h.emulator.rows,
        )
        assertEquals(
            "P3 PASS: resize 回调 = seed(84) + 回前台恢复(140)",
            listOf(84 to 108, 140 to 108),
            h.resizeCalls,
        )

        val windowRows = h.presenter.window.let { it.last - it.first + 1 }
        assertEquals("P3 PASS: window 恢复到 140 行", 140, windowRows)
    }

    /**
     * P4（不倒退守卫）：首帧全高时 fix-ime-no-resize 成果不受影响。
     *
     * 历史：取证探针断言「首帧全高时缺陷不触发」（边界验证），修复前后均 PASS。
     * 本条既是不倒退守卫，也是 fix-ime-no-resize 契约的回归闸：
     *   - 首帧 140 行 → IME 挤压到 84 行 → IME 收起到 140 行
     *   - viewportOutgrewEmulator()：84 < 140（不触发）；140 == 140（不触发）
     *   - resize 回调全程只有首帧那一次，fix-ime-no-resize 成果完整保留
     *
     * 失败含义：fix-ime-no-resize 被破坏，或 D-38 修复引入了新的误 emit。
     */
    @Test
    fun P4_firstFrameFullHeightNoDefectAndNoRegressionOnIme() {
        val h = harness()

        // 首帧全高（无 IME），seed 140 行。
        h.presenter.onViewportSizeChanged(1080, 2800) // 2800/20 = 140 行
        assertEquals("首帧全高: emulator.rows=140", 140, h.emulator.rows)
        assertEquals("首帧全高: emit (140,108)", listOf(140 to 108), h.resizeCalls)

        // IME 弹起，挤压到 84 行。viewportOutgrewEmulator(): 84 < 140 → false → 不触发。
        h.presenter.onViewportSizeChanged(1080, 1680)
        assertEquals("IME 挤压: emulator.rows 仍为 140（fix-ime-no-resize 成果）", 140, h.emulator.rows)
        assertEquals("IME 挤压: 无新 resize", listOf(140 to 108), h.resizeCalls)

        // IME 收起，复原到 140 行。viewportOutgrewEmulator(): 140 == 140 → false → 不触发。
        h.presenter.onViewportSizeChanged(1080, 2800)
        assertEquals("IME 收起: emulator.rows 仍为 140", 140, h.emulator.rows)
        assertEquals("IME 收起: 无新 resize（fix-ime-no-resize 成果）", listOf(140 to 108), h.resizeCalls)

        // window 全程正确 = 140 行，无空黑。
        assertEquals(
            "P4 PASS: window=140 行，D-38 不触发，fix-ime-no-resize 成果完整保留",
            140,
            h.presenter.window.let { it.last - it.first + 1 },
        )
    }

    // P5 已删除。
    // 历史意义保留在 docs/d38-rootcause-probe.md §2：
    //   「evidence.json 的已闭合根因描述了 v3 patch 里的 onRealViewportChanged 行为，
    //    v6 回退 HEAD 里该方法不存在——这解释了为什么按那份诊断修了三次都没成。」
    //   （纪律①第三个实例：回退期间立的账不能按回退前的代码来判。）
    // 第四版修复加入了 onRealViewportChanged，P3 通过该方法验证了它的行为，P5 的历史价值
    // 已转移给 P3 和文档，不再需要以反射测试的形式存在于代码里。
}
