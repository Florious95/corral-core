/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.BinaryFrameCodec
import dev.agentmirror.app.conn.BinaryKind
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameCodec
import dev.agentmirror.app.conn.ReconnectPolicy
import dev.agentmirror.app.conn.ResizeFrame
import dev.agentmirror.app.conn.SubscribeFrame
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.app.session.AttachmentUploader
import dev.agentmirror.app.session.SessionViewModel
import dev.agentmirror.app.session.UploadOutcome
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 081 后台久置回前台重排：仪表字段齐全 + 重连重放最新 cols（不是首次 subscribe 的 40×120）。
 *
 * 单测名含 TermReflow（账本 A-rf-test）。
 */
class TermReflowTest {

    @Before
    fun setUp() {
        DiagLog.resetForTest()
    }

    @After
    fun tearDown() {
        DiagLog.resetForTest()
    }

    @Test
    fun termReflowResumeLogsDerivedAndLastSentCols() {
        val emu = TerminalEmulator(80, 24)
        val presenter = TermViewPresenter(emu) { _, _ -> }
        presenter.seedCellMetrics(cellW = 10, cellH = 20)
        presenter.onViewportSizeChanged(800, 480)
        DiagLog.resetForTest()

        presenter.onRealViewportChanged(800, 480)
        val text = exportedText()
        val resume = lineOf(text, "derived_cols=")
        assertTrue("回前台必须记下 view 宽: $resume", resume.contains("view_width_px=800"))
        assertTrue("回前台必须记下字格宽: $resume", resume.contains("cell_width_px=10"))
        assertEquals("800", field(resume, "view_width_px"))
        assertEquals("10", field(resume, "cell_width_px"))
        assertEquals("80", field(resume, "derived_cols"))
        assertEquals("80", field(resume, "last_sent_cols"))
    }

    @Test
    fun termReflowResizeReasonIsResumeOnRealViewport() {
        var seenReason: String? = null
        val emu = TerminalEmulator(40, 10)
        val presenter = TermViewPresenter(emu) { r, c, reason ->
            seenReason = reason
            emu.resize(c, r)
        }
        presenter.seedCellMetrics(10, 20)
        presenter.onViewportSizeChanged(400, 200)
        assertEquals("user", presenter.lastResizeReason)
        presenter.onRealViewportChanged(900, 400)
        assertEquals("resume", seenReason)
        assertEquals("resume", presenter.lastResizeReason)
    }

    @Test
    fun termReflowReconnectReplaysLatestColsNotInitialSubscribe() {
        val h = ConnHarness()
        h.start()
        ready(h.transport())
        assertTrue(h.manager.subscribe("s1", 40, 120))
        assertEquals(40 to 120, h.manager.subscriptionSize("s1"))
        assertTrue(h.manager.resize("s1", 24, 72, reason = "user"))
        assertEquals(24 to 72, h.manager.subscriptionSize("s1"))
        val resizeLine = lineOf(exportedText(), "resize sent")
        assertTrue(resizeLine.contains("rows=24"))
        assertTrue(resizeLine.contains("cols=72"))
        assertTrue(resizeLine.contains("reason=user"))

        h.transport().sentText.clear()
        h.transport().peerClose(1006, "dropped")
        assertEquals(ConnectionState.RECONNECTING, h.manager.state())
        h.clock.advance(1000)
        h.manager.pump(h.clock.nowMs())
        val t2 = h.dial(1)
        ready(t2)
        assertEquals(ConnectionState.READY, h.manager.state())

        val sent = t2.sentText.map { runCatching { FrameCodec.decode(it) }.getOrNull() }
        val subs = sent.filterIsInstance<SubscribeFrame>()
        assertEquals(1, subs.size)
        assertEquals("重连必须重放 resize 后的列宽，不能回落到首次 120", 72, subs[0].cols)
        assertEquals(24, subs[0].rows)

        val recon = lineOf(exportedText(), "reconnect ok")
        assertTrue(recon.contains("resend_resize=yes"))
        assertEquals("72", field(recon, "cols"))
        assertEquals("24", field(recon, "rows"))
    }

