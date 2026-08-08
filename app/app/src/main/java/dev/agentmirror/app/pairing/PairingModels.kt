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
    /** 保留字段：Tailscale auth key，app-tsnet 接入后扫码同时组网；本任务仅占位不消费。 */
    val tsAuthKey: String,
)

/** 配对成功后的连接配置负载（持久化：url + token）。 */
data class PairingConfig(
    val url: String,
    val token: String,
)

/** 配对页状态机（003 明确报错：成功/失败都可见，静默失效最高罪）。 */
sealed interface PairingStatus {
    /** 无在途配对。 */
    data object Idle : PairingStatus

    /** 正在连接试配对（auth 握手验证中）。 */
    data object Pairing : PairingStatus

    /** auth 通过，配对成功（瞬时态，路由层观察后切工作区）。 */
    data object Success : PairingStatus

    /** 失败，[message] 人类可读（恒不含 token 值，协议 §9 红线）。 */
    data class Failed(val message: String) : PairingStatus
}

/** QR JSON 解析失败（消息恒为固定文案，绝不带 token 值）。 */
class QrParseException(message: String) : Exception(message)

/**
 * ws url 合法性：必须 ws:// 或 wss:// 且 host 非空（手填与 QR 共用）。
 * 前缀"ws" 单独或 http(s) 一律拒绝——协议 §1 只接受 WebSocket 端点。
 */
fun isValidWsUrl(raw: String): Boolean = try {
    val uri = URI(raw.trim())
    (uri.scheme == "ws" || uri.scheme == "wss") && !uri.host.isNullOrEmpty()
} catch (_: Exception) {
    false
}

/**
 * 从配对 ws url 推导 HTTP 上传基地址（协议 §8 同端口 `POST /upload`）：
 * ws→http、wss→https，保留 host:port，去掉路径。注入 ServiceWire.uploadBaseUrl
 * 供 HttpUrlConnectionUploader 使用（session-ui 沉淀欠账②清偿）。
 */
fun deriveUploadBase(wsUrl: String): String {
    val uri = URI(wsUrl)
    val scheme = if (uri.scheme == "wss") "https" else "http"
    val host = uri.host ?: ""
    val port = if (uri.port > 0) ":${uri.port}" else ""
    return "$scheme://$host$port"
}
