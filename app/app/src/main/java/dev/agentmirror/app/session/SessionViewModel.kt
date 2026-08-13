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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.BinaryKind
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.ErrorFrame
import dev.agentmirror.app.conn.FrameError
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.conn.PaneModeChangedFrame
import dev.agentmirror.app.conn.InputKey
import dev.agentmirror.app.termview.TermViewPresenter
import dev.agentmirror.terminal.TerminalEmulator

/**
 * 会话页状态机（003 四标准的落地面）：终端镜像 + 本地输入条 + 发送回执 + 附件管线。
 *
 * 纯 JVM 可测核心：镜像流（snapshot/delta/scrollback）、发送必达、附件路径注入、
 * resize 上报、连接状态映射全部收敛在本类；Compose 屏只是薄渲染壳。
 *
 * 接线（session-ui 知识基底 §1）：
 * - 进入：构造函数即 [ConnectionManager.subscribe] → 首帧 snapshot 重放 + 预取历史；
 * - 增量：delta → [TerminalEmulator.feed]；历史页 → [TerminalEmulator.prependHistory]；
 * - 滚动到顶：[syncFromPresenter] 收敛 presenter 视口信号 → 按页拉更老历史；
 * - 发送：`send-keys` 一次性注入，input_ack 必达回执（003 第二条）；
 * - 附件：multipart HTTP 上传（协议 §8）→ 主机绝对路径插入光标处。
 */
