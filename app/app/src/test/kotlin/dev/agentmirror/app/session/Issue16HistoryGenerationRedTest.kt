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

import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.BinaryFrameCodec
import dev.agentmirror.app.conn.BinaryKind
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameCodec
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.conn.ReconnectPolicy
import dev.agentmirror.app.conn.ScrollbackFrame
import dev.agentmirror.app.conn.TransportFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue16 独立客户端历史代次场景，固定在 corral-core 04bf 基线构建。
 *
 * 该测试不复用服务端 overflow/真机记录：FakeWebSocketTransport 只驱动 App 的
 * 同一 VM、连接代次、在途历史页和帧顺序，单独锁定客户端对 generation 的预期。
 * 红测必须先在 04bf 跑出；后续候选仍用完全相同的输入与断言复验。
 */
class Issue16HistoryGenerationRedTest {

    private class Harness(
        private val rows: Int = 3,
        private val cols: Int = 20,
    ) {
        val clock = FakeClock()
        val transport = FakeWebSocketTransport()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { transport },
            clock = clock,
            policy = ReconnectPolicy(random = { 0.5 }),
        )
        lateinit var vm: SessionViewModel

        init {
            manager.start()
            transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
            vm = SessionViewModel(
                manager = manager,
                uploader = AttachmentUploader { _, _ -> UploadOutcome.Failure("not used") },
                baseUrl = "http://host:0",
                ref = "s1",
                initialRows = rows,
                initialCols = cols,
            )
            // 测试夹具按生产的 ref 路由再挂上页面 listener。
            manager.setListener(vm)
        }

        fun snapshot(text: String) {
            transport.deliverBinary(
                BinaryFrameCodec.encode(
                    BinaryFrame(BinaryKind.SNAPSHOT, "s1", text.toByteArray()),
                ),
            )
        }

        fun scrollback(text: String, fromLine: Int, lineCount: Long) {
            transport.deliverBinary(
                BinaryFrameCodec.encode(
                    BinaryFrame(
                        BinaryKind.SCROLLBACK,
                        "s1",
                        text.toByteArray(),
                        reqId = 1,
                        fromLine = fromLine,
                        lineCount = lineCount,
                    ),
                ),
            )
        }

        fun reconnect() {
            transport.peerClose(1006, "overflow")
            assertEquals(ConnectionState.RECONNECTING, vm.connectionState)
            clock.advance(1_001)
            vm.onTick(clock.nowMs())
            transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
            assertEquals(ConnectionState.READY, vm.connectionState)
        }

        fun scrollbackFrames(): List<ScrollbackFrame> = transport.sentText
            .mapNotNull { runCatching { FrameCodec.decode(it) }.getOrNull() }
            .filterIsInstance<ScrollbackFrame>()

