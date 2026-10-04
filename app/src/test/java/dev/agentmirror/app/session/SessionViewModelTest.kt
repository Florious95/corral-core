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
import dev.agentmirror.app.conn.ScrollWheelFrame
import dev.agentmirror.app.conn.ScrollbackFrame
import dev.agentmirror.app.conn.SubscribeFrame
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
        var lastToken: String? = null
        var uploadCount = 0
        var onUpload: (() -> Unit)? = null
        override fun upload(baseUrl: String, uploadToken: String?, attachment: Attachment): UploadOutcome {
            lastToken = uploadToken
            return upload(baseUrl, attachment)
        }
        override fun upload(baseUrl: String, attachment: Attachment): UploadOutcome {
            lastBaseUrl = baseUrl
            lastAttachment = attachment
            uploadCount++
            onUpload?.invoke()
            return result
        }
    }

    /** 测试夹具：READY 的 ConnectionManager + 已构造的 VM（首订待首次有效视口）。 */
    private class Harness(
        ref: String = "s1", rows: Int = 5, cols: Int = 10, warm: Boolean = false,
        uploadToken: String? = null, uploadBaseUrl: () -> String? = { "http://host:0" },
    ) {
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
            vm = SessionViewModel(
                manager, uploader, "http://host:0", ref, rows, cols,
                uploadToken = uploadToken, liveBaseUrl = uploadBaseUrl, warmSubscribe = warm,
            )
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
        fun subscribeFrames(): List<SubscribeFrame> = sentFrames().filterIsInstance<SubscribeFrame>()
        fun scrollWheelDeltas(): List<Int> = sentFrames().filterIsInstance<ScrollWheelFrame>().map { it.delta }
        fun attachPreviewFrames(): List<AttachPreviewFrame> = sentFrames().filterIsInstance<AttachPreviewFrame>()

        fun snap(text: String) = transport.deliverBinary(
            BinaryFrameCodec.encode(BinaryFrame(BinaryKind.SNAPSHOT, "s1", text.toByteArray())),
        )

        fun delta(text: String) = transport.deliverBinary(
            BinaryFrameCodec.encode(BinaryFrame(BinaryKind.DELTA, "s1", text.toByteArray())),
        )

        fun paneMode(inCopyMode: Boolean) = transport.deliverText(
            """{"v":1,"type":"pane_mode_changed","payload":{"ref":"s1","in_copy_mode":$inCopyMode}}""",
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

    // ---- Issue45: large drafts are files, never terminal text payloads ----

    @Test fun longPasteDoesNotReachTerminalBeforeSend() {
        val h = Harness()
        val draft = "x".repeat(4_000)
        h.vm.onPassthroughInput(tv(""), tv(draft))
        assertTrue("长草稿必须留在本地，不能发送前已全量注入", h.inputFrames().isEmpty())
    }

    @Test fun longDraftUploadsExactUtf8AndSendsOnlyQuotedFileReference() {
        for (sync in listOf(true, false)) {
            val h = Harness()
            h.vm.inputSyncEnabled = sync
            h.uploader.result = UploadOutcome.Success("/host/log files/user's.txt")
            val draft = "日志🙂\r\n".repeat(1_000) + "end\n"
            h.vm.sendDraft(draft)
            val attachment = h.uploader.lastAttachment
            assertTrue("超长文本必须上传", attachment != null)
            assertTrue(attachment!!.name.matches(Regex("upload-text-[A-Za-z0-9-]+\\.txt")))
            assertEquals("text/plain", attachment.mimeType)
            assertEquals(draft, attachment.bytes.toString(Charsets.UTF_8))
            assertEquals("http://host:0", h.uploader.lastBaseUrl)
            val sent = h.inputFrames().last()
            assertEquals("'/host/log files/user'\\''s.txt'\r", sent.text)
            assertTrue(h.inputFrames().none { it.text.contains("日志") })
            assertTrue(h.attachPreviewFrames().isEmpty())
        }
    }

    @Test fun textThresholdIsInclusiveAndShortTextRetainsDirectSend() {
        val short = Harness()
        short.vm.inputSyncEnabled = false
        short.vm.sendDraft("x".repeat(1_999))
        assertEquals(null, short.uploader.lastAttachment)
        assertEquals("x".repeat(1_999) + "\r", short.inputFrames().single().text)
        val long = Harness()
        long.vm.sendDraft("x".repeat(2_000))
        assertTrue(long.uploader.lastAttachment != null)
    }

    @Test fun hundredLinesUploadsWithoutPassthroughEvenBelowCharacterThreshold() {
        val h = Harness()
        val draft = List(100) { "a" }.joinToString("\n")
        h.vm.onPassthroughInput(tv(""), tv(draft))
        assertTrue(h.inputFrames().isEmpty())
        h.vm.sendDraft(draft)
        assertEquals(draft, h.uploader.lastAttachment!!.bytes.toString(Charsets.UTF_8))
    }

    @Test fun longDraftUploadFailureSendsNothingAndShowsFailure() {
        val h = Harness()
        h.uploader.result = UploadOutcome.Failure("网络不可用")
        h.vm.sendDraft("x".repeat(4_000))
        assertTrue(h.inputFrames().isEmpty())
        assertTrue(h.vm.uploadStatus is UploadStatus.Failed)
        assertTrue((h.vm.uploadStatus as UploadStatus.Failed).message.contains("网络不可用"))
    }

    @Test fun longDraftRemovesOnlyPreviouslyMirroredShortPrefix() {
        val h = Harness()
        h.vm.onPassthroughInput(tv(""), tv("ab"))
        val draft = "ab" + "x".repeat(4_000)
        h.vm.onPassthroughInput(tv("ab"), tv(draft))
        h.vm.sendDraft(draft)
        assertEquals(2, h.keyFrames().count { it.keys == listOf(InputKey.BACKSPACE) })
        assertTrue(h.inputFrames().none { it.text.contains("xxx") })
    }

    @Test fun longDraftUploadFailureKeepsMirroredPrefixUntouched() {
        val h = Harness()
        h.vm.onPassthroughInput(tv(""), tv("ab"))
        h.uploader.result = UploadOutcome.Failure("HTTP 507")
        h.vm.sendDraft("ab" + "x".repeat(4_000))
        assertEquals(1, h.inputFrames().size)
        assertEquals("ab", h.inputFrames().single().text)
    }

    @Test fun longDraftUsesPairedCredentialAndCurrentUploadEndpoint() {
        val h = Harness(uploadToken = "fixture-token", uploadBaseUrl = { "http://current:0" })
        assertTrue(h.vm.sendDraft("x".repeat(4_000)))
        assertEquals("http://current:0", h.uploader.lastBaseUrl)
        assertEquals("fixture-token", h.uploader.lastToken)
    }

    @Test fun longDraftKeepsExistingImagePreviewAndDoesNotPreviewTextAsImage() {
        val h = Harness()
        h.vm.uploadAttachment(Attachment("a.png", "image/png", byteArrayOf(1)))
        h.uploader.result = UploadOutcome.Success("/host/upload-text-fixture.txt")
        h.vm.sendDraft("x".repeat(4_000))
        val sent = h.inputFrames().last()
        assertEquals("'/host/upload-text-fixture.txt'", sent.text)
        assertEquals("/host/img.png", sent.attachmentPath)
        assertEquals(listOf("/host/img.png"), h.vm.pendingAttachmentPaths)
        assertEquals(1, h.attachPreviewFrames().size)
    }

    @Test fun longDraftDoesNotUploadAgainWhileUploadIsInProgress() {
        val h = Harness()
        val draft = "x".repeat(4_000)
        h.uploader.onUpload = { assertFalse(h.vm.sendDraft(draft)) }
        assertTrue(h.vm.sendDraft(draft))
        assertEquals(1, h.uploader.uploadCount)
    }

    @Test fun longDraftDisconnectedDuringUploadDoesNotSubmitAndReturnsFailure() {
        val h = Harness()
        h.uploader.onUpload = { h.transport.peerClose(1006, "dropped") }
        assertFalse(h.vm.sendDraft("x".repeat(4_000)))
        assertTrue(h.inputFrames().isEmpty())
        assertTrue(h.vm.inputStatus is InputStatus.Failed)
    }

    @Test fun longDraftUploadFailureCanRetryWithoutSendingOriginalText() {
        val h = Harness()
        val draft = "x".repeat(4_000)
        h.uploader.result = UploadOutcome.Failure("HTTP 507")
        assertFalse(h.vm.sendDraft(draft))
        h.uploader.result = UploadOutcome.Success("/host/retry.txt")
        assertTrue(h.vm.sendDraft(draft))
        assertEquals(2, h.uploader.uploadCount)
        assertEquals("'/host/retry.txt'\r", h.inputFrames().single().text)
    }

    @Test fun longDraftRejectsNonAbsoluteOrMultilineReturnedPath() {
        for (path in listOf("relative.txt", "/host/bad\npath.txt", "/host/bad\rpath.txt")) {
            val h = Harness()
            h.uploader.result = UploadOutcome.Success(path)
            assertFalse(h.vm.sendDraft("x".repeat(4_000)))
            assertTrue(h.vm.uploadStatus is UploadStatus.Failed)
            assertTrue(h.inputFrames().isEmpty())
        }
    }

    @Test fun paragraphsBelowThresholdNeverUploadForEitherSyncMode() {
        for (sync in listOf(true, false)) for (size in listOf(101, 200, 500, 1_999)) {
            val h = Harness()
            h.vm.inputSyncEnabled = sync
            val text = "x".repeat(size)
            h.vm.onPassthroughInput(tv(""), tv(text))
            assertEquals(if (sync) 1 else 0, h.inputFrames().size)
            assertTrue(h.vm.sendDraft(text))
            assertEquals(null, h.uploader.lastAttachment)
            assertEquals(if (sync) "" else "$text\r", h.inputFrames().last().text)
        }
    }

    @Test fun hundredUnitEditsStayLiveUntilCumulativeThresholdThenOnlyFileIsSent() {
        val h = Harness()
        var previous = ""
        for (n in 100..4_500 step 100) {
            val next = "x".repeat(n)
            h.vm.onPassthroughInput(tv(previous), tv(next))
            previous = next
        }
        assertEquals(19, h.inputFrames().size)
        assertTrue(h.inputFrames().all { it.text == "x".repeat(100) })
        h.vm.sendDraft(previous)
        assertEquals(1_900, h.keyFrames().size)
        assertEquals(previous, h.uploader.lastAttachment!!.bytes.toString(Charsets.UTF_8))
        assertEquals("'/host/img.png'\r", h.inputFrames().last().text)
    }

    @Test fun singleKeyMiddleEditRemainsOrdinaryText() {
        val h = Harness()
        val before = "a".repeat(200)
        h.vm.onPassthroughInput(tv(""), tv(before))
        val after = "a".repeat(50) + "b" + "a".repeat(150)
        h.vm.onPassthroughInput(tv(before), tv(after))
        h.vm.sendDraft(after)
        assertEquals(null, h.uploader.lastAttachment)
        assertEquals("", h.inputFrames().last().text)
    }

    @Test fun clearingLongDraftRestoresOrdinaryShortTyping() {
        val h = Harness()
        h.vm.onPassthroughInput(tv(""), tv("x".repeat(2_000)))
        h.vm.onPassthroughInput(tv("x".repeat(2_000)), tv(""))
        h.vm.onPassthroughInput(tv(""), tv("short"))
        assertEquals("short", h.inputFrames().single().text)
    }

    @Test fun capturedLongSubmissionStillUploadsAfterEditorChangesDuringDispatch() {
        val h = Harness()
        val captured = "x".repeat(2_000)
        h.vm.onPassthroughInput(tv(""), tv(captured))
        h.vm.onPassthroughInput(tv(captured), tv(""))
        h.vm.sendDraft(captured)
        assertEquals(captured, h.uploader.lastAttachment!!.bytes.toString(Charsets.UTF_8))
        assertTrue(h.inputFrames().none { it.text.contains("xxx") })
    }

    @Test fun ninetyNineLinesRemainOrdinaryWhileHundredLinesUpload() {
        val h = Harness()
        h.vm.inputSyncEnabled = false
        val draft = List(99) { "a" }.joinToString("\n")
        h.vm.onPassthroughInput(tv(""), tv(draft))
        h.vm.sendDraft(draft)
        assertEquals(null, h.uploader.lastAttachment)
        assertEquals(draft + "\r", h.inputFrames().single().text)
    }

    // User revision: no per-edit bulk trigger; only total >=2000 units or >=100 lines.
    @Test fun mediumClipboardParagraphRemainsOrdinaryLiveText() {
        val h = Harness()
        val paragraph = "x".repeat(500)
        h.vm.onPassthroughInput(tv(""), tv(paragraph))
        assertEquals(paragraph, h.inputFrames().single().text)
        h.vm.sendDraft(paragraph)
        assertEquals(null, h.uploader.lastAttachment)
        assertEquals("", h.inputFrames().last().text)
    }

    @Test fun perKeyDraftStopsExactlyAtNewTwoThousandThreshold() {
        val h = Harness()
        var previous = ""
        for (n in 1..2_000) {
            val next = "x".repeat(n)
            h.vm.onPassthroughInput(tv(previous), tv(next))
            previous = next
        }
        assertEquals(1_999, h.inputFrames().size)
        assertTrue(h.vm.sendDraft(previous))
        assertTrue("达到2000单位必须上传", h.uploader.lastAttachment != null)
        assertEquals(previous, h.uploader.lastAttachment!!.bytes.toString(Charsets.UTF_8))
    }

    @Test fun editBackBelowNewThresholdRestoresOrdinaryText() {
        val h = Harness()
        h.vm.onPassthroughInput(tv(""), tv("x".repeat(2_000)))
        h.vm.onPassthroughInput(tv("x".repeat(2_000)), tv("x".repeat(500)))
        assertEquals("x".repeat(500), h.inputFrames().single().text)
        h.vm.sendDraft("x".repeat(500))
        assertEquals(null, h.uploader.lastAttachment)
    }

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
    fun copyModeSnapshotUsesIndependentDisplayWhileLiveDeltaKeepsFlowing() {
        val h = Harness(rows = 3, cols = 10)
        h.snap("live")
        h.paneMode(true)
        h.snap("history")
        h.delta("-live")

        // The live emulator remains authoritative and complete.
        assertEquals("live-live", text(h.emulator.snapshot().lines[0]))
        // The copy viewport is the only rendered source while in copy-mode.
        h.vm.presenter.beginFrame()
        assertEquals("history", text(h.vm.presenter.lineCells(0)))

        h.paneMode(false)
        h.snap("restored")
        assertFalse(h.vm.inCopyMode)
        h.vm.presenter.beginFrame()
        assertEquals("restored", text(h.vm.presenter.lineCells(0)))
    }

    @Test
    fun copyModeDoesNotApplyLiveDeltaToDisplayedHistory() {
        val h = Harness(rows = 3, cols = 10)
        h.snap("history")
        h.paneMode(true)
        h.snap("copy")
        h.delta("-delta")
        h.vm.presenter.beginFrame()
        assertEquals("copy", text(h.vm.presenter.lineCells(0)))
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
    fun stalledScrollProbeResubscribesAfterQuietWindow() {
        val h = Harness()
        h.snap("screen")
        val before = h.subscribeFrames().size
        h.vm.onScrollWheel(1)

        h.vm.onTick(System.currentTimeMillis() + 2_001L)

        assertEquals(before + 1, h.subscribeFrames().size)
    }

    @Test
    fun binaryReplySuppressesStalledScrollProbe() {
        val h = Harness()
        h.snap("screen")
        val before = h.subscribeFrames().size
        h.vm.onScrollWheel(1)
        h.delta("reply")

        h.vm.onTick(System.currentTimeMillis())

        assertEquals(before, h.subscribeFrames().size)
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
    fun sendDraft_whenInputSyncDisabled_sendsWholeTextAndClearsDraft() {
        val h = Harness()
        h.vm.inputSyncEnabled = false
        // typing does NOT send any input frame
        h.vm.onPassthroughInput(tv(""), tv("echo hello"))
        assertTrue(h.inputFrames().isEmpty())

        // sendDraft sends whole text with CR submit and commits
        h.vm.sendDraft("echo hello")
        assertEquals(InputStatus.Sending, h.vm.inputStatus)
        val sent = h.inputFrames().last()
        assertEquals("echo hello\r", sent.text)
        assertTrue(sent.keys.isEmpty())
        assertEquals("", sent.attachmentPath)
        h.ackOk(sent.reqId)
        assertEquals(InputStatus.Sent, h.vm.inputStatus)
    }

    @Test
    fun sendDraft_whenInputSyncDisabled_emptyText_sendsBareEnter() {
        val h = Harness()
        h.vm.inputSyncEnabled = false
        h.vm.sendDraft("")
        assertEquals(InputStatus.Sending, h.vm.inputStatus)
        val sent = h.inputFrames().last()
        assertEquals("", sent.text)
        assertTrue(sent.keys.isEmpty())
        assertEquals("", sent.attachmentPath)
        h.ackOk(sent.reqId)
        assertEquals(InputStatus.Sent, h.vm.inputStatus)
    }

    @Test
    fun sendDraft_whenInputSyncDisabled_withAttachment_sendsTextAndAttachmentPath() {
        val h = Harness()
        h.vm.inputSyncEnabled = false
        h.vm.uploadAttachment(Attachment("a.png", "image/png", byteArrayOf(1)))
        h.vm.sendDraft("look at this")
        val sent = h.inputFrames().last()
        assertEquals("look at this", sent.text)
        assertEquals("/host/img.png", sent.attachmentPath)
    }

    @Test
    fun sendDraft_whenInputSyncEnabled_ignoresTextAndSendsBareEnter() {
        val h = Harness()
        h.vm.inputSyncEnabled = true
        h.vm.onPassthroughInput(tv(""), tv("live typing"))
        assertEquals(1, h.inputFrames().size)

        // sendDraft with text still sends bare enter because text was already synced
        h.vm.sendDraft("live typing")
        val sent = h.inputFrames().last()
        assertEquals("", sent.text)
    }

    @Test
    fun sendDraft_whenInputSyncEnabled_andComposingTextNotYetSynced_flushesDiffBeforeEnter() {
        val h = Harness()
        h.vm.inputSyncEnabled = true
        // Active IME composition: passthrough input holds keys (0 frames sent)
        val composing = TextFieldValue(text = "git status", composition = androidx.compose.ui.text.TextRange(0, 10))
        h.vm.onPassthroughInput(tv(""), composing)
        assertTrue("composing text must not emit passthrough keys", h.inputFrames().isEmpty())

        // User clicks send while keyboard / composition is still active
        h.vm.sendDraft("git status")
        val frames = h.inputFrames()
        assertEquals("must send typing frame followed by bare enter frame", 2, frames.size)
        assertEquals("first frame must type the unsynced draft into CLI", "git status", frames[0].text)
        assertEquals("second frame must be bare enter to execute", "", frames[1].text)
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
    fun sendKeyAckOkShowsSent() {
        // keys 回执 ok：显示已发送（直通模型下无本地草稿概念，只断言回执与无 text 帧）。
        val h = Harness()
        h.vm.sendKey(InputKey.CTRL_C)
        val sent = h.keyFrames().last()
        h.ackOk(sent.reqId)
        assertEquals(InputStatus.Sent, h.vm.inputStatus)
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
        val r = h.subscribeFrames()
        assertEquals(1, r.size)
        assertEquals(12, r[0].rows)
        assertEquals(41, r[0].cols)
        assertTrue("首次有效几何不得额外发 Resize", h.resizeFrames().isEmpty())
        assertEquals(12, h.emulator.rows)
        assertEquals(41, h.emulator.cols)
    }

    // ---- warm 几何订阅（极速打开：不等 View 首次布局）----

    @Test
    fun warmGeometrySubscribesAtConstructionBeforeAnyLayout() {
        val h = Harness(rows = 12, cols = 41, warm = true)
        val r = h.subscribeFrames()
        assertEquals("缓存命中必须在构造期即订阅，不等 onSizeChanged", 1, r.size)
        assertEquals(12, r[0].rows)
        assertEquals(41, r[0].cols)
        assertEquals(12, h.emulator.rows)
        assertEquals(41, h.emulator.cols)
    }

    @Test
    fun warmGeometryMatchingFirstLayoutSendsNoResizeOrResubscribe() {
        val h = Harness(rows = 12, cols = 41, warm = true)
        h.snap("warm first frame")
        assertTrue("warm 首帧先于布局到达也照常应用", h.vm.hasSnapshot)

        h.vm.presenter.seedCellMetrics(12, 24)
        h.vm.presenter.onViewportSizeChanged(500, 300) // 实测 12×41 == 缓存

        assertEquals("同尺寸不得重订", 1, h.subscribeFrames().size)
        assertTrue("同尺寸 N_resize_active 必须为 0", h.resizeFrames().isEmpty())
        assertEquals(12, h.emulator.rows)
        assertEquals(41, h.emulator.cols)
    }

    @Test
    fun warmGeometryMismatchResubscribesOnceWithMeasuredGeometry() {
        val h = Harness(rows = 10, cols = 41, warm = true)

        h.vm.presenter.seedCellMetrics(12, 24)
        h.vm.presenter.onViewportSizeChanged(500, 300) // 实测 12×41 ≠ 缓存 10×41

        val r = h.subscribeFrames()
        assertEquals(listOf(10 to 41, 12 to 41), r.map { it.rows to it.cols })
        assertTrue("按实测重订，不走 resize", h.resizeFrames().isEmpty())
        assertEquals(12 to 41, h.manager.subscriptionSize("s1"))
        assertEquals(12, h.emulator.rows)
    }

    @Test
    fun coldOpenStillDefersSubscribeUntilFirstLayout() {
        val h = Harness(rows = 12, cols = 41)
        assertTrue("缓存未命中不得按占位尺寸抢订", h.subscribeFrames().isEmpty())
        h.vm.presenter.seedCellMetrics(12, 24)
        h.vm.presenter.onViewportSizeChanged(500, 300)
        assertEquals(1, h.subscribeFrames().size)
    }

    // ---- 滚动跟手：节流尾沿 / 点按不等内核锁 ----

    @Test
    fun flushScrollWheelSendsTailStrandedInsideThrottleWindow() {
        val h = Harness()
        h.vm.onScrollWheel(3) // 前沿立发
        h.vm.onScrollWheel(2) // 窗口内：只累加
        assertEquals(listOf(-3), h.scrollWheelDeltas())

        h.vm.flushScrollWheel() // 尾沿：补发滞留的 2 行

        assertEquals(listOf(-3, -2), h.scrollWheelDeltas())
        h.vm.flushScrollWheel()
        assertEquals("无尾量不发空帧", listOf(-3, -2), h.scrollWheelDeltas())
    }

    @Test
    fun flushScrollWheelAfterDisconnectDropsTailWithoutSending() {
        val h = Harness()
        h.vm.onScrollWheel(3)
        h.vm.onScrollWheel(2)
        h.transport.peerClose(1006, "dropped")

        h.vm.flushScrollWheel()

        assertEquals(listOf(-3), h.scrollWheelDeltas())
    }

    @Test
    fun flushScrollWheelAfterDisposeSendsNothing() {
        val h = Harness()
        h.vm.onScrollWheel(3)
        h.vm.onScrollWheel(2)
        h.vm.dispose()

        h.vm.flushScrollWheel()

        assertEquals(listOf(-3), h.scrollWheelDeltas())
    }

    @Test
    fun terminalTapDoesNotWaitForEmulatorParserLock() {
        val h = Harness()
        h.delta("\u001b[?1000h\u001b[?1006h") // 对端开 SGR 鼠标跟踪
        val expected = h.emulator.encodeMouse(button = 0, column = 5, row = 3, press = true)!!
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val parser = thread {
            synchronized(h.emulator) { // 模拟 WS 线程正在 feed/头插大段历史
                locked.countDown()
                release.await()
            }
        }
        try {
            assertTrue(locked.await(2, TimeUnit.SECONDS))
            val t0 = System.nanoTime()
            val sent = h.vm.onTermMouse(column = 5, row = 3, press = true)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
            assertTrue("跟踪开着必须发出", sent)
            assertTrue("点按不得等内核锁（${elapsedMs}ms）", elapsedMs < 500)
        } finally {
            release.countDown()
            parser.join()
        }
        assertEquals(expected.toList(), h.inputFrames().last().bytes?.toList())
    }

    // ---- 连接状态映射 ----

    @Test
    fun snapshotContent_initiallyFalse_becomesTrueOnSnapshot() {
        val h = Harness()
        assertFalse("快照到达前尚未就绪", h.vm.hasSnapshotContent())
        assertFalse(h.vm.hasSnapshot)
        h.snap("line 1\r\nline 2")
        assertTrue("快照应用后就绪", h.vm.hasSnapshotContent())
        assertTrue(h.vm.hasSnapshot)
    }

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
