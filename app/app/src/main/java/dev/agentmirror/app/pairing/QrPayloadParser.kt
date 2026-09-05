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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * QR JSON 解析与校验（对齐服务端 qr.go 契约）。
 *
 * - 未知 JSON 字段忽略（协议 §4.1 前向兼容，与 conn 层帧解码语义一致）；
 * - 缺 v/url/token、坏版本、非法 ws 地址一律拒绝并给明确原因（003 明确报错）；
 * - 错误文案为固定字符串，绝不内联 token 值（协议 §9 红线：token 不落日志）。
 */
object QrPayloadParser {

    /** 线上载荷 schema 版本（qr.go PayloadVersion）。 */
    const val PAYLOAD_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析一行 QR 内容；失败抛 [QrParseException]（消息不携带 token）。
     * @contract
     * @pre none（任意输入都会被解析/校验；坏输入走 @err）
     * @post 返回版本/url/token 校验通过、候选去重归一后的 [QrPayload]；url/token 已 trim
     * @err 坏 JSON / 版本非 1 / url 非 ws(s) / token 为空 → [QrParseException]（固定文案，不含 token 值）
     * @inv 未知字段忽略；坏候选跳过不报错
     */
    fun parse(raw: String): QrPayload {
        val dto = try {
            json.decodeFromString<QrDto>(raw)
        } catch (_: Exception) {
            throw QrParseException("二维码内容不是有效的配对信息")
        }
        if (dto.version != PAYLOAD_VERSION) {
            throw QrParseException("不支持的配对信息版本：${dto.version}")
        }
        val url = dto.url.trim()
        val hostId = dto.hostId?.trim()?.takeIf { it.isNotEmpty() }
        // v1 QR remains backward compatible: old payloads require a URL; upgraded payloads
        // may be URL-less because the host record/TS+NSD discovery supplies endpoints.
        if (!isValidWsUrl(url) && !HostRouter.isValidHostId(hostId)) {
            throw QrParseException("配对信息中的服务端地址或主机身份不合法")
        }
        if (dto.token.isBlank()) {
            throw QrParseException("配对信息缺少 token")
        }
        val port = dto.port?.takeIf { it in 1..65535 }
        return QrPayload(
            version = dto.version,
            url = url,
            token = dto.token.trim(),
            tsAuthKey = dto.tsAuthKey.orEmpty(),
            candidates = normalizeCandidates(dto.candidates),
            hostId = hostId,
            port = port,
            tsNodeId = dto.tsNodeId?.trim()?.takeIf { it.isNotEmpty() },
            name = dto.name?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * 归一化候选列表（契约 §2.1）：保留顺序、去重；非 ws URL / 空项**跳过不报错**——
     * 坏候选不拖垮整个 QR，主选 [QrPayload.url] 仍可配对。
     */
    private fun normalizeCandidates(raw: List<String>?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        val seen = LinkedHashSet<String>()
        raw.forEach { c ->
            val t = c.trim()
            if (t.isNotEmpty() && isValidWsUrl(t)) seen += t
        }
        return seen.toList()
    }

    /** 线上载荷 DTO（字段名与 qr.go 对齐，勿改名）。 */
    @Serializable
    private data class QrDto(
        @SerialName("v") val version: Int,
        @SerialName("url") val url: String = "",
        @SerialName("token") val token: String = "",
        @SerialName("ts_authkey") val tsAuthKey: String? = null,
        @SerialName("candidates") val candidates: List<String>? = null,
        @SerialName("host_id") val hostId: String? = null,
        @SerialName("port") val port: Int? = null,
        @SerialName("ts_node_id") val tsNodeId: String? = null,
        @SerialName("name") val name: String? = null,
    )
}
