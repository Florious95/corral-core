package dev.agentmirror.app.session

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
 * E3 功能先验红：只用改前已有 API（sendDraft / sendKey / keyFrames）。
 * 改前 sendKey 第一行 `if (inputStatus is Sending) return` 吞掉 Tab，keyFrames 不增。
 * 改后 Sending 中 Tab 仍发出 keys 帧。
 */
class E3NoGatePriorRedTest {

    @Test
    fun sendKeyWhileDraftSendingStillEmits() {
        val h = Harness()
        h.vm.sendDraft()
        assertEquals(InputStatus.Sending, h.vm.inputStatus)
        val before = h.keyFrames().size
        h.vm.sendKey(InputKey.TAB)
        assertEquals(before + 1, h.keyFrames().size)
        assertEquals(listOf(InputKey.TAB), h.keyFrames().last().keys)
        assertEquals(InputStatus.Sending, h.vm.inputStatus)
    }

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
            vm = SessionViewModel(manager, FakeUploader(), "http://host:0", ref, 5, 10)
            manager.setListener(vm)
        }

        fun sentFrames(): List<FramePayload> =
            transport.sentText.mapNotNull { runCatching { FrameCodec.decode(it) }.getOrNull() }

        fun inputFrames(): List<InputFrame> = sentFrames().filterIsInstance<InputFrame>()
        fun keyFrames(): List<InputFrame> = inputFrames().filter { it.keys.isNotEmpty() }
    }
}
