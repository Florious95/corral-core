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

package dev.agentmirror.app.pairing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.agentmirror.app.conn.BinaryFrame
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.ConnectionState
import dev.agentmirror.app.conn.ErrorFrame
import dev.agentmirror.app.conn.FrameError
import dev.agentmirror.app.conn.FramePayload

/**
 * 配对页状态机（纯 JVM 可测核心，验收模式 `--tests "*Pairing*"` 打在它上面）。
 *
 * 两条入口（扫码 / 手填兜底）→ 同一「连接试配对」状态机：用**独立** [ConnectionManager]
 * （注入的工厂新建，配一次建一条，不碰 ServiceWire 常驻连接）拨号并等待 auth 握手——
 * READY 即配对成功（auth 通过），持久化配置并暴露 [PairingStatus.Success]；auth 被拒 /
 * 协议错误 / 超时给明确失败（003 静默失效最高罪）。
 *
 * 纪律：
 * - token 不进日志、错误消息绝不携带 token 值（协议 §9 红线）；
 * - ts_authkey 仅占位保留（app-tsnet 接入前不消费，不参与配对）；
 * - 配对成功后才落配置（[PairingConfigStore.save]），失败不污染已有配置。
 */
class PairingViewModel(
    private val configStore: PairingConfigStore,
    private val connectionFactory: (ConnectionConfig) -> ConnectionManager,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ConnectionManager.Listener {

    // ---- 可观察 UI 状态（Compose 屏直接读）----

    /**
     * 扫码识别出的服务端地址摘要（上屏展示用）。
     * 仅地址，token 绝不上屏（§9 红线：裸 JSON 上屏超出「QR 是 token 唯一合法出口」范围）。
     */
    var recognizedUrl by mutableStateOf<String?>(null)

    /** 手填地址草稿（本地编辑零网络；扫码识别值自动回填，可改地址重试）。 */
    var manualUrl by mutableStateOf("")

    /** 手填 token 草稿（明文编辑；落配置后进入存储加密 TODO）。 */
    var manualToken by mutableStateOf("")

    /** 配对状态机（Idle/Pairing/Success/Failed）。 */
    var pairingStatus by mutableStateOf<PairingStatus>(PairingStatus.Idle)

    /** 试配对连接状态（配对中顶部提示「配对中…」；READY 即成功）。 */
    var connectionState by mutableStateOf(ConnectionState.STOPPED)

    /** 手填表单校验错误（提交前本地判定，明确报错）。 */
    var formError by mutableStateOf<String?>(null)

    /** 配对成功的配置（路由层观察 [PairingStatus.Success] 后读取落位）。 */
    var pendingConfig: PairingConfig? = null
        private set

    private var probe: ConnectionManager? = null

    /** 当前试配对目标（startPairing 记录，成功时持久化）。 */
    private var currentConfig: PairingConfig? = null

    /** 配对开始的单调时间（超时裁决用）。 */
    private var pairingStartedAt = 0L

    /** 已成功标记：成功后忽略后续 STOPPED（自身 stop 触发）不误报拒绝。 */
    private var succeeded = false

    // ---- 入口 ----

    /** 扫码文本进入：解析 → 校验 → 识别值回填手填表单 → 立即自动发起试配对。 */
    fun onQrText(text: String) {
        val payload = try {
            QrPayloadParser.parse(text)
        } catch (e: QrParseException) {
            // 坏 payload（坏 JSON/缺字段/坏版本）显式提示而非静默（003）；文案固定，不含 token（§9）。
            recognizedUrl = null
            failPairing(PairingFailCause.PARSE_ERROR, e.message ?: "二维码内容无法解析")
            return
        }
        recognizedUrl = payload.url
        // 整改点③：识别值自动回填手填表单（url+token 落输入框）——用户可改地址重试，
        // 正是绕过缺陷 A（TUN 地址不可达）的自救通路；地址上屏、token 不上屏（§9）。
        manualUrl = payload.url
        manualToken = payload.token
        startPairing(payload.url, payload.token)
    }

    /** 手填提交：本地校验（非法 url / 空 token 明确报错）→ 试配对。 */
    fun submitManual() {
        val url = manualUrl.trim()
        val token = manualToken.trim()
        formError = when {
            !isValidWsUrl(url) -> "服务端地址不合法（需 ws:// 或 wss://）"
            token.isEmpty() -> "配对 token 不能为空"
            else -> null
        }
        formError?.let { return }
        recognizedUrl = url
        startPairing(url, token)
    }

    /** 重试/重置：回到 Idle，可重新扫码或手填。 */
    fun reset() {
        succeeded = false
        // 先置 Idle 再停旧探针：旧探针 stop 的同步 STOPPED 回调看到非 Pairing 不误报拒绝。
        pairingStatus = PairingStatus.Idle
        stopProbe()
        currentConfig = null
        pendingConfig = null
        formError = null
        recognizedUrl = null
    }

    /** 重试上次失败的配对（失败态可用）：以失败时的配置重拨，不重新解析 QR。 */
    fun retry() {
        val cfg = currentConfig ?: return
        if (pairingStatus !is PairingStatus.Failed) return
        // 扫码识别值仍留在手填表单可编辑；地址上屏、token 不上屏（§9）。
        recognizedUrl = cfg.url
        startPairing(cfg.url, cfg.token)
    }

    /** 失败态是否可重试：解析失败的坏 payload 无配置，重试无意义（应重扫或手填）。 */
    val canRetry: Boolean
        get() = currentConfig != null

    /** 宿主节奏（生产定时器 / 测试假时钟）：重连泵 + 输入超时 + 配对超时。 */
    fun onTick(now: Long) {
        probe?.let { p ->
            p.pump(now)
            p.resolveExpiredInputs(now)
        }
        if (pairingStatus is PairingStatus.Pairing && now - pairingStartedAt > PAIR_TIMEOUT_MS) {
            failPairing(PairingFailCause.TIMEOUT, "配对超时：请检查服务端地址与网络后重试")
        }
    }

    // ---- ConnectionManager.Listener（单收件线程串行回调）----

    override fun onStateChanged(state: ConnectionState) {
        connectionState = state
        when (state) {
            ConnectionState.READY -> {
                if (succeeded) return
                succeeded = true
                // 配对成功 = auth 通过：立即持久化（003 可见成功）。token 不落日志（§9）。
                val cfg = currentConfig
                if (cfg == null) {
                    failPairing(PairingFailCause.PROTOCOL_ERROR, "配对失败：缺少配置")
                    return
                }
                configStore.save(cfg)
                pendingConfig = cfg
                pairingStatus = PairingStatus.Success
                stopProbe()
            }
            ConnectionState.STOPPED -> {
                // 永久关闭 = auth 被拒/显式关闭（拨号失败走 RECONNECTING 自动重连，不在此）。
                // 成功后的自身 stop 已被 succeeded 短路，不误报拒绝。
                if (succeeded || pairingStatus !is PairingStatus.Pairing) return
                failPairing(PairingFailCause.REJECTED, "配对被拒绝：服务端未接受该配对信息")
            }
            ConnectionState.RECONNECTING -> {
                // 配对阶段任何掉线/拨号失败（地址不可达正是缺陷 A 场景）都不可无限退避等超时：
                // 立即显式失败（003 失败可见、静默失效最高罪），重试按钮提供自救通路。
                if (pairingStatus is PairingStatus.Pairing) {
                    failPairing(PairingFailCause.UNREACHABLE, "配对失败：服务端地址不可达，请检查地址后重试")
                }
            }
            // CONNECTING/AUTHENTICATING：配对态统一显示「连接中…」，无需单态。
            else -> Unit
        }
    }

    override fun onFrame(frame: FramePayload) {
        // auth_ack ok:false 帧随后触发连接关闭 → 以 STOPPED 呈现；此处兜底协议错误明确上浮。
        if (frame is ErrorFrame && pairingStatus is PairingStatus.Pairing) {
            failPairing(PairingFailCause.PROTOCOL_ERROR, "服务端返回错误：${frame.code.wire}")
        }
    }

    override fun onBinary(frame: BinaryFrame) = Unit // 配对阶段不订阅镜像，无流帧。

    override fun onLocalDecodeError(code: FrameError, message: String) {
        if (pairingStatus is PairingStatus.Pairing) {
            failPairing(PairingFailCause.PROTOCOL_ERROR, "协议异常：${message ?: code.name}")
        }
    }

    override fun onInputResult(reqId: Long, ok: Boolean, reason: String?) = Unit // 配对不注入输入。

    override fun onReconnect(attempt: Int, delayMs: Long) = Unit // 重连由 conn 层自动，配对超时兜底。

    // ---- 内部 ----

    private fun startPairing(url: String, token: String) {
        succeeded = false
        // 先置 Idle 再停旧探针：旧探针 stop 的同步 STOPPED 回调看到非 Pairing 不误报拒绝。
        pairingStatus = PairingStatus.Idle
        stopProbe()
        currentConfig = PairingConfig(url, token)
        pendingConfig = null
        formError = null
        pairingStatus = PairingStatus.Pairing(url)
        pairingStartedAt = nowMs()
        val manager = connectionFactory(ConnectionConfig(url, token))
        probe = manager
        manager.setListener(this)
        manager.start()
    }

    /**
     * 进入失败态：**先置状态再停探针**（陷阱④反序）——旧探针 stop 的同步 STOPPED 回调
     * 看到非 Pairing 即被短路，不会用「拒绝」覆盖本次明确失败原因。
     */
    private fun failPairing(cause: PairingFailCause, message: String) {
        pairingStatus = PairingStatus.Failed(cause, message)
        stopProbe()
    }

    private fun stopProbe() {
        probe?.stop()
        probe = null
    }

    private companion object {
        /** 配对超时：拨号+auth 握手在期限内未 READY 即判失败（003 明确报错，不无限等）。 */
        const val PAIR_TIMEOUT_MS = 15_000L
    }
}
