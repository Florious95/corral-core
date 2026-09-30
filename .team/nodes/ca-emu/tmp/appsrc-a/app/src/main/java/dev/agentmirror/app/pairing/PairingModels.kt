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
    /** WebSocket 端点，如 ws://192.168.1.5:9900/ws。 */
    val url: String,
    /** 配对 token：只上行一次，不回显、不落日志（协议 §9）。 */
    val token: String,
    /** Tailscale auth key：非空时扫码同时启动 App 内嵌 tsnet；不回显、不落日志。 */
    val tsAuthKey: String,
    /**
     * 全候选 ws URL（可选字段，契约 §2.1，fix-pairing-candidates）：
     * 同一主机的多网卡/多可达地址（LAN + tailnet），主选 [url] 打头；无候选为空列表。
     * 主选不可达时自动逐个试探（3s/个），全败后候选列表可见可点。
     */
    val candidates: List<String>,
) {
    /** data class 默认 toString 会带出两项凭据；显式封口，避免崩溃/调试输出误泄漏。 */
    override fun toString(): String =
        "QrPayload(v=$version, url=$url, token=[redacted], tsAuthKey=[redacted], candidates=$candidates)"
}

/** 配对成功后的连接配置负载（持久化：url + token + 可选 TS authkey）。 */
data class PairingConfig(
    val url: String,
    val token: String,
    /**
     * Tailscale auth key（feat-ts-wire）：扫码/手填带入，随配置持久化——冷启动重连
     * 需要它重新起 tsnet 节点（tailnet 地址拨号依赖 SOCKS 通道）。空串 = 未用 TS，
     * 行为与旧版一致。红线同 token：不落日志、不进错误文案（协议 §2.1/§9）。
     */
    val tsAuthKey: String = "",
) {
    /** 配置对象可能进入断言/崩溃文本；token 与 authkey 永不由 toString 明文输出。 */
    override fun toString(): String =
        "PairingConfig(url=$url, token=[redacted], tsAuthKey=[redacted])"
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