    @Test
    fun termReflowZeroColsResizeIsSkippedAndLogged() {
        val h = ConnHarness()
        h.start()
        ready(h.transport())
        h.manager.subscribe("s1", 40, 120)
        val before = h.transport().sentText.count {
            runCatching { FrameCodec.decode(it) }.getOrNull() is ResizeFrame
        }
        assertFalse(h.manager.resize("s1", 24, 0, reason = "resume"))
        val after = h.transport().sentText.count {
            runCatching { FrameCodec.decode(it) }.getOrNull() is ResizeFrame
        }
        assertEquals("非正 cols 不得发出 resize", before, after)
        assertEquals(40 to 120, h.manager.subscriptionSize("s1"))
        val skip = lineOf(exportedText(), "resize skipped")
        assertTrue(skip.contains("cols=0"))
        assertTrue(skip.contains("reason=resume"))
        assertTrue(skip.contains("bookkept_cols=120"))
    }

    @Test
    fun termReflowSnapshotLogsFrameColsVsRenderCols() {
        val transport = FakeWebSocketTransport()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { transport },
            clock = FakeClock(),
        )
        manager.start()
        transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        val vm = SessionViewModel(
            manager = manager,
            uploader = AttachmentUploader { _, _ -> UploadOutcome.Failure("unused") },
            baseUrl = null,
            ref = "s1",
            initialRows = 24,
            initialCols = 80,
        )
        manager.setListener(vm)
        assertTrue(manager.resize("s1", 24, 72, reason = "user"))
        vm.emulator.resize(72, 24)
        DiagLog.resetForTest()

        transport.deliverBinary(
            BinaryFrameCodec.encode(BinaryFrame(BinaryKind.SNAPSHOT, "s1", "hello\n".toByteArray())),
        )
        val text = exportedText()
        val frameLine = lineOf(text, "frame cols=")
        assertEquals("72", spacedField(frameLine, "frame cols"))
        assertEquals("72", spacedField(frameLine, "render cols"))
    }

    @Test
    fun termReflowExportContainsBothOperandsAndTheyMatch() {
        val emu = TerminalEmulator(80, 24)
        val presenter = TermViewPresenter(emu) { r, c -> emu.resize(c, r) }
        presenter.seedCellMetrics(10, 20)
        presenter.onViewportSizeChanged(800, 480)
        presenter.onRealViewportChanged(800, 480)

        val transport = FakeWebSocketTransport()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { transport },
            clock = FakeClock(),
        )
        manager.start()
        transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
        val vm = SessionViewModel(
            manager, AttachmentUploader { _, _ -> UploadOutcome.Failure("x") },
            null, "s1", 24, 80,
        )
        manager.setListener(vm)
        manager.resize("s1", 24, 80, "resume")
        transport.deliverBinary(
            BinaryFrameCodec.encode(BinaryFrame(BinaryKind.SNAPSHOT, "s1", "x".toByteArray())),
        )

        val text = exportedText()
        val derived = field(lineOf(text, "derived_cols="), "derived_cols").toInt()
        val frameCols = Regex("frame cols=(\\S+)").find(text)?.groupValues?.get(1)?.toInt()
            ?: error("导出里读不到 frame cols —— 仪表没做够")
        assertEquals("A-rf-cols：derived_cols 与 frame cols 必须相等", derived, frameCols)
        assertTrue(derived > 0)
    }

    private class ConnHarness {
        val clock = FakeClock()
        val transports = mutableListOf<FakeWebSocketTransport>()
        lateinit var manager: ConnectionManager

        init {
            manager = ConnectionManager(
                config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
                transportFactory = TransportFactory {
                    FakeWebSocketTransport().also { transports.add(it) }
                },
                clock = clock,
                policy = ReconnectPolicy(baseMs = 1000, maxMs = 30_000, random = { 0.5 }),
            )
        }

        fun transport(): FakeWebSocketTransport = transports.last()
        fun start() = manager.start()
        fun dial(index: Int): FakeWebSocketTransport = transports[index]
    }

    private fun ready(t: FakeWebSocketTransport) {
        t.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
    }

    private fun exportedText(): String {
        val f = File.createTempFile("diag-reflow-", ".log").apply { deleteOnExit() }
        DiagLog.exportTo(f)
        return f.readText()
    }

    private fun lineOf(text: String, needle: String): String =
        text.lineSequence().lastOrNull { it.contains(needle) }
            ?: error("导出里读不到 `$needle` —— 仪表没做够\n$text")

    private fun field(msg: String, key: String): String =
        Regex("$key=([^ ]+)").find(msg)?.groupValues?.get(1)
            ?: error("字段缺失: $key —— $msg")

    private fun spacedField(msg: String, key: String): String =
        Regex("${Regex.escape(key)}=(\\S+)").find(msg)?.groupValues?.get(1)
            ?: error("字段缺失: $key —— $msg")
}
