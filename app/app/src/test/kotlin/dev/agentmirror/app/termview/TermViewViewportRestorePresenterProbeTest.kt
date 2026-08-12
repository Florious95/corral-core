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
 * 根因探针 · fix-viewport-restore-d38（后台返回视口不恢复，presenter 层白盒）。
 *
 * 裁定（raw/019 + taskbook fix-terminal-resize-cluster）：
 *   - IME/输入框引起的**临时挤压** → 视口上推，不重算 rows/cols、不发 resize；
 *   - **真实视口变化**（回前台、旋转、分屏、窗口尺寸变更）→ 必须重算几何、按需发一次 resize。
 *
 * 根因（leader 更正后的事实）：
 *   **回前台时没有任何人负责把终端几何重新对齐到当前 View 尺寸**——View 高复原但
 *   [onSizeChanged] 未必回调（尺寸与离开前相同），presenter 内核 rows 停在离开前
 *   （或首帧被 IME 挤压）的旧小几何上（v5 曾用 TermSurfaceView.onWindowVisibilityChanged
 *   补位，该文件被列为禁区未捞回，缺口一直空着）。emit 抑制是叠加因素：让本来能顺带纠正
 *   的路径也不纠正了，但不是根因。
 *
 * 判据（本任务核心）：
 *   「一律 emit」与「一律不 emit」都错。两个入口语义正交，由 View 层按事件源分派：
 *   - [onViewportSizeChanged] = 布局挤压（IME/输入框，onSizeChanged）→ 只更新 visibleRows、
 *     不重算、不 emit resize；
 *   - [onRealViewportChanged] = 真实视口变化（回前台/旋转/分屏/窗口变更，View 层仅在
 *     IME 不在屏时调它）→ 重算几何、内核尺寸不一致则 emit 一次（重放当前像素几何，
 *     纠正首帧被挤压的小几何 / 后台期间卡住的旧几何）。回前台那一刻 IME 是否在屏只有
 *     View 层知道（insets），故判据落在 View 层分派，presenter 只负责
 *     「真实变化 → 重算 + 按需 emit 一次」的契约。
 *
 * 本探针模拟用户真机序列：进会话首帧已被 IME 挤压 → IME 收起（增长，挤压路径，被吞）→
 * 切后台 → 回前台（真实视口变化）。断言：回前台后几何恢复到与当前 View 尺寸一致、
 * resize 帧数符合预期（seed 一次 + 回前台至多一次）。
 *
 * 数值对齐 w-test 场景（cellWidth=10, cellHeight=20 presenter 默认字格）：
 *   - 首帧 1080x1857（被挤压）→ seed 92 行 108 列，emit 一次；
 *   - IME 收起 1080x1920（onSizeChanged 增长）→ 挤压路径，不 emit（内核仍 92 行）——
 *     此即「卡在旧小几何」的条件；
 *   - 回前台 1080x1920（onRealViewportChanged）→ 重算 96 行 ≠ 内核 92 → emit 一次恢复。
 */
class TermViewViewportRestorePresenterProbeTest {

    /** 夹具：内核 + Presenter + resize 回调记录（同步内核，贴近 SessionViewModel 语义）。 */
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

