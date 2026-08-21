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
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.app.perf.PerfTrace
import dev.agentmirror.app.termview.TermViewPresenter
import dev.agentmirror.terminal.ScreenSnapshot
import dev.agentmirror.terminal.TerminalEmulator

/** 会话 ref = socket + U+001F + pane_id；悬浮窗订阅只取 socket。 */
internal fun sessionSocketFromRef(ref: String): String {
    val unitSep = ref.indexOf('\u001f')
    val literalSep = ref.indexOf("\\u001f")
    val sep = when {
        unitSep > 0 -> unitSep
        literalSep > 0 -> literalSep
        else -> -1
    }
    val socket = if (sep > 0) ref.substring(0, sep) else ref
    // listing 金样 / 无结构分隔的 ref（如 "s1"）不是 tmux socket，不得当路径发出。
    return if (socket.contains('/')) socket else ""
}

/** 从三级同一套网格快照抽出可见纯文本（宽字符占位格跳过）。 */
internal fun ScreenSnapshot.plainText(): String =
    lines.joinToString("\n") { row ->
        buildString {
            for (cell in row) {
                if (cell.width == 0) continue
                append(cell.text)
            }
        }.trimEnd()
    }.trimEnd()

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
 * - 差分同步（084）：本地输入框完整编辑，每次变化经 [onPassthroughInput] 发最小按键
 *   （公共前缀后退格 + 后缀）；纯追加退格 0，键序与逐键直通一致。IME 组合期不上行。
 *   [sendDraft] 发送只提交（裸 Enter）；input_ack 必达回执（003 第二条）；
 * - 附件：multipart HTTP 上传（协议 §8）→ 上传成功立刻贴进 CLI pane（需求 057 发图预贴）→
 *   路径累加进 [pendingAttachmentPaths]，[sendDraft] 提交时经 input 帧的独立
 *   attachment_path 字段带上最新一次预贴路径。
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

    /** 视口状态机：跟随/锁定、字号→行列数换算、脏区（渲染逻辑与 View 分离）。 */
    val presenter = TermViewPresenter(emulator) { rows, cols, reason ->
        // feat-font-size-setting-drop-pinch：字号选定后实测算出的行列数先上报协议，
        // 再同步内核（让 CLI 自己重画）；几何只在进入会话时算一次（seedCellMetrics）。
        if (manager.resize(ref, rows, cols, reason)) {
            emulator.resize(cols, rows)
        }
    }

    // ---- 可观察 UI 状态（Compose 直接读）----

    /** 发送回执状态机（必达：ok 静默收起 / fail+超时明确报错）。 */
    var inputStatus by mutableStateOf<InputStatus>(InputStatus.Idle)

    /** 附件上传状态机（成功路径注入 / 失败明确报错）。 */

    /** 会话内悬浮窗是否打开（072：二级菜单列表，不再订 overlay 抓屏流）。 */
    var overlayOpen by mutableStateOf(false)
        private set

    var uploadStatus by mutableStateOf<UploadStatus>(UploadStatus.Idle)

    /**
     * 已贴进本会话 CLI pane、尚未确认发送的图片路径（需求 057）：上传成功那一刻就
     * 经 [ConnectionManager.sendAttachPreview] 贴进 pane（不等发送）。直通模型下草稿在
     * CLI，本地输入框不显示路径字符串，只显示"已附加 N 张图"这类轻量指示。
     *
     * **可累加**（需求 057 第 4 款）：连选两张就是两张，都已经贴进 pane 了，App 这边只是
     * 记账，不做"覆盖上一张"。[sendDraft] 提交时把列表最后一个路径经 input 帧的
     * attachment_path 字段送出（服务端只需要最新一次预贴的时间戳来算沉降补差额，
     * 不需要逐张路径）；发送成功后清空整个列表，失败保留可重发。
     *
     * **选了图不发、或离开会话，不清理 pane。**（需求 057 第 3 款）App 是 pane 的镜像，
     * 那个 `[Image #N]` 占位符在用户屏幕上看得见，不是静默残留；主动去读 CLI 渲染出来
     * 的 UI 文本再决定要不要清，是一类新的、会随 Claude Code 占位符格式变化而静默失效
     * 的脆弱性——代价大于收益，故不做。
     */
    var pendingAttachmentPaths by mutableStateOf<List<String>>(emptyList())

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

    /** 刚从对端同步来的行列；与本地视口算出的相同则不再上行 resize。 */

    /** 节流窗口内累积的 deltaLines 总量；窗口到点时一并发出（消除"无反应"假象）。 */
    private var pendingScrollDelta = 0

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
    private var lastFrameColsKey: String? = null

    init {
        // 注意：本 VM 不调用 manager.setListener(self)——共享连接（ServiceWire 单例）由
        // fg-service 持有一个包装监听（服务常驻通知 + uiConnector 扇出）。本 VM
        // 实现 Listener 是让接线层把 uiConnector 扇出的回调原样路由进来；自行 setListener
        // 会顶掉服务层包装、破坏常驻通知。同模块测试对测试自建 manager 显式 setListener。
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
            is ErrorFrame -> {
                DiagLog.record(
                    "overlay",
                    "error_frame code=${frame.code.wire} reason=${frame.reason} " +
                        "overlay_open=$overlayOpen ref=$ref",
                )
                transientError = "协议错误：${frame.code.wire}${frame.reason.takeIf { it.isNotEmpty() }?.let { "（$it）" } ?: ""}"
            }
            // 缺陷④：远端 pane copy-mode 状态变更（进入/退出），驱动 UI 角标。
            is PaneModeChangedFrame -> if (frame.ref == ref) inCopyMode = frame.inCopyMode
            else -> Unit
        }
    }

    /**
     * 打开悬浮窗：展示二级会话列表（072）。不再订 overlay 抓屏流。
     *
     * @post [overlayOpen]=true；不发 overlay_subscribe
     */
    fun openOverlay() {
        if (overlayOpen) return
        overlayOpen = true
        DiagLog.record("overlay", "open menu ref=$ref subscribe=false")
    }

    /**
     * 关闭悬浮窗。不再退订抓屏流（主路径已不订阅）。
     *
     * @post [overlayOpen]=false
     */
    fun closeOverlay() {
        if (!overlayOpen) return
        overlayOpen = false
        DiagLog.record("overlay", "close menu ref=$ref")
    }

    override fun perfTraceListenerRef(): String = ref

    override fun onBinary(frame: BinaryFrame) {
        if (frame.ref != ref) {
            // 原先静默 return：帧到了被吞 vs 帧没到，日志同形。关时最外层短路、不拼串。
            if (PerfTrace.isEnabled()) {
                val kind = when (frame.kind) {
                    BinaryKind.SNAPSHOT -> "snapshot"
                    BinaryKind.DELTA -> "delta"
                    BinaryKind.SCROLLBACK -> "scrollback"
                }
                PerfTrace.emitFrameRefMismatch(frame.ref, ref, kind, frame.data.size)
            }
            return
        }
        if (PerfTrace.isEnabled()) {
            val kind = when (frame.kind) {
                BinaryKind.SNAPSHOT -> "snapshot"
                BinaryKind.DELTA -> "delta"
                BinaryKind.SCROLLBACK -> "scrollback"
            }
            PerfTrace.emitFirstFrameIfFirst(ref, kind, frame.data.size) // first_frame_recv
        }
        when (frame.kind) {
            // 首帧快照：清屏重建（replaySnapshot 而非 feed，经验基）。
            BinaryKind.SNAPSHOT -> {
                val bookkept = manager.subscriptionSize(ref)
                val frameCols = bookkept?.second ?: -1
                val renderCols = emulator.cols
                val frameKey = "$frameCols|$renderCols|${bookkept?.first ?: -1}|${emulator.rows}"
                if (frameKey != lastFrameColsKey) {
                    lastFrameColsKey = frameKey
                    DiagLog.record(
                        "reflow",
                        "frame cols=$frameCols render cols=$renderCols " +
                            "bookkept_rows=${bookkept?.first ?: -1} emulator_rows=${emulator.rows}",
                    )
                }
                emulator.replaySnapshot(frame.data, emulator.cols, emulator.rows)
                if (PerfTrace.isEnabled()) {
                    val alt = if (emulator.historyAvailable) 0 else 1
                    PerfTrace.emitSnapshotIfFirst(ref, alt, emulator.rows, emulator.cols) // snapshot_applied
                }
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
            // 003 发送必达：回执可见。直通模型下草稿在 CLI，本地无草稿可清；
            // 提交已发出，附件清单清空（避免跟着下一条重复发送）。
            inputStatus = InputStatus.Sent
            pendingAttachmentPaths = emptyList()
        } else {
            // 失败明确报错（CLI 草稿仍在，可直接重发）。
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
     * 直通输入（059）：发送键只提交——CLI 输入框即草稿（用户逐键直通已把内容打在
     * CLI 输入框里），发送 = 裸 Enter。不再读取任何本地草稿文本整条注入（那正是被
     * 取代的 003 第1条"一次性注入"）。
     *
     * 有待发附件（[pendingAttachmentPaths]）时路径已经在上传成功那一刻贴进 CLI pane 了
     * （需求 057），提交这一步只需带最新一次预贴路径（服务端据此算沉降补差额：常见
     * 路径零等待，只有选完图立刻发才补差额）；text 为空，服务端只发 Enter。
     *
     * @contract
     * @pre connectionState 为 READY，且 inputStatus 非 Sending
     * @post 提交成功置 [InputStatus.Sending]，回执后由 [onInputResult] 转 [InputStatus.Sent]
     *       （并清空 [pendingAttachmentPaths]）或 [InputStatus.Failed]（保留附件，可重发）
     * @err 未就绪 / 提交失败置 [InputStatus.Failed]
     * @inv 在途不回发（发送闸）；不携带任何本地草稿文本（059 取代 003 第1条）
     */
    fun sendDraft() {
        if (inputStatus is InputStatus.Sending) return // 在途不回发
        // 本地先判定可发送性：未就绪立即明确报错（静默失效猎杀）。
        if (connectionState != ConnectionState.READY) {
            inputStatus = InputStatus.Failed("连接未就绪，无法发送")
            return
        }
        // 服务端只需要"最新一次预贴的是哪个路径"来核对沉降时间戳；多张图一起提交，
        // 靠最新那次的时间戳兜底。text 为空 = 裸 Enter 提交（059：发送只提交）。
        val attachmentPath = pendingAttachmentPaths.lastOrNull().orEmpty()
        if (manager.sendInput(ref, "", attachmentPath)) {
            inputStatus = InputStatus.Sending
            // Enter 提交后 CLI 行空；本地框跟着清。不同步会把下一轮当成「删掉上一条」。
            syncedText = ""
        } else {
            inputStatus = InputStatus.Failed("发送失败：连接不可用")
        }
    }

    /**
     * 点按快捷键条（R-1，017）：注入 keys 帧，**不附加回车**。
     *
     * 走既有 input→input_ack 决定性链路（003 发送必达：ack 失败/超时可见），与提交共用
     * 发送闸（在途不回发）；回执 ok 显示已发送。
     *
     * @contract
     * @pre connectionState 为 READY，且 inputStatus 非 Sending
     * @post 注入成功置 [InputStatus.Sending]
     * @err 未就绪 / 注入失败置 [InputStatus.Failed]
     * @inv 在途不回发（发送闸）
     */
    fun sendKey(key: InputKey) {
        if (inputStatus is InputStatus.Sending) return // 在途不回发
        if (connectionState != ConnectionState.READY) {
            inputStatus = InputStatus.Failed("连接未就绪，无法发送")
            return
        }
        if (manager.sendInputKeys(ref, key)) {
            inputStatus = InputStatus.Sending
        } else {
            inputStatus = InputStatus.Failed("发送失败：连接不可用")
        }
    }

    /**
     * 差分同步（084）：每次 [TextFieldValue] 变化立刻对照 [syncedText] 发最小按键。
     *
     * 组合期（[TextFieldValue.composition] != null）零按键；上屏同一调用内发出。
     * 英文/数字无组合期，本方法返回前按键已发出（无 post / delay）。
     *
     * @contract
     * @pre oldValue/newValue 为输入框前后值（Compose onValueChange 参数）
     * @post 非组合期 CLI 行尾草稿 == newValue.text；组合期不发键、synced 不变
     * @err none（连接未就绪时发送静默丢，synced 也不推进，避免以为 CLI 已跟上）
     * @inv 同步后光标约定在行尾；不改本地草稿；不引入额外延迟
     */
    fun onPassthroughInput(oldValue: TextFieldValue, newValue: TextFieldValue) {
        val wasComposing = oldValue.composition != null
        val isComposing = newValue.composition != null
        val composition = newValue.composition
        if (isComposing) {
            if (!wasComposing) {
                DiagLog.record(
                    "diffsync",
                    "composing=true composition=$composition " +
                        "current_len=${newValue.text.length} synced_len=${syncedText.length} " +
                        "→ hold keys=0",
                )
            }
            return
        }
        applyDiffSync(newValue.text)
    }

    /** 相对 [syncedText] 发退格+后缀，立刻更新已同步文本。 */
    private fun applyDiffSync(current: String) {
        val plan = DiffSync.plan(syncedText, current)
        if (plan.backspaces > 0) {
            DiagLog.record(
                "diffsync",
                "prefix=${DiffSync.commonPrefixLength(syncedText, current)} " +
                    "synced_len=${syncedText.length} current_len=${current.length} " +
                    "backspaces=${plan.backspaces} typed_len=${plan.typed.length} " +
                    "key_count=${plan.keyCount} → send",
            )
        }
        if (connectionState != ConnectionState.READY) return
        repeat(plan.backspaces) { sendBackspace() }
        if (plan.typed.isNotEmpty()) sendPassthrough(plan.typed)
        syncedText = current
    }

    /**
     * 直通一段文本到 CLI 输入框（不回车）。连接未就绪时静默丢（CLI 无草稿可编辑，
     * 直通无意义）；不走输入状态闸（直通不是"发送"，每键独立、必达回执但 UI 不阻塞）。
     */
    private fun sendPassthrough(content: String) {
        if (connectionState != ConnectionState.READY) return
        manager.sendKeystroke(ref, content)
    }

    /** 直通删除键（059）：虚拟键盘删除键经 keys 通道发 backspace 命名键到 CLI。 */
    private fun sendBackspace() {
        if (connectionState != ConnectionState.READY) return
        manager.sendBackspace(ref)
    }

    /** 已同步到 CLI 行尾的文本（084）；组合期不推进。 */
    private var syncedText: String = ""

    /**
     * 上传附件（协议 §8 multipart）→ 主机绝对路径**立刻**经 [ConnectionManager.sendAttachPreview]
     * 贴进 CLI pane（需求 057：上传成功那一刻贴，不等发送，让解码在用户打字期间跑完），
     * 并记进 [pendingAttachmentPaths]（累加——需求 042「不填入输入框文本」管的是 CLI 输入框，
     * 直通模型下路径不掺入逐键直通的文字），不自动提交整条消息。
     *
     * @contract
     * @pre baseUrl 已注入（连接配置已落地），且 uploadStatus 非 Uploading
     * @post 成功：路径追加进 [pendingAttachmentPaths]（连选两张就是两张，不覆盖）并发出
     *       AttachPreviewFrame，置 [UploadStatus.Success]；失败：置 [UploadStatus.Failed]
     *       且不改附件列表、不发预贴帧
     * @err 未配置上传地址 / 上传失败均置 [UploadStatus.Failed] 明确报错
     * @inv 在途不重传；上传不自动提交；直通文字不被本函数改写；从不清理 CLI pane
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
                pendingAttachmentPaths = pendingAttachmentPaths + outcome.path
                manager.sendAttachPreview(ref, outcome.path)
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
     * 窗口内的事件**累加**到 pendingScrollDelta，窗口到点时把累计量一并发出——
     * 不累加则一次完整滑动只送出 ~6 帧 × 1–2 行 ≈ 10 行，用户体感是"没反应"。
     *
     * @contract
     * @pre deltaLines 非零（调用方 TermSurfaceView 在 deltaLines==0 时不调用此方法）
     * @post READY 时：每次累加 deltaLines；间隔 ≥50ms 时发一帧 ScrollWheelFrame(delta=-accumulated)
     *       非 READY 时：presenter.onScrollBy(deltaLines) 本地降级
     * @err 帧校验失败由 conn 层静默（累计量为 0 时不发帧）
     * @inv lastScrollSentMs 单调递增；pendingScrollDelta 发出后归零；connectionState 不被本方法改变
     */
    fun onScrollWheel(deltaLines: Int) {
        if (connectionState != ConnectionState.READY) {
            // 降级：非 READY（掉线/重连/停止）时走本地缓冲，保证用户仍可看历史。
            presenter.onScrollBy(deltaLines)
            return
        }
        pendingScrollDelta += deltaLines
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastScrollSentMs < SCROLL_THROTTLE_MS) return
        lastScrollSentMs = nowMs
        val toSend = pendingScrollDelta
        pendingScrollDelta = 0
        if (toSend == 0) return
        // 协议约定：delta<0=向上看历史（scroll-up）。
        // 手势约定：deltaLines>0=presenter 向更早历史滚（正值=看旧内容），
        // 因此 delta=-toSend 使两端符号语义对齐。
        manager.sendScrollWheel(ref, -toSend)
    }

    /** 离开会话页时释放：退订镜像（conn 层幂等），停用连接由服务/接线层决定。 */
    fun dispose() {
        closeOverlay()
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


