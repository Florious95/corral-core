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

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import dev.agentmirror.app.conn.ResizeFrame
import dev.agentmirror.app.conn.ScrollbackFrame
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SessionViewModel 测试：003 四标准在会话页的落地面 + 006 本地滚动补页 + 附件管线。
 *
 * 经验基四条（session-ui 知识基底 §4）：snapshot 走 replaySnapshot 而非 feed、
 * scrollback 按实际区间 prependHistory、input_ack ok 清框 / 超时与失败保留输入+报错、
 * 附件 path 插入光标处——各一条。
 */
class SessionViewModelTest {

    /** 假上传器：脚本化结果，记录收到的附件与 baseUrl。 */
    private class FakeUploader(
        var result: UploadOutcome = UploadOutcome.Success("/host/img.png"),
    ) : AttachmentUploader {
        var lastBaseUrl: String? = null
        var lastAttachment: Attachment? = null
        override fun upload(baseUrl: String, attachment: Attachment): UploadOutcome {
            lastBaseUrl = baseUrl
            lastAttachment = attachment
            return result
        }
    }

    /** 测试夹具：READY 的 ConnectionManager + 已构造的 VM（订阅已发出）。 */
    private class Harness(ref: String = "s1", rows: Int = 5, cols: Int = 10) {
        val clock = FakeClock()
        val transport = FakeWebSocketTransport()
        val uploader = FakeUploader()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { transport },
            clock = clock,
        )
        lateinit var vm: SessionViewModel
        val emulator: TerminalEmulator

        init {
            manager.start()
            // 假传输同步 onOpen ⇒ auth 已发出；auth_ack ok ⇒ READY。
            transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
            vm = SessionViewModel(manager, uploader, "http://host:0", ref, rows, cols)
            // 测试自建 manager：显式把 VM 挂为监听（生产经接线层 uiConnector 扇出路由，见 VM KDoc）。
            manager.setListener(vm)
            emulator = vm.emulator
        }

        fun sentFrames(): List<FramePayload> =
            transport.sentText.mapNotNull { runCatching { FrameCodec.decode(it) }.getOrNull() }

        fun inputFrames(): List<InputFrame> = sentFrames().filterIsInstance<InputFrame>()
        fun keyFrames(): List<InputFrame> = inputFrames().filter { it.keys.isNotEmpty() }
        fun scrollbackFrames(): List<ScrollbackFrame> = sentFrames().filterIsInstance<ScrollbackFrame>()
        fun resizeFrames(): List<ResizeFrame> = sentFrames().filterIsInstance<ResizeFrame>()

        fun snap(text: String) = transport.deliverBinary(
            BinaryFrameCodec.encode(BinaryFrame(BinaryKind.SNAPSHOT, "s1", text.toByteArray())),
        )

        fun delta(text: String) = transport.deliverBinary(
            BinaryFrameCodec.encode(BinaryFrame(BinaryKind.DELTA, "s1", text.toByteArray())),
        )

        fun scrollbackReply(text: String, fromLine: Int, lineCount: Long) = transport.deliverBinary(
            BinaryFrameCodec.encode(
                BinaryFrame(BinaryKind.SCROLLBACK, "s1", text.toByteArray(), reqId = 1, fromLine = fromLine, lineCount = lineCount),
            ),
        )

        fun ackOk(reqId: Long) = transport.deliverText(
            """{"v":1,"type":"input_ack","payload":{"req_id":$reqId,"ok":true}}""",
        )

        fun ackFail(reqId: Long, reason: String) = transport.deliverText(
            """{"v":1,"type":"input_ack","payload":{"req_id":$reqId,"ok":false,"reason":"$reason"}}""",
        )

