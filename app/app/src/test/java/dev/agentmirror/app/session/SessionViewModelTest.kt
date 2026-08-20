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

import androidx.compose.ui.text.input.TextFieldValue
import dev.agentmirror.app.conn.AttachPreviewFrame
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
        fun attachPreviewFrames(): List<AttachPreviewFrame> = sentFrames().filterIsInstance<AttachPreviewFrame>()

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

    // ---- 直通输入（059）：发送只提交 + 每键直通 ----

    @Test
    fun sendDraft_isBareEnterSubmit_only() {
        // 直通模型（059）：CLI 输入框即草稿，发送键只提交（裸 Enter），不再整条注入文本。
        // 红测：sendDraft 发出的 input 帧 text 必须为空（只回车），且不携带本地草稿文本。
        val h = Harness()
        h.vm.sendDraft()
        assertEquals(InputStatus.Sending, h.vm.inputStatus)
        val sent = h.inputFrames().last()
        assertEquals("", sent.text) // 裸 Enter：text 为空
        assertTrue(sent.keys.isEmpty())
        assertEquals("", sent.attachmentPath)
        h.ackOk(sent.reqId)
        assertEquals(InputStatus.Sent, h.vm.inputStatus)
    }

    @Test
    fun sendDraftAckFailureShowsError() {
        val h = Harness()
        h.vm.sendDraft()
        val sent = h.inputFrames().last()
        h.ackFail(sent.reqId, "session_not_found")
        val st = h.vm.inputStatus
        assertTrue(st is InputStatus.Failed)
        assertTrue((st as InputStatus.Failed).message.contains("会话已不存在"))
    }

    @Test
    fun sendDraftTimeoutShowsError() {
        val h = Harness()
        h.vm.sendDraft()
        h.tick()
        val st = h.vm.inputStatus
        assertTrue(st is InputStatus.Failed)
        assertTrue((st as InputStatus.Failed).message.contains("超时"))
    }

    @Test
    fun sendWhileDisconnectedFailsVisiblyWithoutFrame() {
        val h = Harness()
        h.transport.peerClose(1006, "dropped")
        h.vm.sendDraft()
        assertTrue(h.vm.inputStatus is InputStatus.Failed)
        assertTrue(h.inputFrames().isEmpty())
    }

    @Test
    fun passthroughTypedChar_sendsOneCharText() {
        // 直通（059）：每个按键单独直通到 CLI 输入框（不回车，text 单字符）。
        val h = Harness()
        h.vm.onPassthroughInput(tv(""), tv("l"))
        h.vm.onPassthroughInput(tv("l"), tv("ls"))
        val sent = h.inputFrames()
        assertEquals(2, sent.size)
        assertEquals("l", sent[0].text)
        assertTrue(sent[0].keys.isEmpty())
        assertEquals("s", sent[1].text)
        // 直通不占发送闸：inputStatus 不进入 Sending。
        assertEquals(InputStatus.Idle, h.vm.inputStatus)
    }

    @Test
    fun passthroughDelete_sendsBackspaceKey() {
        // 直通（059）+ 084：先同步到 "ls"，再删末字 → 1 次 backspace（行尾退格）。
        val h = Harness()
        h.vm.onPassthroughInput(tv(""), tv("ls"))
        val before = h.inputFrames().size
        h.vm.onPassthroughInput(tv("ls"), tv("l"))
        val sent = h.inputFrames().drop(before)
        assertEquals(1, sent.size)
        // 删除 = keys=[backspace]（服务端 SendKeys 映射 tmux BSpace，不回车）。
        assertTrue(sent[0].text.isEmpty())
        assertEquals(listOf(InputKey.BACKSPACE), sent[0].keys)
    }

    // ---- 快捷键条（R-1，017）：keys 帧 + 必达回执 ----

    @Test
    fun sendKeyEmitsKeysFrameWithoutText() {
        // VM 层红测（R-1）：点按键条 → 发出的 input 帧 keys 字段正确且无 text（互斥）。
        // E3：控制键不进发送闸。
        val h = Harness()
        h.vm.sendKey(InputKey.ESC)
        assertEquals(InputStatus.Idle, h.vm.inputStatus)
        assertEquals(InputStatus.Sending, h.vm.controlKeyStatus)
        val keys = h.keyFrames()
        assertEquals(1, keys.size)
        assertEquals("s1", keys[0].ref)
        assertEquals("", keys[0].text) // keys 帧不得携带 text（契约 §4.2 互斥）
        assertEquals(listOf(InputKey.ESC), keys[0].keys)
    }

    @Test
    fun sendKeyAckOkShowsSent() {
        // keys 回执 ok：显示已发送（直通模型下无本地草稿概念，只断言回执与无 text 帧）。
        val h = Harness()
        h.vm.sendKey(InputKey.CTRL_C)
        val sent = h.keyFrames().last()
        h.ackOk(sent.reqId)
        assertEquals(InputStatus.Idle, h.vm.inputStatus)
        assertEquals(InputStatus.Sent, h.vm.controlKeyStatus)
        // 未发出任何 text 帧（keys 帧互斥 text）。
        assertTrue(h.inputFrames().none { it.keys.isEmpty() })
    }

    @Test
    fun sendKeyFailureKeepsDraftAndShowsError() {
        // keys 帧失败回执：输入框保留内容 + 明确报错（003 发送必达，不静默）。
        val h = Harness()
        h.vm.sendKey(InputKey.TAB)
        val sent = h.keyFrames().last()
        h.ackFail(sent.reqId, "session_not_found")
        assertEquals(InputStatus.Idle, h.vm.inputStatus)
        val st = h.vm.controlKeyStatus
        assertTrue(st is InputStatus.Failed)
        assertTrue((st as InputStatus.Failed).message.contains("会话已不存在"))
    }

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

    @Test
    fun sendKeyWhileDisconnectedFailsVisiblyWithoutFrame() {
        // 未就绪点按：明确报错、不发帧。
        val h = Harness()
        h.transport.peerClose(1006, "dropped")
        h.vm.sendKey(InputKey.UP)
        assertTrue(h.vm.controlKeyStatus is InputStatus.Failed)
        assertEquals(InputStatus.Idle, h.vm.inputStatus)
        assertTrue(h.keyFrames().isEmpty())
    }

    // ---- R-2 多行不拆分（017 裁定）----

    @Test
    fun passthroughMultiline_typesEachLineDelta() {
        // 直通（059）：多行文本经 onPassthroughInput 逐键/逐段直通（不回车），不再是
        // sendDraft 的一次性整条注入。每段 text 是增量而非整条草稿。
        val h = Harness()
        h.vm.onPassthroughInput(tv(""), tv("line one\n"))
        h.vm.onPassthroughInput(tv("line one\n"), tv("line one\nline two"))
        val sent = h.inputFrames()
        assertEquals(2, sent.size)
        assertEquals("line one\n", sent[0].text)
        assertEquals("line two", sent[1].text)
        assertTrue(sent.all { it.keys.isEmpty() })
    }

    // ---- 附件管线（003 附加输入能力 / 需求 042：不填入输入框文本 / feat-image-upload-inline：
    //      路径走独立 attachment_path 字段，不拼进 text，回炉记录见类注释）----

    @Test
    fun attachmentUploadDoesNotTouchTextAndSendsPreviewImmediately() {
        // 需求 042 + 直通（059）：上传成功后路径记入 pendingAttachmentPaths，不掺入直通文本
        // （本地无草稿字段）。需求 057：上传成功那一刻立刻发 AttachPreviewFrame 贴进 CLI pane。
        val h = Harness()
        h.vm.uploadAttachment(Attachment("a.png", "image/png", byteArrayOf(1, 2)))
        assertTrue(h.vm.uploadStatus is UploadStatus.Success)
        // 路径记在独立状态里（不掺入任何直通文本帧）。
        assertEquals(listOf("/host/img.png"), h.vm.pendingAttachmentPaths)
        assertEquals("http://host:0", h.uploader.lastBaseUrl)
        assertEquals("a.png", h.uploader.lastAttachment?.name)

        val previews = h.attachPreviewFrames()
        assertEquals(1, previews.size)
        assertEquals("s1", previews[0].ref)
        assertEquals("/host/img.png", previews[0].path)
    }

    @Test
    fun uploadFailureSurfacesErrorAndSendsNoPreview() {
        val h = Harness()
        h.uploader.result = UploadOutcome.Failure("HTTP 500")
        h.vm.uploadAttachment(Attachment("a.png", "image/png", byteArrayOf(1)))
        assertTrue(h.vm.uploadStatus is UploadStatus.Failed)
        assertEquals(emptyList<String>(), h.vm.pendingAttachmentPaths) // 失败不留下半个附件
        assertTrue("上传失败不应发预贴帧", h.attachPreviewFrames().isEmpty())
    }

    @Test
    fun secondUploadAccumulatesRatherThanOverwrites() {
        // 需求 057 第 4 款：附件语义从"单附件覆盖"改为"可累加"——连选两张就是两张，
        // 两张都已经各自贴进 pane 了（各发一次 AttachPreviewFrame），不是"后选覆盖前选"。
        val h = Harness()
        h.vm.uploadAttachment(Attachment("a.png", "image/png", byteArrayOf(1)))
        h.uploader.result = UploadOutcome.Success("/host/img2.png")
        h.vm.uploadAttachment(Attachment("b.png", "image/png", byteArrayOf(2)))

        assertEquals(listOf("/host/img.png", "/host/img2.png"), h.vm.pendingAttachmentPaths)
        val previews = h.attachPreviewFrames()
        assertEquals(2, previews.size)
        assertEquals("/host/img.png", previews[0].path)
        assertEquals("/host/img2.png", previews[1].path)
    }

    @Test
    fun sendDraftWithAttachmentSendsSeparateFieldNotSplicedText() {
        // 直通（059）+ feat-image-upload-inline：提交 = 裸 Enter（text 空），路径走
        // input 帧独立 attachment_path 字段——不拼进 text、不强加换行（上一版把两者拼进
        // 同一条含 \n 的 text 一次性粘贴会撞粘贴时序竞态，见 fix-image-upload-input-box）。
        val h = Harness()
        h.vm.uploadAttachment(Attachment("a.png", "image/png", byteArrayOf(1)))
        h.vm.sendDraft()
        val sent = h.inputFrames()
        assertEquals(1, sent.size)
        assertEquals("", sent[0].text) // 裸 Enter：text 为空（不注入草稿）
        assertFalse("text 不应含路径", sent[0].text.contains("/host/img.png"))
        assertEquals("/host/img.png", sent[0].attachmentPath) // 路径走独立字段
    }

    @Test
    fun sendDraftWithMultipleAttachmentsUsesMostRecentPathForField() {
        // 累加多张时，input 帧的 attachment_path 只带最新一次预贴的路径——服务端只需要
        // 最新那次的时间戳核对沉降补差额（其余早前的图已经各自贴在 pane 里了，不需要
        // 再逐张确认）。
        val h = Harness()
        h.vm.uploadAttachment(Attachment("a.png", "image/png", byteArrayOf(1)))
        h.uploader.result = UploadOutcome.Success("/host/img2.png")
        h.vm.uploadAttachment(Attachment("b.png", "image/png", byteArrayOf(2)))
        h.vm.sendDraft()
        val sent = h.inputFrames()
        assertEquals(1, sent.size)
        assertEquals("/host/img2.png", sent[0].attachmentPath)
    }

    @Test
    fun sendDraftWithOnlyAttachmentSendsEmptyTextAndPath() {
        // 没打字、只发图：text 是空串，attachment_path 是路径——服务端据此只发 Enter
        // + 沉降（预贴路径已在 CLI pane）。
        val h = Harness()
        h.vm.uploadAttachment(Attachment("a.png", "image/png", byteArrayOf(1)))
        h.vm.sendDraft()
        val sent = h.inputFrames()
        assertEquals(1, sent.size)
        assertEquals("", sent[0].text)
        assertEquals("/host/img.png", sent[0].attachmentPath)
    }

    @Test
    fun plainTextWithoutAttachmentSendsEmptyAttachmentPath() {
        // 不倒退：没有附件时，提交 = 裸 Enter（text 空），attachment_path 为空。
        val h = Harness()
        h.vm.sendDraft()
        val sent = h.inputFrames()
        assertEquals(1, sent.size)
        assertEquals("", sent[0].text)
        assertEquals("", sent[0].attachmentPath)
    }

    @Test
    fun attachmentIsClearedAfterSuccessfulSendAndNotResentNextMessage() {
        // 不倒退：附件状态在提交成功后清空，不会跟着下一条消息重复发出。
        val h = Harness()
        h.vm.uploadAttachment(Attachment("a.png", "image/png", byteArrayOf(1)))
        h.vm.sendDraft()
        val first = h.inputFrames().single()
        h.ackOk(first.reqId)
        assertEquals(emptyList<String>(), h.vm.pendingAttachmentPaths) // 提交成功后附件已清空

        h.vm.sendDraft()
        val sent = h.inputFrames()
        assertEquals(2, sent.size)
        assertEquals("", sent[1].attachmentPath) // 第二条不含第一次的附件路径
    }

    @Test
    fun attachmentSurvivesSendFailureAndIsResent() {
        // leader 独立变异逮到的缺口：KDoc（sendDraft）写了"发送失败保留附件，可重发"，
        // 必须有断言盯着——重发那一帧的 attachment_path 确实又带上了这个路径。
        val h = Harness()
        h.vm.uploadAttachment(Attachment("a.png", "image/png", byteArrayOf(1)))
        h.vm.sendDraft()
        val first = h.inputFrames().single()
        h.ackFail(first.reqId, "inject_failed")
        assertTrue(h.vm.inputStatus is InputStatus.Failed)
        assertEquals(listOf("/host/img.png"), h.vm.pendingAttachmentPaths) // 附件没被静默丢掉

        h.vm.sendDraft() // 重发：同一份附件
        val sent = h.inputFrames()
        assertEquals(2, sent.size)
        assertEquals("/host/img.png", sent[1].attachmentPath) // 重发的帧里附件真的又带上了
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

    // ---- 夹具 ----

    /** TextFieldValue 便捷构造（无组合区）。 */
    private fun tv(text: String) = TextFieldValue(text)
}