class SessionViewModel(
    private val manager: ConnectionManager,
    private val uploader: AttachmentUploader,
    /** 上传基地址（协议 §8 同端口 `POST /upload`）；internal 供统一收口锁定测试断言。 */
    internal val baseUrl: String?,
    val ref: String,
    initialRows: Int,
    initialCols: Int,
    /** 上传认证与持久连接共用配对 token；只下传给 uploader，不记录、不回显。 */
    internal val uploadToken: String? = null,
) : ConnectionManager.Listener {

    /** 终端内核：snapshot 重放 + delta 追加 + 本地 scrollback（006 本地化滚动）。 */
    val emulator = TerminalEmulator(initialCols, initialRows)

    /** 视口状态机：跟随/锁定、捏合行列数换算、脏区（渲染逻辑与 View 分离）。 */
    val presenter = TermViewPresenter(emulator) { rows, cols ->
        // 005：捏合换算出的新行列数先上报协议，再同步内核（让 CLI 自己重画）。
        if (manager.resize(ref, rows, cols)) {
            emulator.resize(cols, rows)
        }
    }

    // ---- 可观察 UI 状态（Compose 直接读）----

    /** 输入条草稿（本地编辑零网络，003 第一条）。 */
    var textFieldValue by mutableStateOf(TextFieldValue(""))

    /** 发送回执状态机（必达：ok 清框 / fail+超时保留内容并报错）。 */
    var inputStatus by mutableStateOf<InputStatus>(InputStatus.Idle)

    /**
     * 在途发送是否为快捷键（keys）而非草稿（text）。
     *
     * 草稿发送 ok 回执要清空输入框；快捷键发送（R-1，017）ok 回执**不动草稿**——
     * 用户点 Esc/Ctrl-C 打断 agent 时往往正打着字。本标记在送出时置位、回执时消费，
     * 区分同一发送闸（InputStatus.Sending）下的两种回执语义。
     */
    private var pendingSendIsKey = false

    /** 附件上传状态机（成功路径注入 / 失败明确报错）。 */
    var uploadStatus by mutableStateOf<UploadStatus>(UploadStatus.Idle)

    /** 连接状态（顶部条提示；重连由 conn 层自动，VM 只映射）。 */
    var connectionState by mutableStateOf(ConnectionState.STOPPED)

    /** 顶部连接状态条文案；null = 无条（READY）。 */
    var connectionBanner by mutableStateOf<String?>(null)

    /** 协议/解码错误等被动异常的可见提示（静默失效猎杀）。 */
    var transientError by mutableStateOf<String?>(null)

    /** 是否滚到历史顶（本地滚动边界：可补页）。 */
    var atHistoryTop by mutableStateOf(false)

    /** 是否还有更老历史可分页（服务端收敛判顶后为 false）。 */
    var hasMoreHistory by mutableStateOf(true)

    /** 是否锁定在历史中（"回到底部"可见性；会话屏 Compose 悬浮钮按此渲染）。 */
    var showBackToBottom by mutableStateOf(false)

    /**
     * 远端 pane 是否处于 tmux copy-mode（缺陷④ 远端滚动投送）。
     *
     * copy-mode 中用户按键被 tmux 拦截为 copy-mode 命令（而非送到运行的程序），
     * 屏幕上看起来「敲了没反应」。服务端 handleScrollWheel 进入 copy-mode 后推
     * PaneModeChangedFrame{inCopyMode=true}；handleInput 退出后推 {inCopyMode=false}。
     * UI 据此显示 copy-mode 角标，告知用户当前模式（最小提示，不做花的）。
     */
    var inCopyMode by mutableStateOf(false)

    /** 上次向服务端发出 ScrollWheelFrame 的时间戳（ms）；用于 50ms 节流。 */
    private var lastScrollSentMs = 0L

    // ---- 历史分页簿记（006：滚动到边界按需补页）----

    /** 下一页请求的 from_line 锚点（协议 §6.3 capture-pane 语义：负=屏上历史）。 */
    private var historyNextFromLine = -HISTORY_PAGE

    /** 在途请求的 from_line（服务端收敛判顶的依据）。 */
    private var historyRequestedFromLine = 0

    /** 有分页请求在途（防滚动驻顶时叠发）。 */
    private var historyRequestInFlight = false

    /** 首帧 snapshot 是否已预取过历史（重连重放不重复预取）。 */
    private var hasPrefetchedHistory = false

    init {
        // 注意：本 VM 不调用 manager.setListener(self)——共享连接（ServiceWire 单例）由
        // fg-service 持有一个包装监听（服务 StateWatcher/通知 + uiConnector 扇出）。本 VM
        // 实现 Listener 是让接线层把 uiConnector 扇出的回调原样路由进来；自行 setListener
        // 会顶掉服务层包装、破坏状态守望与通知。同模块测试对测试自建 manager 显式 setListener。
        connectionState = manager.state()
        onStateChanged(manager.state())
        // 进入即订阅：conn 层记簿，READY 立发，重连自动重放（004 无状态）。
        manager.subscribe(ref, initialRows, initialCols)
    }

    // ---- ConnectionManager.Listener（单收件线程串行回调）----

    override fun onStateChanged(state: ConnectionState) {
        connectionState = state
        connectionBanner = when (state) {
            ConnectionState.CONNECTING -> "连接中…"
            ConnectionState.AUTHENTICATING -> "认证中…"
            ConnectionState.RECONNECTING -> {
                // 掉线分页意图作废：重连后快照重放，视口重锚，避免陈旧补页。
                historyRequestInFlight = false
                "连接断开，正在重连…"
            }
            ConnectionState.STOPPED -> "连接已断开"
            ConnectionState.READY -> null
        }
    }

    override fun onFrame(frame: FramePayload) {
        when (frame) {
            // 列表帧归 workspace 渲染；本页只关心协议级错误（被动异常必须可见）。
            is ErrorFrame -> transientError = "协议错误：${frame.code.wire}${frame.reason.takeIf { it.isNotEmpty() }?.let { "（$it）" } ?: ""}"
            // 缺陷④：远端 pane copy-mode 状态变更（进入/退出），驱动 UI 角标。
            is PaneModeChangedFrame -> if (frame.ref == ref) inCopyMode = frame.inCopyMode
            else -> Unit
        }
    }

    override fun onBinary(frame: BinaryFrame) {
        if (frame.ref != ref) return // 共享连接上的其它会话镜像，不消费
        when (frame.kind) {
            // 首帧快照：清屏重建（replaySnapshot 而非 feed，经验基）。
            BinaryKind.SNAPSHOT -> {
                emulator.replaySnapshot(frame.data, emulator.cols, emulator.rows)
                // 006 秒开：打开即预取最近一页历史，滚动边界再按需补页。
                if (!hasPrefetchedHistory) {
                    hasPrefetchedHistory = true
                    requestOlderHistoryPage()
                }
            }
            // 增量字节流：常规推进。
            BinaryKind.DELTA -> emulator.feed(frame.data)
            // 历史分页：按服务端收敛后的实际区间头插（经验基）。
            BinaryKind.SCROLLBACK -> {
                emulator.prependHistory(frame.data)
                historyRequestInFlight = false
                // 收敛判顶：实际区间起点比请求的更近 0 ⇒ 已到历史顶。
                if (frame.fromLine > historyRequestedFromLine) {
                    hasMoreHistory = false
                }
            }
        }
    }

    override fun onLocalDecodeError(code: FrameError, message: String) {
        // 坏帧/未知 type/版本不匹配必须显式浮出，不得静默（静默失效猎杀）。
        transientError = "解码失败：${message ?: code.name}"
    }

    override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) {
        // 发送态阻塞并发发送（UI 置灰），且 conn 层对每次投递只回执一次 ⇒ 在途回执即本页的。
        if (inputStatus !is InputStatus.Sending) return
        if (ok) {
            // 003 发送必达：回执可见 + 清输入框。快捷键回执（R-1，017）不动草稿——
            // 用户点 Esc/Ctrl-C 打断 agent 时往往正打着字（见 pendingSendIsKey）。
            inputStatus = InputStatus.Sent
            if (!pendingSendIsKey) {
                textFieldValue = TextFieldValue("")
            }
        } else {
            // 失败明确报错：输入框保留内容（可重发）。
            inputStatus = InputStatus.Failed(mapInputReason(reason))
        }
    }

    override fun onReconnect(attempt: Int, delayMs: Long) {
        // 状态条已由 onStateChanged(RECONNECTING) 覆盖；此处无需额外动作。
    }

    // ---- 宿主节奏（生产定时器 / 测试假时钟驱动）----

    /** 时钟泵：重连调度 + 输入超时裁决（超时 = 明确失败）。 */
    fun onTick(nowMs: Long) {
        manager.pump(nowMs)
        manager.resolveExpiredInputs(nowMs)
    }

    // ---- 用户动作 ----

    /**
     * 发送草稿：一次性注入并回车（R-2 多行不拆分：含 \n 的文本整段一条 input.text，
     * 服务端 paste-buffer -p 括号粘贴路径处理）；回执经 [onInputResult] 判定（必达）。
     *
     * @contract
     * @pre connectionState 为 READY，且 inputStatus 非 Sending
     * @post 注入成功置 [InputStatus.Sending]，回执后由 [onInputResult] 转 [InputStatus.Sent] 或 [InputStatus.Failed]
     * @err 未就绪 / 注入失败置 [InputStatus.Failed] 并保留草稿
     * @inv 在途不回发（发送闸）
     */
    fun sendDraft() {
        if (inputStatus is InputStatus.Sending) return // 在途不回发
        // 本地先判定可发送性：未就绪立即明确报错（静默失效猎杀）。
        if (connectionState != ConnectionState.READY) {
            inputStatus = InputStatus.Failed("连接未就绪，无法发送")
            return
        }
        val text = textFieldValue.text
        if (manager.sendInput(ref, text)) {
            pendingSendIsKey = false
            inputStatus = InputStatus.Sending
        } else {
            inputStatus = InputStatus.Failed("发送失败：连接不可用")
        }
    }

    /**
     * 点按快捷键条（R-1，017）：注入 keys 帧，**不附加回车**。
     *
     * 走既有 input→input_ack 决定性链路（003 发送必达：ack 失败/超时可见），与草稿共用
     * 发送闸（在途不回发）；回执 ok 只显示已发送、不动草稿（用户在打字）。
     *
     * @contract
     * @pre connectionState 为 READY，且 inputStatus 非 Sending
     * @post 注入成功置 [InputStatus.Sending] 并标记本回执为快捷键（回执 ok 不动草稿）
     * @err 未就绪 / 注入失败置 [InputStatus.Failed] 并保留草稿
     * @inv 在途不回发（发送闸）
     */
    fun sendKey(key: InputKey) {
        if (inputStatus is InputStatus.Sending) return // 在途不回发
        if (connectionState != ConnectionState.READY) {
            inputStatus = InputStatus.Failed("连接未就绪，无法发送")
            return
        }
        if (manager.sendInputKeys(ref, key)) {
            pendingSendIsKey = true
            inputStatus = InputStatus.Sending
        } else {
            inputStatus = InputStatus.Failed("发送失败：连接不可用")
        }
    }

    /**
     * 上传附件（协议 §8 multipart）→ 主机绝对路径插入光标处，不自动发送。
     *
     * @contract
     * @pre baseUrl 已注入（连接配置已落地），且 uploadStatus 非 Uploading
     * @post 成功：路径插入光标处并置 [UploadStatus.Success]；失败：置 [UploadStatus.Failed] 且不修改草稿
     * @err 未配置上传地址 / 上传失败均置 [UploadStatus.Failed] 明确报错
     * @inv 在途不重传；上传不自动发送草稿
     */
    fun uploadAttachment(attachment: Attachment) {
        if (uploadStatus is UploadStatus.Uploading) return
        val base = baseUrl
        if (base == null) {
            // 接线层未注入上传地址（配对/配置未落地）：明确报错而非静默（halt 纪律）。
            uploadStatus = UploadStatus.Failed("未配置上传地址")
            return
        }
        uploadStatus = UploadStatus.Uploading
        val outcome = uploader.upload(base, uploadToken, attachment)
        uploadStatus = when (outcome) {
            is UploadOutcome.Success -> {
                insertPathAtCursor(outcome.path)
                UploadStatus.Success(outcome.path)
            }
            is UploadOutcome.Failure -> UploadStatus.Failed(outcome.reason)
        }
    }

    /** 视口信号收敛（会话屏时钟泵周期调用 / 测试显式调用）：滚动到顶即补页。 */
    fun syncFromPresenter() {
        val locked = presenter.showBackToBottom
        showBackToBottom = locked
        atHistoryTop = locked && presenter.window.first == 0
        if (atHistoryTop && hasMoreHistory) {
            requestOlderHistoryPage()
        }
    }

    /** 回到底部：恢复跟随（会话屏「回到底部」悬浮钮点击回调）。 */
    fun onScrollToBottom() {
        presenter.onScrollToBottom()
        syncFromPresenter()
    }

    /**
     * 处理手势滚动（缺陷④ 远端滚动投送，由 TermSurfaceView 经 onRemoteScrollBy 回调触发）。
     *
     * READY 状态：以 50ms 节流发 ScrollWheelFrame 到服务端；delta = -deltaLines（协议约定
     * delta<0=向上/看历史，deltaLines>0=看更早历史）。非 READY 状态降级到本地缓冲滚动，
     * 保证掉线中仍可看本地 scrollback。
     *
     * 50ms 节流说明：GestureDetector 在快速滑动时每 ~16ms 触发一次 onScroll；不节流时
     * 每帧都发帧，链路负担大；50ms 约保留 20fps 的手势密度，足以响应滑动速度。
     * 超出窗口的事件被丢弃（非累加），因为每个滚轮档位都是相对偏移，丢中间帧不影响方向正确性。
     *
     * @contract
     * @pre deltaLines 非零（调用方 TermSurfaceView 在 deltaLines==0 时不调用此方法）
     * @post READY 时：间隔 ≥50ms 则发一帧 ScrollWheelFrame(delta=-deltaLines)；否则丢弃
     *       非 READY 时：presenter.onScrollBy(deltaLines) 本地降级
     * @err 帧校验失败由 conn 层静默（delta 不会为 0，由 deltaLines≠0 保证）
     * @inv lastScrollSentMs 单调递增；connectionState 不被本方法改变
     */
    fun onScrollWheel(deltaLines: Int) {
        if (connectionState != ConnectionState.READY) {
            // 降级：非 READY（掉线/重连/停止）时走本地缓冲，保证用户仍可看历史。
            presenter.onScrollBy(deltaLines)
            return
        }
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastScrollSentMs < SCROLL_THROTTLE_MS) return
        lastScrollSentMs = nowMs
        // 协议约定：delta<0=向上看历史（scroll-up）。
        // 手势约定：deltaLines>0=presenter 向更早历史滚（正值=看旧内容），
        // 因此 delta=-deltaLines 使两端符号语义对齐。
        manager.sendScrollWheel(ref, -deltaLines)
    }

    /** 离开会话页时释放：退订镜像（conn 层幂等），停用连接由服务/接线层决定。 */
    fun dispose() {
        manager.unsubscribe(ref)
    }

    /** 收起瞬时状态（Sent / 上传 Success / 错误提示，会话屏 LaunchedEffect 延迟后自动触发）。 */
    fun dismissTransient() {
        if (inputStatus is InputStatus.Sent) inputStatus = InputStatus.Idle
        if (uploadStatus is UploadStatus.Success) uploadStatus = UploadStatus.Idle
        transientError = null
    }

    // ---- 内部 ----

    /** 拉一页更老历史；在途或已到顶不叠发（同模块测试直接驱动）。 */
    internal fun requestOlderHistoryPage() {
        if (!hasMoreHistory || historyRequestInFlight) return
        historyRequestedFromLine = historyNextFromLine
        if (manager.scrollback(ref, historyRequestedFromLine, HISTORY_PAGE.toLong())) {
            historyRequestInFlight = true
            historyNextFromLine = historyRequestedFromLine - HISTORY_PAGE
        }
    }

    /** 把主机绝对路径插入输入框光标处（选择区折叠到起点），不自动发送。 */
    private fun insertPathAtCursor(path: String) {
        val tv = textFieldValue
        val insertAt = tv.selection.min
        val newText = tv.text.substring(0, insertAt) + path + tv.text.substring(insertAt)
        textFieldValue = TextFieldValue(newText, selection = TextRange(insertAt + path.length))
    }

    /** 协议 reason / 本地判定 → 人类可读错误文案（明确报错）。 */
    private fun mapInputReason(reason: String?): String = when (reason) {
        null, "" -> "发送失败"
        "timeout" -> "发送超时，未收到回执"
        "session_not_found" -> "会话已不存在"
        "not_subscribed" -> "未订阅该会话"
        "inject_failed" -> "主机拒绝注入"
        "too_large" -> "消息过长"
        "internal" -> "服务端内部错误"
        else -> if (reason.startsWith("connection")) "连接已断开，发送失败" else "发送失败：$reason"
    }

    private companion object {
        /** 历史分页大小（006：打开预取/滚到边界按页补）。 */
        const val HISTORY_PAGE = 400

        /** ScrollWheelFrame 发送节流窗口（ms）：GestureDetector 约每 16ms 触发，节流到 ~20fps。 */
        const val SCROLL_THROTTLE_MS = 50L
    }
}
