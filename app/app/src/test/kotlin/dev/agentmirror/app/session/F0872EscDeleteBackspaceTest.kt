package dev.agentmirror.app.session

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
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F-087-2 功能先验红：只用改前已有的 API（sendKey / ack / delta / onPassthroughInput）。
 *
 * 改前：ESC 改写了远端行，syncedText 仍是空串 → DiffSync.plan("","") → BackSpace 0。
 * 改后：光标锚定回读把 syncedText 校正为 "prev-command" → 删到空发出 12 次 BackSpace。
 */
class F0872EscDeleteBackspaceTest {

    @Test
    fun escThenDelete_sendsTwelveBackspacesAgainstRemotePrevCommand() {
        val h = Harness()
        h.vm.sendKey(InputKey.ESC)
        h.ackOk(h.keyFrames().last().reqId)
        h.paintCursorLine(row0 = 10, text = "prev-command")
        val before = h.inputFrames().size
        h.vm.onPassthroughInput(tv("prev-command"), tv(""))
        assertEquals(12, backspaces(h.inputFrames().drop(before)))
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
