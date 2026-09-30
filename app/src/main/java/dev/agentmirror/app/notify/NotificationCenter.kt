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

package dev.agentmirror.app.notify

import android.content.Context
import dev.agentmirror.app.conn.TransportFactory
import dev.agentmirror.app.conn.TransportListener
import dev.agentmirror.app.conn.WebSocketTransport
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.app.service.NotificationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** 当前服务端对 notifications_v1 的支持情况（未连上前为 Unknown）。 */
enum class NotificationSupport { Unknown, Supported, Unsupported }

/**
 * 单个认证连接上的通知协商与历史同步（契约 §3、§4、§9）。
 *
 * - auth_ack 交集含 notifications_v1 → 记住握手 source/head，按本地游标发一次首页 sync；
 * - notifications_page 逐页入库（不提醒），有 next_page_token 才续页，终页才提交游标；
 * - notification 实时帧入库；source/stream 匹配且 seq > 握手 head 才有资格提醒一次。
 */
class NotificationHub(
    val repository: NotificationRepository,
    private val alerter: (NotificationRecord) -> Unit,
) {
    /** 一条物理连接。[send] 直接写该连接的传输（OkHttp send 线程安全，不经 Core）。 */
    class Link internal constructor(internal val send: (String) -> Boolean) {
        @Volatile internal var state: NotificationState? = null
        @Volatile internal var pendingReqId: Long = 0
    }

    private val reqIds = AtomicLong(0)

    private val _support = MutableStateFlow(NotificationSupport.Unknown)
    val support: StateFlow<NotificationSupport> = _support.asStateFlow()

    fun newLink(send: (String) -> Boolean): Link = Link(send)

    /** 必须在 Core 处理完同一 auth_ack（连接已 READY）之后调用。 */
    fun onAuthAck(link: Link, ack: NotificationInbound.AuthAck) {
        if (!ack.ok) return
        val state = ack.state?.takeIf { NotificationCodec.CAPABILITY in ack.capabilities }
        if (state == null) {
            // 旧服务端 / 能力未启用：本地缓存照常可读，但不发任何通知请求。
            _support.value = NotificationSupport.Unsupported
            return
        }
        link.state = state
        _support.value = NotificationSupport.Supported
        repository.beginSync(state.hostId) { cursor ->
            sendFirstPage(link, cursor)
        }
    }

    fun onLive(link: Link, record: NotificationRecord?) {
        val state = link.state
        if (record == null || state == null) {
            DiagLog.record("notify", "live_rejected valid=${record != null} negotiated=${state != null}")
            return
        }
        val eligible = record.hostId == state.hostId &&
            record.streamId == state.streamId &&
            record.seqNumber > (state.headSeq.toULongOrNull() ?: ULong.MAX_VALUE)
        repository.acceptLive(record, eligible, alerter)
    }

    fun onPage(link: Link, page: NotificationPage) {
        val state = link.state ?: return
        if (page.reqId != link.pendingReqId) return
        if (!page.ok) {
            link.pendingReqId = 0
            DiagLog.record("notify", "sync_failed req=${page.reqId}")
            return
        }
        val token = page.nextPageToken
        val cursor = page.snapshotCursor
        repository.acceptHistory(page.records) {
            when {
                token != null -> {
                    val reqId = nextReqId()
                    link.pendingReqId = reqId
                    link.send(NotificationCodec.continuation(reqId, token))
                }
                cursor != null -> {
                    link.pendingReqId = 0
                    repository.commitCursor(state.hostId, cursor)
                }
                else -> link.pendingReqId = 0
            }
        }
    }

    fun onClosed(link: Link) {
        link.state = null
        link.pendingReqId = 0
    }

    private fun sendFirstPage(link: Link, cursor: NotificationCursor?) {
        if (link.state == null) return
        val reqId = nextReqId()
        link.pendingReqId = reqId
        link.send(NotificationCodec.syncRequest(reqId, cursor))
    }

    /** req_id 取值 1..2,147,483,647，连接内不复用。 */
    private fun nextReqId(): Long = (reqIds.incrementAndGet() - 1) % Int.MAX_VALUE + 1
}

/**
 * 持久连接的传输装饰器：Core AAR 不认识 notifications_v1，这里在同一条 WebSocket 上
 * 给出站 auth 加能力声明、把通知帧在交给 Core 之前截获；其它帧逐字节透传。
 */
internal class NotificationTransport(
    private val inner: WebSocketTransport,
    private val hubProvider: () -> NotificationHub?,
) : WebSocketTransport {
    @Volatile private var hub: NotificationHub? = null

    override val isOpen: Boolean get() = inner.isOpen

    override fun start(listener: TransportListener) {
        val h = hubProvider()
        hub = h
        if (h == null) {
            inner.start(listener)
            return
        }
        val link = h.newLink(inner::sendText)
        inner.start(object : TransportListener {
            override fun onOpen() = listener.onOpen()

            override fun onText(text: String) {
                when (val msg = NotificationCodec.peek(text)) {
                    null -> listener.onText(text)
                    is NotificationInbound.AuthAck -> {
                        listener.onText(text)
                        h.onAuthAck(link, msg)
                    }
                    is NotificationInbound.Live -> h.onLive(link, msg.record)
                    is NotificationInbound.Page -> h.onPage(link, msg.page)
                }
            }

            override fun onBinary(bytes: ByteArray) = listener.onBinary(bytes)

            override fun onClosed(code: Int, reason: String) {
                h.onClosed(link)
                listener.onClosed(code, reason)
            }

            override fun onFailure(throwable: Throwable) {
                h.onClosed(link)
                listener.onFailure(throwable)
            }
        })
    }

    override fun sendText(text: String): Boolean =
        inner.sendText(if (hub != null) NotificationCodec.withCapability(text) else text)

    override fun sendBinary(bytes: ByteArray): Boolean = inner.sendBinary(bytes)

    override fun close(reason: String) = inner.close(reason)
}

/**
 * 进程级通知中心入口：Activity 与前台服务共用幂等 [install]；未安装（单测 / 未启动）时
 * 持久连接不声明能力，行为与旧客户端一致。
 */
object NotificationCenter {
    @Volatile
    private var hub: NotificationHub? = null

    fun install(context: Context): NotificationHub {
        hub?.let { return it }
        return synchronized(this) {
            hub ?: run {
                val app = context.applicationContext
                val helper = NotificationHelper(app).apply { createChannels() }
                NotificationHub(
                    repository = NotificationRepository(File(app.noBackupFilesDir, "notifications/notifications_v1.json")),
                    alerter = helper::postTaskNotification,
                ).also { hub = it }
            }
        }
    }

    fun hubOrNull(): NotificationHub? = hub

    /** 只包持久连接的工厂（配对探针不走这里）。 */
    fun wrap(factory: TransportFactory): TransportFactory =
        TransportFactory { url -> NotificationTransport(factory.create(url)) { hub } }

    internal fun installForTest(testHub: NotificationHub?) {
        hub = testHub
    }
}
