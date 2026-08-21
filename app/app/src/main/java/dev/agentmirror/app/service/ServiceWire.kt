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

package dev.agentmirror.app.service

import androidx.compose.runtime.mutableStateOf
import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FrameError
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.conn.ListingFrame
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.conn.TransportListener
import dev.agentmirror.app.conn.WebSocketTransport
import dev.agentmirror.app.tsnet.ConnectionPath

/**
 * 前台服务的接线点（service 包之外唯一改动接口；UI/配对层注入）。
 *
 * - [transportFactory]：真实 WebSocket 传输工厂。默认 [OkHttpTransportFactory]
 *   （leader 裁定 A，清偿传输欠账①）：配对后注入 [setConfig] 并启动连接即走真实 OkHttp
 *   拨号；[NoopTransportFactory] 保留为测试与降级用（拨号立即失败 → conn 层退避重连，
 *   生命周期可运行、不静默吞）。
 * - [uiConnector]：UI 侧监听桥。本对象持有唯一 [ConnectionManager]（背景常驻连接，
 *   [MirrorForegroundService] 经 [serviceListener] 与 UI 并行扇出），UI
 *   （[WorkspaceViewModel] / [SessionViewModel]）经此桥订阅同一连接，服务回调原样转投。
 *   多槽扇出：单屏在屏时挂一槽（SessionRoute 等），无需多屏同挂；配对试连接是**独立**
 *   ConnectionManager（[PairingViewModel] 自带探针），不走本桥。
 * - [serviceListener]：前台服务监听槽（feat-fg-service-wiring）。连接事件原样转投
 *   [MirrorForegroundService]（状态→常驻通知、帧→状态守望），与 UI 的 [uiConnector]
 *   并行——服务不持有连接状态（004 无状态底线），只在 onStartCommand/泵单拍时经
 *   [managerOrNull] 读取同一单例。泵归属标记 [servicePumpActive] 同槽置位/复位：
 *   在屏兜底泵（[AppClockPump]）以此让出（fix-app-runtime-sa，不双泵）。
 * - [uploadBaseUrl]：图片上传基地址（协议 §8 同端口 `POST /upload`）。配对层成功后从
 *   配对 ws url 推导 http(s) 基地址注入（清偿 session-ui 沉淀欠账②）；未注入时
 *   [HttpUrlConnectionUploader] 明确报错「未配置上传地址」，不静默。
 */
object ServiceWire {
    /** 传输工厂（UI/配对层在拨号前注入；默认真实 OkHttp，服务永远可跑）。 */
    @Volatile
    var transportFactory: TransportFactory = OkHttpTransportFactory

    /**
     * 配对试探针与持久连接共用真实传输能力，但生产 OkHttp 探针不得改写当前连接路径徽标。
     * 测试/替代工厂原样返回，保留既有注入接缝。
     */
    internal fun pairingTransportFactory(): TransportFactory =
        if (transportFactory === OkHttpTransportFactory) OkHttpPairingTransportFactory else transportFactory

    /**
     * UI 侧监听桥（UI 接线层注入；服务回调原样转投）。
     *
     * 挂载时补播当前连接态 + 最近一次全量 listing：工作区 VM 可能在连接已 READY 之后才挂载
     * （配对完成启动服务 / 冷启动 onCreate 即启动连接 → READY+listing 早于 UI 组合），若只靠
     * 包装监听"事件发生时转投"，VM 会错过已发生的 onStateChanged(READY) 与全量 listing——
     * 前者顶栏永停"连接中…"（fix-workspace-wiring），后者列表永空 / 只渲染零散 delta
     * （fix-cold-start-reconnect 层2 实证：force-stop 重开只显示一个真实舰队工作区，隔离会话
     * 缺失）。补播让新挂载的 UI 监听立即反映真实连接态与全量数据（004 无状态：列表由
     * READY+全量 listing 恢复，连接态由本补播即时对齐；语义同 SessionViewModel.init 自行
     * onStateChanged(manager.state())）。listing 后到达的 list_delta 会继续流式更新，
     * 补播的 listing 只是兜底基线（与 conn 层自动重 list 语义一致，无缓存引入）。
     *
     * @contract
     * @pre 无（任意时刻可挂/摘）
     * @post 挂载时补播当前连接态与最近一次全量 listing（若已有）；摘除（置 null）不补播
     * @err none
     * @inv 前台槽任意时刻至多一个（后挂覆盖前挂）。列表槽 [listConnector] 可同时挂，
     *      身份相同则扇出去重，避免同一 VM 收两遍帧。
     */
    @Volatile
    var uiConnector: ConnectionManager.Listener? = null
        set(value) {
            field = value
            if (value == null) return
            replayTo(value)
        }

