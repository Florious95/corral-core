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
 * - [transportFactory]：真实 WebSocket 传输工厂。conn 层只交付传输抽象
 *   （Transport.kt：OkHttp 真连接归 e2e），未注入真实工厂时用 [NoopTransportFactory]——
 *   每次拨号立即失败 → ConnectionManager 指数退避持续重连，服务生命周期与状态守望
 *   保持可运行、可测，不静默吞（失败经 onFailure 上浮到重连路径）。
 * - [uiConnector]：UI 侧监听桥。服务持有唯一 ConnectionManager（背景常驻连接），
 *   UI（WorkspaceViewModel / SessionViewModel 等）经此桥订阅同一连接，服务回调原样转投。
 *   未接线（null）时服务仅自管通知，不影响其他包。
 */
object ServiceWire {
    /** 传输工厂（UI/配对层在启动服务前注入；默认占位工厂保证服务永远可跑）。 */
    @Volatile
    var transportFactory: TransportFactory = NoopTransportFactory

    /** UI 侧监听桥（UI 接线层注入；服务回调原样转投）。 */
    @Volatile
    var uiConnector: ConnectionManager.Listener? = null

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