    /**
     * 核心红测：首帧被 IME 挤压（小几何 seed）→ 增长被吞（挤压路径）→ 回前台（真实视口变化）。
     *
     * 断言一：resize 帧 = [92 to 108, 96 to 108]——seed 一次 + 回前台纠正一次，无多余帧；
     *         回前台那一刻像素(96) ≠ 内核(92)，**必须 emit 一次**（「一律不 emit」的回归哨兵）；
     * 断言二：window 长度复原为内核行数（96，无残留挤压，不卡在旧小几何）；
     * 断言三：window 贴底覆盖内核全部屏幕行（D-20 末行可见）。
     */
    @Test
    fun backgroundRestoreRecoversGeometryToCurrentView() {
        val h = harness(rows = 24, cols = 80)
        var frameRequests = 0
        h.presenter.onFrameRequested = { frameRequests++ }

        // 进会话首帧已被 IME 挤压（1857）：seed 92 行 108 列，emit 一次（唯一合法 resize）。
        h.presenter.onViewportSizeChanged(1080, 1857)
        assertEquals(listOf(92 to 108), h.resizeCalls)

        // IME 收起，View 增长到 1920：挤压路径，只推可见行、不 emit resize。
        // 内核仍停在 92 行——这正是「回前台卡在旧小几何」的条件。
        h.presenter.onViewportSizeChanged(1080, 1920)
        assertEquals(listOf(92 to 108), h.resizeCalls)

        // 切后台、回前台：onRealViewportChanged 重放当前几何。
        // 像素 96 行 ≠ 内核 92 行 → 必须重算并 emit 一次恢复。
        h.presenter.onRealViewportChanged(1080, 1920)

        assertEquals(
            "回前台真实视口变化：seed 一次 + 纠正一次，无多余帧",
            listOf(92 to 108, 96 to 108),
            h.resizeCalls,
        )
        // 用公开的 window 断言几何复原：长度 = 内核行数（无残留挤压）+ 贴底（末行可见）。
        val win = h.presenter.window
        assertEquals(
            "window 长度应复原为当前 View 行数（不卡在旧小几何）",
            96,
            win.last - win.first + 1,
        )
        assertEquals(
            "window 应贴底覆盖内核全部屏幕行（D-20 末行可见）",
            h.emulator.rows - 1,
            win.last,
        )
        // 回前台必须触发重画。
        assert(frameRequests > 0) { "回前台未触发 onFrameRequested" }
    }

    /**
     * 守卫：真实视口变化**从不绕过**「内核尺寸不一致才发 resize」——几何没变（如回前台
     * 尺寸与内核一致）时不得重复发 resize 帧。防「一律 emit」走回头路的回归哨兵。
     */
    @Test
    fun realViewportNoOpWhenGeometryUnchanged() {
        val h = harness(rows = 24, cols = 80)
        h.presenter.onViewportSizeChanged(1080, 1857) // 首帧 seed：92 行
        assertEquals(listOf(92 to 108), h.resizeCalls)

        // 回前台但几何与内核一致（1857）：不得再发 resize。
        h.presenter.onRealViewportChanged(1080, 1857)
        assertEquals(listOf(92 to 108), h.resizeCalls)
    }

    /**
     * 守卫：**真实**视口几何变化（旋转/分屏/窗口变更导致 View 真小）时，onRealViewportChanged
     * 必须重算并按需 emit 一次——「一律不 emit」走回头路的回归哨兵。
     *
     * 场景：内核 24x80，首帧 500x300 → 15 行 50 列；真实几何变 500x240（12 行）→ 必须 emit
     * 12x50（真实变小，不是 IME 挤压）；复原 500x300 → 必须 emit 15x50 恢复。
     */
    @Test
    fun realViewportChangeEmitsResizeWhenGeometryDiffers() {
        val h = harness(rows = 24, cols = 80)
        h.presenter.onViewportSizeChanged(500, 300) // 首帧 seed：15x50（300/20=15, 500/10=50）
        assertEquals(listOf(15 to 50), h.resizeCalls)

        // 真实视口变小（旋转/分屏/窗口变更，非 IME）：必须 emit 12x50。
        h.presenter.onRealViewportChanged(500, 240)
        assertEquals(listOf(15 to 50, 12 to 50), h.resizeCalls)

        // 回前台复原：必须 emit 15x50 恢复几何。
        h.presenter.onRealViewportChanged(500, 300)
        assertEquals(listOf(15 to 50, 12 to 50, 15 to 50), h.resizeCalls)
    }

    /**
     * 守卫：回前台后 window 几何必须与当前 View 尺寸完全一致（顶/底/长度三者）。
     * 用户所见「顶部 1/4 + 大片空黑」正是窗口停留在旧小几何——此处直接锚定窗口本体。
     */
    @Test
    fun backgroundRestoreWindowMatchesCurrentViewGeometry() {
        val h = harness(rows = 24, cols = 80)
        h.presenter.onViewportSizeChanged(1080, 1857) // 首帧 seed：92 行
        h.presenter.onViewportSizeChanged(1080, 1920) // 增长被吞（挤压路径，内核仍 92 行）
        h.presenter.onRealViewportChanged(1080, 1920) // 回前台：重算恢复 96 行

        assertEquals("内核应恢复到当前 View 行数", 96, h.emulator.rows)
        val win = h.presenter.window
        assertEquals("可见窗口长度应 = 当前 View 行数", 96, win.last - win.first + 1)
        assertEquals("窗口应贴底（跟随态，末行可见）", h.emulator.rows - 1, win.last)
    }
}
