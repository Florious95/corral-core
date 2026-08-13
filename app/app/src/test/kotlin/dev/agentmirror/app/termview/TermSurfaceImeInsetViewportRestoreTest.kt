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
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 根因探针 · D-38 真根因（fix-viewport-restore-d38）：回前台时 IME 在屏，View 层把「被挤压的
 * 高度」当真实视口传给 onRealViewportChanged → 挤压值被提拔成永久基线，服务端永远停在错的小几何。
 *
 * w-base-v2 机器眼实测（bottomMarginPx，非目检）：
 *   - 基线（未开 IME）108x87，bottomMarginPx=6（健康）；
 *   - 切后台→回前台（IME 仍在屏）：108x82 ← 变了（挤压被当真实视口）；
 *   - 收起键盘：108x82 卡住，bottomMarginPx=106（≈5 行）。
 *
 * 裁定（leader 打回几何推断后定稿，raw/019）：
 *   1. presenter **不做任何 IME 推断**——View 层从 WindowInsets 拿 IME 高度，回前台传
 *      「当前 View 高 + imeInset」= 扣除 IME 后的稳定窗口高；
 *   2. **同源**：height 与 imeInset 取自同一次 insets 分发（onApplyWindowInsets），
 *      竞态窗口短但错误状态黏住（钉成永久基线不自愈），故必须同源 + 自愈兜底；
 *   3. **自愈**：IME 变为不可见（或 View 复原）时，若像素全高 rows > 内核 rows（内核被钉小），
 *      补发一次 resize 纠正 + [geometryCorrectionCount] 可观测。正常路径恒为 0。
 *
 * 本探针锚 View 层接线 + presenter 自愈。
 * 数值（presenter 默认 cellWidth=10, cellHeight=20）：
 *   - View 1080x1731（被 IME 挤过，对应 86 行），imeInset=189px → 稳定高 1920（96 行）；
 *   - 修复前：回前台传 1731 → presenter 重算 86 行 → emit（红：86≠96）。
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

    /** 构造带指定 ime bottom inset 的 WindowInsetsCompat 并 dispatch 给 View（触发 onApplyWindowInsets）。 */
    private fun applyImeInset(view: TermSurfaceView, imeBottomPx: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
            .build()
        view.dispatchApplyWindowInsets(insets.toWindowInsets())
    }

    /**
     * 核心红测：回前台 IME 在屏，View 层必须传「当前 View 高 + imeInset」（扣除 IME 的稳定高），
     * presenter 不得把挤压值当真实视口 rebase。
     *
     * 场景：View 高被 IME 挤成 1731（86 行），imeInset=189 → 稳定高 1920（96 行）。
     * 修复前：传 1731 → emit 86（红，86≠96）。
     * 修复后：传 1920 → 内核已 96，不重复 emit。
     */
    @Test
    fun returningToForegroundPassesHeightPlusImeInset() {
        val h = harness()
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // IME 挤压：View 实际高缩到 1731（86 行），imeInset=189（同源：一次 insets 分发）。
        h.view.layout(0, 0, 1080, 1731)
        applyImeInset(h.view, 189)
        // 挤压路径 + 同源重算：稳定高 1920，内核已 96，不 emit。
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // 回前台：IME 仍在屏。onApplyWindowInsets 或 onWindowVisibilityChanged 传 1731+189=1920。
        h.view.dispatchWindowVisibilityChanged(View.VISIBLE)
        applyImeInset(h.view, 189)

        assertEquals(
            "回前台必须传扣除 IME 后的稳定高（不把挤压值当真实视口）",
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
     * 守卫：IME 不可见（imeInset=0）时回前台，View 层传的就是当前 View 高——分屏/旋转等
     * 真实几何变化必须正常重算并 emit，绝不能被当成 IME 忽略（leader 点名要锚的洞）。
     */
    @Test
    fun foregroundWithoutImePassesRealGeometryAndResizes() {
        val h = harness()
        h.view.layout(0, 0, 1080, 1920)
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // 分屏：View 高真的变 960（48 行），IME 不可见（imeInset=0）。
        applyImeInset(h.view, 0)
        h.view.layout(0, 0, 1080, 960)
        assertEquals(listOf(96 to 108), h.resizeCalls) // onSizeChanged→onViewportSizeChanged 挤压路径不 emit

        // 回前台：imeInset=0，传 960 → presenter 重算 48 ≠ 96 → 必须 emit。
        h.view.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertEquals(
            "IME 不可见的分屏几何变化必须重算 emit",
            listOf(96 to 108, 48 to 108),
            h.resizeCalls,
        )
        assertEquals("分屏是真实几何，非自愈场景", 0, h.presenter.geometryCorrectionCount)
    }

    /**
     * 竞态红测（leader 第三点）：构造「imeInset 已更新为 0、height 仍是旧挤压值」的错开时刻。
     * 断言：要么求和正确（同源兜住），要么自愈纠正——两者居一即可，不许两者都没有。
     *
     * 错开场景：IME 收起，insets 已更新为 0（onApplyWindowInsets 已跑），但 View 高还是
     * 挤压值 1731（布局未重排）。此时 View 层若传 height（1731）→ presenter 重算 86 会 rebase
     * 错误；若传 height+imeInset（1731+0=1731）→ 同样错。真正的防御是**自愈**：当 View 随后
     * 复原到全高（onSizeChanged 1920）时，presenter 发现像素 rows 96 > 内核 86 → 补发纠正。
     */
    @Test
    fun raceBetweenImeAndLayoutIsHealedByCorrection() {
        val h = harness()
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // View 被 IME 挤压到 1731（86 行可见）：挤压路径，内核保持 96、不 emit。
        h.view.layout(0, 0, 1080, 1731)
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // 竞态：回前台那一刻 IME 在屏，onRealViewportChanged 收到挤压值 1731 当真实视口 → emit 86，
        // 内核被钉小（错误状态黏住）。
        h.presenter.onRealViewportChanged(1080, 1731)
        assertEquals(listOf(96 to 108, 86 to 108), h.resizeCalls)

        // 错开时刻：insets 已更新为 0（IME 收起），但 View 高还是挤压值 1731（布局未重排）。
        applyImeInset(h.view, 0)
        // 同源重算：height(1731) + imeInset(0) = 1731 → 可能仍错；此路径允许不纠正。

        // 自愈：布局重排，View 复原到全高 1920 → onSizeChanged → onViewportSizeChanged(1920)。
        // presenter 发现像素 rows 96 > 内核 86 → 补发一次 resize 纠正 + 计数器 +1。
        h.view.layout(0, 0, 1080, 1920)

        assertTrue(
            "竞态后果必须被自愈纠正（geometryCorrectionCount 应 >0）",
            h.presenter.geometryCorrectionCount > 0,
        )
        assertEquals(
            "自愈补发一次 resize 纠正内核到全高 96",
            listOf(96 to 108, 86 to 108, 96 to 108),
            h.resizeCalls,
        )
    }

    /**
     * 守卫（leader 收工条件）：捏合放大字号时自愈**不得误触发**。
     *
     * 自愈条件是「像素全高 rows > 内核 rows」。捏合放大字号 → 字格变大 → rows 变小，
     * 若内核更新有先后，可能被误判成「内核被钉小了」而多发一次 resize。断言 geometryCorrectionCount
     * 恒为 0——这证明自愈只在真正的 IME rebase 后果下触发，不误伤捏合族。
     */
    @Test
    fun pinchZoomDoesNotTriggerCorrection() {
        val h = harness()
        h.view.layout(0, 0, 1080, 1920) // seed 96 行
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // 捏合放大字号：cell 10x20 → 12x24。视口 1080x1920 → rows=80（1920/24）、cols=90（1080/12）。
        h.presenter.onFontSizeChanged(newCellWidth = 12, newCellHeight = 24)
        assertEquals(
            "捏合放大必须正常 emit（005 契约）",
            listOf(96 to 108, 80 to 90),
            h.resizeCalls,
        )

        // 捏合后自愈不得误触发：内核已同步到 80，像素 rows=80 == 内核，非「被钉小」。
        assertEquals(
            "捏合放大字号不得触发几何自愈（geometryCorrectionCount 恒 0）",
            0,
            h.presenter.geometryCorrectionCount,
        )
    }
}
