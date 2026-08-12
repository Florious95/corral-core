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
import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameCodec
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.conn.ScrollbackFrame
import dev.agentmirror.app.conn.TransportFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-36 红测（SessionViewModel 层）：打破「鸡生蛋」——本地 buffer 为空时上滑必须能触发补页，
 * 且补页并入缓冲后视口保持锚定、可继续上滑到更老历史。
 *
 * 现状（修前红）：
 * - replaySnapshot 填满网格、scrollback 恒空 ⇒ `maxTop==0` ⇒ 上滑锁不住（topLine 恒 null）
 *   ⇒ `syncFromPresenter` 的 `atHistoryTop` 永不成立 ⇒ 补页请求永不发出。
 * - 即使预取已返回（空历史），buffer 仍空，同一死结。
 *
 * 修复（绿）：上滑命中空 buffer 边界即进入可补页锁定态；补页头插后视口锚点平移
 * （并入 N 行 ⇒ topLine += N），窗口内容不变、可继续上滑到更老页。
 */
class SessionScrollbackPagingTest {

    private class Harness(ref: String = "s1", rows: Int = 5, cols: Int = 10) {
        val clock = FakeClock()
        val transport = FakeWebSocketTransport()
        val uploader = object : AttachmentUploader {
            override fun upload(baseUrl: String, attachment: Attachment): UploadOutcome =
                UploadOutcome.Success("/x")
        }
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { transport },
            clock = clock,
        )
        lateinit var vm: SessionViewModel

        init {
            manager.start()
            transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
            vm = SessionViewModel(manager, uploader, "http://host:0", ref, rows, cols)
            manager.setListener(vm)
        }

        fun sentFrames(): List<FramePayload> =
            transport.sentText.mapNotNull { runCatching { FrameCodec.decode(it) }.getOrNull() }

        fun scrollbackFrames(): List<ScrollbackFrame> = sentFrames().filterIsInstance<ScrollbackFrame>()

        fun snap(text: String) = transport.deliverBinary(
            BinaryFrameCodec.encode(BinaryFrame(BinaryKind.SNAPSHOT, "s1", text.toByteArray())),
        )

        fun scrollbackReply(text: String, fromLine: Int, lineCount: Long) = transport.deliverBinary(
            BinaryFrameCodec.encode(
                BinaryFrame(
                    BinaryKind.SCROLLBACK, "s1", text.toByteArray(),
                    reqId = 1, fromLine = fromLine, lineCount = lineCount,
                ),
            ),
        )
    }

    @Test
    fun scrollUpFromEmptyBufferRequestsHistory() {
        val h = Harness(rows = 5, cols = 10)
        h.snap("screen line") // snapshot 填满网格，scrollback 恒空；同时触发首帧预取
        // 预取响应返回空历史（服务端无更老历史）：在途清、buffer 仍空。
        h.scrollbackReply("", fromLine = -400, lineCount = 400)
        assertEquals("预取空历史后 buffer 应仍为空", 0, h.vm.emulator.scrollback.size)

        val framesBefore = h.scrollbackFrames().size
        // 用户上滑（空 buffer，无可滚空间）→ 修复前锁不住 → 不补页 → 红。
        h.vm.presenter.onScrollBy(1)
        h.vm.syncFromPresenter()
        assertTrue(
            "空 buffer 上滑后未发出新的 scrollback 补页请求（鸡生蛋：锁不住就永远不补页）",
            h.scrollbackFrames().size >= framesBefore + 1,
        )
    }

    @Test
    fun prependedHistoryKeepsViewportAndExtendsScroll() {
        val h = Harness(rows = 2, cols = 5)
        // 制造本地 scrollback=[a,b]、屏幕=[c,d]（feed 直接累积，不触发快照预取）。
        h.vm.emulator.feed("a\r\nb\r\nc\r\nd")
        h.vm.presenter.onScrollBy(2) // 锁到历史顶，窗口=[a,b]
        assertEquals("上滑后窗口应在历史区", 0..1, h.vm.presenter.window)
        h.vm.syncFromPresenter() // 滚到顶 → 补页请求发出

        // 补页并入更老历史 h1,h2 → scrollback=[h1,h2,a,b]。
        h.scrollbackReply("h1\nh2\n", fromLine = -2, lineCount = 2)
        assertEquals("并入历史应头插", 4, h.vm.emulator.scrollback.size)

        // 补页后视口必须保持锚定（窗口内容仍是 a,b，而非跳到最老 h1 或落回跟随）→ 修复前红。
        assertEquals(
            "补页后视口顶应随并入行数平移（锚定衔接处，窗口内容不变）",
            2..3, h.vm.presenter.window,
        )

        // 继续上滑可滚到新并入的更老历史 [h1,h2]。
        h.vm.presenter.onScrollBy(2)
        assertEquals("继续上滑应能滚到并入的更老历史", 0..1, h.vm.presenter.window)

        // 再滚到顶触发下一补页（分页收敛：不重复拉、可继续拉）。
        h.vm.syncFromPresenter()
        assertTrue("滚到并入历史顶部后应可再次补页", h.vm.atHistoryTop)
    }
}
