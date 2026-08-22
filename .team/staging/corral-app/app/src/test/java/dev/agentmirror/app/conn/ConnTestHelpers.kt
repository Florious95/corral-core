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

import java.io.File

/**
 * 契约夹具解析器。
 *
 * 夹具是协议的一部分（leader 裁定，conn 知识基底 §1）：本层编解码单测必须消费
 * 同一份 testdata 做字节级断言。Gradle/IDE 对单测工作目录的约定不同，这里按候选
 * 顺序解析第一个存在的目录，全部缺失则断言失败（夹具缺失本身就是契约破坏）。
 */
internal object FixturePath {
    private val candidates = listOf(
        // Gradle unit tests run with working dir = module dir (app/app/),
        // so the fixtures live two levels up at repo root.
        File("../../server/internal/protocol/testdata"),
        // Some IDE/test-runner configs anchor at repo root.
        File("app/server/internal/protocol/testdata"),
        File("server/internal/protocol/testdata"),
    )

    private val resolved: File by lazy {
        candidates.firstOrNull { it.isDirectory && it.exists() }
            ?: error(
                "contract fixtures not found under any candidate: " +
                    candidates.joinToString { it.absolutePath },
            )
    }

    /** 读取一个夹具的字节内容。 */
    fun read(name: String): ByteArray = File(resolved, name).readBytes()

    /** 读取一个夹具的 UTF-8 文本内容。 */
    fun readText(name: String): String = File(resolved, name).readText()
}

/**
 * 记录型假传输：按脚本出队连接事件，供状态机驱动（不真正联网）。
 *
 * - [dialScript] 控制每次连接尝试的拨号结果（默认全部成功）。
 * - 已送出的帧被记录在 [sentText]/[sentBinary]，测试据此断言。
 * - 终端回调（onClosed/onFailure）只投递一次，close() 幂等。
 */
internal class FakeWebSocketTransport : WebSocketTransport {
    var listener: TransportListener? = null
    override var isOpen: Boolean = false
    val sentText = mutableListOf<String>()
    val sentBinary = mutableListOf<ByteArray>()

    /** 已消费的连接尝试次数（拨号脚本下标）。 */
    var dialIndex = 0

    /** 连接尝试脚本：true = 拨号成功 onOpen，false = 拨号失败 onFailure。 */
    var dialScript: List<Boolean> = listOf(true)

    /** 已投递过终端回调（onClosed/onFailure）则不再投递。 */
    private var terminalDelivered = false

    /** 记录 onClosed 是否曾以失败形式（非 client 主动）到达。 */
    val failureMessages = mutableListOf<String>()

    override fun start(listener: TransportListener) {
        this.listener = listener
        val ok = dialScript.getOrElse(dialIndex) { dialScript.last() }
        dialIndex++
        if (ok) {
            isOpen = true
            listener.onOpen()
        } else {
            isOpen = false
            terminalDelivered = true
            listener.onFailure(RuntimeException("dial failed"))
        }
    }

    override fun sendText(text: String): Boolean {
        if (!isOpen) return false
        sentText.add(text)
        return true
    }

    override fun sendBinary(bytes: ByteArray): Boolean {
        if (!isOpen) return false
        sentBinary.add(bytes)
        return true
    }

    override fun close(reason: String) {
        if (terminalDelivered) return
        if (isOpen) {
            isOpen = false
            terminalDelivered = true
            listener?.onClosed(1000, reason)
        }
    }

    /** 测试注入：模拟对端主动关闭连接（READY 掉线）。 */
    fun peerClose(code: Int, reason: String) {
        if (terminalDelivered || !isOpen) return
        isOpen = false
        terminalDelivered = true
        listener?.onClosed(code, reason)
    }

    /** 测试注入：模拟传输层失败。 */
    fun peerFailure(cause: Throwable) {
        if (terminalDelivered || !isOpen) return
        isOpen = false
        terminalDelivered = true
        failureMessages.add(cause.message ?: "peer failure")
        listener?.onFailure(cause)
    }

    /** 测试注入：喂入一条文本帧。 */
    fun deliverText(text: String) {
        listener?.onText(text)
    }

    /** 测试注入：喂入一条二进制帧。 */
    fun deliverBinary(bytes: ByteArray) {
        listener?.onBinary(bytes)
    }
}

/** 测试用假时钟：显式推进时间，供退避序列与输入超时断言。 */
internal class FakeClock(private var now: Long = 1_000_000L) : Clock {
    override fun nowMs(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}

/** 字节数组的十六进制表示（断言输出辅助）。 */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** 记录型上层监听：捕获状态变更、帧与输入回执，供断言。 */
internal class RecordingConnListener : ConnectionManager.Listener {
    val states = mutableListOf<ConnectionState>()
    val frames = mutableListOf<FramePayload>()
    val binaries = mutableListOf<BinaryFrame>()
    val inputResults = mutableListOf<Triple<Long, Boolean, String?>>()
    val reconnectEvents = mutableListOf<Pair<Int, Long>>()
    val decodeErrors = mutableListOf<Pair<FrameError, String>>()

    override fun onStateChanged(state: ConnectionState) {
        states.add(state)
    }

    override fun onFrame(frame: FramePayload) {
        frames.add(frame)
    }

    override fun onBinary(frame: BinaryFrame) {
        binaries.add(frame)
    }

    override fun onLocalDecodeError(code: FrameError, message: String) {
        decodeErrors.add(code to message)
    }

    override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) {
        inputResults.add(Triple(reqId, ok, reason))
    }

    override fun onReconnect(attempt: Int, delayMs: Long) {
        reconnectEvents.add(attempt to delayMs)
    }
}
