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

// ScrollWheelTest: 缺陷④ App 侧手势接入红测。
//
// 这批测试要先在无实现的 HEAD 上编译红（undefined: onScrollWheel/inCopyMode/ScrollWheelFrame
// 等），在实现后全绿。红证已在 git worktree 上验证（见 evidence/feat-remote-scroll-forward.json）。
//
// T-sw1: READY 状态下 onScrollWheel(+3) → 发出 delta=-3（向上，看历史）。钉死方向符号。
// T-sw2: READY 状态下 onScrollWheel(-2) → 发出 delta=+2（向下，看最新）。
// T-sw3: 50ms 节流——500ms 内只发一帧（不多发，不漏发）。
// T-sw4: App 侧无额外延迟——frame 在 onScrollWheel 调用时同步发出（节流窗口外不加时延）。
// T-sw5: 非 READY（STOPPED）时 onScrollWheel → 不发网络帧，走本地缓冲降级。
// T-sw6: PaneModeChangedFrame 推送 → inCopyMode 更新为 true / false。
// T-sw7: 协议层：ScrollWheelFrame validate 拒绝 delta=0 / 空 ref。
// T-sw8: 协议层：PaneModeChangedFrame 编码拒绝（S→C only，永不上行）。
// T-sw9: 协议层：ScrollWheelFrame/PaneModeChangedFrame round-trip decode。

