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

import java.net.URI

/**
 * 配对领域模型（纯 JVM，可单测）。
 *
 * 对应服务端 `server/internal/pairing/qr.go` 的 QR JSON 契约（v1）：
 * `{"v":1,"url":"ws://…/ws","token":"…","ts_authkey":""}`——字段名/顺序是线上契约，
 * 客户端解析必须与之对齐（知识基底 §2：先读 qr.go 再实现）。
 */

/** 扫描 QR 解析出的配对信息（契约与 qr.go Payload 一致）。 */
data class QrPayload(
    /** 载荷 schema 版本（当前固定 1；其他版本拒绝）。 */
    val version: Int,
    /** 旧 QR 的主 URL；新 QR 可省略，客户端会由主机记录/发现补出端点。 */
    val url: String = "",
    /** 配对 token：只上行一次，不回显、不落日志（协议 §9）。 */
    val token: String,
    /** Tailscale auth key：非空时扫码同时启动 App 内嵌 tsnet；不回显、不落日志。 */
    val tsAuthKey: String = "",
    /** 旧 QR 的候选提示；新客户端只把它当未信任发现提示，绝不直接 auth。 */
    val candidates: List<String> = emptyList(),
    /** 新 QR 的公开主机身份；绑定的是主机，不是 URL。 */
    val hostId: String? = null,
    /** 主机监听端口，来自 QR 的可信提示而非用户输入。 */
    val port: Int? = null,
    /** TS peer StableID，用于无窗口截断的全表命中。 */
    val tsNodeId: String? = null,
    /** 仅展示，不参与身份匹配。 */
    val name: String? = null,
) {
    /** data class 默认 toString 会带出两项凭据；显式封口，避免崩溃/调试输出误泄漏。 */
    override fun toString(): String =
        "QrPayload(v=$version, url=$url, token=[redacted], tsAuthKey=[redacted], hostId=$hostId, port=$port, tsNodeId=$tsNodeId, name=$name, candidates=$candidates)"
}

/** 配对成功后的连接配置负载（持久化：url + token + 可选 TS authkey）。 */
data class PairingConfig(
    /** 当前 READY 的 ws endpoint；未首次 READY 的 URL-less 绑定允许为空。 */
    val url: String,
    /** 主机 token，只在 identify 成功后进入 WS auth。 */
    val token: String,
    /** 待用/当前 TS auth key，磁盘由 Keystore 加密。 */
    val tsAuthKey: String = "",
    /** 公开主机身份；空值只表示旧 v1 配置。 */
    val hostId: String? = null,
    val port: Int? = null,
    val tsNodeId: String? = null,
    val name: String? = null,
    /** 旧主 URL，仅用于精确 404 legacy 谓词。 */
    val legacyBootstrapUrl: String? = null,
    val lastTsUrl: String? = null,
    val lastLanUrl: String? = null,
    /** QR 原始提示，始终未信任，供重连前重新 identify。 */
    val scanHints: List<String> = emptyList(),
) {
    val isHostBound: Boolean get() = !hostId.isNullOrBlank()

    /** 配置对象可能进入断言/崩溃文本；token 与 authkey 永不由 toString 明文输出。 */
    override fun toString(): String =
        "PairingConfig(url=$url, token=[redacted], tsAuthKey=[redacted], hostId=$hostId, port=$port, tsNodeId=$tsNodeId, name=$name, legacyBootstrapUrl=$legacyBootstrapUrl, lastTsUrl=$lastTsUrl, lastLanUrl=$lastLanUrl, scanHints=$scanHints)"
}

/** 配对失败原因分类（供 UI 区分超时/拒绝/不可达/解析失败并给对应指引；003 明确报错）。 */
enum class PairingFailCause {
    /** 服务端拒绝配对信息（auth 被拒 / 协议拒绝）。 */
    REJECTED,

    /** 拨号失败 / 地址不可达（缺陷 A 的 TUN 虚拟网卡地址正是此型）。 */
    UNREACHABLE,

    /** 配对超时：候选逐试每地址 3s、无候选 15s，期限内未完成拨号+auth 握手。 */
    TIMEOUT,

    /** QR 内容解析失败（坏 JSON / 缺字段 / 坏版本）。 */
    PARSE_ERROR,

    /** 协议层错误帧 / 本地解码失败。 */
    PROTOCOL_ERROR,
}

/** 配对页状态机（003 明确报错：成功/失败都可见，静默失效最高罪）。 */
sealed interface PairingStatus {
    /** 无在途配对。 */
    data object Idle : PairingStatus

    /** 正在连接试配对（auth 握手验证中）；[targetUrl] 供「连接中… <地址>」进度展示（token 不上屏）。 */
    data class Pairing(val targetUrl: String) : PairingStatus

    /** auth 通过，配对成功（瞬时态，路由层观察后切工作区）。 */
    data object Success : PairingStatus

    /**
     * 失败，[cause] 供 UI 区分超时/拒绝/不可达/解析失败/协议错误，
     * [message] 人类可读（恒不含 token 值，协议 §9 红线）。
     */
    data class Failed(val cause: PairingFailCause, val message: String) : PairingStatus
}

/** QR JSON 解析失败（消息恒为固定文案，绝不带 token 值）。 */
class QrParseException(message: String) : Exception(message)

/**
 * ws url 合法性：必须 ws:// 或 wss:// 且 host 非空（手填与 QR 共用）。
 * 前缀"ws" 单独或 http(s) 一律拒绝——协议 §1 只接受 WebSocket 端点。
 * @contract
 * @pre none
 * @post 仅当 scheme 为 ws/wss 且 host 非空时返回 true
 * @err none（内部解析异常一律返回 false）
 * @inv none
 */
fun isValidWsUrl(raw: String): Boolean = try {
    val uri = URI(raw.trim())
    (uri.scheme == "ws" || uri.scheme == "wss") && !uri.host.isNullOrEmpty()
} catch (_: Exception) {
    false
}

/**
 * 从配对 ws url 推导 HTTP 上传基地址（协议 §8 同端口 `POST /upload`）：
 * ws→http、wss→https，保留 host:port，去掉路径。注入 [ServiceWire.uploadBaseUrl]
 * 供 [HttpUrlConnectionUploader] 使用（session-ui 沉淀欠账②清偿）。
 * @contract
 * @pre wsUrl 为合法 ws:// 或 wss:// URL
 * @post 返回 `scheme://host[:port]` 基地址（无路径；无显式端口时省略端口）
 * @err none
 * @inv none
 */
fun deriveUploadBase(wsUrl: String): String {
    val uri = URI(wsUrl)
    val scheme = if (uri.scheme == "wss") "https" else "http"
    val host = uri.host ?: ""
    val port = if (uri.port > 0) ":${uri.port}" else ""
    return "$scheme://$host$port"
}
