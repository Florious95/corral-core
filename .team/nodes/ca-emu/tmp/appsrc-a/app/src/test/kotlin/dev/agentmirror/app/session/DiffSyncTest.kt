package dev.agentmirror.app.session

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 084 输入框差分同步：算法 + VM 发出的按键在行尾 CLI 上重放后与本地相等。
 */
class DiffSyncTest {

    @Test
    fun DiffSync_append_zeroBackspaces_keySeqMatchesOldPassthrough() {
        val p1 = DiffSync.plan("", "l")
        assertEquals(0, p1.backspaces)
        assertEquals("l", p1.typed)
        val p2 = DiffSync.plan("l", "ls")
        assertEquals(0, p2.backspaces)
        assertEquals("s", p2.typed)
        val p3 = DiffSync.plan("ls", "ls ")
        assertEquals(0, p3.backspaces)
        assertEquals(" ", p3.typed)
    }

    @Test
    fun DiffSync_editMiddle_cliBufferEqualsLocal() {
        val local = StringBuilder("hello")
        val cli = StringBuilder("hello")
        // 删中间 e，再插入 a → hallo
        local.deleteAt(1)
        DiffSync.applyTo(cli, DiffSync.plan(cli.toString(), local.toString()))
        local.insert(1, 'a')
        DiffSync.applyTo(cli, DiffSync.plan(cli.toString(), local.toString()))
        assertEquals("hallo", local.toString())
        assertEquals(local.toString(), cli.toString())
    }

    @Test
    fun DiffSync_midEdit_keyCountMeasured() {
        val synced = "hello world"
        val current = "hello World"
        val plan = DiffSync.plan(synced, current)
        assertEquals(5, plan.backspaces)
        assertEquals("World", plan.typed)
        assertEquals(10, plan.keyCount)
        val long = "x".repeat(40)
        val edited = long.substring(0, 10) + "Y" + long.substring(11)
        val p2 = DiffSync.plan(long, edited)
        assertEquals(30, p2.backspaces)
        assertEquals(30, p2.typed.length)
        assertEquals(60, p2.keyCount)
    }

    @Test
    fun DiffSync_vmAppend_zeroBackspaceFrames_sameCharsAsTyped() {
        val h = Harness()
        h.vm.onPassthroughInput(tv(""), tv("l"))
        h.vm.onPassthroughInput(tv("l"), tv("ls"))
        val sent = h.inputFrames()
        assertEquals(2, sent.size)
        assertTrue(sent.all { it.keys.isEmpty() })
        assertEquals("l", sent[0].text)
        assertEquals("s", sent[1].text)
        assertEquals("ls", replayCli(sent))
    }

    @Test
    fun DiffSync_vmEditMiddle_finalTextEqualOnBothSides() {
        val h = Harness()
        h.vm.onPassthroughInput(tv(""), tv("hello"))
        h.vm.onPassthroughInput(tv("hello"), tv("hllo"))
        h.vm.onPassthroughInput(tv("hllo"), tv("hallo"))
        val cli = replayCli(h.inputFrames())
        assertEquals("hallo", cli)
    }

    @Test
    fun DiffSync_imeComposition_emitsZeroKeysUntilCommit() {
        val h = Harness()
        val composing = TextFieldValue("ni", TextRange(2), TextRange(0, 2))
        h.vm.onPassthroughInput(tv(""), composing)
        assertTrue(h.inputFrames().isEmpty())
        val still = TextFieldValue("nih", TextRange(3), TextRange(0, 3))
        h.vm.onPassthroughInput(composing, still)
        assertTrue(h.inputFrames().isEmpty())
        val committed = TextFieldValue("你", TextRange(1), composition = null)
        h.vm.onPassthroughInput(still, committed)
        val sent = h.inputFrames()
        assertEquals(1, sent.size)
        assertEquals("你", sent[0].text)
        assertEquals("你", replayCli(sent))
    }

    @Test
    fun DiffSync_englishChar_emitsInSameCall_noExtraDelay() {
        val h = Harness()
        h.vm.onPassthroughInput(tv(""), tv("a"))
        // 返回即已入传输队列：无 Handler / delay / 合并窗。
        assertEquals(1, h.inputFrames().size)
        assertEquals("a", h.inputFrames()[0].text)
    }

    @Test
    fun DiffSync_sendDraft_resetsSyncedSoNextCharIsAppend() {
        val h = Harness()
        h.vm.onPassthroughInput(tv(""), tv("ls"))
        h.vm.sendDraft()
        val before = h.inputFrames().size
        h.vm.onPassthroughInput(tv(""), tv("a"))
        val after = h.inputFrames().drop(before)
        assertEquals(1, after.size)
        assertTrue(after[0].keys.isEmpty())
        assertEquals("a", after[0].text)
    }

    private fun tv(text: String) = TextFieldValue(text)

    private fun replayCli(frames: List<InputFrame>): String {
        val cli = StringBuilder()
        for (f in frames) {
            if (f.keys.isNotEmpty()) {
                for (k in f.keys) {
                    if (k == InputKey.BACKSPACE && cli.isNotEmpty()) cli.deleteAt(cli.lastIndex)
                }
            } else if (f.text.isNotEmpty()) {
                cli.append(f.text)
            }
        }
        return cli.toString()
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
    }
}