    /**
     * 工作区列表监听槽（083 §10）：会话页占用 [uiConnector] 时，二级状态仍要进
     * [WorkspaceViewModel]。值必须来自连接层推送，不得轮询。
     */
    @Volatile
    var listConnector: ConnectionManager.Listener? = null
        set(value) {
            field = value
            if (value == null) return
            replayTo(value)
        }

    private fun replayTo(listener: ConnectionManager.Listener) {
        manager?.state()?.let(listener::onStateChanged)
        lastListing?.let(listener::onFrame)
    }

    private fun fanOut(block: (ConnectionManager.Listener) -> Unit) {
        val ui = uiConnector
        val list = listConnector
        ui?.let(block)
        if (list != null && list !== ui) block(list)
    }

    /**
     * 前台服务监听槽（feat-fg-service-wiring）：连接事件 → 常驻通知 + 状态守望。
     *
     * 与 [uiConnector] 并行扇出（服务回调在 manager 包装监听里原样转投本槽）。服务在
     * [MirrorForegroundService.onCreate] 挂载、[onDestroy] 摘除（幂等）。服务不缓存
     * [ConnectionManager] 引用（004 无状态底线：状态唯一来源是 prefs/conn 层），
     * 只在泵单拍时经 [managerOrNull] 读取同一单例。
     */
    @Volatile
    var serviceListener: ConnectionManager.Listener? = null

    /**
     * 前台服务时钟泵在跑标记（fix-app-runtime-sa 在屏兜底泵的归属判据）。
     *
     * 服务 onStartCommand 置位、onDestroy 复位（同一处挂 pumpRunnable）。在屏兜底泵
     * [AppClockPump.fallbackPumpOnce] 以本标记让出：服务泵在跑时兜底泵零工作，不双泵。
     * 服务被系统杀 → 本标记随进程/服务销毁复位 → 前台兜底泵接管；服务重建 → 服务泵
     * 恢复，兜底泵让出。标记与 [serviceListener] 同生命周期槽（都挂在服务上），但语义独立
     * （监听与泵归属不捆绑：前者管事件扇出，后者管时钟归属）。
     */
    @Volatile
    var servicePumpActive: Boolean = false

    /**
     * 最近一次全量 listing 帧（挂载补播用）。只保留最新一份（覆盖式），
     * 与 [uiConnector] setter 的补播语义配套：晚挂载 UI 以它为列表基线。
     */
    @Volatile
    private var lastListing: ListingFrame? = null

    /** 图片上传基地址（协议 §8 `POST /upload`；配对成功后由配对层注入，见 [deriveUploadBase]）。 */
    @Volatile
    var uploadBaseUrl: String? = null

    /** 配对后的连接配置（URL + token）。token 只上行一次、不回显、不落日志（红线）。 */
    @Volatile
    private var config: ConnectionConfig? = null

    /**
     * 注入配对后的连接配置（URL + token）。须在首次 [manager] 调用前注入；
     * 未注入时 [manager] 抛明确异常（halt 纪律：缺字段不猜）。
     *
     * **配置变更语义（fix-reconnect-stale-config P0 根因①）**：已存在 manager 的拨号地址是
     * 构造期快照（ConnectionConfig 是 val），setConfig 只更新本层字段不会热更已存活实例。
     * 用户先扫错地址(10.20.55.20)再改对(192.168.31.116)的真实序列下，若不做任何处理，重连
     * 永远拨旧址（真机实证：daemon 侧全程零连接到达）。因此：
     * - 新配置 ≠ 当前配置 ⇒ 重建 manager（stop 置空），下次 [manager()] 以新地址拨号；
     * - 相同配置重复注入（重复扫同码 / 冷启动同一 storedConfig）⇒ 保持单例，不闪断既有会话。
     * 重建语义 = 用户显式改了地址 → 旧链路的拨号意图作废，必须以新地址重拨（016 首触零阻断）。
     *
     * @contract
     * @pre 无（任意时刻可注入；重复注入同配置幂等）
     * @post config 更新为新值；新配置 ≠ 旧配置 ⇒ 清路径徽标并 [releaseManager]（下次
     *       [manager] 以新地址拨号）；相同配置重复注入 ⇒ 保持单例、不闪断
     * @err none
     * @inv 配置变更只影响后续拨号，不热更已存活 manager（ConnectionConfig 是 val 快照）
     */
    fun setConfig(c: ConnectionConfig) {
        val old = config
        config = c
        if (old != c) connectionPathState.value = null
        if (old != null && old != c) {
            // 配置变更：作废旧拨号目标（stop + 置空），下次 manager() 用新 config 重建。
            releaseManager()
        }
    }

