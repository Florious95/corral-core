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
import androidx.test.core.app.ApplicationProvider
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回归门 · fix-viewport-restore-d38（TermSurfaceView 层回前台钩子）。
 *
 * 锚定 View 层接线：回前台（窗口可见性变化）必须经 [TermSurfaceView.onWindowVisibilityChanged]
 * → presenter.onRealViewportChanged 把几何重新对齐到当前 View 尺寸，并请求重画。
 *
 * 根因（leader 更正后的事实）：回前台时**没有任何人负责**把终端几何重新对齐到当前 View——
 * [onSizeChanged] 在 View 高复原（与离开前相同）时未必回调，presenter 内核 rows 停在离开前
 * 旧小几何上（v5 曾用本钩子补位，文件被列为禁区未捞回，缺口一直空着）。本测试锚定该缺口已补。
 *
 * 序列完全由 View 生命周期驱动（layout→onSizeChanged、dispatchWindowVisibilityChanged）：
 *   1. attach 时首帧被 IME 挤压（layout 1080x1857）→ seed 92 行，emit 一次；
 *   2. IME 收起 View 增长（layout 1080x1920）→ 挤压路径，不 emit，内核仍 92 行（卡旧几何）；
 *   3. 切后台（GONE）→ 撤销待执行帧、不发 resize；
 *   4. 回前台（VISIBLE）→ onRealViewportChanged 重放 1080x1920 → 重算 96 行 ≠ 92 → emit 一次恢复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TermSurfaceViewportRestoreTest {

    /** 夹具：View + Presenter + resize 回调记录（同步内核，贴近 SessionViewModel 语义）。 */
    private class Harness(
        val view: TermSurfaceView,
        val presenter: TermViewPresenter,
        val resizeCalls: MutableList<Pair<Int, Int>>,
    )

    /** 建 View 并绑定 presenter（不 layout——由各测试用 layout 驱动首帧 seed 尺寸）。 */
    private fun harness(): Harness {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resizeCalls = mutableListOf<Pair<Int, Int>>()
        val emulator = TerminalEmulator(cols = 80, rows = 24)
        val presenter = TermViewPresenter(emulator) { r, c ->
            resizeCalls.add(r to c)
            // 同步内核：模拟 SessionViewModel `manager.resize ok → emulator.resize`（否则内核
            // 停在构造尺寸，onRealViewportChanged 每次重算都 ≠ 内核 → 误 emit，测不出「按需」）。
            emulator.resize(c, r)
        }
        val view = TermSurfaceView(context).apply {
            this.presenter = presenter
        }
        return Harness(view, presenter, resizeCalls)
    }

    /**
     * 核心：回前台（onWindowVisibilityChanged VISIBLE）必须把几何重新对齐到当前 View 尺寸。
     *
     * 断言一：resize 帧 = [92 to 108, 96 to 108]——回前台 emit 一次恢复（「一律不 emit」哨兵）；
     * 断言二：window 长度恢复到与 View 高一致（96 行，不卡在旧小几何）；
     * 断言三：window 贴底覆盖内核全部屏幕行（D-20 末行可见）。
     */
    @Test
    fun returningToForegroundRealignsGeometryToCurrentView() {
        val h = harness()

        // 首帧被 IME 挤压（1857）：seed 92 行 108 列，emit 一次。
        h.view.layout(0, 0, 1080, 1857)
        assertEquals(listOf(92 to 108), h.resizeCalls)

        // IME 收起：setImeVisible(false) 告知事件源 + View 增长到 1920。
        // recordStableHeightIfImeClosed 记录稳定高 1920 并重放 → presenter 重算 96 ≠ 内核 92
        // → emit 一次恢复（首帧挤压 seed 在 IME 收起时即被纠正，不必等回前台）。
        h.view.setImeVisible(false)
        h.view.layout(0, 0, 1080, 1920)
        assertEquals(listOf(92 to 108, 96 to 108), h.resizeCalls)

        // 切后台：GONE 分支撤销待执行帧，不发 resize（resizeCalls 保持纠正后不变）。
        h.view.dispatchWindowVisibilityChanged(View.GONE)
        assertEquals(
            "切后台不得 emit resize（保持纠正后不变）",
            listOf(92 to 108, 96 to 108),
            h.resizeCalls,
        )

        // 回前台：VISIBLE → 复用稳定高 1920，几何已一致（96）→ 不重复 emit。
        h.view.dispatchWindowVisibilityChanged(View.VISIBLE)

        assertEquals(
            "回前台复用稳定高：几何已纠正（96），不重复 emit",
            listOf(92 to 108, 96 to 108),
            h.resizeCalls,
        )
        val win = h.presenter.window
        assertEquals(
            "window 长度应恢复到当前 View 行数（不卡在旧小几何）",
            96,
            win.last - win.first + 1,
        )
        assertEquals("window 应贴底覆盖内核全部屏幕行（D-20 末行可见）", 95, win.last)
    }

    /**
     * 守卫：几何与当前 View 一致时回前台不得重复 emit（防「一律 emit」走回头路）。
     */
    @Test
    fun foregroundNoDuplicateResizeWhenGeometryAlreadyAligned() {
        val h = harness()

        // 首帧 seed 1920：96 行（无挤压）。
        h.view.layout(0, 0, 1080, 1920)
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // 切后台再回前台，View 尺寸未变：几何与内核一致 → 不得再 emit。
        h.view.dispatchWindowVisibilityChanged(View.GONE)
        h.view.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertEquals(
            "回前台几何未变：不得重复 emit resize",
            listOf(96 to 108),
            h.resizeCalls,
        )
    }

    /**
     * 守卫：切后台不得请求重画（后台不渲染，静默经济红线——空闲零帧）。
     */
    @Test
    fun backgroundingDoesNotRequestFrame() {
        val h = harness()
        var frameRequests = 0
        h.presenter.onFrameRequested = { frameRequests++ }

        h.view.layout(0, 0, 1080, 1857) // seed 92
        frameRequests = 0
        h.view.dispatchWindowVisibilityChanged(View.GONE)

        assertEquals("切后台不得请求帧（后台不渲染）", 0, frameRequests)
    }
}
