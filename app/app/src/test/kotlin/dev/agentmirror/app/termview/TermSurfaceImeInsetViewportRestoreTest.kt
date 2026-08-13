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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 根因探针 · D-38 真根因（fix-viewport-restore-d38）——候选 3：Compose isImeVisible 事件源驱动。
 *
 * w-base-v2 实测推翻「View 能看 insets」前提：
 *   - bottomMarginPx=6（健康）但 geometryCorrectionCount=1（主路径没稳住）；
 *   - insetsCallbackCount=8（View 持续收到回调）但 imeBottom **恒 0**（另有两个瞬时 1882/126）；
 *   - uiautomator 独立确认 View 被挤到 936px。
 * 结论：**键盘从未覆盖 View，是 Compose imePadding 把 Box 挤小**。View 的 ime inset 报 0 是
 * "正确"的（确实无键盘盖它）。「两值相加」与「ime==0 记稳定高」两版都错在同一前提。
 *
 * 修法（leader 批准候选 3）：IME 状态从知道它的 Compose 层拿（WindowInsets.isImeVisible，
 * 与 imePadding 同源已被证实工作），经 setImeVisible(bool) 传给 View。稳定高写入条件：
 * **imeVisibleKnown && !imeVisible 才写**（布尔未到/IME 在屏都不写），避免把被挤压的高当稳定高。
 *
 * 数值（presenter 默认 cellWidth=10, cellHeight=20）：
 *   - 稳定高 1920（96 行）在 imeVisible=false 时观测记录；
 *   - IME 在屏（imeVisible=true）View 高被挤到 1731（86 行）；
 *   - 回前台宽度不变 → 复用稳定高 1920 → presenter 不 rebase 到 86。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TermSurfaceImeInsetViewportRestoreTest {

    /** 夹具：View + Presenter + resize 回调记录（同步内核）。 */
    private class Harness(
        val view: TermSurfaceView,
        val presenter: TermViewPresenter,
        val resizeCalls: MutableList<Pair<Int, Int>>,
    )

    private fun harness(): Harness {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resizeCalls = mutableListOf<Pair<Int, Int>>()
        val emulator = TerminalEmulator(cols = 80, rows = 24)
        val presenter = TermViewPresenter(emulator) { r, c ->
            resizeCalls.add(r to c)
            emulator.resize(c, r)
        }
        val view = TermSurfaceView(context).apply {
            this.presenter = presenter
            layout(0, 0, 1080, 1920) // 首帧全高 seed：96 行
        }
        return Harness(view, presenter, resizeCalls)
    }

    /**
     * 核心红测（候选 3）：IME 收起（setImeVisible(false)）时记录稳定高；回前台 IME 在屏
     * （setImeVisible(true) + 高度变矮）宽度不变 → 复用稳定高，不得把挤压值当真实视口 rebase。
     */
    @Test
    fun imeShrinkReusesObservedStableHeight() {
        val h = harness()
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // 场景 A：IME 弹起挤压（setImeVisible(true) + View 高缩到 1731）——挤压路径不 emit、不记稳定高。
        h.view.setImeVisible(true)
        h.view.layout(0, 0, 1080, 1731)
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // 场景 B：IME 收起复原（setImeVisible(false) + View 高回 1920）——onSizeChanged 触发，
        // recordStableHeightIfImeClosed 记录稳定高 1920（观测事实）。
        h.view.setImeVisible(false)
        h.view.layout(0, 0, 1080, 1920)
        assertEquals(listOf(96 to 108), h.resizeCalls) // 稳定高 1920 == 内核 96，不 emit

        // 场景 C：IME 再弹起（setImeVisible(true) + 挤到 1731）。
        h.view.setImeVisible(true)
        h.view.layout(0, 0, 1080, 1731)
        assertEquals(listOf(96 to 108), h.resizeCalls) // 稳定高 1920 不被 1731 覆盖

        // 回前台：IME 在屏，宽度不变 → 复用 stableHeightPx=1920，不把 1731 当真实视口。
        h.view.dispatchWindowVisibilityChanged(View.VISIBLE)

        assertEquals(
            "回前台必须复用观测的稳定高（不把挤压值当真实视口）",
            listOf(96 to 108),
            h.resizeCalls,
        )
        assertEquals(
            "内核 rows 不得被挤压值改写（应保持 96 而非 86）",
            96,
            h.presenter.window.last - h.presenter.window.first + 1,
        )
        assertEquals("正常路径下自愈计数器必须恒为 0", 0, h.presenter.geometryCorrectionCount)
    }

    /**
     * 时序守卫（leader 点名）：`setImeVisible` 布尔尚未到达（imeVisibleKnown=false，初始态未知）
     * 时发生高度变化 → **不得写入稳定高**（避免把挤压高当稳定高）。RED-STUB（忽略 imeVisible）
     * 下此守卫必红。
     */
    @Test
    fun heightChangeBeforeImeKnownDoesNotRecordStableHeight() {
        val h = harness()
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // 关键：不调用 setImeVisible（imeVisibleKnown=false，模拟 Compose 布尔晚于布局到达）。
        // View 高变化到 1731（可能是 IME 挤压，但布尔未到我们不知道）。
        h.view.layout(0, 0, 1080, 1731)

        // 回前台：宽度不变。若稳定高未被记录（0），IME 状态未知 → 不得把 1731 当真实几何 emit。
        h.view.dispatchWindowVisibilityChanged(View.VISIBLE)

        assertEquals(
            "布尔未到（IME 状态未知）时不得把挤压值当真实视口 emit",
            listOf(96 to 108),
            h.resizeCalls,
        )
    }

    /**
     * 守卫（leader 边界 + 分屏守卫合并）：宽度变化 = 真实几何变化（旋转/分屏/多窗口），
     * **必须重新确立稳定高**，不得复用过期值。IME 在屏期间宽度变也要重算。
     */
    @Test
    fun widthChangeReEstablishesStableHeight() {
        val h = harness()
        h.view.layout(0, 0, 1080, 1920)
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // 建立稳定基准 1920：IME 弹起挤压（setImeVisible(true) + 挤到 1731）→ 收起复原（setImeVisible(false) + 回 1920）
        // → onSizeChanged 触发 recordStableHeightIfImeClosed 记录 stableWidth=1080/stableHeight=1920。
        h.view.setImeVisible(true)
        h.view.layout(0, 0, 1080, 1731)
        h.view.setImeVisible(false)
        h.view.layout(0, 0, 1080, 1920)
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // 分屏：宽度 1080→540，高度 960（48 行）。IME 在屏（imeVisible=true）期间发生。
        h.view.setImeVisible(true)
        h.view.layout(0, 0, 540, 960)
        assertEquals(listOf(96 to 108), h.resizeCalls) // 挤压路径不 emit

        // 回前台：stableHeightPx>0 且宽度 540 ≠ stableWidthPx 1080 → 必须重新确立，用当前高 960。
        h.view.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertEquals(
            "宽度变化必须重新确立稳定高（不能用过期 1920，应重算 48x54）",
            listOf(96 to 108, 48 to 54),
            h.resizeCalls,
        )
        assertEquals("宽度变化是真实几何，非自愈场景", 0, h.presenter.geometryCorrectionCount)
    }

    /**
     * 守卫（leader 收工条件）：捏合放大字号时自愈**不得误触发**。
     */
    @Test
    fun pinchZoomDoesNotTriggerCorrection() {
        val h = harness()
        h.view.layout(0, 0, 1080, 1920)
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // fix-pinch-preview-commit：预览只更新字号不 emit，手势结束（onPinchCommit）才 emit 一次。
        h.presenter.onFontSizeChanged(newCellWidth = 12, newCellHeight = 24) // 预览，不 emit
        assertEquals(listOf(96 to 108), h.resizeCalls)
        h.presenter.onPinchCommit() // 手势结束，emit 一次
        assertEquals(
            "捏合放大必须正常 emit（005 契约）",
            listOf(96 to 108, 80 to 90),
            h.resizeCalls,
        )

        assertEquals(
            "捏合放大字号不得触发几何自愈（geometryCorrectionCount 恒 0）",
            0,
            h.presenter.geometryCorrectionCount,
        )
    }
}
