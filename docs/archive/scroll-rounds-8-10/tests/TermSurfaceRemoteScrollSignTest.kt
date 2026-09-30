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

package dev.agentmirror.app.session

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameCodec
import dev.agentmirror.app.conn.ScrollWheelFrame
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.termview.TermSurfaceView
import dev.agentmirror.app.termview.TermViewPresenter
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 端到端手势红测（回炉·第9/10轮）：真正驱动 [TermSurfaceView] 的手势路径
 * （真实 MotionEvent → GestureDetector → onScroll → onRemoteScrollBy），而不是像
 * ScrollWheelTest 那样直接调用 `vm.onScrollWheel(N)`（那条路径已被证明会跳过手势层
 * 本身的计算，是"测了个替身"——leader 2026-08-14 回炉·第9轮指出的系统性缺口）。
 *
 * 第9轮背景：QA 用透明 TCP 代理实测出上滑（用户想看历史）被送成了正协议 delta（应为负），
 * 根因是 SessionViewModel.onScrollWheel 曾经多反了一次号——TermSurfaceView.onScroll
 * 算出来的 deltaLines 本身已经是协议原生符号。T-上滑/T-下滑 覆盖这条。
 *
 * 第10轮背景：符号修好后 QA 复测出阅读式拖动（不带甩动，700px/1.5s）九成以上位移被丢
 * ——GestureDetector 每次 onScroll 的 dy 常年不足一整行，此前直接 roundToInt 判 0 就地
 * 丢弃、不留余数。修法是像素余数累加（见 TermSurfaceView.pendingScrollPx）。
 * 验收线换形状：不用"有没有发出帧"这种存在式断言（touch-slop 补偿性大位移会让它必然
 * 通过，掩盖了"发了但丢了九成"这种情况），改用**总量守恒**——送出的所有 deltaLines 之和
 * 必须约等于 round(总像素位移 / 行高)，误差不超过 1 行。T-守恒/T-慢滑/T-正常拖动 覆盖
 * 这条，直接测 TermSurfaceView 层（onRemoteScrollBy 收到的值求和），不经
 * SessionViewModel 的节流累加（那层的"不丢值只延迟发送"已由 ScrollWheelTest.kt 的
 * T-sw10 单独覆盖，混在一起会把两层各自的问题绞在同一条断言里，出错时分不清是哪层）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TermSurfaceRemoteScrollSignTest {

    private class Harness(ref: String = "s1", rows: Int = 24, cols: Int = 80) {
        val transport = FakeWebSocketTransport()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { transport },
            clock = FakeClock(),
        )
        val vm: SessionViewModel

        init {
            manager.start()
            transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
            vm = SessionViewModel(
                manager = manager,
                uploader = NoOpUploader,
                baseUrl = "http://host:0",
                ref = ref,
                initialRows = rows,
                initialCols = cols,
            )
            manager.setListener(vm)
        }

        fun scrollWheelFrames(): List<ScrollWheelFrame> =
            transport.sentText.mapNotNull { runCatching { FrameCodec.decode(it) as? ScrollWheelFrame }.getOrNull() }
    }

    private companion object {
        // TermViewPresenter.DEFAULT_CELL_HEIGHT 是私有伴生常量，值为 20（只读调研④已
        // 确认：用户未双指缩放过时，presenter.cellHeight 恒为此值）——这里钉死同一个
        // 数值而不去反射私有常量，和 mountIsolatedView 用的默认 TermViewPresenter 保持
        // 一致。若 DEFAULT_CELL_HEIGHT 以后改了这里也要跟着改（无法自动同步，见风险）。
        const val LINE_HEIGHT_PX = 20f
    }

    private object NoOpUploader : AttachmentUploader {
        override fun upload(baseUrl: String, attachment: Attachment): UploadOutcome =
            UploadOutcome.Success("/noop")
    }

    /**
     * 装一个真实 TermSurfaceView，presenter/onRemoteScrollBy 都接到 [h] 的 VM
     * （与 SessionScreen.kt 的 AndroidView factory 接线完全一致）。
     *
     * 必须跑一次真实 draw：lineHeightPx 只在 [TermSurfaceView.onDraw] 里调用的私有
     * measureCells() 中被赋值，layout() 本身不会触发它——不跑 draw 的话 lineHeightPx
     * 恒为 0，手势层会拿 0 做除数（此前这条踩过一次，emitted 全是 Int.MIN_VALUE，
     * 旧的"至少一帧"式弱断言居然没拦住，因为 Int.MIN_VALUE 依然满足 <0）。
     */
    private fun mountView(h: Harness): TermSurfaceView =
        TermSurfaceView(ApplicationProvider.getApplicationContext()).apply {
            presenter = h.vm.presenter
            onRemoteScrollBy = h.vm::onScrollWheel
            layout(0, 0, 1080, 1920)
            draw(Canvas(Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)))
        }

    // ---- T-上滑：物理上滑手势 → 协议 delta 必须为负（看更早历史）---------------

    @Test
    fun physicalUpwardSwipeSendsNegativeProtocolDelta() {
        val h = Harness()
        assertEquals(ConnectionState.READY, h.vm.connectionState)
        val view = mountView(h)

        // 手指从下往上移动（e2.y 递减）：QA 实测真实 dy 量级 40~200px/回调，这里用 60px
        // 一步、5 步，覆盖同一量级——不用先前被证伪的"个位数~十几像素"假设。
        feedSwipe(view, downTime = 1_000L, startY = 1200f, stepPx = -60f, steps = 5)

        val frames = h.scrollWheelFrames()
        assertTrue("上滑手势必须至少送出一帧 scroll_wheel：$frames", frames.isNotEmpty())
        for (f in frames) {
            assertTrue(
                "上滑（看更早历史）送出的协议 delta 必须为负，实际 ${f.delta}" +
                    "（QA 实测过这条曾经全为正，回炉·第9轮根因：多反了一次号）",
                f.delta < 0,
            )
        }
    }

    // ---- T-下滑：物理下滑手势 → 协议 delta 必须为正（回到实时）-----------------

    @Test
    fun physicalDownwardSwipeSendsPositiveProtocolDelta() {
        val h = Harness()
        val view = mountView(h)

        // 手指从上往下移动（e2.y 递增）。
        feedSwipe(view, downTime = 2_000L, startY = 400f, stepPx = 60f, steps = 5)

        val frames = h.scrollWheelFrames()
        assertTrue("下滑手势必须至少送出一帧 scroll_wheel：$frames", frames.isNotEmpty())
        for (f in frames) {
            assertTrue(
                "下滑（回到实时）送出的协议 delta 必须为正，实际 ${f.delta}",
                f.delta > 0,
            )
        }
    }

    // ---- T-本地降级：非 READY 时的本地路径不许被本轮的符号修复带偏 --------------

    /**
     * 本轮修复只动了 SessionViewModel.onScrollWheel 里"发协议帧"那一处（READY 分支），
     * 非 READY 分支 `presenter.onScrollBy(deltaLines)` 原样未动。这条测试钉住修复后
     * 该分支的**实测**行为，不是"应该怎样"的推断：
     *
     * presenter.onScrollBy 的公式是 `next = current - deltaLines`（正值=看历史）；
     * TermSurfaceView 传给它的 deltaLines 对上滑是负值（协议原生符号，T-上滑 已钉死）。
     * 负值代入公式 ⇒ next 被算大而不是算小 ⇒ 撞到 `.coerceIn(0, maxTop)` 的上界被夹回
     * maxTop ⇒ `next >= maxTop` ⇒ topLine 保持 null（跟随底部不动）。也就是说非 READY 时
     * 上滑手势在本地降级路径上目前是**结构性无效**（不是丢手势，是符号方向直接被夹住）
     * ——这正是 SessionViewModel.onScrollWheel KDoc 里标注为"本轮已知但未处理的独立问题"
     * 的那处约定不一致，本测试用真实断言把它实锤下来，而不是继续只停留在注释里。
     * 断言：showBackToBottom 在上滑后保持不变（false→false），且不发协议帧。
     * 300 行 scrollback 的存在保证"没锁定"不是因为"没历史可锁"，而确实是符号被夹住。
     */
    @Test
    fun nonReadyUpwardSwipeDoesNotSendProtocolFrameAndStaysUnlockedDueToKnownConventionMismatch() {
        val h = Harness() // rows=24,cols=80：mountView 用的 1080x1920 视口约合 96 可见行
        // (viewportHeightPx/cellHeight=1920/20)，但 presenter.visibleRows 会 coerceIn 到
        // emulator.rows(24) 为止；scrollback 仍需明显超过这个数，才能确认"没锁定"不是
        // 因为可用历史本来就不够。
        repeat(300) { h.vm.emulator.feed("line-$it\r\n") }
        h.transport.peerClose(1006, "dropped")
        h.manager.stop()
        assertEquals(ConnectionState.STOPPED, h.vm.connectionState)

        val view = mountView(h)
        assertTrue(
            "precondition: 上滑前应处于跟随底部态",
            h.vm.presenter.showBackToBottom.not(),
        )

        feedSwipe(view, downTime = 3_000L, startY = 1200f, stepPx = -60f, steps = 5)
        h.vm.syncFromPresenter()

        assertEquals("非 READY 时不应发出协议帧", 0, h.scrollWheelFrames().size)
        assertTrue(
            "非 READY 本地降级路径的已知符号不一致（presenter 正值=历史，TermSurfaceView 上滑" +
                "传负值）会让上滑手势被 coerceIn 夹回跟随底部——这是本轮修复范围之外的现状，" +
                "这里钉住它没有被这次改动波及；实际 showBackToBottom=" +
                "${h.vm.presenter.showBackToBottom}（应仍为 false）",
            h.vm.presenter.showBackToBottom.not(),
        )
    }

    // ---- T-守恒/T-慢滑/T-正常拖动：手势层像素守恒（回炉·第10轮）------------------

    /**
     * 装一个真实 TermSurfaceView，presenter 用一个独立 [TermViewPresenter]（不经
     * SessionViewModel/网络层），onRemoteScrollBy 只记录收到的 deltaLines——直接测
     * TermSurfaceView 自己这层的像素守恒，不与 SessionViewModel 的节流累加纠缠。
     */
    private fun mountIsolatedView(emitted: MutableList<Int>): TermSurfaceView {
        val presenter = TermViewPresenter(TerminalEmulator(cols = 80, rows = 24)) { _, _ -> }
        return TermSurfaceView(ApplicationProvider.getApplicationContext()).apply {
            this.presenter = presenter
            onRemoteScrollBy = { deltaLines -> emitted += deltaLines }
            layout(0, 0, 1080, 1920)
            // 见 mountView 的同名注释：不跑一次真实 draw，lineHeightPx 恒为 0。
            draw(Canvas(Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)))
        }
    }

    @Test
    fun conservationHoldsForArbitraryFineGrainedSwipe() {
        val emitted = mutableListOf<Int>()
        val view = mountIsolatedView(emitted)

        // 233px，用 5px 一步（远小于半行 10px，逐次都不足一整行）——刻意不是行高的整数倍。
        val totalPx = 233f
        feedFineSwipe(view, downTime = 1_000L, startY = 2000f, totalPx = totalPx, stepPx = 5f, durationMs = 1000L)

        // 上滑手势（startY 递减）：符号已由 T-上滑 单独钉住，这里只关心总量，用绝对值
        // 比较，避免把两条本该独立的判据（方向 / 总量）绞在同一条断言里。
        val expectedLines = Math.round(totalPx / LINE_HEIGHT_PX)
        val actualLines = Math.abs(emitted.sum())
        assertTrue(
            "像素守恒：233px 总位移应约等于 $expectedLines 行（行高=${LINE_HEIGHT_PX}px），" +
                "实际送出 |deltaLines 之和|=$actualLines，emitted=$emitted",
            Math.abs(actualLines - expectedLines) <= 1,
        )
    }

    @Test
    fun slowReadingDragOf150pxOver3sNoLongerDropsToZero() {
        val emitted = mutableListOf<Int>()
        val view = mountIsolatedView(emitted)

        // QA 第10轮实测量级：150px/3s，此前送出 0 行（九成以上位移在手势层被静默丢弃）。
        feedFineSwipe(view, downTime = 2_000L, startY = 2000f, totalPx = 150f, stepPx = 5f, durationMs = 3000L)

        val actualLines = emitted.sum()
        assertTrue(
            "150px/3s 的阅读式慢拖动必须送出非零行（此前是 0，九成以上位移被手势层丢弃），" +
                "实际 emitted=$emitted",
            actualLines != 0,
        )
    }

    @Test
    fun normalReadingDragOf700pxOver1500msDeliversApproximately35Lines() {
        val emitted = mutableListOf<Int>()
        val view = mountIsolatedView(emitted)

        // QA 第10轮实测量级：700px/1.5s（正常阅读式拖动，不是甩动），此前只送出 3 行
        // （对应 delta -2,-1 两帧），按 20px 行高应为 35 行。
        val totalPx = 700f
        feedFineSwipe(view, downTime = 3_000L, startY = 3000f, totalPx = totalPx, stepPx = 5f, durationMs = 1500L)

        val expectedLines = Math.round(totalPx / LINE_HEIGHT_PX)
        val actualLines = Math.abs(emitted.sum())
        assertTrue(
            "700px/1.5s 的正常拖动应送出约 $expectedLines 行（此前只送出 3 行，九成以上位移" +
                "被丢弃），实际送出 |deltaLines 之和|=$actualLines，emitted=$emitted",
            Math.abs(actualLines - expectedLines) <= 1,
        )
    }

    /**
     * 用固定像素步长把一次总位移为 [totalPx] 的上滑手势拆成多次 onScroll 回调，
     * 步间隔按 [durationMs] 均匀分布——贴近真实触屏高频采样、单次回调位移常不足一整行
     * 的场景（QA 实测的"阅读式拖动"就是这个量级，不是刻意构造的极端值）。
     */
    private fun feedFineSwipe(view: TermSurfaceView, downTime: Long, startY: Float, totalPx: Float, stepPx: Float, durationMs: Long) {
        val steps = Math.ceil((totalPx / stepPx).toDouble()).toInt().coerceAtLeast(1)
        val intervalMs = (durationMs / steps).coerceAtLeast(1)
        val events = buildList {
            add(singlePointerEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 540f, startY))
            var moved = 0f
            for (i in 1..steps) {
                val remaining = totalPx - moved
                val thisStep = if (remaining < stepPx) remaining else stepPx
                moved += thisStep
                add(singlePointerEvent(downTime, downTime + i * intervalMs, MotionEvent.ACTION_MOVE, 540f, startY - moved))
            }
            add(singlePointerEvent(downTime, downTime + (steps + 1) * intervalMs, MotionEvent.ACTION_UP, 540f, startY - moved))
        }
        try {
            events.forEach(view::onTouchEvent)
        } finally {
            events.forEach(MotionEvent::recycle)
        }
    }

    private fun feedSwipe(view: TermSurfaceView, downTime: Long, startY: Float, stepPx: Float, steps: Int) {
        val events = buildList {
            add(singlePointerEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 540f, startY))
            for (i in 1..steps) {
                add(singlePointerEvent(downTime, downTime + i * 16L, MotionEvent.ACTION_MOVE, 540f, startY + stepPx * i))
            }
            add(
                singlePointerEvent(
                    downTime,
                    downTime + (steps + 1) * 16L,
                    MotionEvent.ACTION_UP,
                    540f,
                    startY + stepPx * steps,
                ),
            )
        }
        try {
            events.forEach(view::onTouchEvent)
        } finally {
            events.forEach(MotionEvent::recycle)
        }
    }

    private fun singlePointerEvent(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): MotionEvent {
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            },
        )
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }
}
