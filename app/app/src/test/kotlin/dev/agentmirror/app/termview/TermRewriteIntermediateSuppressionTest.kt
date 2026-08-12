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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整屏 recap 中间态抑制红测（fix-input-send-fullrepaint 收工门第二半）。
 *
 * 用户报的「看不到底部最新消息」：真实 CLI 收到消息后整屏 recap（ED2/ED3 清屏 + 自上而下
 * 逐行重写），这段字节被 pipe/relay 按网络分片流式回传。高 RTT（TS）下每分片一帧 → 用户
 * 看到「写了一半」的画面：清屏后、重写未完成前，屏幕底部是空白的——最新消息正落在最后
 * 才写到的底部，于是「看不到底部最新消息」。
 *
 * 目标（leader 明确「不是防抖降低概率」）：**屏幕正在被整体重写时不展示中间态**。
 * 终态是确定的，只是不展示写了一半的样子；等这阵 recap 落定后画一次稳定的完整画面。
 * （对齐 Web 端 xterm.js 的内部 buffer + 约 120ms 合并，见 docs/web-vs-android-terminal-model.md。）
 *
 * 判据（具体形态由本测试定）：presenter 暴露「本帧要呈现的重绘范围」——整屏重写期间
 * 该范围必须为「空」（无可呈现的中间帧）；recap 落定后一次性呈现完整画面；普通增量
 * （非整屏重写）必须立即呈现、不得被吞。
 *
 * ⚠️ 这与「加防抖让 bug 更难撞见」的本质区别（实现/注释必须写清楚）：
 *  - ❌ 被否掉的：对**所有** delta 加延迟，让问题更难触发、测 N 次不复现；
 *  - ✅ 要做的：只对**整屏重写**这一确定路径抑制中间帧——重写期间画面停在上一帧稳定态，
 *    重写落定后一次性呈现终态。终态是确定的，只是不展示中间帧。
 */
class TermRewriteIntermediateSuppressionTest {

    /** 全屏 12 行。 */
    private val ROWS = 12

    /** 夹具：内核 + presenter，捕获帧请求次数。 */
    private class Harness(val emulator: TerminalEmulator, val presenter: TermViewPresenter) {
        var frameRequests = 0

        init {
            presenter.onFrameRequested = { frameRequests++ }
        }

        companion object {
            fun create(): Harness {
                val e = TerminalEmulator(20, 12)
                return Harness(e, TermViewPresenter(e) { _, _ -> })
            }
        }
    }

    /** 整屏清屏（ED2）——recap 起点：内核全窗口标脏。 */
    private fun clear(h: Harness) {
        h.emulator.feed("[2J")
    }

    /** 在屏幕第 [row] 行写一行（recap 的逐行重写；row 0 基）。 */
    private fun writeRow(h: Harness, row: Int, text: String) {
        h.emulator.feed("[${row + 1};1H$text")
    }

    /**
     * 当前帧要呈现的重绘范围。**红测驱动的接缝**：生产上由 TermSurfaceView 帧回调调用。
     *
     * 语义（本测试要锁定的契约）：
     *  - null：几何整帧（滚动/字号/视口变化，整窗重绘）；
     *  - 空列表：无可呈现的中间帧（整屏重写进行中——被抑制）；
     *  - 非空：本帧只重绘这些脏行。
     */
    private fun frameRepaint(h: Harness): List<IntRange>? = h.presenter.takeFrameRepaint()

    @Test
    fun fullScreenRewriteSuppressesIntermediateFrames_untilSettled() {
        val h = Harness.create()
        // 初始填满 10 行。
        h.emulator.feed((1..10).joinToString("\r\n") { "row-$it" } + "\r\n")

        // ---- recap 分片到达（网络 chunking：清屏一帧，随后逐行重写分多帧）----
        // 帧 1：整屏清屏（ED2）。此帧进入抑制态（清屏本身不呈现）。
        clear(h)
        val clearFrame = frameRepaint(h)
        assertTrue("清屏帧不得呈现中间态（应为空），实得 $clearFrame", clearFrame != null && clearFrame.isEmpty())
        assertTrue("清屏后应进入抑制态", h.presenter.isRewriteInProgress)

        // 帧 2：重写前 6 行（0..5）——未覆盖全窗，继续抑制。
        for (row in 0..5) writeRow(h, row, "recap-$row")
        val midFrame = frameRepaint(h)
        assertTrue(
            "重写中途不得呈现中间帧（应为空），实得 $midFrame",
            midFrame != null && midFrame.isEmpty(),
        )
        assertTrue("重写中途应保持抑制态", h.presenter.isRewriteInProgress)

        // 帧 3：重写后 6 行（6..11）——覆盖全窗 → 落定，一次性呈现完整画面。
        for (row in 6 until ROWS) writeRow(h, row, "recap-$row")
        val settled = frameRepaint(h) ?: emptyList()
        assertTrue("recap 落定后必须一次性呈现完整画面（非空），实得 $settled", settled.isNotEmpty())
        val window = h.presenter.window
        assertTrue(
            "落定呈现必须覆盖全窗口（0..${window.last}），实得 $settled",
            settled.any { it.first <= window.first && it.last >= window.last },
        )
        assertTrue("落定后必须退出抑制态", !h.presenter.isRewriteInProgress)
    }

    @Test
    fun normalIncrementalDeltaPresentsImmediately() {
        val h = Harness.create()
        h.emulator.feed((1..10).joinToString("\r\n") { "row-$it" } + "\r\n")
        // 排掉初始整屏脏区。
        h.presenter.takeDamage()

        // 普通增量（非整屏重写）：底部追加两行。
        h.emulator.feed("bottom-A\r\nbottom-B")

        val repaint = frameRepaint(h) ?: emptyList()
        // 必须立即呈现（非空），且只覆盖底部脏行，不得被吞。
        assertTrue("普通增量必须立即呈现（非空），实得 $repaint", repaint.isNotEmpty())
        val window = h.presenter.window
        assertTrue(
            "普通增量只重绘底部脏行（不应覆盖全窗口），实得 $repaint",
            repaint.all { it.first >= window.last - 3 },
        )
        // 不得被抑制为「空」（中间态抑制只作用于整屏重写，不得误伤普通增量）。
        assertFalse("普通增量不得被当作整屏重写吞掉", repaint.isEmpty() && h.presenter.isRewriteInProgress)
    }

    @Test
    fun geometricEventStillRequestsFullFrame() {
        val h = Harness.create()
        h.emulator.feed("abc")
        h.presenter.takeDamage()

        // 几何事件（滚动/字号/视口变化）：整窗重绘，takeFrameRepaint 必须返回 null（全窗口）。
        h.presenter.onScrollBy(0)
        // onScrollBy 不产生 damage，但几何变化本身要整窗重绘——takeFrameRepaint 语义为 null。
        val repaint = frameRepaint(h)
        assertTrue("几何事件必须整窗重绘（null），实得 $repaint", repaint == null)
    }
}
