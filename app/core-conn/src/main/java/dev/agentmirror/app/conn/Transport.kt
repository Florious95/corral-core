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

/**
 * WebSocket 传输抽象。
 *
 * 生产实现由 OkHttp 提供（Apache-2.0，Android 事实标准，见 conn 知识基底 §1 选型）；
 * 单测用假传输（见 FakeWebSocketTransport）驱动状态机，OkHttp 真连接归 e2e。
 *
 * 约定：监听回调在**单一收件线程**上串行调用（OkHttp WebSocket 监听即如此），
 * 状态机据此免锁；调用方不得在回调内重入本传输。
 */
interface WebSocketTransport {
    /** 是否已建立连接（onOpen 后为 true）。 */
    val isOpen: Boolean

    /** 开始拨号并挂上监听；onOpen 到达前不得发任何帧。 */
    fun start(listener: TransportListener)

    /**
     * 发送一条文本消息（WS text = 一个 JSON 控制帧）。
     * @return true = 已交给传输；false = 发送失败（连接已不可用）。
     */
    fun sendText(text: String): Boolean

    /**
     * 发送一条二进制消息（WS binary = 一个二进制流帧）。
     * @return true = 已交给传输；false = 发送失败（连接已不可用）。
     */
    fun sendBinary(bytes: ByteArray): Boolean

    /** 主动关闭；随后必然回调一次 [TransportListener.onClosed] 或 [onFailure]。 */
    fun close(reason: String)
}

/**
 * 传输事件回调（单收件线程串行到达）。
 *
 * 回调顺序：start 后至多一次 onOpen（拨号成功）或 onFailure（拨号失败）；
 * 打开后按到达顺序交错 onText / onBinary；最终一次 onClosed 或 onFailure 后不再回调。
 */
interface TransportListener {
    /** 连接建立。 */
    fun onOpen()

    /** 收到一条文本消息（WS text）。 */
    fun onText(text: String)

    /** 收到一条二进制消息（WS binary）。 */
    fun onBinary(bytes: ByteArray)

    /** 连接已结束（对端或本端关闭握手完成后）。 */
    fun onClosed(code: Int, reason: String)

    /** 传输层失败（网络断、拨号失败、读异常等）。 */
    fun onFailure(throwable: Throwable)
}

/**
 * 每次连接尝试创建一条全新传输（OkHttp WebSocket 是逐连接实例）。
 * 重连需要新传输，工厂使测试能按尝试次数出队假传输。
 */
fun interface TransportFactory {
    fun create(url: String): WebSocketTransport
}
