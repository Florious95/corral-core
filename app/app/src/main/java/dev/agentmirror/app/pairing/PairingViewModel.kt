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
import dev.agentmirror.app.tsnet.TsnetDial
import dev.agentmirror.app.tsnet.TsnetState
import java.net.URI

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
 * - ts_authkey 非空时先启动内嵌 tsnet，并随成功配置安全持久化供冷启动恢复；
 * - 配对成功后才落配置（[PairingConfigStore.save]），失败不污染已有配置。
 */
class PairingViewModel(
    private val configStore: PairingConfigStore,
    private val connectionFactory: (ConnectionConfig) -> ConnectionManager,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * tsnet 起网入口（feat-ts-wire）：扫码带 key / 手填 key 时调用。生产由
     * PairingRoute 接 [dev.agentmirror.app.tsnet.TsnetWire.ensureStarted]；
     * 测试注入记录假件。VM 不持节点生命周期（节点随进程存活，归 TsnetWire）。
     */
    private val tsnetStarter: (String) -> Unit = {},
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

    /**
     * 手填 Tailscale auth key 草稿（feat-ts-wire 手填通道；屏上密文态渲染）。
     * 扫码带入的 key **不回填**本框——QR 是 key 唯一分发出口，不主动上屏（§2.1 红线）。
     */
    var manualTsAuthKey by mutableStateOf("")

    /** tsnet 节点状态（TS 态可视，018 标准5；TsnetWire 监听经 [onTsnetState] 投递）。 */
    var tsState by mutableStateOf<TsnetState>(TsnetState.Idle)
        private set

    /** TsnetWire 状态监听落点（可能来自后台线程；Compose snapshot 写线程安全）。 */
    fun onTsnetState(state: TsnetState) {
        tsState = state
        if (!waitingForTsnet || pairingStatus !is PairingStatus.Pairing) return
        when (state) {
            is TsnetState.Up -> {
                waitingForTsnet = false
                startProbe()
            }
            is TsnetState.Error -> {
                waitingForTsnet = false
                // 起网失败仍走候选推进：后面的 LAN 候选不被 tailnet 拖垮。
                advanceAttempt(PairingFailCause.UNREACHABLE, "tailnet 入网失败：${state.reason}")
            }
            else -> Unit
        }
    }

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

    /** 候选 ws URL 列表（fix-pairing-candidates：全败后失败卡逐项展示，主选打头；无候选为空）。 */
    var candidateUrls by mutableStateOf<List<String>>(emptyList())

    /** 候选逐试队列（主选打头；无候选 = 单元素 = 单次试配，行为与旧版一致）。 */
    private var attemptQueue: List<String> = emptyList()

    /** 当前逐试的 token（主选与候选同源同一 token）。 */
    private var currentToken = ""

    /** 当前 TS authkey（扫码/手填带入，trim 后；随配对成功持久化，重试序列间保留）。 */
    private var currentTsAuthKey = ""

    /** 当前尝试的超时预算（有候选时每候选 3s；无候选保持旧版 15s）。 */
    private var attemptBudgetMs = PAIR_TIMEOUT_MS

    /** 当前尝试在 [attemptQueue] 中的下标（推进逐试序列）。 */
    private var attemptIndex = 0

    /** tailnet 候选已就位，但必须等 tsnet Up 后才创建拨号探针。 */
    private var waitingForTsnet = false

    // ---- 入口 ----

    /** 扫码文本进入：解析 → 校验 → 识别值回填手填表单 → 立即自动发起试配对（含候选逐试）。 */
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
        // feat-ts-wire（011 预授权分发）：QR 带 authkey → 立即起网（先于试配对，SOCKS
        // 通道尽早就绪供 tailnet 候选拨号）。key 不回填手填框（QR 是唯一出口，不上屏）。
        currentTsAuthKey = payload.tsAuthKey.trim()
        if (currentTsAuthKey.isNotEmpty()) tsnetStarter(currentTsAuthKey)
        // fix-pairing-candidates：主选打头 + candidates 全候选逐试（无候选 = 单元素队列）。
        startPairingSequence(buildAttemptQueue(payload), payload.token, resetCandidates = true)
    }

    /** 手填提交：本地校验（非法 url / 空 token 明确报错）→ 试配对（单地址，无候选逐试）。 */
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
        // feat-ts-wire 手填通道（FIELD 裁定：输入框接活）：填了 key 即起网。
        currentTsAuthKey = manualTsAuthKey.trim()
        if (currentTsAuthKey.isNotEmpty()) tsnetStarter(currentTsAuthKey)
        startPairingSequence(listOf(url), token, resetCandidates = true)
    }

    /**
     * 手填表单从候选下拉选中一项（fix-pairing-candidates）：把选中地址回填地址框，
     * 用户可随后「连接」以该地址试配（多网卡下不赌主选的手填自救通路）。
     */
    fun selectCandidateUrl(url: String) {
        manualUrl = url
        formError = null
    }

    /** 重试/重置：回到 Idle，可重新扫码或手填。 */
    fun reset() {
        succeeded = false
        // 先置 Idle 再停旧探针：旧探针 stop 的同步 STOPPED 回调看到非 Pairing 不误报拒绝。
        pairingStatus = PairingStatus.Idle
        waitingForTsnet = false
        stopProbe()
        currentConfig = null
        pendingConfig = null
        formError = null
        recognizedUrl = null
        candidateUrls = emptyList()
        attemptQueue = emptyList()
        currentToken = ""
        currentTsAuthKey = ""
    }

    /**
     * 重试整个逐试序列（失败态可用）：从主选重新打头自动逐试，不重新解析 QR。
     * 无候选时 = 以原配置重拨（旧版行为不变）。
     */
    fun retry() {
        if (pairingStatus !is PairingStatus.Failed) return
        val queue = attemptQueue
        if (queue.isEmpty()) return
        // 扫码识别值仍留在手填表单可编辑；地址上屏、token 不上屏（§9）。
        recognizedUrl = queue.first()
        restartTsnetAfterFailure()
        startPairingSequence(queue, currentToken, resetCandidates = true)
    }

    /**
     * 失败态候选列表一键重试（fix-pairing-candidates）：以用户点中的候选地址单次试配，
     * 不复逐试序列（点谁试谁）；失败后候选列表保留可见（[candidateUrls] 不清空）。
     */
    fun retryCandidate(url: String) {
        if (pairingStatus !is PairingStatus.Failed) return
        if (!isValidWsUrl(url)) return
        recognizedUrl = url
        restartTsnetAfterFailure()
        // resetCandidates=false：保留全候选列表展示，单候选再失败仍可点其他候选。
        startPairingSequence(listOf(url), currentToken, resetCandidates = false)
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
        if (!waitingForTsnet && pairingStatus is PairingStatus.Pairing && now - pairingStartedAt > attemptBudgetMs) {
            // fix-pairing-candidates：超时也逐试推进（有候选时每候选 3s；无候选保持旧版 15s 单次失败）。
            advanceAttempt(PairingFailCause.TIMEOUT, "配对超时：请检查服务端地址与网络后重试")
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
                try {
                    configStore.save(cfg)
                } catch (_: Exception) {
                    pendingConfig = null
                    failPairing(PairingFailCause.PROTOCOL_ERROR, "配对失败：安全存储不可用，请检查设备安全设置后重试")
                    return
                }
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
                // 立即显式失败（003 失败可见、静默失效最高罪）。fix-pairing-candidates：
                // 有候选时推进到下一候选自动逐试（每候选 3s 预算）；无候选保持旧版立即失败。
                if (pairingStatus is PairingStatus.Pairing) {
                    advanceAttempt(PairingFailCause.UNREACHABLE, "配对失败：服务端地址不可达，请检查地址后重试")
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

    /**
     * 启动一个配对逐试序列（主选 + 候选）：从队列头开始逐个试。无候选时队列为单元素，
     * 即旧版单次试配行为（15s 超时不变）；有候选时每候选 3s 超时、拨号失败立即推进。
     */
    private fun startPairingSequence(queue: List<String>, token: String, resetCandidates: Boolean) {
        succeeded = false
        // 先置 Idle 再停旧探针：旧探针 stop 的同步 STOPPED 回调看到非 Pairing 不误报拒绝（陷阱④反序）。
        pairingStatus = PairingStatus.Idle
        stopProbe()
        attemptQueue = queue
        currentToken = token
        if (resetCandidates) {
            // 全候选列表（主选打头）供失败后逐项展示/手填下拉；无候选时为空（旧行为）。
            candidateUrls = if (queue.size > 1) queue else emptyList()
        }
        pendingConfig = null
        formError = null
        waitingForTsnet = false
        // 每次新序列从队列头开始：attemptIndex 必须归零，否则 retryCandidate/retry 的新序列
        // beginAttempt 时用旧序列的推进值判 `attemptIndex >= size` 直接误落「全部候选失败」。
        attemptIndex = 0
        beginAttempt()
    }

    /** tsnet 失败后的重试必须重新起网；同一 key 的 Up/Starting 节点由 TsnetWire 幂等保留。 */
    private fun restartTsnetAfterFailure() {
        if (currentTsAuthKey.isNotEmpty() && (tsState is TsnetState.Idle || tsState is TsnetState.Error)) {
            tsnetStarter(currentTsAuthKey)
        }
    }

    /**
     * 从当前 [attemptQueue] 头开始一次尝试：无更多候选则落「全部候选均不可达」失败。
     * 每次尝试独立记录配对开始时刻与超时预算（有候选 3s / 无候选 15s）。
     */
    private fun beginAttempt() {
        if (attemptIndex >= attemptQueue.size) {
            failPairing(PairingFailCause.UNREACHABLE, "已尝试全部候选地址，均无法连接")
            return
        }
        val url = attemptQueue[attemptIndex]
        attemptBudgetMs = if (attemptQueue.size > 1) CANDIDATE_TRY_MS else PAIR_TIMEOUT_MS
        // authkey 随配置走（成功即持久化，冷启动重连用它重新起网，feat-ts-wire）。
        currentConfig = PairingConfig(url, currentToken, currentTsAuthKey)
        pendingConfig = null
        recognizedUrl = url
        pairingStatus = PairingStatus.Pairing(url)
        if (mustWaitForTsnet(url)) {
            when (val state = tsState) {
                is TsnetState.Up -> startProbe()
                is TsnetState.Error ->
                    advanceAttempt(PairingFailCause.UNREACHABLE, "tailnet 入网失败：${state.reason}")
                // 等待起网不占用候选的 3s/15s 拨号预算，预算从 Up 后真正首拨起算。
                else -> waitingForTsnet = true
            }
            return
        }
        startProbe()
    }

    /** 真正创建并启动当前候选的试配对探针。 */
    private fun startProbe() {
        val config = checkNotNull(currentConfig) { "pairing config missing before dial" }
        pairingStartedAt = nowMs()
        val manager = connectionFactory(ConnectionConfig(config.url, config.token))
        probe = manager
        manager.setListener(this)
        manager.start()
    }

    /** 只有携带/正在使用 tsnet 的 CGNAT 字面地址需等 Up；无 key 降级仍按旧路径直拨显错。 */
    private fun mustWaitForTsnet(url: String): Boolean {
        val tailnet = TsnetDial.isTailnetHost(URI(url).host)
        return tailnet && (currentTsAuthKey.isNotEmpty() || tsState is TsnetState.Starting)
    }

    /**
     * 推进到下一候选：本次尝试失败（不可达/超时）时调用。单候选（无 candidates）直接落
     * 失败（[cause]/[message] 即最终失败，旧版行为不变）；多候选推进，最后一个也失败则
     * 落「全部候选均不可达」（候选列表已由 [candidateUrls] 暴露，UI 可一键重试）。
     */
    private fun advanceAttempt(cause: PairingFailCause, message: String) {
        // 先置 Idle 再停旧探针：旧探针 stop 的同步 STOPPED 回调看到非 Pairing 不误报拒绝。
        pairingStatus = PairingStatus.Idle
        waitingForTsnet = false
        stopProbe()
        if (attemptQueue.size <= 1) {
            failPairing(cause, message)
            return
        }
        attemptIndex++
        if (attemptIndex >= attemptQueue.size) {
            failPairing(PairingFailCause.UNREACHABLE, "已尝试全部候选地址，均无法连接")
        } else {
            beginAttempt()
        }
    }

    /** 组装逐试队列：主选打头 + candidates 去重；非法/空候选在解析层已过滤。 */
    private fun buildAttemptQueue(payload: QrPayload): List<String> {
        val seen = LinkedHashSet<String>()
        seen += payload.url
        payload.candidates.forEach { c ->
            val t = c.trim()
            if (t.isNotEmpty() && isValidWsUrl(t)) seen += t
        }
        return seen.toList()
    }

    /**
     * 进入失败态：**先置状态再停探针**（陷阱④反序）——旧探针 stop 的同步 STOPPED 回调
     * 看到非 Pairing 即被短路，不会用「拒绝」覆盖本次明确失败原因。
     */
    private fun failPairing(cause: PairingFailCause, message: String) {
        // leader 追加范围：失败态不得残留「正在连接」进行中文案——识别摘要随失败清空，
        // 避免 ScanCard 在 Failed 态仍显示旧地址的进行中文本（003 失败可见、状态纯净）。
        recognizedUrl = null
        pairingStatus = PairingStatus.Failed(cause, message)
        waitingForTsnet = false
        stopProbe()
    }

    private fun stopProbe() {
        probe?.stop()
        probe = null
    }

    private companion object {
        /** 配对超时：拨号+auth 握手在期限内未 READY 即判失败（003 明确报错，不无限等）。 */
        const val PAIR_TIMEOUT_MS = 15_000L

        /** 候选逐试单地址预算（fix-pairing-candidates，leader 裁定 3s/个）。 */
        const val CANDIDATE_TRY_MS = 3_000L
    }
}
