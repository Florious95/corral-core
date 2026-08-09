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

import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.FrameError
import dev.agentmirror.app.conn.FramePayload
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.conn.TransportListener
import dev.agentmirror.app.conn.WebSocketTransport

/**
 * 前台服务的接线点（service 包之外唯一改动接口；UI/配对层注入）。
 *
 * - [transportFactory]：真实 WebSocket 传输工厂。默认 [OkHttpTransportFactory]
 *   （leader 裁定 A，清偿传输欠账①）：配对后注入 [setConfig] 并启动服务即走真实 OkHttp
 *   连接；[NoopTransportFactory] 保留为测试与降级用（拨号立即失败 → conn 层退避重连，
 *   生命周期可运行、不静默吞）。
 * - [uiConnector]：UI 侧监听桥。服务持有唯一 ConnectionManager（背景常驻连接），
 *   UI（WorkspaceViewModel / SessionViewModel / PairingViewModel 等）经此桥订阅同一连接，
 *   服务回调原样转投。多槽扇出：单屏在屏时挂一槽（SessionRoute 等），无需多屏同挂；
 *   配对试连接是**独立** ConnectionManager，不走本桥。
 * - [uploadBaseUrl]：图片上传基地址（协议 §8 同端口 `POST /upload`）。配对层成功后从
 *   配对 ws url 推导 http(s) 基地址注入（清偿 session-ui 沉淀欠账②）；未注入时
 *   [HttpUrlConnectionUploader] 明确报错「未配置上传地址」，不静默。
 */
object ServiceWire {
    /** 传输工厂（UI/配对层在启动服务前注入；默认真实 OkHttp，服务永远可跑）。 */
    @Volatile
    var transportFactory: TransportFactory = OkHttpTransportFactory

    /**
     * UI 侧监听桥（UI 接线层注入；服务回调原样转投）。
     *
     * 挂载时补播当前连接态：工作区 VM 可能在连接已 READY 之后才挂载（配对完成启动服务 →
     * READY 早于 UI 组合），若只靠包装监听"事件发生时转投"，VM 会错过已发生的
     * onStateChanged(READY)，顶栏永远停在"连接中…"。补播让新挂载的 UI 监听立即反映真实
     * 连接态（004 无状态：列表由 READY+全量 listing 恢复，连接态由本补播即时对齐；
     * 语义同 SessionViewModel.init 自行 onStateChanged(manager.state())）。
     */
    @Volatile
    var uiConnector: ConnectionManager.Listener? = null
        set(value) {
            field = value
            // manager 可能尚未创建（fg-service 未启动 / 连接未配置）：此时无当前态可补，跳过。
            value?.let { manager?.state()?.let(it::onStateChanged) }
        }

    /** 图片上传基地址（协议 §8 `POST /upload`；配对成功后由配对层注入，见 [deriveUploadBase]）。 */
    @Volatile
    var uploadBaseUrl: String? = null

    /** 配对后的连接配置（URL + token）。token 只上行一次、不回显、不落日志（红线）。 */
    @Volatile
    private var config: ConnectionConfig? = null

    /**
     * 注入配对后的连接配置（URL + token）。须在服务启动前调用；
     * 未注入时服务启动抛明确异常（halt 纪律：缺字段不猜）。
     */
    fun setConfig(c: ConnectionConfig) {
        config = c
    }

    /**
     * 获取/创建持久 [ConnectionManager]（前台服务与应用同进程生命周期）。
     *
     * 仅 [MirrorForegroundService] 首次 onCreate 时创建；connListener 既喂服务自身
     * （连接状态→常驻通知、帧→状态守望），又原样转投 [uiConnector]（UI 侧共享同一连接）。
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
                        uiConnector?.onStateChanged(state)
                    }

                    override fun onFrame(frame: FramePayload) {
                        connListener.onFrame(frame)
                        uiConnector?.onFrame(frame)
                    }

                    override fun onBinary(frame: BinaryFrame) {
                        connListener.onBinary(frame)
                        uiConnector?.onBinary(frame)
                    }

                    override fun onLocalDecodeError(code: FrameError, message: String) {
                        connListener.onLocalDecodeError(code, message)
                        uiConnector?.onLocalDecodeError(code, message)
                    }

                    override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) {
                        connListener.onInputResult(reqId, ok, reason)
                        uiConnector?.onInputResult(reqId, ok, reason)
                    }

                    override fun onReconnect(attempt: Int, delayMs: Long) {
                        connListener.onReconnect(attempt, delayMs)
                        uiConnector?.onReconnect(attempt, delayMs)
                    }
                },
            )
            manager = created
            return created
        }
    }

    /** 连接管理器（服务持有；[MirrorForegroundService.onDestroy] 时 stop）。 */
    @Volatile
    private var manager: ConnectionManager? = null

    /** 停止并释放连接管理器（服务 onDestroy 调用）。 */
    fun releaseManager() {
        val m = manager
        manager = null
        m?.stop()
    }
}

/**
 * 占位传输工厂：每次拨号立即失败（未注入真实传输时的默认，见 [ServiceWire]）。
 *
 * 每次 create 返回一个立即 [WebSocketTransport.start] 即 onFailure 的传输；
 * ConnectionManager 依此进入 RECONNECTING → 指数退避重连，生命周期可运行。
 */
object NoopTransportFactory : TransportFactory {
    override fun create(url: String): WebSocketTransport = object : WebSocketTransport {
        override var isOpen: Boolean = false
            get() = false

        override fun start(listener: TransportListener) {
            // 拨号立即失败：真实传输未接线（ServiceWire.transportFactory 仍为默认值）。
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
