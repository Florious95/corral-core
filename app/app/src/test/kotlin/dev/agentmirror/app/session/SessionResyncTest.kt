package dev.agentmirror.app.session

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.BinaryFrameCodec
import dev.agentmirror.app.conn.BinaryKind
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 087 光标锚定回读：控制键 ack 后用仿真器光标校正 syncedText。
 * 先验红：回读前空本地缓冲上删除发 0 键（F-087-2）。
 */
class SessionResyncTest {

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
    fun tabThenDelete_sendsBackspacesAgainstRemote() {
        val h = Harness()
        h.vm.onPassthroughInput(tv(""), tv("/fo"))
        h.vm.sendKey(InputKey.TAB)
        h.ackOk(h.keyFrames().last().reqId)
        h.paintCursorLine(row0 = 10, text = "/foobar")
        assertEquals("/foobar", h.vm.resyncDraft?.text)
        assertFalse(h.vm.resyncPending)
        val before = h.inputFrames().size
        h.vm.onPassthroughInput(tv("/foobar"), tv(""))
        val sent = h.inputFrames().drop(before)
        assertEquals(7, backspaces(sent))
        val logs = DiagLog.snapshotForTest().joinToString("\n")
        assertTrue(logs.contains("resync_wait_ms="))
        assertTrue(logs.contains("snapshot_gen="))
        assertTrue(logs.contains("trigger=Tab"))
    }

    @Test
    fun escEscThenDelete_clearsRemote() {
        val h = Harness()
        h.vm.sendKey(InputKey.ESC)
        h.ackOk(h.keyFrames().last().reqId)
        h.paintCursorLine(row0 = 10, text = "prev-command")
        assertEquals("prev-command", h.vm.resyncDraft?.text)
        val before = h.inputFrames().size
        h.vm.onPassthroughInput(tv("prev-command"), tv(""))
        assertEquals("prev-command".length, backspaces(h.inputFrames().drop(before)))
        val logs = DiagLog.snapshotForTest().joinToString("\n")
        assertTrue(logs.contains("trigger=Esc"))
        assertTrue(logs.contains("cursorY=10"))
    }

    @Test
    fun appendStillZeroBackspaceAfterExtractCodeExists() {
        val h = Harness()
        h.vm.onPassthroughInput(tv(""), tv("l"))
        h.vm.onPassthroughInput(tv("l"), tv("ls"))
        val sent = h.inputFrames()
        assertEquals(2, sent.size)
        assertTrue(sent.all { it.keys.isEmpty() })
        assertEquals("l", sent[0].text)
        assertEquals("s", sent[1].text)
        assertEquals(0, backspaces(sent))
    }

    @Test
    fun resyncPending_passthroughDoesNotEmitKeys() {
        val h = Harness()
        h.vm.onPassthroughInput(tv(""), tv("/fo"))
        h.vm.sendKey(InputKey.TAB)
        h.ackOk(h.keyFrames().last().reqId)
        assertTrue(h.vm.resyncPending)
        val before = h.inputFrames().size
        h.vm.onPassthroughInput(tv("/fo"), tv("/fox"))
        assertEquals(before, h.inputFrames().size)
        h.paintCursorLine(row0 = 10, text = "/foobar")
        assertFalse(h.vm.resyncPending)
    }

    @Test
    fun composingHold_doesNotOverlayUntilCommit() {
        val h = Harness()
        val composing = TextFieldValue("ni", TextRange(2), TextRange(0, 2))
        h.vm.onPassthroughInput(tv(""), composing)
        h.vm.sendKey(InputKey.TAB)
        h.ackOk(h.keyFrames().last().reqId)
        h.paintCursorLine(row0 = 10, text = "/foobar")
        assertEquals(null, h.vm.resyncDraft)
        val logs = DiagLog.snapshotForTest().joinToString("\n")
        assertTrue(logs.contains("composing=true trigger=Tab → hold overlay"))
        h.vm.onPassthroughInput(composing, TextFieldValue("你", TextRange(1)))
        assertEquals("/foobar", h.vm.resyncDraft?.text)
    }

    @Test
    fun timeout400ms_failsVisiblyWithoutClearingSynced() {
        val h = Harness()
        h.vm.onPassthroughInput(tv(""), tv("/fo"))
        h.vm.sendKey(InputKey.TAB)
        h.ackOk(h.keyFrames().last().reqId)
        h.vm.onTick(0)
        assertTrue(h.vm.resyncPending)
        h.vm.onTick(400)
        assertFalse(h.vm.resyncPending)
        assertNotNull(h.vm.transientError)
        assertTrue(h.vm.transientError!!.contains("远端输入行无法读取"))
        assertEquals(null, h.vm.resyncDraft)
        val before = h.inputFrames().size
        h.vm.onPassthroughInput(tv("/fo"), tv("/f"))
        val sent = h.inputFrames().drop(before)
        assertEquals(1, backspaces(sent))
        val logs = DiagLog.snapshotForTest().joinToString("\n")
        assertTrue(logs.contains("resync_wait_ms="))
        assertTrue(logs.contains("trigger=Tab"))
        assertTrue(logs.contains("fail-visible"))
    }

    @Test
    fun extractIgnoresLastRowWhenCursorIsHigher() {
        val h = Harness()
        h.vm.sendKey(InputKey.UP)
        h.ackOk(h.keyFrames().last().reqId)
        val last = "Y".repeat(24)
        h.delta("\u001b[12;1H$last\u001b[5;1Hhist-line\u001b[5;10H")
        assertEquals("hist-line", h.vm.resyncDraft?.text)
        val logs = DiagLog.snapshotForTest().joinToString("\n")
        assertTrue(logs.contains("trigger=Up"))
        assertTrue(logs.contains("cursorY=4"))
    }

    private fun tv(text: String) = TextFieldValue(text)

    private fun backspaces(frames: List<InputFrame>): Int =
        frames.sumOf { f -> f.keys.count { it == InputKey.BACKSPACE } }

    private class FakeUploader : AttachmentUploader {
        override fun upload(baseUrl: String, attachment: Attachment): UploadOutcome =
            UploadOutcome.Success("/host/img.png")
    }

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
            vm = SessionViewModel(manager, FakeUploader(), "http://host:0", ref, 12, 24)
            manager.setListener(vm)
        }

        fun sentFrames(): List<FramePayload> =
            transport.sentText.mapNotNull { runCatching { FrameCodec.decode(it) }.getOrNull() }

        fun inputFrames(): List<InputFrame> = sentFrames().filterIsInstance<InputFrame>()
        fun keyFrames(): List<InputFrame> = inputFrames().filter { it.keys.isNotEmpty() }

        fun delta(text: String) = transport.deliverBinary(
            BinaryFrameCodec.encode(BinaryFrame(BinaryKind.DELTA, "s1", text.toByteArray())),
        )

        fun paintCursorLine(row0: Int, text: String) {
            val r = row0 + 1
            val c = text.length + 1
            delta("\u001b[${r};1H$text\u001b[${r};${c}H")
        }

        fun ackOk(reqId: Long) = transport.deliverText(
            """{"v":1,"type":"input_ack","payload":{"req_id":$reqId,"ok":true}}""",
        )
    }
}
