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
import dev.agentmirror.app.conn.InputFrame
import dev.agentmirror.app.conn.InputKey
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.diag.DiagLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * t.afix 先验红：静态 alt-screen 的 KindSnapshot 含字形，模型/画布必须留下这些字。
 *
 * 样例照 .team/nodes/hl1-sprobe2/甄别.md：27B = `STATIC_ALT_MARKER_092` + CUP ESC[2;1H
 * （pane alt=1；capture-pane 不带 1049h，字形在字节里）。
 *
 * 冷点开真实顺序（pm clear 无几何缓存 → INITIAL 40×120，视口再 seed 成手机行列）：
 * 1. 订阅首帧 = 上述 27B（sfix 在 Resize/WINCH 清屏之前 capture）；
 * 2. 视口 seed 发 resize，服务端对已清空的静止备用屏再 capture，补发只有 CUP 的空快照。
 * 改前 replaySnapshot 无条件清屏重建，第 2 帧把第 1 帧的字抹掉 → 本测试红。
 * ⛔ 不是「等到有 delta 才画」：断言发生在只喂了 snapshot 之后。
 */
class AltScreenSnapshotGlyphsTest {

    @Before
    fun setUp() {
        DiagLog.resetForTest()
        DiagLog.initialize(null)
    }

    @After
    fun tearDown() {
        DiagLog.resetForTest()
    }

    @Test
    fun altScreenKindSnapshotGlyphsSurviveEmptyResizeSnapshot() {
        val h = Harness(rows = 40, cols = 120)
        val glyphSnap = sprobe2AltSnapshot()
        assertEquals("甄别.md 27B 样例", 27, glyphSnap.size)
        assertTrue("27B 样例必须含可绘字形", glyphSnap.toString(Charsets.US_ASCII).contains(MARKER))

        h.deliverSnapshot(glyphSnap)
        assertTrue(
            "首帧 27B 进模型就必须有 STATIC_ALT_MARKER_092，got=${h.canvasText()}",
            h.canvasText().contains(MARKER),
        )

        h.vm.presenter.seedCellMetrics(10, 20)
        h.vm.presenter.onViewportSizeChanged(1080, 1920)

        val emptyResizeSnap = "\u001b[1;1H".toByteArray()
        assertEquals(6, emptyResizeSnap.size)
        h.deliverSnapshot(emptyResizeSnap)

        val shown = h.canvasText()
        assertTrue(
            "resize 补发的空 CUP 快照不得抹掉 alt-screen 首帧字形，got=$shown",
            shown.contains(MARKER),
        )
    }

    @Test
    fun ordinaryEmptySnapshotAfterFirstFrameWindowApplies() {
        val h = Harness(rows = 40, cols = 120)
        h.deliverSnapshot(sprobe2AltSnapshot())
        h.deliverSnapshot(EMPTY_CUP)
        assertTrue(
            "窗口内空 resize 仍须保住首帧，got=${h.canvasText()}",
            h.canvasText().contains(MARKER),
        )
        h.deliverSnapshot(EMPTY_CUP)
        val shown = h.canvasText()
        assertFalse(
            "窗口外合法空快照必须清屏重建（clear/真空屏），got=$shown",
            shown.contains(MARKER),
        )
    }

    @Test
    fun reconnectEmptySnapshotAppliesEvenIfScreenHasGlyphs() {
        val h = Harness(rows = 40, cols = 120)
        h.deliverSnapshot(sprobe2AltSnapshot())
        assertTrue(h.canvasText().contains(MARKER))
        h.vm.onStateChanged(ConnectionState.RECONNECTING)
        h.vm.onStateChanged(ConnectionState.READY)
        h.deliverSnapshot(EMPTY_CUP)
        val shown = h.canvasText()
        assertFalse(
            "重连订阅首帧空快照是收敛点，必须应用，got=$shown",
            shown.contains(MARKER),
        )
    }

    @Test
    fun suppressedResizeSnapshotStillBumpsSnapshotGen() {
        val h = Harness(rows = 40, cols = 120)
        h.deliverSnapshot(sprobe2AltSnapshot())
        h.vm.sendKey(InputKey.TAB)
        val req = h.keyFrames().last().reqId
        h.ackOk(req)
        h.deliverSnapshot(EMPTY_CUP)
        assertTrue(
            "窗口内空 resize 不得抹字，got=${h.canvasText()}",
            h.canvasText().contains(MARKER),
        )
        val logs = DiagLog.snapshotForTest().joinToString("\n")
        assertTrue(
            "early-return 不得跳过 snapshotGen++，logs=$logs",
            logs.contains("snapshot_gen=2"),
        )
    }

    private class FakeUploader : AttachmentUploader {
        override fun upload(baseUrl: String, attachment: Attachment): UploadOutcome =
            UploadOutcome.Success("/host/img.png")
    }

    private class Harness(rows: Int, cols: Int) {
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
            vm = SessionViewModel(manager, FakeUploader(), "http://host:0", "s1", rows, cols)
            manager.setListener(vm)
        }

        fun deliverSnapshot(data: ByteArray) {
            transport.deliverBinary(
                BinaryFrameCodec.encode(BinaryFrame(BinaryKind.SNAPSHOT, "s1", data)),
            )
        }

        fun canvasText(): String {
            vm.presenter.beginFrame()
            return vm.presenter.window.joinToString("\n") { row ->
                vm.presenter.lineCells(row).joinToString("") { it.text }.trimEnd()
            }
        }

        fun sentFrames(): List<FramePayload> =
            transport.sentText.mapNotNull { runCatching { FrameCodec.decode(it) }.getOrNull() }

        fun keyFrames(): List<InputFrame> =
            sentFrames().filterIsInstance<InputFrame>().filter { it.keys.isNotEmpty() }

        fun ackOk(reqId: Long) = transport.deliverText(
            """{"v":1,"type":"input_ack","payload":{"req_id":$reqId,"ok":true}}""",
        )
    }

    companion object {
        const val MARKER = "STATIC_ALT_MARKER_092"
        val EMPTY_CUP: ByteArray = "\u001b[1;1H".toByteArray()

        fun sprobe2AltSnapshot(): ByteArray =
            (MARKER + "\u001b[2;1H").toByteArray(Charsets.US_ASCII)
    }
}
