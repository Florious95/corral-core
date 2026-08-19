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

package dev.agentmirror.app.conn

import dev.agentmirror.app.diag.DiagLog

/**
 * 连接层对外状态（docs/protocol.md §3 生命周期）。
 */
enum class ConnectionState {
    /** 拨号中。 */
    CONNECTING,

    /** 传输已建立、auth 上行前/上行中，等待 auth_ack。 */
    AUTHENTICATING,

    /** 认证通过，可交换业务帧。 */
    READY,

    /** 掉线，正在退避等待重连。 */
    RECONNECTING,

    /** 永久关闭（auth 被拒 / 显式 stop）。 */
    STOPPED,
}

/**
 * 连接配置：目标 URL 与配对 token（token 只上行一次，不回显、不落日志）。
 *
 * [toString] 安全覆盖（前置任务②，w-diag-rev 对抗预审发现）：data class 默认 toString
 * 会把 token 明文吐进日志/崩溃 trace。url 保留（诊断仍能看出拨号目标），token 替换为
 * [redacted]。序列化仍走手写/编译器生成路径，不受影响。
 */
data class ConnectionConfig(
    val url: String,
    val token: String,
) {
    override fun toString(): String = "ConnectionConfig(url=$url, token=[redacted])"
}

/**
 * 重连策略 + 订阅簿记（docs/protocol.md §3 重连语义、004 无状态铁律）。
 *
 * 掉线 → 指数退避重连（[ReconnectPolicy]）→ 重连 READY 后：
 * 1. 重新 [list] 拉全量列表（无状态恢复，重建模型）；
 * 2. 重放全部活跃 subscribe（当前屏快照重放）。
 * listing seq 不连续或 list_delta 先于 listing 到达 ⇒ 自动重新 list。
 *
 * 本层不持久任何会话状态；[ConnectionManager] 持有的簿记仅是**连接存活期间**的重放
 * 意图，随进程消失（004 无状态：链路的唯一状态是主机 tmux 这个事实源）。
 *
 * 上层只见 [Listener] 回调。调度由宿主驱动：生产用定时器周期调用 [pump]，
 * 单测用假时钟推进（conn 知识基底 §1）。
 */