        fun screenRows(): List<String> = vm.emulator.snapshot().lines.map { row ->
            row.joinToString("") { it.text }.trimEnd()
        }
    }

    /** B0 已有历史到顶后断线：重连必须清旧 history、回底并以 -400 重新取页。 */
    @Test
    fun reconnectClearsExhaustedHistoryResetsViewportAndRefetchesFromInitialAnchor() {
        val h = Harness()
        h.snapshot("B0S00\r\nB0S01\r\nB0S02")
        h.scrollback("B0H00\r\n", fromLine = -1, lineCount = 1)
        assertFalse("B0 page must establish an exhausted history state", h.vm.hasMoreHistory)

        // 本地上滑锁在旧 history 顶部；没有额外输出，也不手动退订。
        h.vm.presenter.onScrollBy(1)
        h.vm.syncFromPresenter()
        assertTrue(h.vm.showBackToBottom)
        assertTrue(h.vm.atHistoryTop)

        h.reconnect()
        h.snapshot("B1S00\r\nB1S01\r\nB1S02")

        assertEquals("reconnect snapshot must clear the old B0 history", 0, h.vm.emulator.scrollback.size)
        assertTrue("new generation must allow a fresh history page", h.vm.hasMoreHistory)
        assertTrue("reconnect snapshot must return to current bottom", h.vm.presenter.isFollowingBottom)
        assertFalse(h.vm.showBackToBottom)
        assertEquals(listOf("B1S00", "B1S01", "B1S02"), h.screenRows())
        assertEquals(
            "new generation must prefetch from the initial history anchor",
            listOf(-400),
            h.scrollbackFrames().map { it.fromLine }.takeLast(1),
        )

        h.scrollback("B1H00\r\n", fromLine = -1, lineCount = 1)
        assertEquals(1, h.vm.emulator.scrollback.size)
        assertEquals("B1H00", historyText(h.vm, 0))
        assertFalse("B1 page is the new generation, so its clamped reply ends paging", h.vm.hasMoreHistory)
    }

    /** B0 页在途时掉线：重连首帧仍必须丢弃锁位/旧历史并重新发 -400，而非沿用 -800。 */
    @Test
    fun reconnectCancelsInFlightOldPageAndDoesNotReuseOldAnchor() {
        val h = Harness()
        h.snapshot("B0S00\r\nB0S01\r\nB0S02")
        h.scrollback("B0H00\r\n", fromLine = -400, lineCount = 400)
        h.vm.presenter.onScrollBy(1)
        h.vm.syncFromPresenter()
        assertTrue(h.vm.showBackToBottom)
        assertEquals(listOf(-400, -800), h.scrollbackFrames().map { it.fromLine })

        // -800 请求刻意不回包，模拟掉线时的在途历史页。
        h.reconnect()
        h.snapshot("B1S00\r\nB1S01\r\nB1S02")

        assertEquals(0, h.vm.emulator.scrollback.size)
        assertTrue(h.vm.presenter.isFollowingBottom)
        assertFalse(h.vm.showBackToBottom)
        assertEquals(listOf("B1S00", "B1S01", "B1S02"), h.screenRows())
        assertEquals(listOf(-400, -800, -400), h.scrollbackFrames().map { it.fromLine })

        h.scrollback("B1H00\r\n", fromLine = -1, lineCount = 1)
        assertEquals("B1H00", historyText(h.vm, 0))
        assertFalse(historyText(h.vm, 0).contains("B0"))
    }

    /** 同一连接 resize 后的 snapshot 是正控：旧 history 与锁位必须保持。 */
    @Test
    fun sameConnectionResizeSnapshotPreservesHistoryAndLockedViewport() {
        val h = Harness()
        h.snapshot("B0S00\r\nB0S01\r\nB0S02")
        h.scrollback("B0H00\r\n", fromLine = -1, lineCount = 1)
        h.vm.presenter.onScrollBy(1)
        h.vm.syncFromPresenter()
        assertTrue(h.vm.showBackToBottom)
        assertEquals("B0H00", historyText(h.vm, 0))

        assertTrue(h.manager.resize("s1", rows = 3, cols = 20, reason = "user"))
        h.snapshot("B0R00\r\nB0R01\r\nB0R02")

        assertEquals("same-connection resize snapshot must preserve local history", 1, h.vm.emulator.scrollback.size)
        assertEquals("B0H00", historyText(h.vm, 0))
        assertTrue("same-connection resize must not unlock a history viewport", h.vm.showBackToBottom)
        assertFalse(h.vm.presenter.isFollowingBottom)
        assertEquals(listOf("B0R00", "B0R01", "B0R02"), h.screenRows())
        assertEquals("resize snapshot must not prefetch a second page", 1, h.scrollbackFrames().size)
    }

    private fun historyText(vm: SessionViewModel, index: Int): String = vm.emulator.scrollback
        .line(index)
        .joinToString("") { it.text }
        .trimEnd()

    /** 初始 snapshot 之前掉线不能丢失下一代首帧；首帧仍应开启新历史预取。 */
    @Test
    fun lossBeforeInitialSnapshotStillAcceptsNextGenerationAndPrefetches() {
        val h = Harness()
        h.reconnect()
        h.snapshot("B1S00\r\nB1S01\r\nB1S02")

        assertEquals(0, h.vm.emulator.scrollback.size)
        assertEquals(listOf("B1S00", "B1S01", "B1S02"), h.screenRows())
        assertEquals(listOf(-400), h.scrollbackFrames().map { it.fromLine })
        assertTrue(h.vm.presenter.isFollowingBottom)
    }
}