    /**
     * 当前持久连接配置的只读快照；会话上传与 WebSocket 认证必须消费同一 token 来源。
     *
     * @contract
     * @pre 无
     * @post 已注入配置时返回该 [ConnectionConfig]，否则返回 null
     * @err none
     * @inv 不复制、不记录、不回显 token
     */
    internal fun currentConfig(): ConnectionConfig? = config

    /**
     * 最近一次真实 transport create 选择的网络类型；未拨号时为 null（不能按配置猜）。
     *
     * 必须是 Compose snapshot state：tailnet 冷启动首拨可能在节点 Starting 时先记为 LAN，
     * Up 后重试才切为 TAILNET。工作区/会话顶栏直接读 [connectionPath]，若这里只是普通字段，
     * 父组合不会因重试改路而重组，标签会永久停在首拨的 LAN。
     */
    private val connectionPathState = mutableStateOf<ConnectionPath?>(null)

    /** UI/通知读取实际拨号路径（READY 时即为当前已连接路径）。 */
    fun connectionPath(): ConnectionPath? = connectionPathState.value

    /** transport 工厂在每次拨号选择完成后记录；internal 防其他层伪造状态。 */
    internal fun recordConnectionPath(path: ConnectionPath) {
        connectionPathState.value = path
    }

    /**
     * 网络可达性恢复钩子（fix-reconnect-stale-config P0 根因② E2 缺口收口）。
     *
     * [NetworkConnectivityWatcher]（Android ConnectivityManager 回调）接这里；转发给当前
     * [ConnectionManager.onNetworkAvailable]——RECONNECTING 中立即重拨，打断退避（LIBRARIAN
     * 撞库回执：需求库无退避算法条目，但**网络恢复必须打断退避**）。manager 为 null（未建
     * 连接）时无态可打，安全跳过。
     */
    fun onNetworkAvailable() {
        manager?.onNetworkAvailable()
    }

    /**
     * 获取当前持久 [ConnectionManager] 单例；不存在（未创建）返回 null。
     *
     * 调用方是 [MirrorForegroundService]（时钟泵 [MirrorForegroundService.pumpOnce]、通知文案、
     * 启动时确保连接）与测试断言。服务不缓存引用——每次经本方法读取，避免把连接状态
     * 搬进服务（004 无状态底线）。未注入配置时返回 null（不是抛异常：服务可启动但连接
     * 不建，由冷启动路径恢复，不静默白屏）。
     *
     * @contract
     * @pre 无
     * @post 返回进程级唯一 [ConnectionManager] 或 null（未创建时）
     * @err none（不抛异常）
     * @inv 与 [manager] 共享同一单例；[releaseManager] 后返回 null
     */
    fun managerOrNull(): ConnectionManager? = manager

