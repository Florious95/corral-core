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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
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
 * 根因：两条规则各自都对、撞在一起才出病——
 *   - onRealViewportChanged（回前台）重算几何并 emit 一次，但那一刻 IME 在屏，把「被挤压的 82 行」
 *     当真实视口；
 *   - onViewportSizeChanged（收起键盘）按 fix-ime-no-resize 规则不 emit → 没有人把 82 纠正回 87。
 *
 * 裁定（leader 打回几何推断后定稿，raw/019）：presenter **不做任何关于 IME 的推断**——
 * 「回前台那一刻 IME 在不在屏」是 View 层通过 WindowInsets 直接可知的事实。View 层从 insets 拿
 * IME 高度，回前台时传「当前 View 高 + imeInset」= 扣除 IME 后的稳定窗口高给 presenter。
 * 分屏/旋转（IME 不可见，imeInset=0）传真实变化，正常重算——绝不把「宽度不变+高度变小」当 IME
 * 忽略（分屏是这条判据最易误伤的场景，leader 要求加守卫锚死）。
 *
 * 本探针锚 View 层接线：回前台 onWindowVisibilityChanged 必须把 imeInset 加上再传给 presenter。
 * 数值（presenter 默认 cellWidth=10, cellHeight=20）：
 *   - View 1080x1731（被 IME 挤过，对应 86 行），imeInset=189px（≈9.45 行，凑 1920 全高）；
 *   - 修复前：回前台传 1731 → presenter 重算 86 行 → emit（红：86≠96）；
 *   - 修复后：回前台传 1731+189=1920 → presenter 重算 96 行 → 不 rebase（绿）。
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

    /** 反射设 imeInsetPx（Robolectric 不便构造真实 WindowInsets 分发）。 */
    private fun setImeInset(view: TermSurfaceView, px: Int) {
        val f = TermSurfaceView::class.java.getDeclaredField("imeInsetPx")
        f.isAccessible = true
        f.setInt(view, px)
    }

    /**
     * 核心红测：回前台时 IME 在屏，View 层必须传「当前 View 高 + imeInset」（扣除 IME 的稳定高），
     * presenter 不得把挤压值当真实视口 rebase。
     *
     * 场景：View 高被 IME 挤成 1731（86 行），imeInset=189 → 稳定高 1920（96 行）。
     * 修复前：回前台传 1731 → emit 86（红，86≠96）。
     * 修复后：回前台传 1920 → 内核已 96，不重复 emit。
     */
    @Test
    fun returningToForegroundPassesHeightPlusImeInset() {
        val h = harness()
        // 首帧 seed 96 行（1920）。
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // IME 挤压：View 实际高缩到 1731（86 行），imeInset=189。
        h.view.layout(0, 0, 1080, 1731)
        // onViewportSizeChanged 挤压路径：不 emit。
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // 回前台：IME 仍在屏。View 层必须传 1731+189=1920（扣除 IME 的稳定高），
        // presenter 重算 96 == 内核，不 emit、不 rebase 到 86。
        setImeInset(h.view, 189)
        h.view.dispatchWindowVisibilityChanged(View.VISIBLE)

        assertEquals(
            "回前台必须传扣除 IME 后的稳定高（不把挤压值当真实视口）",
            listOf(96 to 108),
            h.resizeCalls,
        )
        assertEquals("内核 rows 不得被挤压值改写（应保持 96 而非 86）", 96, h.presenter.window.last - h.presenter.window.first + 1)
    }

    /**
     * 守卫：IME 不可见（imeInset=0）时回前台，View 层传的就是当前 View 高——分屏/旋转等
     * 真实几何变化必须正常重算并 emit，绝不能被当成 IME 忽略（leader 点名要锚的洞）。
     */
    @Test
    fun foregroundWithoutImePassesRealGeometryAndResizes() {
        val h = harness()
        h.view.layout(0, 0, 1080, 1920) // seed 96 行
        assertEquals(listOf(96 to 108), h.resizeCalls)

        // 分屏：View 高真的变 960（48 行），IME 不可见（imeInset=0）。
        setImeInset(h.view, 0)
        h.view.layout(0, 0, 1080, 960)
        assertEquals(listOf(96 to 108), h.resizeCalls) // 挤压路径（onSizeChanged→onViewportSizeChanged）不 emit

        // 回前台：imeInset=0，View 层传 960 → presenter 重算 48 ≠ 96 → 必须 emit。
        h.view.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertEquals(
            "IME 不可见的分屏几何变化必须重算 emit",
            listOf(96 to 108, 48 to 108),
            h.resizeCalls,
        )
    }
}
