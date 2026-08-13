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
 * 根因探针 · fix-ime-no-resize（presenter 层白盒，锚定 onResizeRequest 回调）。
 *
 * 裁定（raw/019 + taskbook fix-terminal-resize-cluster）：
 *   「仅首次进入 CLI 时 resize 一次，此后键盘/输入框不触发 resize」。
 *
 * 根因：TermViewPresenter.onViewportSizeChanged 无法区分「用户捏合」与「IME/输入框挤压」——
 * 两者都走 recomputeGeometry → onResizeRequest。输入框变高使终端 View 高度收缩，
 * rows = viewportHeightPx / cellHeight 变小 → rows != emulator.rows → 上抛 resize 请求，
 * 这是协议 resize 帧的源头（SessionViewModel 收到回调后 manager.resize → ResizeFrame）。
 *
 * 本探针断言对象是 presenter 的 resize 请求回调序列（协议帧的上游白盒锚点），
 * 与 w-test-ime 的 SessionImeResizeProtocolRegressionTest（黑盒锚定已发出的 ResizeFrame 集合）
 * 互补：本探针直接定位「收缩不该 emit」这一根因；场景红测证明它穿到协议帧。
 *
 * 期望修复行为：
 *   - 首帧真实视口建立行列数：emit 一次（首次进入 CLI 的唯一一次，保留）；
 *   - IME 弹起 / 输入框 1→2→3 行使 View 高度收缩：不再 emit（本任务核心）；
 *   - 键盘收起 View 高度复原：不再 emit（同裁定「此后键盘/输入框不触发 resize」）；
 *   - 捏合改字号（onFontSizeChanged）：仍 emit（005 契约，D-29 本轮不做，不得误伤）。
 *
 * 数值对齐 w-test-ime 场景红测（cellWidth=10, cellHeight=20 presenter 默认字格）：
 *   - 首帧视口 1080x1920 → 96 行 108 列；
 *   - IME/输入框挤压以 63px 收缩模拟（对应 A/B/C 实测 1896→1833→1770 的逐级上移）。
 */
class TermViewImeResizePresenterProbeTest {

    /**
     * 夹具：内核 + Presenter + resize 回调记录。
     *
     * resize 回调同步内核（emulator.resize），模拟 SessionViewModel 中
     * `manager.resize(ref, rows, cols)` 成功后的 `emulator.resize(cols, rows)`——
     * 使内核行列数与已 emit 的请求保持一致，贴近真实链路语义（否则后续断言会因
     * 内核停留在构造初始尺寸而失真）。
     */
    private class Harness(
        val emulator: TerminalEmulator,
        val presenter: TermViewPresenter,
        val resizeCalls: MutableList<Pair<Int, Int>>,
    )

    private fun harness(rows: Int = 24, cols: Int = 80): Harness {
        val emulator = TerminalEmulator(cols, rows)
        val resizeCalls = mutableListOf<Pair<Int, Int>>()
        val presenter = TermViewPresenter(emulator) { r, c ->
            resizeCalls.add(r to c)
            emulator.resize(c, r)
        }
        return Harness(emulator, presenter, resizeCalls)
    }

    // ---- 守卫：首次进入 CLI 的唯一一次 resize 必须保留 ----

    @Test
    fun firstViewportEmitsInitialResize() {
        val h = harness(rows = 24, cols = 80)
        // 首帧真实视口（初始 0x0 提前返回，首帧才建立行列数）：96 行 108 列。
        h.presenter.onViewportSizeChanged(1080, 1920)
        assertEquals(listOf(96 to 108), h.resizeCalls)
    }

    // ---- 核心红测：输入框变高 / IME 挤压（高度收缩）不得 emit resize ----

    @Test
    fun imeShrinkAfterFirstViewportDoesNotEmitResize() {
        val h = harness(rows = 24, cols = 80)
        // 首帧真实视口建立行列数：唯一允许的一次 resize。
        h.presenter.onViewportSizeChanged(1080, 1920)
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // IME 弹起 + 输入框一行→两行→三行：终端 View 高度逐级收缩（63px 步进）。
        h.presenter.onViewportSizeChanged(1080, 1857)
        h.presenter.onViewportSizeChanged(1080, 1794)
        h.presenter.onViewportSizeChanged(1080, 1731)

        assertEquals(
            "输入框变高（View 收缩）不应再 emit resize——首帧后 rows/cols 保持稳定",
            listOf(96 to 108),
            h.resizeCalls,
        )
    }

    // ---- 键盘收起（高度复原）同样不得 emit resize（同裁定对称半边）----

    @Test
    fun imeExpandAfterFirstViewportDoesNotEmitResize() {
        val h = harness(rows = 24, cols = 80)
        // 首帧建立行列数。
        h.presenter.onViewportSizeChanged(1080, 1920)
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // IME 弹起收缩（内核被移到收缩后尺寸）：当前行为已在此错误 emit。
        h.presenter.onViewportSizeChanged(1080, 1857)
        // 键盘收起：View 复原到首帧尺寸，rows=96 ≠ 当前内核 92 → 当前代码仍会 emit。
        h.presenter.onViewportSizeChanged(1080, 1920)

        assertEquals(
            "键盘收起（View 复原）不应再 emit resize——首帧后 rows/cols 保持稳定",
            listOf(96 to 108),
            h.resizeCalls,
        )
    }

    // ---- 守卫：捏合改字号在提交点 emit resize（005 契约 + raw/041 预览/提交拆分）----
    // 新语义（fix-pinch-preview-commit）：onFontSizeChanged = 预览（不 emit），onPinchCommit =
    // 手势结束用最终字号 emit 一次。005 契约「捏合改字号会 resize」仍在（提交点），
    // 只是从「每个手势步」变成「松手一次」（raw/041 裁定）。

    @Test
    fun pinchFontChangeStillRequestsResize() {
        val h = harness(rows = 15, cols = 50)
        // 首帧视口 500x300：300/20=15 行、500/10=50 列，与内核一致 → 无 resize（no-op skip）。
        h.presenter.onViewportSizeChanged(500, 300)
        assertEquals(emptyList<Pair<Int, Int>>(), h.resizeCalls)

        // 预览（手势中）：改字号 12x24，但不 emit（不重排）。
        h.presenter.onFontSizeChanged(newCellWidth = 12, newCellHeight = 24)
        assertEquals("预览阶段不得 emit resize（raw/041：预览不重排）", emptyList<Pair<Int, Int>>(), h.resizeCalls)

        // 提交（手势结束）：300/24=12 行、500/12=41 列 → emit 一次（005 契约在提交点）。
        h.presenter.onPinchCommit()
        assertEquals(listOf(12 to 41), h.resizeCalls)
    }
}