    /**
     * 获取/创建持久 [ConnectionManager]（应用进程级单例）。
     *
     * 调用方是配对/冷启动入口 `startPersistentConnection` 与 [SessionRoute]
     * （createSessionViewModel）；[MirrorForegroundService] 启动时经本方法确保连接创建
     * （已存在则复用，幂等）。connListener 既喂调用方（服务经 [serviceListener] 槽、
     * UI 经 [uiConnector] 槽），又原样转投 [uiConnector]/[serviceListener]（UI 与服务共享
     * 同一连接）。未注入配置（[setConfig] 未调用）时抛 [IllegalStateException]
     * （halt 纪律：缺字段不猜）。
     *
     * @contract
     * @pre 若尚未创建，则 [config] 必须已由 [setConfig] 注入（否则抛 [IllegalStateException]）
     * @post 返回进程级唯一 [ConnectionManager]；重复调用返回同一实例（幂等）
     * @err [IllegalStateException]：未注入配置即请求创建时
     * @inv manager 单例在进程存活期间复用；[releaseManager] 后才重建
     */
    fun manager(
        connListener: ConnectionManager.Listener,
    ): ConnectionManager {
        val existing = manager
        if (existing != null) return existing
        val cfg = config
            ?: throw IllegalStateException(
                "ConnectionConfig not set: pairing layer must call ServiceWire.setConfig before service start",
            )
        synchronized(this) {
            val m = manager
            if (m != null) return m
            val created = ConnectionManager(
                config = cfg,
                transportFactory = transportFactory,
            )
            created.setListener(
                object : ConnectionManager.Listener {
                    override fun onStateChanged(state: ConnectionState) {
                        connListener.onStateChanged(state)
                        serviceListener?.onStateChanged(state)
                        fanOut { it.onStateChanged(state) }
                    }

                    override fun onFrame(frame: FramePayload) {
                        // 捕获全量 listing：晚挂载 UI 的列表基线（uiConnector setter 补播）。
                        if (frame is ListingFrame) lastListing = frame
                        connListener.onFrame(frame)
                        serviceListener?.onFrame(frame)
                        fanOut { it.onFrame(frame) }
                    }

                    override fun onBinary(frame: BinaryFrame) {
                        connListener.onBinary(frame)
                        serviceListener?.onBinary(frame)
                        fanOut { it.onBinary(frame) }
                    }

                    override fun onLocalDecodeError(code: FrameError, message: String) {
                        connListener.onLocalDecodeError(code, message)
                        serviceListener?.onLocalDecodeError(code, message)
                        fanOut { it.onLocalDecodeError(code, message) }
                    }

                    override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) {
                        connListener.onInputResult(reqId, ok, reason)
                        serviceListener?.onInputResult(reqId, ok, reason)
                        fanOut { it.onInputResult(reqId, ok, reason) }
                    }

                    override fun onReconnect(attempt: Int, delayMs: Long) {
                        connListener.onReconnect(attempt, delayMs)
                        serviceListener?.onReconnect(attempt, delayMs)
                        fanOut { it.onReconnect(attempt, delayMs) }
                    }
                },
            )
            manager = created
            return created
        }
    }

    /** 连接管理器（进程级单例；由 [MirrorForegroundService.onDestroy] 或 [setConfig]
     *  配置变更时 stop）。 */
    @Volatile
    private var manager: ConnectionManager? = null

    /** 停止并释放连接管理器（服务 onDestroy / 配置变更时调用；幂等）。 */
    fun releaseManager() {
        val m = manager
        manager = null
        // 清掉列表基线：新管理器重连后以新 listing 为准（旧基线随旧连接作废）。
        lastListing = null
        m?.stop()
    }

    /**
     * 仅测试用：清空已注入的连接配置。
     *
     * [config] 与 manager 生命周期解耦：`releaseManager()` 不清 config（冷启动重连依赖
     * 配置持久——Activity 重建后经 [setConfig] 再次注入同一配置）。但 config 是进程级
     * 单例状态，测试泄漏会让后续用例误以为已配对（SessionRoute 据此建 SessionViewModel，
     * 实证污染 WorkspaceWiringTest）。测试应在 teardown 调用本方法复位。
     */
    internal fun resetConfigForTest() {
        config = null
        connectionPathState.value = null
    }
}

/**
 * 占位传输工厂：每次拨号立即失败（测试与降级用，见 [ServiceWire]）。
 *
 * 每次 create 返回一个立即 [WebSocketTransport.start] 即 onFailure 的传输；
 * ConnectionManager 依此进入 RECONNECTING → 指数退避重连，生命周期可运行。
 * 生产默认值是 [OkHttpTransportFactory]，本工厂不参与生产路径。
 */
object NoopTransportFactory : TransportFactory {
    override fun create(url: String): WebSocketTransport = object : WebSocketTransport {
        override var isOpen: Boolean = false
            get() = false

        override fun start(listener: TransportListener) {
            // 拨号立即失败：测试/降级用（本工厂非生产默认值，见类 KDoc）。
            listener.onFailure(
                IllegalStateException(
                    "no real transport wired: ServiceWire.transportFactory not injected",
                ),
            )
        }

        override fun sendText(text: String): Boolean = false

        override fun sendBinary(bytes: ByteArray): Boolean = false

        override fun close(reason: String) = Unit
    }
}