class ConnectionManager(
    private val config: ConnectionConfig,
    private val transportFactory: TransportFactory,
    private val clock: Clock = Clock.Real,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val inputTimeoutMs: Long = 10_000,
) {
    /** 上层监听（单收件线程串行回调）。 */
    interface Listener {
        fun onStateChanged(state: ConnectionState)
        fun onFrame(frame: FramePayload)
        fun onBinary(frame: BinaryFrame)
        fun onLocalDecodeError(code: FrameError, message: String)

        /**
         * 输入投递的判定结果（必达回执）。reason 非空当且仅当 ok=false。
         * ok=false 的 reason 为 input_ack 的 reason.wire，或本地判定："timeout"（超时无回执）、
         * "connection lost: …"（掉线时未决输入）、"connection stopped"（[ConnectionManager.stop]）、
         * "connection rejected/closed: …"（auth 被拒 / 永久关闭）。
         */
        fun onInputResult(reqId: Long, ok: Boolean, reason: String?)

        /** 掉线后即将重连：attempt 为下一次尝试序号（0 起），delayMs 为等待时长。 */
        fun onReconnect(attempt: Int, delayMs: Long)
    }

    private var listener: Listener? = null

    /** 当前状态；[ConnectionManager] 在同一收件线程串行使用，无需加锁。 */
    private var state: ConnectionState = ConnectionState.STOPPED

    private var connection: Connection? = null

    /** 活跃订阅簿记：ref → (rows, cols)，重连后重放。 */
    private val activeSubscriptions = LinkedHashMap<String, Pair<Int, Int>>()

    /** 二级订阅簿记：workspace cwd；READY / 重连后重发 level2_subscribe。 */
    private val activeLevel2 = LinkedHashSet<String>()

    /** 悬浮窗抓屏流应订的 socket；null = 未订。READY / 重连后重放。 */
    private var overlayWantedSocket: String? = null
    private var overlayWantedCols: Int = 0
    private var overlayWantedRows: Int = 0

    /** 上次见过的 listing seq；list_delta 连续性据此判定。 */
    private var lastSeenSeq: Long? = null

    /** 下一次重连触发时刻（毫秒）；null = 无待执行重连。 */
    private var pendingReconnectAt: Long? = null

    /** 退避尝试计数；成功连接后重置。 */
    private var attempt = 0

    /** C→S 请求自增 req_id（list/input/scrollback 共用单调计数）。 */
    private var nextReqId = 1L

    /** 未决输入：req_id → 期限与完成标记。 */
    private val pendingInputs = LinkedHashMap<Long, PendingInput>()

    private class PendingInput(val deadlineMs: Long) {
        var resolved: Boolean = false
    }

    /** 挂上上层监听。 */
    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /** 当前状态（测试与 UI 可读）。 */
    fun state(): ConnectionState = state

    /**
     * 当前拨号地址（构造期配置，重连中 UI 展示用，018 标准5 失败可见）。
     *
     * 返回本管理器**实际拨向**的地址——不是 ServiceWire 层配置字段。真机 stale-config 缺陷
     * 时期，此值能直接暴露"重连正拨旧址"（改配置后仍拨旧地址）；配合 onReconnect 的
     * 已试次数一并展示，用户不再面对无声的重连循环。
     */
    fun dialUrl(): String = config.url

    /** 活跃订阅 ref 集合（测试断言重放依据）。 */
    fun activeRefs(): Set<String> = activeSubscriptions.keys.toSet()

    /**
     * 启动：首次连接立即发起；已启动（非 STOPPED）时幂等。
     *
     * @contract
     * @pre 无
     * @post 若此前 STOPPED：attempt/lastSeenSeq 重置并进入 CONNECTING 发起连接；否则保持现状
     * @err 无（连接失败经 Listener.onClosed(permanent=false) 走重连调度）
     * @inv 不改变已非 STOPPED 的状态；可重复调用
     */
    fun start() {
        if (state != ConnectionState.STOPPED) return
        attempt = 0
        lastSeenSeq = null
        attemptConnect()
    }

    /**
     * 永久关闭：取消待重连、关闭当前连接、未决输入一律判失败。
     * 幂等；stop 后可重新 [start]（attempt/seq 重算，订阅簿记保留待重放）。
     *
     * @contract
     * @pre 无
     * @post 状态为 STOPPED；activeSubscriptions 保留（重 start 时仍重放），pendingInputs 清空
     * @err 未决输入经 onInputResult(reqId, false, "connection stopped") 判失败
     * @inv 可重复调用；STOPPED 状态下再 stop 为无操作
     */
    fun stop() {
        pendingReconnectAt = null
        connection?.close()
        connection = null
        failAllPending("connection stopped")
        setState(ConnectionState.STOPPED)
    }

    /**
     * 宿主驱动的时钟泵：nowMs 到达重连触发时刻即发起重连。
     * 生产由定时器周期调用；单测由假时钟推进驱动（确定性）。
     *
     * @contract
     * @pre 无
     * @post nowMs ≥ 待触发时刻时清除 pendingReconnectAt 并发起连接；否则无操作
     * @err 无（连接失败经调度路径处理）
     * @inv 可重复调用；不改变非重连状态
     */
    fun pump(nowMs: Long) {
        val at = pendingReconnectAt ?: return
        if (nowMs >= at) {
            pendingReconnectAt = null
            attemptConnect()
        }
    }

    /**
     * 输入超时裁决：超时无 input_ack 的未决输入判为明确失败（静默失效猎杀）。
     * 由宿主周期调用（与 [pump] 同节奏）；单测用假时钟推进。
     */
    fun resolveExpiredInputs(nowMs: Long) {
        val it = pendingInputs.iterator()
        while (it.hasNext()) {
            val (reqId, p) = it.next()
            if (!p.resolved && nowMs >= p.deadlineMs) {
                p.resolved = true
                listener?.onInputResult(reqId, false, "timeout")
                it.remove()
            }
        }
    }

    /**
     * 网络可达性变化钩子：RECONNECTING 中立即重试，不等退避到点。
     * Android 侧 ConnectivityManager 回调接这里（知识基底 §1 钩子接口）。
     *
     * @contract
     * @pre 无
     * @post RECONNECTING 且有待触发重连时清除 pendingReconnectAt 并立即发起连接；否则无操作
     * @err 无（连接失败经调度路径处理）
     * @inv 非 RECONNECTING 时为无操作
     */
    fun onNetworkAvailable() {
        if (state == ConnectionState.RECONNECTING && pendingReconnectAt != null) {
            pendingReconnectAt = null
            attemptConnect()
        }
    }

    /**
     * 注入整条文本，可选带一个图片附件路径（input 以 input_ack 完结：必达回执）。
     * text 为空且 attachmentPath 为空 = 仅回车。attachmentPath 非空时服务端按三步序列
     * 注入（先单独粘贴路径内联成 `[Image #N]`，再发 text，最后一次 Enter；feat-image
     * -upload-inline），text 本身**不**掺路径、不被强加换行——那是服务端的事。
     * @return false = 当前不可发送（未就绪/校验不过）；true = 已送出，结果以
     * [Listener.onInputResult] 判定（超时 = 明确失败）。
     *
     * @contract
     * @pre 当前处于 READY 且 connection 非空
     * @post 返回 true 时 input 帧已发出且 pendingInputs 登记了超时期限；结果必达 [Listener.onInputResult]
     * @err 未就绪 / 编码校验不过 ⇒ 返回 false；已送出则超时 / 掉线 / stop 经 onInputResult 判失败
     * @inv ref 不变；req_id 单调递增（nextReqId）；attachmentPath 为空时与该参数引入前逐字节一致
     */
    fun sendInput(ref: String, text: String, attachmentPath: String = ""): Boolean {
        val conn = connection ?: return false
        if (!conn.isReady) return false
        val reqId = nextReqId++
        val frame = InputFrame(reqId = reqId, ref = ref, text = text, attachmentPath = attachmentPath)
        if (!conn.send(frame)) return false
        pendingInputs[reqId] = PendingInput(clock.nowMs() + inputTimeoutMs)
        return true
    }

    /**
     * 注入一个命名特殊键（input.keys，R-1 快捷键条，017 裁定）。
     *
     * 与 [sendInput] 同款决定性链路：input 以 input_ack 完结（003 发送必达），超时/掉线
     * 判明确失败。keys 不附加回车（快捷键条语义 = 按一下那个键）；text 与 keys 互斥
     * （契约 §4.2，InputFrame.validate 兜底）。
     * @return false = 当前不可发送；true = 已送出，结果以 [Listener.onInputResult] 判定。
     *
     * @contract
     * @pre 当前处于 READY 且 connection 非空
     * @post 返回 true 时 input 帧已发出（keys 携带且不附加回车）且 pendingInputs 登记超时期限
     * @err 未就绪 / 编码校验不过 ⇒ 返回 false；已送出则超时 / 掉线 / stop 经 onInputResult 判失败
     * @inv keys 与 text 一帧至多其一；req_id 单调递增
     */
    fun sendInputKeys(ref: String, key: InputKey): Boolean {
        val conn = connection ?: return false
        if (!conn.isReady) return false
        val reqId = nextReqId++
        val frame = InputFrame(reqId = reqId, ref = ref, keys = listOf(key))
        if (!conn.send(frame)) return false
        pendingInputs[reqId] = PendingInput(clock.nowMs() + inputTimeoutMs)
        return true
    }

    /**
     * 直通输入（059）：把单个按键字符（或一段上屏文本）发到 CLI 输入框，**不追加回车**。
     *
     * 服务端 handleInput 对非空 text 走 TypeKeys 逐键注入（草稿在 CLI 输入框），
     * 空 text 才是提交（Enter）。因此本方法传 text=内容、不传 keys，注入即打字不提交。
     * 与 [sendInput] 同款决定性链路（input_ack 必达，超时/掉线判失败）。
     *
     * @contract
     * @pre 当前处于 READY 且 connection 非空
     * @post 返回 true 时 input 帧（text=char，无 keys）已发出且 pendingInputs 登记超时期限
     * @err 未就绪 / 编码校验不过 ⇒ 返回 false
     * @inv 不追加回车；text 与 keys 互斥（本方法只用 text）
     */
    fun sendKeystroke(ref: String, char: String): Boolean {
        val conn = connection ?: return false
        if (!conn.isReady) return false
        val reqId = nextReqId++
        val frame = InputFrame(reqId = reqId, ref = ref, text = char)
        if (!conn.send(frame)) return false
        pendingInputs[reqId] = PendingInput(clock.nowMs() + inputTimeoutMs)
        return true
    }

    /**
     * 直通删除键（059）：虚拟键盘删除键经 keys 通道发 backspace 命名键到 CLI，**不追加回车**。
     * 服务端 SendKeys 映射 backspace → tmux BSpace，删除 CLI 输入框光标前字符。
     *
     * @contract
     * @pre 当前处于 READY 且 connection 非空
     * @post 返回 true 时 input 帧（keys=[backspace]）已发出且 pendingInputs 登记超时期限
     * @err 未就绪 / 编码校验不过 ⇒ 返回 false
     * @inv 不追加回车；keys 与 text 互斥（本方法只用 keys）
     */
    fun sendBackspace(ref: String): Boolean {
        val conn = connection ?: return false
        if (!conn.isReady) return false
        val reqId = nextReqId++
        val frame = InputFrame(reqId = reqId, ref = ref, keys = listOf(InputKey.BACKSPACE))
        if (!conn.send(frame)) return false
        pendingInputs[reqId] = PendingInput(clock.nowMs() + inputTimeoutMs)
        return true
    }

    /**
     * 订阅会话镜像；记簿待重放，已就绪则立发。
     *
     * @contract
     * @pre 状态非 STOPPED；rows/cols ≥ 1 由帧校验兜底
     * @post ref 已记入 activeSubscriptions（重连后重放）；连接就绪时立发 SubscribeFrame
     * @err STOPPED ⇒ 返回 false 且不记簿；未就绪（但已启动）⇒ 返回 true 仅记簿待重放
     * @inv 重复订阅以最新 rows/cols 覆盖簿记（重放意图最新优先）；同一 ref 可多次立发 SubscribeFrame
     */
    fun subscribe(ref: String, rows: Int, cols: Int): Boolean {
        if (state == ConnectionState.STOPPED) return false
        activeSubscriptions[ref] = rows to cols
        val conn = connection ?: return true // 已记簿，重连后重放
        if (!conn.isReady) return true
        return conn.send(SubscribeFrame(ref = ref, rows = rows, cols = cols))
    }

    /**
     * 退订（幂等）；同时移出重放簿记。
     *
     * @contract
     * @pre 无（任意状态可调，幂等）
     * @post ref 已从 activeSubscriptions 移除；连接就绪时立发 UnsubscribeFrame
     * @err 无（不抛异常）；未就绪 ⇒ 返回 true 仅移簿记
     * @inv 退订不改变连接状态
     */
    fun unsubscribe(ref: String): Boolean {
        activeSubscriptions.remove(ref)
        val conn = connection ?: return true
        if (!conn.isReady) return true
        return conn.send(UnsubscribeFrame(ref = ref))
    }

    /**
     * 二级菜单订阅（061）：进入二级时发 [Level2SubscribeFrame]。App 只订不轮。
     * 未就绪先簿记，READY 后重放。
     */
    fun subscribeLevel2(workspace: String): Boolean {
        if (state == ConnectionState.STOPPED) return false
        activeLevel2.add(workspace)
        val conn = connection ?: return true
        if (!conn.isReady) return true
        return conn.send(Level2SubscribeFrame(workspace = workspace))
    }

    /** 二级退订：离开二级时发 [Level2UnsubscribeFrame]，并移出重放簿记。幂等。 */
    fun unsubscribeLevel2(workspace: String): Boolean {
        activeLevel2.remove(workspace)
        val conn = connection ?: return true
        if (!conn.isReady) return true
        return conn.send(Level2UnsubscribeFrame(workspace = workspace))
    }

    /**
     * 会话内悬浮窗订阅（065）：打开时发带 [socket] 的 [OverlaySubscribeFrame]。
     * 关掉必须 [unsubscribeOverlay]。未就绪先簿记，READY 后重放。
     */
    fun subscribeOverlay(socket: String, cols: Int = 0, rows: Int = 0): Boolean {
        if (state == ConnectionState.STOPPED) return false
        if (socket.isEmpty()) return false
        overlayWantedSocket = socket
        if (cols > 0) overlayWantedCols = cols
        if (rows > 0) overlayWantedRows = rows
        val conn = connection ?: return true
        if (!conn.isReady) return true
        return conn.send(
            OverlaySubscribeFrame(
                socket = socket,
                cols = overlayWantedCols,
                rows = overlayWantedRows,
            ),
        )
    }

    /** 悬浮窗退订：关闭时发 [OverlayUnsubscribeFrame]。幂等。关后不得继续收流。 */
    fun unsubscribeOverlay(): Boolean {
        overlayWantedSocket = null
        overlayWantedCols = 0
        overlayWantedRows = 0
        val conn = connection ?: return true
        if (!conn.isReady) return true
        return conn.send(OverlayUnsubscribeFrame())
    }

    /**
     * 请求全量列表。
     *
     * @contract
     * @pre 当前处于 READY 且 connection 非空
     * @post 返回 true 时 ListFrame 已发出（req_id 单调递增）
     * @err 未就绪 ⇒ 返回 false
     * @inv 不改变订阅簿记
     */
    fun list(): Boolean {
        val conn = connection ?: return false
        if (!conn.isReady) return false
        return conn.send(ListFrame(reqId = nextReqId++))
    }

    /**
     * 拉一页历史（from_line 按 tmux capture-pane 语义；count >= 1）。
     *
     * @contract
     * @pre 当前处于 READY 且 connection 非空；count ≥ 1
     * @post 返回 true 时 ScrollbackFrame 已发出（req_id 单调递增）
     * @err 未就绪 ⇒ 返回 false；count 非法由帧校验兜底
     * @inv 不改变订阅簿记
     */
    fun scrollback(ref: String, fromLine: Int, count: Long): Boolean {
        val conn = connection ?: return false
        if (!conn.isReady) return false
        return conn.send(ScrollbackFrame(reqId = nextReqId++, ref = ref, fromLine = fromLine, count = count))
    }

    /**
     * 投送滚轮手势到远端 pane（缺陷④ 远端滚动投送）。
     *
     * fire-and-forget：服务端成功时无 ack（屏幕变化即反馈）；失败时服务端回 ErrorFrame，
     * 由 [Listener.onFrame] 路径以 transientError 浮出。节流由调用方（SessionViewModel）负责。
     *
     * @contract
     * @pre 当前处于 READY 且 connection 非空；delta 非零
     * @post 返回 true 时 ScrollWheelFrame 已发出
     * @err 未就绪 ⇒ 返回 false；delta=0 由帧校验兜底
     * @inv 不登记 pending 回执（fire-and-forget）
     */
    fun sendScrollWheel(ref: String, delta: Int): Boolean {
        val conn = connection ?: return false
        if (!conn.isReady) return false
        return conn.send(ScrollWheelFrame(ref = ref, delta = delta))
    }

    /**
     * 发图预贴（需求 057）：图片一上传成功就调用，把路径贴进 CLI pane，让解码在用户
     * 打字期间悄悄跑完。同 [sendScrollWheel]，无 ack，成功与否看镜像流里 `[Image #N]`
     * 有没有出现；失败会有 ErrorFrame。
     *
     * @contract
     * @pre 当前处于 READY 且 connection 非空；ref/path 非空
     * @post 返回 true 时 AttachPreviewFrame 已发出；无回执跟踪
     * @err 未就绪 ⇒ 返回 false；path/ref 非法由帧校验兜底
     * @inv 不改变附件累加簿记（那是 VM 层的事）
     */
    fun sendAttachPreview(ref: String, path: String): Boolean {
        val conn = connection ?: return false
        if (!conn.isReady) return false
        return conn.send(AttachPreviewFrame(ref = ref, path = path))
    }

    /**
     * 上报手机行列数（只作用于已订阅会话）。
     *
     * @contract
     * @pre 当前处于 READY 且 connection 非空；rows/cols ≥ 1
     * @post 返回 true 时 ResizeFrame 已发出
     * @err 未就绪 ⇒ 返回 false；rows/cols 非法由帧校验兜底
     * @inv 不改变订阅簿记
     */
    fun resize(ref: String, rows: Int, cols: Int): Boolean {
        val conn = connection ?: return false
        if (!conn.isReady) return false
        return conn.send(ResizeFrame(ref = ref, rows = rows, cols = cols))
    }

    // ---- 内部 ----

    private fun attemptConnect() {
        // 唯一入口是 start()（gate 在 start 里）或调度/网络钩子（此时 state 必非 STOPPED），
        // 因此这里直接进入 CONNECTING，无需再判 STOPPED。
        setState(ConnectionState.CONNECTING)
        val conn = Connection(transportFactory.create(config.url), config.token, connListener)
        connection = conn
        conn.start()
    }

    private val connListener = object : Connection.Listener {
        override fun onOpened() {
            setState(ConnectionState.AUTHENTICATING)
        }

        override fun onReady() {
            attempt = 0 // 成功后退避重置
            setState(ConnectionState.READY)
            // 无状态恢复：重建全量列表 + 重放全部活跃订阅（当前屏快照重放）。
            sendList()
            replaySubscriptions()
        }

        override fun onFrame(frame: FramePayload) {
            when (frame) {
                is ListingFrame -> {
                    lastSeenSeq = frame.seq
                    listener?.onFrame(frame)
                }
                is ListDeltaFrame -> {
                    // lastSeenSeq 是可变属性，先捕获局部值再判连续（智能转换不可用）。
                    val seen = lastSeenSeq
                    when {
                        seen == null -> {
                            // delta 先于 listing ⇒ 必须重新 list 拉全量（§4.2）。
                            sendList()
                        }
                        frame.seq == seen + 1 -> {
                            lastSeenSeq = frame.seq
                            listener?.onFrame(frame)
                        }
                        frame.seq <= seen -> {
                            // 已由 listing / 更早 delta 覆盖（handleList 重扫会 fanout
                            // 与 listing 同 seq 的 delta）。再 list 会自激。
                            listener?.onFrame(frame)
                        }
                        else -> {
                            // 真正的空洞 ⇒ 重新 list。
                            sendList()
                        }
                    }
                }
                is InputAckFrame -> resolveInput(frame)
                else -> listener?.onFrame(frame)
            }
        }

        override fun onBinary(frame: BinaryFrame) {
            listener?.onBinary(frame)
        }

        override fun onLocalDecodeError(code: FrameError, message: String) {
            listener?.onLocalDecodeError(code, message)
        }

        override fun onClosed(permanent: Boolean, reason: String) {
            if (permanent) {
                failAllPending("connection rejected/closed: $reason")
                connection = null
                setState(ConnectionState.STOPPED)
            } else {
                failAllPending("connection lost: $reason")
                connection = null
                scheduleReconnect()
            }
        }
    }

    private fun sendList() {
        connection?.send(ListFrame(reqId = nextReqId++))
    }

    private fun replaySubscriptions() {
        val conn = connection ?: return
        for ((ref, dims) in activeSubscriptions) {
            conn.send(SubscribeFrame(ref = ref, rows = dims.first, cols = dims.second))
        }
        for (workspace in activeLevel2) {
            conn.send(Level2SubscribeFrame(workspace = workspace))
        }
        overlayWantedSocket?.let {
            conn.send(
                OverlaySubscribeFrame(
                    socket = it,
                    cols = overlayWantedCols,
                    rows = overlayWantedRows,
                ),
            )
        }
    }

    private fun scheduleReconnect() {
        if (state == ConnectionState.STOPPED) return
        setState(ConnectionState.RECONNECTING)
        // delay 对应"下一次尝试"的序号 attempt（0 起），报告后再递增。
        val delayMs = policy.nextDelayMs(attempt)
        listener?.onReconnect(attempt, delayMs)
        pendingReconnectAt = clock.nowMs() + delayMs
        attempt++
    }

    private fun resolveInput(ack: InputAckFrame) {
        val pending = pendingInputs.remove(ack.reqId) ?: return
        pending.resolved = true
        listener?.onInputResult(ack.reqId, ack.ok, ack.reason?.wire)
    }

    private fun failAllPending(reason: String) {
        val it = pendingInputs.iterator()
        while (it.hasNext()) {
            val (reqId, p) = it.next()
            if (!p.resolved) {
                p.resolved = true
                listener?.onInputResult(reqId, false, reason)
            }
            it.remove()
        }
    }

    private fun setState(s: ConnectionState) {
        if (state != s) {
            // 缺陷观测点：连接状态迁移（CONNECTING→AUTHENTICATING→READY→RECONNECTING→STOPPED）。
            // 配合 ws 层的关闭原因记录，能重建"何时连上、何时掉、为何掉"的完整时间线。
            DiagLog.record("ws", "conn state $state → $s")
            state = s
            listener?.onStateChanged(s)
        }
    }
}
