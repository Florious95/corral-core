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
 * 单条 WebSocket 生命周期状态机（docs/protocol.md §3）。
 *
 * 生命周期：传输建立 → 发 auth（一次性上行）→ 等 auth_ack → READY（可交换帧）→ 关闭。
 * - auth_ack ok ⇒ [Listener.onReady]；ok:false 或 auth 后立即断开 ⇒ 视作拒绝（永久关闭）。
 * - READY 后掉线 ⇒ 非永久关闭（上层重连并重放 auth+subscribe）。
 * - 拨号失败（auth 未发出）⇒ 非永久关闭（网络问题，可重连）。
 *
 * 本地解码失败（坏帧/未知 type/版本不匹配）显式经 [Listener.onLocalDecodeError] 上浮，
 * 不静默吞掉（静默失效猎杀）。发送路径返回值可判定：非 READY 或校验不过即返回 false。
 */
class Connection(
    private val transport: WebSocketTransport,
    private val token: String,
    private val listener: Listener,
) : TransportListener {

    /** 是否已通过认证、可交换业务帧。 */
    var isReady: Boolean = false
        private set

    /** 事件回调（单一收件线程串行到达）。 */
    interface Listener {
        /** 传输已建立且 auth 已发出，等待 auth_ack。 */
        fun onOpened()

        /** auth_ack ok，连接就绪，可交换业务帧。 */
        fun onReady()

        /** 解码出的一帧控制帧（含 listing / list_delta / input_ack / error / auth_ack 透传）。 */
        fun onFrame(frame: FramePayload)

        /** 解码出的一帧二进制流帧（snapshot / delta / scrollback）。 */
        fun onBinary(frame: BinaryFrame)

        /** 本地解码失败（坏帧/未知 type/版本不匹配等），显式上浮不静默。 */
        fun onLocalDecodeError(code: FrameError, message: String)

        /**
         * 连接结束。
         * @param permanent true = 永久关闭（auth 被拒 / 显式关闭），不再重连；
         *                  false = 可重连（READY 掉线或拨号失败）。
         * @param reason 人类可读原因。
         */
        fun onClosed(permanent: Boolean, reason: String)
    }

    private var closed = false
    private var authSent = false
    private var authAcked = false

    /** 显式设定的关闭属性；优先于状态推导。 */
    private var explicitPermanent: Boolean? = null

    /** 开始拨号。 */
    fun start() {
        transport.start(this)
    }

    /**
     * 发送一个控制帧。
     * @return false = 连接未就绪或帧校验不过（调用方必须能判定失败）。
     */
    fun send(frame: FramePayload): Boolean {
        if (closed || !isReady) return false
        val text = try {
            FrameCodec.encode(frame)
        } catch (e: FrameEncodeException) {
            listener.onLocalDecodeError(e.code, e.message ?: "encode rejected")
            return false
        }
        return transport.sendText(text)
    }

    /**
     * 发送一个二进制流帧。
     * @return false = 连接未就绪或帧校验不过。
     */
    fun sendBinary(frame: BinaryFrame): Boolean {
        if (closed || !isReady) return false
        val bytes = try {
            BinaryFrameCodec.encode(frame)
        } catch (e: FrameEncodeException) {
            listener.onLocalDecodeError(e.code, e.message ?: "encode rejected")
            return false
        }
        return transport.sendBinary(bytes)
    }

    /** 显式关闭（永久）。 */
    fun close() {
        if (closed) return
        explicitPermanent = true
        transport.close("client close")
    }

    // ---- TransportListener ----

    override fun onOpen() {
        if (closed) return
        authSent = true
        listener.onOpened()
        val auth = AuthFrame(token)
        val ok = try {
            transport.sendText(FrameCodec.encode(auth))
        } catch (e: FrameEncodeException) {
            false
        }
        if (!ok) {
            // auth 发送失败视为本次连接失败（可重连），而非认证拒绝。
            explicitPermanent = false
            transport.close("auth send failed")
        }
    }

    override fun onText(text: String) {
        if (closed) return
        val frame = try {
            FrameCodec.decode(text)
        } catch (e: FrameDecodeException) {
            // 本地解码失败显式上浮：坏帧/未知 type/版本不匹配必须浮出，不得静默。
            listener.onLocalDecodeError(e.code, e.message ?: "decode rejected")
            return
        }
        if (frame is AuthAckFrame) {
            if (frame.ok) {
                authAcked = true
                isReady = true
                listener.onReady()
            } else {
                // 拒绝：S 随后立即关闭连接。
                explicitPermanent = true
                listener.onFrame(frame)
                transport.close("auth rejected: ${frame.reason}")
                return
            }
        }
        listener.onFrame(frame)
    }

    override fun onBinary(bytes: ByteArray) {
        if (closed) return
        val frame = try {
            BinaryFrameCodec.decode(bytes)
        } catch (e: FrameDecodeException) {
            listener.onLocalDecodeError(e.code, e.message ?: "binary decode rejected")
            return
        }
        listener.onBinary(frame)
    }

    override fun onClosed(code: Int, reason: String) {
        finish(reason)
    }

    override fun onFailure(throwable: Throwable) {
        finish(throwable.message ?: "transport failure")
    }

    private fun finish(reason: String) {
        if (closed) return
        closed = true
        val permanent = explicitPermanent ?: when {
            authAcked -> false // READY 掉线 → 可重连
            authSent -> true   // auth 后立即断开 → 视作拒绝
            else -> false      // 拨号失败（auth 未发出）→ 可重连
        }
        listener.onClosed(permanent, reason)
    }
}