import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameCodec
import dev.agentmirror.app.conn.FrameEncodeException
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.conn.PaneModeChangedFrame
import dev.agentmirror.app.conn.ScrollWheelFrame
import dev.agentmirror.app.conn.TransportFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollWheelTest {

    // ---- 测试夹具 --------------------------------------------------------

    private class Harness(ref: String = "s1") {
        val clock = FakeClock()
        val transport = FakeWebSocketTransport()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { transport },
            clock = clock,
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
                initialRows = 5,
                initialCols = 10,
            )
            manager.setListener(vm)
        }

        fun scrollWheelFrames(): List<ScrollWheelFrame> =
            transport.sentText.mapNotNull {
                runCatching {
                    val f = FrameCodec.decode(it)
                    f as? ScrollWheelFrame
                }.getOrNull()
            }

        fun deliverPaneModeChanged(ref: String, inCopyMode: Boolean) {
            transport.deliverText(
                """{"v":1,"type":"pane_mode_changed","payload":{"ref":"$ref","in_copy_mode":$inCopyMode}}""",
            )
        }
    }

    private object NoOpUploader : AttachmentUploader {
        override fun upload(baseUrl: String, attachment: Attachment): UploadOutcome =
            UploadOutcome.Success("/noop")
    }

    // ---- T-sw1: 方向断言 — 向上（看历史）------------------------------------

    /**
     * 手势向上（deltaLines>0，presenter 约定正值=看更早历史）→ ScrollWheelFrame.delta<0
     * （协议约定 delta<0=scroll-up/向上/看历史）。钉死符号不反转。
     */
    @Test
    fun `READY onScrollWheel positive deltaLines sends negative delta`() {
        val h = Harness()
        assertEquals(ConnectionState.READY, h.vm.connectionState)

        h.vm.onScrollWheel(3) // deltaLines=+3 = 手势向上，看更早历史

        val frames = h.scrollWheelFrames()
        assertEquals("exactly one frame sent", 1, frames.size)
        assertTrue("delta must be negative (scroll-up)", frames[0].delta < 0)
        assertEquals("delta = -deltaLines", -3, frames[0].delta)
    }

    // ---- T-sw2: 方向断言 — 向下（看最新）------------------------------------

    @Test
    fun `READY onScrollWheel negative deltaLines sends positive delta`() {
        val h = Harness()

        h.vm.onScrollWheel(-2) // deltaLines=-2 = 手势向下，看最新内容

        val frames = h.scrollWheelFrames()
        assertEquals(1, frames.size)
        assertTrue("delta must be positive (scroll-down)", frames[0].delta > 0)
        assertEquals(2, frames[0].delta)
    }

    // ---- T-sw3: 50ms 节流 ------------------------------------------------

    /**
     * 连续两次调用间隔 < 50ms → 只发一帧；间隔 ≥ 50ms → 第二帧发出。
     * 节流由 System.currentTimeMillis() 驱动；测试靠线程 sleep 模拟间隔。
     */
    @Test
    fun `scroll throttle drops frames within 50ms window`() {
        val h = Harness()

        h.vm.onScrollWheel(1) // 第一帧：发出
        h.vm.onScrollWheel(1) // 间隔约 0ms：丢弃
        h.vm.onScrollWheel(1) // 间隔约 0ms：丢弃

        assertEquals("only first frame sent within throttle window", 1, h.scrollWheelFrames().size)
    }

    @Test
    fun `scroll throttle allows frame after 50ms`() {
        val h = Harness()

        h.vm.onScrollWheel(1) // 第一帧
        Thread.sleep(60)       // 等过节流窗口
        h.vm.onScrollWheel(1) // 第二帧：应发出

        assertEquals("second frame sent after throttle window", 2, h.scrollWheelFrames().size)
    }

    // ---- T-sw4: App 侧无额外延迟 -----------------------------------------

    /**
     * 从 onScrollWheel 调用到 ScrollWheelFrame 出现在 transport.sentText 的时间，
     * 即 App 自身处理时间，必须 < 20ms（节流窗口 50ms 之外不得引入额外等待）。
     * 确保我们自己那一段不增加钝感。
     */
    @Test
    fun `onScrollWheel dispatches frame synchronously within 20ms`() {
        val h = Harness()
        val before = System.currentTimeMillis()

        h.vm.onScrollWheel(1)

        val elapsed = System.currentTimeMillis() - before
        assertEquals("frame must appear in transport", 1, h.scrollWheelFrames().size)
        assertTrue("App side processing must be < 20ms (got ${elapsed}ms)", elapsed < 20)
    }

    // ---- T-sw5: 非 READY 降级 -------------------------------------------

    @Test
    fun `non-READY state falls back to local scroll without sending frame`() {
        val h = Harness()
        // 掉线：变为 STOPPED
        h.transport.peerClose(1006, "dropped")
        h.manager.stop()
        assertEquals(ConnectionState.STOPPED, h.vm.connectionState)

        val countBefore = h.scrollWheelFrames().size
        h.vm.onScrollWheel(3) // 不应发网络帧

        assertEquals("no scroll wheel frame sent when not READY", countBefore, h.scrollWheelFrames().size)
    }

    // ---- T-sw6: PaneModeChangedFrame → inCopyMode 状态更新 ---------------

    @Test
    fun `PaneModeChangedFrame inCopyMode=true sets viewmodel inCopyMode`() {
        val h = Harness(ref = "s1")
        assertFalse("initially false", h.vm.inCopyMode)

        h.deliverPaneModeChanged("s1", true)

        assertTrue("inCopyMode must be true after server notification", h.vm.inCopyMode)
    }

    @Test
    fun `PaneModeChangedFrame inCopyMode=false clears viewmodel inCopyMode`() {
        val h = Harness(ref = "s1")
        h.deliverPaneModeChanged("s1", true) // 先进 copy-mode
        assertTrue(h.vm.inCopyMode)

        h.deliverPaneModeChanged("s1", false)

        assertFalse("inCopyMode must be false after exit notification", h.vm.inCopyMode)
    }

    @Test
    fun `PaneModeChangedFrame for wrong ref is ignored`() {
        val h = Harness(ref = "s1")
        h.deliverPaneModeChanged("other-ref", true) // wrong ref

        assertFalse("inCopyMode must stay false for wrong ref", h.vm.inCopyMode)
    }

    // ---- T-sw7: 协议层 ScrollWheelFrame validate -------------------------

    @Test
    fun `ScrollWheelFrame validate rejects zero delta`() {
        val f = ScrollWheelFrame(ref = "s1", delta = 0)
        val err = f.validate()
        assertTrue("validate() must reject delta=0", err != null)
    }

    @Test
    fun `ScrollWheelFrame validate rejects empty ref`() {
        val f = ScrollWheelFrame(ref = "", delta = -1)
        val err = f.validate()
        assertTrue("validate() must reject empty ref", err != null)
    }

    @Test
    fun `ScrollWheelFrame validate accepts nonzero delta and nonempty ref`() {
        val f = ScrollWheelFrame(ref = "s1", delta = -3)
        assertEquals(null, f.validate())
    }

    // ---- T-sw8: PaneModeChangedFrame 编码拒绝（S→C only）-----------------

    @Test
    fun `encoding PaneModeChangedFrame throws FrameEncodeException`() {
        val f = PaneModeChangedFrame(ref = "s1", inCopyMode = true)
        var threw = false
        try {
            FrameCodec.encode(f) // 应抛出异常
        } catch (e: FrameEncodeException) {
            threw = true
        }
        assertTrue("encoding PaneModeChangedFrame must be rejected (S→C only)", threw)
    }

    // ---- T-sw9: 协议层 round-trip decode ---------------------------------

    @Test
    fun `ScrollWheelFrame round-trips through encode and decode`() {
        val f = ScrollWheelFrame(ref = "s1", delta = -3)
        val json = FrameCodec.encode(f)
        val decoded = FrameCodec.decode(json)
        assertEquals(f, decoded)
    }

    @Test
    fun `PaneModeChangedFrame decodes from server JSON`() {
        val json = """{"v":1,"type":"pane_mode_changed","payload":{"ref":"s1","in_copy_mode":true}}"""
        val decoded = FrameCodec.decode(json)
        assertTrue(decoded is PaneModeChangedFrame)
        val pmc = decoded as PaneModeChangedFrame
        assertEquals("s1", pmc.ref)
        assertTrue(pmc.inCopyMode)
    }
}