        /** 推进假时钟越过 input 超时并触发超时裁决（生产宿主节奏）。 */
        fun tick() {
            clock.advance(10_001)
            vm.onTick(clock.nowMs())
        }
    }

    /** 屏幕第 [row] 行的可见文本（去尾部空白）。 */
    private fun text(row: List<dev.agentmirror.terminal.Cell>): String =
        row.joinToString("") { it.text }.trimEnd()

    // ---- 镜像流：snapshot 重放 / delta 追加 / scrollback 头插 ----

    @Test
    fun snapshotReplaysGridNotAppends() {
        val h = Harness()
        h.delta("hello")
        h.snap("hi")
        // replaySnapshot 是清屏重建：残留的 "hello" 不得留在首行。
        assertEquals("hi", text(h.emulator.snapshot().lines[0]))
    }

    @Test
    fun deltaFeedsEmulatorAppending() {
        val h = Harness()
        h.snap("ab")
        h.delta("cd")
        assertEquals("abcd", text(h.emulator.snapshot().lines[0]))
    }

    @Test
    fun firstSnapshotPrefetchesHistory() {
        val h = Harness()
        h.snap("x")
        // 006 秒开：打开即预取最近几百行历史。
        val sb = h.scrollbackFrames()
        assertEquals(1, sb.size)
        assertEquals(-400, sb[0].fromLine)
        assertEquals(400L, sb[0].count)
    }

    @Test
    fun scrollbackReplyPrependsActualRange() {
        val h = Harness()
        h.snap("screen")
        h.scrollbackReply("old1\r\nold2\r\n", fromLine = -2, lineCount = 2)
        // 按实际区间头插：old1 为最老，接在屏幕上方。
        assertEquals(2, h.emulator.scrollback.size)
        assertEquals("old1", text(h.emulator.scrollback.line(0)))
        assertEquals("old2", text(h.emulator.scrollback.line(1)))
    }

    @Test
    fun clampedScrollbackStopsPaging() {
        val h = Harness()
        h.snap("x")
        // 请求 -400 但服务端收敛到 -1（仅 1 行历史）⇒ 到顶，不再拉更老页。
        h.scrollbackReply("only\r\n", fromLine = -1, lineCount = 1)
        assertFalse(h.vm.hasMoreHistory)
        h.vm.requestOlderHistoryPage()
        assertEquals(1, h.scrollbackFrames().size) // 不叠发
    }

    @Test
    fun olderPageRequestsFromLastAnchor() {
        val h = Harness()
        h.snap("x")
        // 首页如实从 -400 返回 ⇒ 上方还有历史。
        h.scrollbackReply("a\r\nb\r\n", fromLine = -400, lineCount = 400)
        assertTrue(h.vm.hasMoreHistory)
        h.vm.requestOlderHistoryPage()
        val sb = h.scrollbackFrames()
        assertEquals(-800, sb.last().fromLine)
        assertEquals(400L, sb.last().count)
    }

    @Test
    fun historyTopFlagAfterScrollingToBoundary() {
        val h = Harness(rows = 2, cols = 5)
        // 制造 scrollback=[a,b]、屏幕=[c,d]。
        h.emulator.feed("a\r\nb\r\nc\r\nd")
        h.vm.presenter.onScrollBy(2)
        h.vm.syncFromPresenter()
        // 滚到历史顶：可补页 + 显示回到底部。
        assertTrue(h.vm.atHistoryTop)
        assertTrue(h.vm.showBackToBottom)
    }

    // ---- 发送必达（003 第二条）----

    @Test
    fun inputAckOkClearsDraftAndShowsSent() {
        val h = Harness()
        h.vm.textFieldValue = TextFieldValue("ls -la")
        h.vm.sendDraft()
        assertEquals(InputStatus.Sending, h.vm.inputStatus)
        val sent = h.inputFrames().last()
        assertEquals("ls -la", sent.text)
        h.ackOk(sent.reqId)
        // 回执可见：ok ⇒ 清输入框 + 显示已发送。
        assertEquals(InputStatus.Sent, h.vm.inputStatus)
        assertEquals("", h.vm.textFieldValue.text)
    }

    @Test
    fun inputAckFailureKeepsDraftAndShowsError() {
        val h = Harness()
        h.vm.textFieldValue = TextFieldValue("ls")
        h.vm.sendDraft()
        val sent = h.inputFrames().last()
        h.ackFail(sent.reqId, "session_not_found")
        // 失败明确报错：输入框保留内容。
        val st = h.vm.inputStatus
        assertTrue(st is InputStatus.Failed)
        assertTrue((st as InputStatus.Failed).message.contains("会话已不存在"))
        assertEquals("ls", h.vm.textFieldValue.text)
    }

    @Test
    fun inputTimeoutKeepsDraftAndShowsError() {
        val h = Harness()
        h.vm.textFieldValue = TextFieldValue("slow")
        h.vm.sendDraft()
        h.tick()
        val st = h.vm.inputStatus
        assertTrue(st is InputStatus.Failed)
        assertTrue((st as InputStatus.Failed).message.contains("超时"))
        assertEquals("slow", h.vm.textFieldValue.text)
    }

    @Test
    fun sendWhileDisconnectedFailsVisiblyWithoutFrame() {
        val h = Harness()
        h.transport.peerClose(1006, "dropped")
        h.vm.textFieldValue = TextFieldValue("x")
        h.vm.sendDraft()
        assertTrue(h.vm.inputStatus is InputStatus.Failed)
        assertTrue(h.inputFrames().isEmpty())
    }

    // ---- 快捷键条（R-1，017）：keys 帧 + 必达回执 ----

    @Test
    fun sendKeyEmitsKeysFrameWithoutText() {
        // VM 层红测（R-1）：点按键条 → 发出的 input 帧 keys 字段正确且无 text（互斥）。
        val h = Harness()
        h.vm.sendKey(InputKey.ESC)
        assertEquals(InputStatus.Sending, h.vm.inputStatus)
        val keys = h.keyFrames()
        assertEquals(1, keys.size)
        assertEquals("s1", keys[0].ref)
        assertEquals("", keys[0].text) // keys 帧不得携带 text（契约 §4.2 互斥）
        assertEquals(listOf(InputKey.ESC), keys[0].keys)
    }

    @Test
    fun sendKeyAckOkKeepsDraft() {
        // keys 回执 ok：只显示已发送，不动草稿（用户点 Esc/Ctrl-C 打断时往往正打着字）。
        val h = Harness()
        h.vm.textFieldValue = TextFieldValue("ls -la")
        h.vm.sendKey(InputKey.CTRL_C)
        val sent = h.keyFrames().last()
        h.ackOk(sent.reqId)
        assertEquals(InputStatus.Sent, h.vm.inputStatus)
        assertEquals("ls -la", h.vm.textFieldValue.text) // 草稿保留
        // 未发出任何 text 帧。
        assertTrue(h.inputFrames().none { it.keys.isEmpty() })
    }

    @Test
    fun sendKeyFailureKeepsDraftAndShowsError() {
        // keys 帧失败回执：输入框保留内容 + 明确报错（003 发送必达，不静默）。
        val h = Harness()
        h.vm.sendKey(InputKey.TAB)
        val sent = h.keyFrames().last()
        h.ackFail(sent.reqId, "session_not_found")
        val st = h.vm.inputStatus
        assertTrue(st is InputStatus.Failed)
        assertTrue((st as InputStatus.Failed).message.contains("会话已不存在"))
    }

    @Test
    fun sendKeyWhileDisconnectedFailsVisiblyWithoutFrame() {
        // 未就绪点按：明确报错、不发帧。
        val h = Harness()
        h.transport.peerClose(1006, "dropped")
        h.vm.sendKey(InputKey.UP)
        assertTrue(h.vm.inputStatus is InputStatus.Failed)
        assertTrue(h.keyFrames().isEmpty())
    }

    // ---- R-2 多行不拆分（017 裁定）----

    @Test
    fun multilineTextSentAsSingleFrame() {
        // R-2：含 \n 的输入**不拆分**，整段一条 input.text 发送（服务端 paste-buffer -p
        // 括号粘贴路径处理）。锁定防回归：禁止出现按行拆分的多次 send。
        val h = Harness()
        val multiline = "line one\nline two\nline three"
        h.vm.textFieldValue = TextFieldValue(multiline)
        h.vm.sendDraft()
        val sent = h.inputFrames()
        assertEquals(1, sent.size) // 单帧不拆分
        assertEquals(multiline, sent[0].text) // 整段原样（含换行）
        assertTrue(sent[0].keys.isEmpty())
        h.ackOk(sent[0].reqId)
        assertEquals(InputStatus.Sent, h.vm.inputStatus)
    }

    // ---- 附件管线（003 附加输入能力 / 需求 042：不填入输入框文本）----

    @Test
    fun attachmentUploadDoesNotTouchDraftText() {
        // 需求 042：上传成功后，路径记入 pendingAttachmentPath，textFieldValue 保持用户原样草稿——
        // 不出现路径字符串。这条是本轮红测的第一条（改动前会红：旧实现把路径拼进了 textFieldValue）。
        val h = Harness()
        h.vm.textFieldValue = TextFieldValue("see ", TextRange(4))
        h.vm.uploadAttachment(Attachment("a.png", "image/png", byteArrayOf(1, 2)))
        assertTrue(h.vm.uploadStatus is UploadStatus.Success)
        assertEquals("see ", h.vm.textFieldValue.text) // 草稿一字未变
        assertFalse(h.vm.textFieldValue.text.contains("/host/img.png")) // 路径没有落进可见文本
        assertEquals("/host/img.png", h.vm.pendingAttachmentPath) // 路径记在独立状态里
        assertEquals("http://host:0", h.uploader.lastBaseUrl)
        assertEquals("a.png", h.uploader.lastAttachment?.name)
    }

    @Test
    fun uploadFailureSurfacesErrorAndKeepsDraftAndAttachment() {
        val h = Harness()
        h.uploader.result = UploadOutcome.Failure("HTTP 500")
        h.vm.textFieldValue = TextFieldValue("keep")
        h.vm.uploadAttachment(Attachment("a.png", "image/png", byteArrayOf(1)))
        assertTrue(h.vm.uploadStatus is UploadStatus.Failed)
        assertEquals("keep", h.vm.textFieldValue.text)
        assertEquals(null, h.vm.pendingAttachmentPath) // 失败不留下半个附件
    }

    @Test
    fun sendDraftWithAttachmentSplicesPathWithNewline() {
        // 发送时才把路径带换行拼进最终 input.text——命中 R-2 多行分支，服务端
        // pasteMultiline/paste-buffer -d -p 由 tmux 插入 bracketed-paste 标记，
        // Claude Code 据此把路径识别成一次粘贴、内联为 [Image #N]（fix-image-upload-input-box 探针实证）。
        val h = Harness()
        h.vm.textFieldValue = TextFieldValue("look at this")
        h.vm.uploadAttachment(Attachment("a.png", "image/png", byteArrayOf(1)))
        h.vm.sendDraft()
        val sent = h.inputFrames()
        assertEquals(1, sent.size)
        assertTrue("最终 text 必须含换行", sent[0].text.contains("\n"))
        assertTrue("最终 text 必须含附件路径", sent[0].text.contains("/host/img.png"))
        assertEquals("look at this\n/host/img.png", sent[0].text)
    }

    @Test
    fun sendDraftWithOnlyAttachmentTrimsToBarePath() {
        // 没打字、只发图：拼出来的文本 trim 后必须就是纯路径——这是 Claude Code 粘贴路径
        // 识别（L5S 正则）成立的前提，若混进其它字符会命不中，退化成裸文本粘贴。
        val h = Harness()
        h.vm.uploadAttachment(Attachment("a.png", "image/png", byteArrayOf(1)))
        h.vm.sendDraft()
        val sent = h.inputFrames()
        assertEquals(1, sent.size)
        assertEquals("/host/img.png", sent[0].text.trim())
    }

    @Test
    fun plainTextWithoutAttachmentSendsUnmodified() {
        // 不倒退：没有附件时，普通文本消息原样发出，不被强加换行——防止②被写成一刀切。
        val h = Harness()
        h.vm.textFieldValue = TextFieldValue("ls -la")
        h.vm.sendDraft()
        val sent = h.inputFrames()
        assertEquals(1, sent.size)
        assertEquals("ls -la", sent[0].text)
        assertFalse("无附件消息不应含换行", sent[0].text.contains("\n"))
    }

    @Test
    fun attachmentIsClearedAfterSuccessfulSendAndNotResentNextMessage() {
        // 不倒退：附件状态在发送成功后清空，不会跟着下一条消息重复发出。
        val h = Harness()
        h.vm.textFieldValue = TextFieldValue("first")
        h.vm.uploadAttachment(Attachment("a.png", "image/png", byteArrayOf(1)))
        h.vm.sendDraft()
        val first = h.inputFrames().single()
        h.ackOk(first.reqId)
        assertEquals(null, h.vm.pendingAttachmentPath) // 发送成功后附件已清空

        h.vm.textFieldValue = TextFieldValue("second")
        h.vm.sendDraft()
        val sent = h.inputFrames()
        assertEquals(2, sent.size)
        assertEquals("second", sent[1].text) // 第二条不含第一次的附件路径
    }

    @Test
    fun attachmentSurvivesSendFailureAndIsResent() {
        // leader 独立变异逮到的缺口：KDoc（sendDraft :262）写了"发送失败保留附件，可重发"，
        // 但原来没有断言盯着。只查字段非空挡不住"字段还在但 compose 不再用它"，所以第二段
        // 必须真的重发一次、断言拼出来的 text 里确实又带上了这个路径。
        val h = Harness()
        h.vm.textFieldValue = TextFieldValue("retry me")
        h.vm.uploadAttachment(Attachment("a.png", "image/png", byteArrayOf(1)))
        h.vm.sendDraft()
        val first = h.inputFrames().single()
        h.ackFail(first.reqId, "inject_failed")
        assertTrue(h.vm.inputStatus is InputStatus.Failed)
        assertEquals("/host/img.png", h.vm.pendingAttachmentPath) // 附件没被静默丢掉

        h.vm.sendDraft() // 重发：同一份草稿+附件
        val sent = h.inputFrames()
        assertEquals(2, sent.size)
        assertEquals("retry me\n/host/img.png", sent[1].text) // 重发的帧里附件真的又拼进去了
    }

    // ---- resize（005：让 CLI 自己重画）----

    @Test
    fun seededFontSizeResizeReachesManagerAndEmulator() {
        val h = Harness(rows = 15, cols = 50)
        // feat-font-size-setting-drop-pinch：字号实测值一次性 seed，视口建立时一次算对 ⇒ 12 行 41 列。
        h.vm.presenter.seedCellMetrics(12, 24)
        h.vm.presenter.onViewportSizeChanged(500, 300)
        val r = h.resizeFrames()
        assertEquals(1, r.size)
        assertEquals(12, r[0].rows)
        assertEquals(41, r[0].cols)
        assertEquals(12, h.emulator.rows)
        assertEquals(41, h.emulator.cols)
    }

    // ---- 连接状态映射 ----

    @Test
    fun reconnectStateSurfacesBanner() {
        val h = Harness()
        h.transport.peerClose(1006, "dropped")
        assertEquals(ConnectionState.RECONNECTING, h.vm.connectionState)
        assertTrue(h.vm.connectionBanner!!.contains("重连"))
    }
}
