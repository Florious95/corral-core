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

import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.conn.TransportListener
import dev.agentmirror.app.conn.WebSocketTransport
import dev.agentmirror.app.tsnet.TsnetDial
import dev.agentmirror.app.tsnet.TsnetWire
import dev.agentmirror.app.tsnet.ConnectionPath
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * OkHttp WebSocket 真实传输（conn 层 [WebSocketTransport] 接口的 service 实现）。
 *
 * leader 裁定 A（清偿传输欠账①）：conn 层只交付抽象与状态机，真实传输由本层提供并
 * 注入 [ServiceWire.transportFactory]。语义严格对齐 conn KDoc：
 * - text = JSON 控制帧、binary = 二进制流帧（协议 §1）；
 * - 关闭/失败必须可判定上抛（[TransportListener.onClosed]/[onFailure]），静默吞错最高罪。
 *
 * 线程语义：OkHttp 回调都在 WebSocket 单线程串行到达（conn 层状态机据此免锁），本类
 * 原样转发。OkHttp 的 [WebSocketListener] 失败/关闭必有一次终结回调，映射到传输终结。
 */
class OkHttpWebSocketTransport(
    url: String,
    private val client: OkHttpClient = defaultClient(),
) : WebSocketTransport {

    /** 已建立连接（OkHttp onOpen 后为 true）。 */
    override var isOpen: Boolean = false
        private set

    private var socket: WebSocket? = null

    private var listener: TransportListener? = null

    private val request = Request.Builder().url(url).build()

    override fun start(listener: TransportListener) {
        this.listener = listener
        val ws = client.newWebSocket(request, webSocketListener)
        socket = ws
    }

    override fun sendText(text: String): Boolean {
        val s = socket ?: return false
        return s.send(text)
    }

    override fun sendBinary(bytes: ByteArray): Boolean {
        val s = socket ?: return false
        return s.send(ByteString.of(*bytes))
    }

    override fun close(reason: String) {
        val s = socket ?: return
        s.close(1000, reason)
    }

    /** 终结守护：OkHttp 单终结（onClosed 或 onFailure），不双发。 */
    private var terminalDelivered = false

    private fun deliverTerminal(run: () -> Unit) {
        if (terminalDelivered) return
        terminalDelivered = true
        run()
    }

    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isOpen = true
            listener?.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            listener?.onText(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            listener?.onBinary(bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // 服务端发起关闭：走 WebSocket 关闭握手后 onClosed 会到，这里不提前终结，
            // 由 onClosed 统一收尾（避免握手窗口内丢帧）。
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isOpen = false
            deliverTerminal { listener?.onClosed(code, reason) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isOpen = false
            deliverTerminal { listener?.onFailure(t) }
        }
    }

    private companion object {
        /** 默认客户端：短读写超时 + 不自动重连（重连归 conn 层状态机，传输逐拨号）。 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}

/**
 * 真实传输工厂：每次拨号新建一条 OkHttp WebSocket（WebSocket 是逐连接实例）。
 * 注入 [ServiceWire.transportFactory] 即全 app 共享真实连接（session/workspace 共用）。
 *
 * feat-ts-wire 按址选路：每次 create（= conn 层每次拨号/重连）现查 [TsnetWire.state]，
 * 目标是 tailnet 段（100.64/10）且节点 Up 才经 loopback SOCKS5 走 tsnet（自实现握手
 * 的 socketFactory 注入，[TsnetDial.socketFactoryFor]——Android libcore 内建 SOCKS
 * 客户端认证不生效），其余直拨。选路在拨号时刻而非工厂构造时刻——节点 Up 晚于
 * 首拨时，conn 层退避重连的下一次 create 自然拿到 SOCKS 通路，无需额外通知。
 */
object OkHttpTransportFactory : TransportFactory {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override fun create(url: String): WebSocketTransport = create(url, recordConnectionPath = true)

    /** 配对探针复用同一选路实现，但不得覆盖持久连接的用户可见路径。 */
    internal fun create(url: String, recordConnectionPath: Boolean): WebSocketTransport {
        // 提取 host 判 tailnet 段；坏 URL host=null → 直拨（拨号层自会显式失败，003）。
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        val sf = TsnetDial.socketFactoryFor(TsnetWire.state, host)
        // 记录的是本次实际选择：只有确实注入 SOCKS socketFactory 才叫 tailnet；
        // 不能仅凭目标 100.x 或节点 Up 事后推断，否则 UI 会把直拨误标成隧道。
        if (recordConnectionPath) {
            ServiceWire.recordConnectionPath(if (sf == null) ConnectionPath.LAN else ConnectionPath.TAILNET)
        }
        // newBuilder 共享连接池/线程池，仅 socketFactory 差异——非 tailnet 目标零行为变化。
        val chosen = if (sf == null) client else client.newBuilder().socketFactory(sf).build()
        return OkHttpWebSocketTransport(url, chosen)
    }
}

/** 独立配对探针工厂：真实拨号选路不变，仅不写持久连接的全局 LAN/tailnet 徽标。 */
internal object OkHttpPairingTransportFactory : TransportFactory {
    override fun create(url: String): WebSocketTransport =
        OkHttpTransportFactory.create(url, recordConnectionPath = false)
}
